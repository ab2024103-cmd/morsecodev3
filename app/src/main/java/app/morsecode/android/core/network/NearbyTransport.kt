package app.morsecode.android.core.network

import android.content.Context
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.DiscoveredPeer
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.transfer.IncomingFiles
import app.morsecode.android.core.util.Ids
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * §11.3 NEARBY CONNECTIONS TRANSPORT — P2P_STAR, a fixed service id, and
 * advertising and discovery running concurrently.
 *
 * It implements the same [Transport] interface as LAN, so the engine cannot
 * tell them apart (§8.2), and it publishes its peers into the SAME discovery
 * list with a NEARBY badge (§11.1).
 *
 * A device without Play Services degrades to LAN only: [start] returns false,
 * the Connection Doctor shows §6.15's line, and nothing crashes or silently
 * pretends to advertise (§20.6).
 */
class NearbyTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val deviceName: String,
    private val incoming: IncomingFiles,
    private val openContent: (TransferItem) -> InputStream?,
    private val onPeerFound: (DiscoveredPeer) -> Unit,
    private val onPeerLost: (String) -> Unit,
    private val logStore: LogStore? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Transport {

    override val kind: TransportKind = TransportKind.NEARBY

    private val appContext = context.applicationContext
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(appContext) }

    /** §9.8 consent, asked before a connection is accepted. */
    var consent: suspend (String, String) -> Boolean = { _, _ -> true }

    var onSessionEstablished: ((NearbySession) -> Unit)? = null

    private val sessions = ConcurrentHashMap<String, NearbySession>()
    private val endpointNames = ConcurrentHashMap<String, String>()
    private val pendingStreams = ConcurrentHashMap<Long, String>()

    /** Whichever batch the peer's files belong to; one per endpoint. */
    private val batchByEndpoint = ConcurrentHashMap<String, String>()

    @Volatile
    private var running = false

    /**
     * @return false when Nearby is unavailable on this device. The caller
     *   keeps LAN running and shows the Doctor line — it does not retry.
     */
    fun start(): Boolean {
        if (running) return true
        if (!PlayServices.canUseNearby(appContext)) {
            logStore?.w("Nearby unavailable: Play Services missing — Wi-Fi LAN only")
            return false
        }
        val strategy = Strategy.P2P_STAR
        client.startAdvertising(
            deviceName,
            Ids.NEARBY_SERVICE_ID,
            connectionCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener { error ->
            logStore?.w("Nearby advertise failed: ${error.javaClass.simpleName}")
        }
        client.startDiscovery(
            Ids.NEARBY_SERVICE_ID,
            discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener { error ->
            logStore?.w("Nearby discovery failed: ${error.javaClass.simpleName}")
        }
        running = true
        logStore?.i("Nearby advertising and discovering on ${Ids.NEARBY_SERVICE_ID}")
        return true
    }

    override suspend fun connect(peerId: String): TransportSession? {
        val endpointId = endpointNames.keys.firstOrNull { it == peerId } ?: peerId
        client.requestConnection(deviceName, endpointId, connectionCallback)
        // The session appears through onConnectionResult; the caller waits for
        // onSessionEstablished rather than blocking here, because the peer may
        // be showing a consent dialog.
        return sessions[endpointId]
    }

    override suspend fun shutdown() {
        running = false
        for (session in sessions.values) session.close("shutdown")
        sessions.clear()
        endpointNames.clear()
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
    }

    // ----- Callbacks --------------------------------------------------------

    private val discoveryCallback = object : EndpointDiscoveryCallback() {

        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointNames[endpointId] = info.endpointName
            // §11.1: the same list as LAN, with a NEARBY badge.
            onPeerFound(
                DiscoveredPeer(
                    deviceId = endpointId,
                    name = info.endpointName,
                    address = endpointId,
                    port = 0,
                    transport = TransportKind.NEARBY,
                    protocolVersion = Ids.PROTOCOL_VERSION,
                    lastSeenMillis = clock(),
                ),
            )
        }

        override fun onEndpointLost(endpointId: String) {
            endpointNames.remove(endpointId)
            onPeerLost(endpointId)
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            scope.launch {
                // §9.8: nothing crosses before Accept, on this transport too.
                if (consent(endpointId, info.endpointName)) {
                    client.acceptConnection(endpointId, payloadCallback)
                } else {
                    client.rejectConnection(endpointId)
                    logStore?.i("Rejected Nearby connection from ${info.endpointName}")
                }
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (!resolution.status.isSuccess) {
                logStore?.w("Nearby connection to $endpointId refused")
                return
            }
            val session = NearbySession(
                client = client,
                endpointId = endpointId,
                peerId = endpointId,
                peerName = endpointNames[endpointId] ?: endpointId,
                scope = scope,
                openContent = openContent,
                logStore = logStore,
            )
            sessions[endpointId] = session
            batchByEndpoint[endpointId] = java.util.UUID.randomUUID().toString()
            logStore?.i("Nearby connected · peer=${session.peerName}")
            onSessionEstablished?.invoke(session)
        }

        override fun onDisconnected(endpointId: String) {
            // SESSION-END RULE (§11.3): fail the waiters and clear the maps
            // first; only then is the session forgotten.
            sessions.remove(endpointId)?.onTransportDisconnected()
            incoming.abandonAll("Connection lost")
            logStore?.w("Nearby disconnected · endpoint=$endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> handleControl(endpointId, payload)
                Payload.Type.STREAM -> handleStream(endpointId, payload)
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val status = when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> NearbyTransfers.PayloadStatus.SUCCESS
                PayloadTransferUpdate.Status.FAILURE -> NearbyTransfers.PayloadStatus.FAILURE
                PayloadTransferUpdate.Status.CANCELED -> NearbyTransfers.PayloadStatus.CANCELED
                else -> NearbyTransfers.PayloadStatus.IN_PROGRESS
            }
            sessions[endpointId]?.onPayloadUpdate(
                update.payloadId,
                status,
                update.bytesTransferred,
                update.totalBytes,
            )
        }
    }

    private fun handleControl(endpointId: String, payload: Payload) {
        val bytes = payload.asBytes() ?: return
        val message = NearbyPayloads.parse(bytes) ?: return
        val session = sessions[endpointId]
        val batchId = batchByEndpoint.getOrPut(endpointId) { java.util.UUID.randomUUID().toString() }

        when (message.type) {
            Protocol.META -> {
                // The metadata that precedes a file: register it so the stream
                // has somewhere to go and §9.4's checks can run.
                incoming.onMeta(message, endpointId, endpointNames[endpointId] ?: endpointId, batchId)
                message.string("fileId")?.let { fileId -> nextStreamFile = fileId }
            }
            Protocol.PAUSE_REQ -> message.string("fileId")?.let { session?.transfers?.markPaused(it) }
            Protocol.CANCEL -> message.string("fileId")?.let { session?.transfers?.markCancelled(it) }
            else -> Unit
        }
    }

    private fun handleStream(endpointId: String, payload: Payload) {
        val stream = payload.asStream()?.asInputStream() ?: return
        val fileId = nextStreamFile ?: return
        pendingStreams[payload.id] = fileId
        nextStreamFile = null
        scope.launch(Dispatchers.IO) {
            stream.use { incoming.onStream(fileId, it) }
            pendingStreams.remove(payload.id)
        }
    }

    /**
     * §11.3: "a small metadata payload precedes every file". The id it carries
     * is what the next STREAM payload belongs to — the SDK gives no other way
     * to correlate them, since the stream's own id is only known here.
     */
    @Volatile
    private var nextStreamFile: String? = null
}
