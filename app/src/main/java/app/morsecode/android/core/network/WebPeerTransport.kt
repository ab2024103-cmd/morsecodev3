package app.morsecode.android.core.network

import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.DiscoveredPeer
import app.morsecode.android.core.model.SendResult
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.util.Ids
import app.morsecode.android.core.webshare.PushOffers
import app.morsecode.android.core.webshare.WebSessions
import java.io.File

/**
 * §11.4 WEB PEER TRANSPORT.
 *
 * "A WebPeerSession wraps an accepted browser session so the engine can treat
 * it like any other peer (§7.8). Its sendFile publishes an offer over SSE and
 * completes when the browser's ranged download finishes or times out;
 * pause/cancel revoke the offer."
 *
 * Because it implements [TransportSession], every §9 rule the engine already
 * enforces — the bounded await, the retry table, INV-3's pause on loss —
 * applies to a browser without a line of special-casing.
 */
class WebPeerSession(
    private val session: WebSessions.Session,
    private val offers: PushOffers,
    private val logStore: LogStore? = null,
) : TransportSession {

    override val peerId: String = "web:${session.id}"

    override val peerName: String = session.userAgent

    override val transport: TransportKind = TransportKind.WEB

    @Volatile
    private var closed = false

    override val isAlive: Boolean get() = !closed

    override suspend fun sendFile(
        item: TransferItem,
        sha256: String?,
        onProgress: (Long, Long) -> Unit,
    ): SendResult {
        val path = item.file.uri.path ?: return SendResult.Failed("no path for ${item.file.displayName}")
        if (!File(path).exists()) return SendResult.Failed("file no longer available")

        val (offer, waiter) = offers.offer(
            sessionId = session.id,
            fileId = item.id,
            name = item.file.displayName,
            path = path,
            sizeBytes = item.totalBytes,
        )
        logStore?.i("Push offered · ${item.file.displayName} → ${session.label}")
        // The browser's ranged download reports progress through the server,
        // which calls back into the offer; until then the engine's own idle
        // watchdog is what bounds this wait (INV-1c).
        return waiter.await()
    }

    /** §11.4: pause and cancel revoke the offer. */
    override suspend fun pauseOutgoing(fileId: String) {
        offers.cancelSession(session.id, "paused")
    }

    override suspend fun cancelTransfer(fileId: String) {
        offers.cancelSession(session.id, "cancelled")
    }

    override suspend fun close(reason: String) {
        closed = true
        offers.cancelSession(session.id, reason)
    }
}

/**
 * Publishes every ACCEPTED browser session into the one discovery list
 * (§11.1), so a laptop shows up beside phones as "Chrome · WebShare" with a
 * [WEB] badge (G11).
 */
class WebPeerTransport(
    private val sessions: WebSessions,
    private val offers: PushOffers,
    private val onPeerFound: (DiscoveredPeer) -> Unit,
    private val onPeerLost: (String) -> Unit,
    private val logStore: LogStore? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Transport {

    override val kind: TransportKind = TransportKind.WEB

    private val published = HashSet<String>()

    /** Called whenever the session list changes. */
    fun publish(active: List<WebSessions.Session>) {
        val accepted = active.filter { it.state == WebSessions.State.ACCEPTED }
        for (session in accepted) {
            onPeerFound(
                DiscoveredPeer(
                    deviceId = "web:${session.id}",
                    name = session.userAgent,
                    address = session.address,
                    port = Ids.PORT_WEBSHARE,
                    transport = TransportKind.WEB,
                    protocolVersion = Ids.PROTOCOL_VERSION,
                    lastSeenMillis = clock(),
                ),
            )
            published.add(session.id)
        }
        // A browser that went away stops being a peer.
        val live = accepted.map { it.id }.toSet()
        for (gone in published - live) {
            onPeerLost("web:$gone")
        }
        published.retainAll(live)
    }

    override suspend fun connect(peerId: String): TransportSession? {
        val id = peerId.removePrefix("web:")
        val session = sessions.get(id) ?: return null
        if (session.state != WebSessions.State.ACCEPTED) return null
        logStore?.i("Connected to browser peer ${session.label}")
        return WebPeerSession(session, offers, logStore)
    }

    override suspend fun shutdown() {
        offers.clear()
        for (id in published) onPeerLost("web:$id")
        published.clear()
    }
}
