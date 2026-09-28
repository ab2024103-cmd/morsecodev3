package app.morsecode.android.core.network

import android.content.Context
import app.morsecode.android.core.data.RecentDevices
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.DiscoveredPeer
import app.morsecode.android.core.transfer.TransferEngine
import app.morsecode.android.core.transfer.TransferService
import java.util.UUID

/**
 * One place that owns "are we discovering, and who are we connected to".
 *
 * §8.2 says coordinators are process-scoped, and §3.7 says a foreground
 * service owns every active session; both are honoured here rather than in a
 * fragment, so rotating or navigating away cannot stop discovery or drop a
 * session.
 *
 * It also closes the hole recorded at the end of Stages 6 and 7: **the consent
 * callbacks are installed before either transport is ever started**, so no
 * build can reach a state where a peer is accepted without being asked about.
 */
class ConnectionCoordinator(
    context: Context,
    private val engine: TransferEngine,
    private val discovery: Discovery,
    private val lan: LanTransport,
    private val nearby: NearbyTransport,
    private val sessions: SessionRegistry,
    private val consent: ConsentRequests,
    private val recentDevices: RecentDevices,
    private val logStore: LogStore? = null,
) {

    private val appContext = context.applicationContext

    /** §6.13 Sounds: connect · fail · success. */
    var soundFx: app.morsecode.android.core.transfer.SoundFx? = null
    private var listeners = 0
    private var nearbyAvailable = false

    @Volatile
    var installed = false
        private set

    /** Wires consent and session handling. Must run before anything starts. */
    fun install() {
        if (installed) return
        installed = true

        lan.consent = { hello ->
            logStore?.i("Consent asked · peer=${hello.name} · ${hello.address}")
            consent.ask(
                ConsentRequests.Request(
                    id = UUID.randomUUID().toString(),
                    kind = ConsentRequests.Kind.PEER,
                    title = hello.name,
                    // §6.16a: "Phone · Wi-Fi LAN · 192.168.1.42".
                    detail = "Phone · Wi-Fi LAN · ${hello.address}",
                    peerId = hello.deviceId,
                ),
            )
        }
        lan.onSessionEstablished = { session -> bind(session) }
        lan.onVersionProblem = { message -> logStore?.w(message) }

        nearby.consent = { endpointId, name ->
            logStore?.i("Consent asked · peer=$name · nearby/$endpointId")
            consent.ask(
                ConsentRequests.Request(
                    id = UUID.randomUUID().toString(),
                    kind = ConsentRequests.Kind.PEER,
                    title = name,
                    detail = "Phone · Nearby · $endpointId",
                    peerId = endpointId,
                ),
            )
        }
        nearby.onSessionEstablished = { session -> bind(session) }
    }

    /**
     * Discovery runs while at least one connect surface is on screen, and
     * stops when the last one leaves — §11.1 wants every mechanism running at
     * once, not running forever.
     */
    fun addListener() {
        install()
        listeners++
        if (listeners == 1) {
            discovery.start()
            lan.start()
            nearbyAvailable = nearby.start()
            if (!nearbyAvailable) {
                // §20.6: say so in the log and the Doctor, do not pretend.
                logStore?.i("Nearby unavailable — Wi-Fi LAN only")
            }
        }
    }

    fun removeListener() {
        if (listeners == 0) return
        listeners--
        if (listeners == 0) discovery.stop()
    }

    fun isNearbyAvailable(): Boolean = nearbyAvailable

    /**
     * Dials a peer. The consent handshake happens on the OTHER phone, so this
     * suspends until it answers or the dial fails (§6.16, §9.8).
     */
    suspend fun connect(peer: DiscoveredPeer, broadcasting: Boolean = false): TransportSession? {
        install()
        val session = when (peer.transport) {
            TransportKind.LAN -> lan.connect(peer.address, peer.port, broadcasting)
            // Nearby metadata travels before each STREAM, so the broadcast bit
            // belongs to its session/metadata path rather than LAN's HELLO.
            TransportKind.NEARBY -> nearby.connect(peer.deviceId)
            TransportKind.WEB -> null
        }
        if (session != null) {
            recentDevices.remember(peer.deviceId, peer.name, peer.transport)
        }
        return session
    }

    /** §11.5 manual pairing: the only typed path into a session. */
    suspend fun connectManually(target: ManualAddress.Target): LanSession? {
        install()
        val session = lan.connect(target.host, target.port)
        if (session != null) {
            recentDevices.remember(session.peerId, session.peerName, TransportKind.LAN)
        }
        return session
    }

    /** Binds a live session to the engine and starts the §3.7 service. */
    private fun bind(session: TransportSession) {
        // §6.13's Sounds switch is read by SoundFx itself, so this line is a
        // no-op when the user has turned it off.
        soundFx?.play(app.morsecode.android.core.transfer.SoundFx.Cue.CONNECT)
        sessions.add(session)
        engine.onSessionConnected(session)
        TransferService.start(appContext)
        recentDevices.remember(session.peerId, session.peerName, session.transport)
        logStore?.i("Session bound · peer=${session.peerName}")
    }

    /** §5.3 End / §9.8: a clean BYE, then the queue pauses rather than fails. */
    suspend fun endSession(reason: String = "ended by user") {
        for (session in sessions.all()) {
            consent.endSession(session.peerId)
            session.close(reason)
        }
        sessions.closeAll(reason)
        engine.onSessionLost(reason)
        TransferService.stop(appContext)
    }
}
