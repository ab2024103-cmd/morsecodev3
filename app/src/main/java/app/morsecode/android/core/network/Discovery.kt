package app.morsecode.android.core.network

import android.content.Context
import android.net.wifi.WifiManager
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.DiscoveredPeer
import app.morsecode.android.core.util.Ids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * §11.1 UNIFIED DISCOVERY: one component, one deduplicated peer list.
 *
 * LAN beacons are implemented here (UDP :33457, one announce every 1200 ms,
 * a MulticastLock held while discovering). Nearby and WebShare publish into
 * the SAME list through [publish], because §11.1 forbids a second list and
 * §6.3 renders every peer in one place with its transport badge.
 *
 * The TRANSPORT row is a PREFERENCE, not a filter that hides half the network:
 * [preference] only affects which peer Auto would dial first, never which
 * peers are visible.
 */
class Discovery(
    context: Context,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val deviceName: String,
    private val servicePort: Int = Ids.PORT_TCP,
    private val beaconPort: Int = Ids.PORT_UDP_DISCOVERY,
    private val logStore: LogStore? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    enum class Preference { AUTO, LAN, NEARBY }

    private val appContext = context.applicationContext

    private val peersFlow = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val peers: StateFlow<List<DiscoveredPeer>> get() = peersFlow

    var preference: Preference = Preference.AUTO

    /** Set while this phone is broadcasting, so peers can show it (§10). */
    var broadcasting: Boolean = false

    private val known = LinkedHashMap<String, DiscoveredPeer>()
    private var announceJob: Job? = null
    private var listenJob: Job? = null
    private var expiryJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * §6.15's multicast check reads this rather than assuming. Stage 12 had to
     * report it optimistically because nothing exposed the lock; this closes
     * that gap honestly (§20.6).
     */
    val isMulticastLockHeld: Boolean get() = multicastLock?.isHeld == true

    /** A failed UDP bind is not a running LAN discovery transport. */
    val isRunning: Boolean get() = announceJob?.isActive == true && socket != null
    private var socket: DatagramSocket? = null

    /** Best-effort local IPv4 shown with the live LAN beacon status (§6.3). */
    fun localIpv4Address(): String? = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (!network.isUp || network.isLoopback) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }

    fun start() {
        if (announceJob?.isActive == true) return
        acquireMulticastLock()

        val udp = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = SOCKET_TIMEOUT_MS
                bind(java.net.InetSocketAddress(beaconPort))
            }
        } catch (e: Exception) {
            logStore?.e("UDP :$beaconPort unavailable: ${e.javaClass.simpleName}")
            return
        }
        socket = udp
        logStore?.i("UDP broadcast :$beaconPort sent")

        announceJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                announceOnce(udp)
                // §11.2: one announce every 1200 ms.
                delay(ANNOUNCE_INTERVAL_MS)
            }
        }

        listenJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udp.receive(packet)
                } catch (timeout: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (!isActive) break
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val peer = Beacon.decode(text, packet.address?.hostAddress ?: "", clock()) ?: continue
                if (peer.deviceId == deviceId) continue
                merge(peer)
            }
        }

        expiryJob = scope.launch {
            while (isActive) {
                delay(ANNOUNCE_INTERVAL_MS)
                expire()
            }
        }
    }

    fun stop() {
        announceJob?.cancel()
        listenJob?.cancel()
        expiryJob?.cancel()
        announceJob = null
        listenJob = null
        expiryJob = null
        runCatching { socket?.close() }
        socket = null
        releaseMulticastLock()
    }

    /** Nearby (§11.3) and WebShare (§11.4) publish into the same list. */
    fun publish(peer: DiscoveredPeer) = merge(peer)

    fun forget(deviceId: String) {
        synchronized(known) { known.remove(deviceId) }
        republish()
    }

    /**
     * Auto prefers LAN whenever the phone has a network, because Nearby never
     * uses the shared infrastructure network and negotiates Bluetooth on old
     * hardware (§11.1).
     */
    fun preferred(candidates: List<DiscoveredPeer>): DiscoveredPeer? = when (preference) {
        Preference.LAN -> candidates.firstOrNull { it.transport == TransportKind.LAN }
            ?: candidates.firstOrNull()
        Preference.NEARBY -> candidates.firstOrNull { it.transport == TransportKind.NEARBY }
            ?: candidates.firstOrNull()
        Preference.AUTO -> candidates.firstOrNull { it.transport == TransportKind.LAN }
            ?: candidates.firstOrNull()
    }

    private fun announceOnce(udp: DatagramSocket) {
        val payload = Beacon.encode(deviceId, deviceName, servicePort, broadcasting)
            .toByteArray(Charsets.UTF_8)
        for (address in broadcastAddresses()) {
            try {
                udp.send(DatagramPacket(payload, payload.size, address, beaconPort))
            } catch (e: Exception) {
                // One unreachable interface must not stop the others.
            }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val addresses = ArrayList<InetAddress>()
        try {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.interfaceAddresses) {
                    address.broadcast?.let { addresses.add(it) }
                }
            }
        } catch (e: Exception) {
            // Fall through to the global broadcast address.
        }
        if (addresses.isEmpty()) {
            runCatching { addresses.add(InetAddress.getByName("255.255.255.255")) }
        }
        return addresses
    }

    private fun merge(peer: DiscoveredPeer) {
        synchronized(known) {
            // Deduplicated by device id: the same phone seen over two
            // transports is ONE row (§11.1), and LAN wins because it is faster.
            val existing = known[peer.deviceId]
            known[peer.deviceId] = when {
                existing == null -> peer
                existing.transport == TransportKind.LAN && peer.transport != TransportKind.LAN ->
                    existing.copy(lastSeenMillis = peer.lastSeenMillis)
                else -> peer
            }
        }
        republish()
    }

    private fun expire() {
        val now = clock()
        val changed = synchronized(known) {
            val stale = known.filterValues { now - it.lastSeenMillis > PEER_TTL_MS }.keys
            for (key in stale) known.remove(key)
            stale.isNotEmpty()
        }
        if (changed) republish()
    }

    private fun republish() {
        peersFlow.value = synchronized(known) { known.values.toList() }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = wifi.createMulticastLock("morsecode.discovery")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
            logStore?.i("Multicast lock acquired")
        } catch (e: Exception) {
            logStore?.w("Multicast lock unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    /**
     * The beacon payload: deviceId, name, appId, protocolVersion, broadcast
     * flag and the TCP port to dial (§11.2).
     */
    object Beacon {

        fun encode(deviceId: String, name: String, port: Int, broadcasting: Boolean): String {
            val json = JSONObject()
            json.put("deviceId", deviceId)
            json.put("name", name)
            json.put("appId", Ids.APPLICATION_ID)
            json.put("protocolVersion", Ids.PROTOCOL_VERSION)
            json.put("broadcasting", broadcasting)
            json.put("port", port)
            return json.toString()
        }

        /** Returns null for anything that is not a Morsecode beacon. */
        fun decode(payload: String, fromAddress: String, nowMillis: Long): DiscoveredPeer? {
            val json = try {
                JSONObject(payload)
            } catch (e: Exception) {
                return null
            }
            if (json.optString("appId") != Ids.APPLICATION_ID) return null
            val deviceId = json.optString("deviceId").takeIf { it.isNotEmpty() } ?: return null
            return DiscoveredPeer(
                deviceId = deviceId,
                name = json.optString("name").takeIf { it.isNotEmpty() } ?: fromAddress,
                address = fromAddress,
                port = json.optInt("port", Ids.PORT_TCP),
                transport = TransportKind.LAN,
                protocolVersion = json.optInt("protocolVersion", 0),
                broadcasting = json.optBoolean("broadcasting", false),
                lastSeenMillis = nowMillis,
            )
        }
    }

    private companion object {
        /** §11.2: one announce every 1200 ms. */
        const val ANNOUNCE_INTERVAL_MS = 1200L

        /** Four missed beacons before a peer disappears from the list. */
        const val PEER_TTL_MS = 5_000L
        const val SOCKET_TIMEOUT_MS = 1_000
    }
}
