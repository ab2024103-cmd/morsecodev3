package app.morsecode.android.core.transfer

import app.morsecode.android.core.model.BatchSummary
import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * §20.1 SINGLE SOURCE OF TRUTH: TransferQueue is the one source for the queue
 * view, the notification and the batch summary. There are no derived copies —
 * the summary in §9.6 is computed from these same items, never recomputed
 * independently, which is what keeps the summary and the rows from
 * disagreeing.
 *
 * All mutation is synchronised and every change republishes an immutable list,
 * so a UI that renders the flow can never see a half-applied update (§8.2:
 * anything the UI reacts to is a Flow, never a plain field).
 */
class TransferQueue(private val onChanged: ((List<TransferItem>) -> Unit)? = null) {

    private val lock = Any()
    private val order = ArrayList<String>()
    private val byId = HashMap<String, TransferItem>()
    private val batchStartedAt = HashMap<String, Long>()

    private val itemsFlow = MutableStateFlow<List<TransferItem>>(emptyList())

    val items: StateFlow<List<TransferItem>> get() = itemsFlow

    fun snapshot(): List<TransferItem> = itemsFlow.value

    fun item(id: String): TransferItem? = synchronized(lock) { byId[id] }

    fun add(items: List<TransferItem>, nowMillis: Long = System.currentTimeMillis()) {
        if (items.isEmpty()) return
        synchronized(lock) {
            for (item in items) {
                if (!byId.containsKey(item.id)) order.add(item.id)
                byId[item.id] = item
                batchStartedAt.getOrPut(item.batchId) { nowMillis }
            }
        }
        publish()
    }

    /**
     * Applies [change] to one item. Returns the updated item, or null when the
     * id is unknown — callers treat that as "already gone", never as a crash.
     */
    fun update(id: String, change: (TransferItem) -> TransferItem): TransferItem? {
        val updated = synchronized(lock) {
            val current = byId[id] ?: return@synchronized null
            val next = change(current)
            byId[id] = next
            next
        }
        if (updated != null) publish()
        return updated
    }

    fun setState(id: String, state: TransferState, error: String? = null): TransferItem? =
        update(id) { it.copy(state = state, lastError = error ?: it.lastError) }

    fun setProgress(id: String, bytesTransferred: Long, speedBps: Long): TransferItem? =
        update(id) { it.copy(bytesTransferred = bytesTransferred, speedBps = speedBps) }

    /** §9.2: the worker picks the FIRST queued send, in insertion order. */
    fun nextQueued(direction: Direction = Direction.SENDING): TransferItem? = synchronized(lock) {
        for (id in order) {
            val item = byId[id] ?: continue
            if (item.direction == direction && item.state == TransferState.QUEUED) return item
        }
        null
    }

    fun itemsOf(batchId: String): List<TransferItem> = synchronized(lock) {
        order.mapNotNull { byId[it] }.filter { it.batchId == batchId }
    }

    fun activeBatches(): List<String> = synchronized(lock) {
        order.mapNotNull { byId[it] }.map { it.batchId }.distinct()
    }

    /** True when nothing in the batch can still move by itself (§9.6). */
    fun isBatchSettled(batchId: String): Boolean =
        itemsOf(batchId).let { items -> items.isNotEmpty() && items.all { it.state.isTerminal } }

    /**
     * INV-3: when a session is lost, every IN_PROGRESS and QUEUED send becomes
     * PAUSED with "Connection lost" — never stuck, never failed. The resume
     * offset is whatever was actually transferred.
     */
    fun pauseAllForConnectionLoss(reason: String): Int {
        var count = 0
        synchronized(lock) {
            for (id in order) {
                val item = byId[id] ?: continue
                if (item.direction != Direction.SENDING) continue
                if (item.state != TransferState.IN_PROGRESS && item.state != TransferState.QUEUED) continue
                byId[id] = item.copy(
                    state = TransferState.PAUSED,
                    resumeOffset = item.bytesTransferred,
                    speedBps = 0,
                    lastError = reason,
                )
                count++
            }
        }
        if (count > 0) publish()
        return count
    }

    /**
     * INV-3, the other half: on a new session every PAUSED send is re-queued
     * and resumes from its offset. Items the user paused deliberately are
     * identified by [userPaused] and left alone.
     */
    fun requeuePaused(userPaused: Set<String>): Int {
        var count = 0
        synchronized(lock) {
            for (id in order) {
                val item = byId[id] ?: continue
                if (item.direction != Direction.SENDING) continue
                if (item.state != TransferState.PAUSED) continue
                if (item.id in userPaused) continue
                byId[id] = item.copy(state = TransferState.QUEUED, lastError = null)
                count++
            }
        }
        if (count > 0) publish()
        return count
    }

    /**
     * §6.7 drag-reorder. Only a QUEUED item can move, and it can only land
     * among QUEUED items: reordering an active or finished row is disabled in
     * the UI and refused here, so it can never become a silent no-op.
     *
     * @return true when the queue actually changed.
     */
    fun move(itemId: String, targetIndex: Int): Boolean {
        val moved = synchronized(lock) {
            val item = byId[itemId] ?: return@synchronized false
            if (item.state != TransferState.QUEUED) return@synchronized false
            val from = order.indexOf(itemId)
            if (from < 0) return@synchronized false
            val bounded = targetIndex.coerceIn(0, order.size - 1)
            val target = byId[order[bounded]]
            if (target != null && target.state != TransferState.QUEUED) return@synchronized false
            if (from == bounded) return@synchronized false
            order.removeAt(from)
            order.add(bounded, itemId)
            true
        }
        if (moved) publish()
        return moved
    }

    /** §6.7 [Send now]: move a queued item to the head of the queue. */
    fun sendNow(itemId: String): Boolean {
        val firstQueued = synchronized(lock) {
            order.indexOfFirst { id -> byId[id]?.state == TransferState.QUEUED }
        }
        if (firstQueued < 0) return false
        return move(itemId, firstQueued)
    }

    /** §6.7 [Remove] and [Clear completed]. */
    fun remove(itemId: String): Boolean {
        val removed = synchronized(lock) {
            if (!byId.containsKey(itemId)) return@synchronized false
            byId.remove(itemId)
            order.remove(itemId)
            true
        }
        if (removed) publish()
        return removed
    }

    fun clearCompleted(): Int {
        val cleared = synchronized(lock) {
            val done = order.filter { id ->
                val state = byId[id]?.state
                state == TransferState.COMPLETED || state == TransferState.SKIPPED ||
                    state == TransferState.CANCELLED
            }
            for (id in done) {
                byId.remove(id)
                order.remove(id)
            }
            done.size
        }
        if (cleared > 0) publish()
        return cleared
    }

    /** §9.6 [Retry failed]: only the failed items, and their retry count resets. */
    fun retryFailed(batchId: String): Int {
        var count = 0
        synchronized(lock) {
            for (id in order) {
                val item = byId[id] ?: continue
                if (item.batchId != batchId || item.state != TransferState.FAILED) continue
                byId[id] = item.copy(
                    state = TransferState.QUEUED,
                    retryCount = 0,
                    lastError = null,
                )
                count++
            }
        }
        if (count > 0) publish()
        return count
    }

    /**
     * §9.6 the coalesced summary, derived from these items — the same objects
     * the live rows rendered. SKIPPED is counted separately from FAILED.
     */
    fun summaryOf(batchId: String, nowMillis: Long = System.currentTimeMillis()): BatchSummary {
        val items = itemsOf(batchId)
        val startedAt = synchronized(lock) { batchStartedAt[batchId] } ?: nowMillis
        return BatchSummary(
            batchId = batchId,
            sent = items.count { it.state == TransferState.COMPLETED },
            failed = items.count { it.state == TransferState.FAILED },
            skipped = items.count { it.state == TransferState.SKIPPED },
            totalBytes = items.filter { it.state == TransferState.COMPLETED }.sumOf { it.bytesTransferred },
            elapsedMillis = (nowMillis - startedAt).coerceAtLeast(0),
        )
    }

    fun clear() {
        synchronized(lock) {
            order.clear()
            byId.clear()
            batchStartedAt.clear()
        }
        publish()
    }

    private fun publish() {
        val snapshot = synchronized(lock) { order.mapNotNull { byId[it] } }
        itemsFlow.value = snapshot
        onChanged?.invoke(snapshot)
    }
}
