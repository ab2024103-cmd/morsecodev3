package app.morsecode.android.core.transfer

import app.morsecode.android.core.model.PeerDelivery
import app.morsecode.android.core.model.PeerSessionState
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.util.Fmt

/**
 * §10.4 STAT FORMULAS — "these must match the mocks exactly".
 *
 * The mocks labelled two different quantities "TOTAL MB"; Appendix D resolves
 * that, and this object is where the resolution lives:
 *
 *     PEERS      = accepted peers                         (3)
 *     BATCH MB   = Σ size of every file in the queue       (212)
 *     TO SEND MB = BATCH MB × PEERS                        (636)
 *     MB SENT    = Σ bytesSent across peers
 *     throughput = Σ per-peer speedBps                     (14.2 MB/s)
 *     deliveries = files × accepted peers                  (9)
 *     FILES EACH = files in the queue                      (3)
 *     avg speed  = MB SENT ÷ elapsed seconds
 *
 * Pure, so A9's numbers are checked arithmetically rather than read off a
 * screenshot.
 */
object BroadcastStats {

    data class Running(
        val peers: Int,
        val batchBytes: Long,
        val toSendBytes: Long,
        val sentBytes: Long,
        val throughputBps: Long,
    )

    data class Complete(
        val phones: Int,
        val filesEach: Int,
        val sentBytes: Long,
        val deliveries: Int,
        val failed: Int,
        val skipped: Int,
        val averageBps: Long,
        val verifiedPhones: Int,
    ) {
        /** INV-B4: only when every accepted peer verified everything. */
        val allVerified: Boolean get() = phones > 0 && verifiedPhones == phones
    }

    /** §10.2: only ACCEPTED peers count; a rejected one is not a target. */
    fun acceptedPeers(deliveries: List<PeerDelivery>): List<PeerDelivery> =
        deliveries.filter { it.sessionState != PeerSessionState.REJECTED }

    fun running(items: List<TransferItem>, deliveries: List<PeerDelivery>): Running {
        val accepted = acceptedPeers(deliveries)
        val batch = items.sumOf { it.totalBytes }
        return Running(
            peers = accepted.size,
            batchBytes = batch,
            toSendBytes = batch * accepted.size,
            sentBytes = accepted.sumOf { it.bytesSent },
            throughputBps = accepted.sumOf { it.speedBps },
        )
    }

    fun complete(
        items: List<TransferItem>,
        deliveries: List<PeerDelivery>,
        elapsedMillis: Long,
    ): Complete {
        val accepted = acceptedPeers(deliveries)
        val sent = accepted.sumOf { it.bytesSent }
        return Complete(
            phones = accepted.size,
            filesEach = items.size,
            sentBytes = sent,
            deliveries = items.size * accepted.size,
            failed = accepted.sumOf { it.failedCount },
            skipped = accepted.sumOf { delivery ->
                delivery.itemStates.values.count {
                    it == app.morsecode.android.core.model.TransferState.SKIPPED
                }
            },
            averageBps = if (elapsedMillis <= 0) 0 else (sent * 1000L) / elapsedMillis,
            verifiedPhones = accepted.count { it.verified },
        )
    }

    /** §6.8.2's three running tiles, in order. */
    fun runningTiles(stats: Running): List<Pair<String, String>> = listOf(
        stats.peers.toString() to "PEERS",
        Fmt.size(stats.batchBytes) to "BATCH MB",
        Fmt.size(stats.toSendBytes) to "TO SEND MB",
    )

    /** §6.8.4's three completion tiles, in order. */
    fun completeTiles(stats: Complete): List<Pair<String, String>> = listOf(
        stats.phones.toString() to "PHONES",
        stats.filesEach.toString() to "FILES EACH",
        Fmt.size(stats.sentBytes) to "MB SENT",
    )

    /**
     * INV-B4's header. "Broadcast complete / All 3 phones verified" only when
     * every accepted peer verified everything; otherwise the degraded form,
     * which names how many did.
     */
    fun header(stats: Complete): String = if (stats.allVerified) {
        "✓ Broadcast complete — all ${stats.phones} phones verified"
    } else {
        "Broadcast finished — ${stats.verifiedPhones} of ${stats.phones} phones verified"
    }

    /**
     * §10.6 HONESTY. Below ~40 % of the single-peer baseline the card says
     * "Speed is shared between 3 phones" rather than letting someone think
     * something is broken.
     */
    fun sharedSpeedNote(
        perPeerBps: Long,
        singlePeerBaselineBps: Long,
        peers: Int,
    ): String? {
        if (peers < 2 || singlePeerBaselineBps <= 0) return null
        val ratio = perPeerBps.toDouble() / singlePeerBaselineBps
        return if (ratio < SHARED_SPEED_THRESHOLD) "Speed is shared between $peers phones" else null
    }

    /** §10.2's cap message when the transport, not the user, is the limit. */
    fun capNote(requested: Int, cap: Int, nearby: Boolean, lowTier: Boolean): String? = when {
        requested <= cap -> null
        nearby -> "Nearby supports up to $cap phones — the rest will wait"
        lowTier -> "This phone can broadcast to $cap at a time — the rest will wait"
        else -> "Up to $cap phones at a time — the rest will wait"
    }

    const val SHARED_SPEED_THRESHOLD = 0.40
}
