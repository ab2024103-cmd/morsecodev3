package app.morsecode.android.core.transfer

import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.util.Fmt

/**
 * Everything the transfer screens, the queue sheet and the notification need
 * to know, derived from the queue and nothing else.
 *
 * §16.5: any byte or progress figure shown in the UI or the logs is read from
 * the real underlying state. These functions take the queue's items and return
 * text — there is no side-counter for a code path to forget to update, and no
 * screen computes progress itself (§20.1).
 *
 * Pure on purpose: the §6.7 action sets, the §6.6 two-line summary and the
 * §6.18 notification text are all decisions, and decisions are testable.
 */
object QueuePresentation {

    /** §6.7's contextual buttons, one set per state. */
    enum class Action { PAUSE, RESUME, RETRY, CANCEL, SEND_NOW, REMOVE }

    fun actionsFor(state: TransferState): List<Action> = when (state) {
        TransferState.IN_PROGRESS -> listOf(Action.PAUSE, Action.RETRY, Action.CANCEL)
        TransferState.QUEUED -> listOf(Action.SEND_NOW, Action.REMOVE)
        TransferState.PAUSED -> listOf(Action.RESUME, Action.REMOVE)
        TransferState.FAILED -> listOf(Action.RETRY, Action.REMOVE)
        // A finished row keeps one honest action: get it out of the list.
        TransferState.COMPLETED, TransferState.SKIPPED, TransferState.CANCELLED ->
            listOf(Action.REMOVE)
    }

    /** §6.7: reordering an active or finished row is visibly disabled. */
    fun isReorderable(state: TransferState): Boolean = state == TransferState.QUEUED

    /** "Sending · 3 files" / "Receiving · 1 file" (§6.4, §6.6). */
    fun sectionCount(items: List<TransferItem>): Int = items.size

    /**
     * The mono meta line of a row, exactly as §6.4 mocks it:
     *   "48.9 / 144 MB · 6.2 MB/s"   while moving
     *   "4.1 MB · waiting"           queued
     *   "39.7 / 64 MB · resume 39.7 MB" paused with an offset
     *   "144 MB · CRC verified"      done
     */
    fun metaLine(item: TransferItem): String = when (item.state) {
        TransferState.IN_PROGRESS ->
            "${Fmt.progress(item.bytesTransferred, item.totalBytes)} · ${Fmt.speed(item.speedBps)}"
        TransferState.QUEUED -> "${Fmt.size(item.totalBytes)} · waiting"
        TransferState.PAUSED ->
            if (item.resumeOffset > 0) {
                "${Fmt.progress(item.resumeOffset, item.totalBytes)} · resume ${Fmt.size(item.resumeOffset)}"
            } else {
                "${Fmt.size(item.totalBytes)} · paused"
            }
        TransferState.COMPLETED -> "${Fmt.size(item.totalBytes)} · CRC verified"
        TransferState.SKIPPED -> "${Fmt.size(item.totalBytes)} · already on the other phone"
        TransferState.CANCELLED -> "${Fmt.size(item.totalBytes)} · cancelled"
        TransferState.FAILED -> item.lastError ?: "failed"
    }

    /**
     * §6.4's live summary detail: "1 sending · 1 queued · 1 paused — avg
     * 6.2 MB/s". Counts come from the same items the rows rendered.
     */
    fun liveDetail(items: List<TransferItem>): String {
        val parts = ArrayList<String>(3)
        val sending = items.count { it.state == TransferState.IN_PROGRESS }
        val queued = items.count { it.state == TransferState.QUEUED }
        val paused = items.count { it.state == TransferState.PAUSED }
        if (sending > 0) parts.add("$sending sending")
        if (queued > 0) parts.add("$queued queued")
        if (paused > 0) parts.add("$paused paused")
        if (parts.isEmpty()) parts.add("nothing in flight")
        return "${parts.joinToString(" · ")} — avg ${Fmt.speed(averageSpeed(items))}"
    }

    /**
     * §6.6 [GAP]: when both directions have a terminal batch the card shows
     * TWO lines, never one that silently mixes directions.
     *
     * Returns the "In:" line, the "Out:" line, or both — and only for
     * directions that actually have items.
     */
    fun terminalLines(items: List<TransferItem>): List<String> {
        val lines = ArrayList<String>(2)
        val incoming = items.filter { it.direction == Direction.RECEIVING }
        val outgoing = items.filter { it.direction == Direction.SENDING }
        if (incoming.isNotEmpty()) lines.add(directionLine("In", incoming, "received"))
        if (outgoing.isNotEmpty()) lines.add(directionLine("Out", outgoing, "sent"))
        return lines
    }

    private fun directionLine(prefix: String, items: List<TransferItem>, verb: String): String {
        val done = items.count { it.state == TransferState.COMPLETED }
        val failed = items.count { it.state == TransferState.FAILED }
        // §9.6: SKIPPED is always reported distinctly from FAILED.
        val skipped = items.count { it.state == TransferState.SKIPPED }
        return "$prefix: $done $verb · $failed failed · $skipped skipped — avg ${Fmt.speed(averageSpeed(items))}"
    }

    /** Sum of the live speeds; 0 when nothing is moving (§10.4 uses the same rule). */
    fun averageSpeed(items: List<TransferItem>): Long =
        items.filter { it.state == TransferState.IN_PROGRESS }.sumOf { it.speedBps }

    /** Combined progress across everything still in play (§6.18). */
    fun combinedProgress(items: List<TransferItem>): Int {
        val relevant = items.filterNot { it.state == TransferState.CANCELLED }
        val total = relevant.sumOf { it.totalBytes }
        if (total <= 0) return 0
        val done = relevant.sumOf {
            if (it.state == TransferState.COMPLETED || it.state == TransferState.SKIPPED) {
                it.totalBytes
            } else {
                it.bytesTransferred
            }
        }
        return ((done * 100) / total).toInt().coerceIn(0, 100)
    }

    /**
     * §6.18's notification text: the peer (or "3 phones"), the current file
     * and the combined progress.
     */
    data class NotificationContent(
        val title: String,
        val text: String,
        val progressPercent: Int,
        val showProgress: Boolean,
    )

    fun notification(items: List<TransferItem>, peerLabel: String): NotificationContent {
        val active = items.filter { !it.state.isTerminal }
        val current = items.firstOrNull { it.state == TransferState.IN_PROGRESS }
        val percent = combinedProgress(items)
        return when {
            current != null -> NotificationContent(
                title = peerLabel,
                text = "${current.file.displayName} · $percent%",
                progressPercent = percent,
                showProgress = true,
            )
            active.isNotEmpty() -> NotificationContent(
                title = peerLabel,
                text = "${active.size} waiting · $percent%",
                progressPercent = percent,
                showProgress = true,
            )
            else -> NotificationContent(
                title = peerLabel,
                text = terminalLines(items).firstOrNull() ?: "Idle",
                progressPercent = 100,
                showProgress = false,
            )
        }
    }

    /** "3 phones" once a broadcast is running, the peer's name otherwise (§6.18). */
    fun peerLabel(peerName: String?, peerCount: Int): String = when {
        peerCount > 1 -> "$peerCount phones"
        peerName != null -> peerName
        else -> "Morsecode"
    }
}
