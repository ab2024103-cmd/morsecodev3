package app.morsecode.android.core.network

import app.morsecode.android.core.model.SendResult
import app.morsecode.android.core.model.TransferItem

/**
 * §8.2: `TransportSession` abstracts ONE connected peer. The engine is
 * transport-agnostic — LanSession, NearbySession and WebPeerSession all
 * implement this and nothing above them knows which is in use.
 *
 * Two rules from §9 are part of this contract rather than of any one
 * implementation:
 *
 *  - INV-1(a): [close] must immediately complete every pending [sendFile] with
 *    a failure result. A session that closes while a send is awaiting and
 *    leaves the waiter hanging is the hang §9.2 forbids.
 *  - §9.3: [sendFile] returns an outcome; it never throws for an ordinary
 *    failure. `CancellationException` is the single exception that must
 *    propagate unmodified.
 */
interface TransportSession {

    val peerId: String

    val peerName: String

    /** Which transport this session runs over, for the §4.12a badge. */
    val transport: TransportKind

    /** False once the peer is gone; the engine reads this on every loop turn. */
    val isAlive: Boolean

    /**
     * Sends one file and suspends until it reaches a terminal outcome.
     *
     * @param sha256 the pre-hash when the file is under PREHASH_LIMIT, else
     *   null — the receiver uses it for the "already present" check (§9.4).
     * @param onProgress called with cumulative bytes as they are flushed;
     *   progress advances only AFTER a successful flush (§11.2).
     */
    suspend fun sendFile(
        item: TransferItem,
        sha256: String?,
        onProgress: (Long, Long) -> Unit,
    ): SendResult

    /** Asks the peer to pause one file. Only that file is affected (INV-2). */
    suspend fun pauseOutgoing(fileId: String)

    /** Asks the peer to cancel one file. Only that file is affected (INV-2). */
    suspend fun cancelTransfer(fileId: String)

    /** Clean teardown. Must complete every pending waiter (INV-1a). */
    suspend fun close(reason: String)
}

enum class TransportKind { LAN, NEARBY, WEB }

/**
 * A transport that can produce sessions. Discovery lives in `Discovery`
 * (§11.1); this is only the connect side, so the engine can be handed a
 * session without knowing who found the peer.
 */
interface Transport {

    val kind: TransportKind

    suspend fun connect(peerId: String): TransportSession?

    suspend fun shutdown()
}
