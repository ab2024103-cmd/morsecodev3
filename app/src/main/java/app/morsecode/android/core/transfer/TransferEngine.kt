package app.morsecode.android.core.transfer

import app.morsecode.android.core.data.HistoryStore
import app.morsecode.android.core.data.JournalStore
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.EngineEvent
import app.morsecode.android.core.model.SendResult
import app.morsecode.android.core.model.SessionState
import app.morsecode.android.core.model.TransferFile
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.network.TransportSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID

/**
 * §9 THE TRANSFER ENGINE — the single source of truth for queue and session
 * state (§8.2). Fragments observe [items], [session] and [events] and render;
 * they never own transfer state.
 *
 * The outgoing worker (§9.2) is the most important loop in the app, and the
 * three invariants it exists to satisfy are implemented here rather than in
 * any transport:
 *
 *  - **INV-1 NEVER HANG.** Every send is awaited through [awaitSend], which
 *    races the transport against a watchdog. The watchdog completes the wait
 *    when the session dies (a), 3 seconds after a pause or cancel that the
 *    transport never acknowledged (b) — several OEM stacks emit no terminal
 *    update after a cancelled payload — and when no progress has arrived for
 *    the idle timeout (c). The await is therefore always bounded.
 *  - **INV-2 PAUSE/CANCEL NEVER BLOCKS OTHERS.** Pause and cancel act on one
 *    item id. The worker returns from that item and picks the next queued file
 *    on its very next turn.
 *  - **INV-3 SESSION RESTART UNSTICKS EVERYTHING.** Losing a session turns
 *    every IN_PROGRESS and QUEUED send into PAUSED "Connection lost" — never
 *    stuck, never failed — and a new session re-queues them from their offset.
 *
 * Time constants are injectable so the tests can drive the same code paths in
 * milliseconds instead of seconds. The defaults are the specified values.
 */
class TransferEngine(
    private val scope: CoroutineScope,
    val queue: TransferQueue = TransferQueue(),
    private val journal: JournalStore? = null,
    private val history: HistoryStore? = null,
    private val logStore: LogStore? = null,
    private val timings: Timings = Timings(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    data class Timings(
        /** §9.3: auto-retry up to 3 times, 800 ms apart. */
        val maxRetries: Int = 3,
        val retryDelayMs: Long = 800,
        /** INV-1(b) terminal fallback. */
        val terminalFallbackMs: Long = 3_000,
        /** INV-1(c): no progress for this long ends the wait (§11.2 read timeout). */
        val idleTimeoutMs: Long = 25_000,
        /** §9.6: one coalesced summary ~2.5 s after the last activity. */
        val batchWindowMs: Long = 2_500,
        val watchTickMs: Long = 100,
    )

    val items: StateFlow<List<TransferItem>> get() = queue.items

    private val sessionFlow = MutableStateFlow<SessionState>(SessionState.NeverConnected)
    val session: StateFlow<SessionState> get() = sessionFlow

    private val eventFlow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EngineEvent> get() = eventFlow

    private var current: TransportSession? = null
    private var worker: Job? = null

    /** Items the USER paused; INV-3 must not re-queue these behind their back. */
    private val userPaused: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val pauseRequested: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val cancelRequested: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val batchTimers = HashMap<String, Job>()
    private val summarisedBatches: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** §3.6: items shared into the app before a peer exists are held here. */
    private var pendingShare: List<TransferFile> = emptyList()

    // ----- Queue intake -----------------------------------------------------

    fun newBatchId(): String = UUID.randomUUID().toString()

    /**
     * Enqueues a picker session as ONE batch (§9.6). The worker starts itself
     * if a session is live; if not, the items sit QUEUED and INV-3 picks them
     * up the moment one is.
     */
    fun enqueue(
        files: List<TransferFile>,
        batchId: String = newBatchId(),
        relativePaths: Map<String, String> = emptyMap(),
        shaByName: Map<String, String> = emptyMap(),
    ): String {
        val peerId = (sessionFlow.value as? SessionState.Connected)?.peerId
        val items = files.map { file ->
            TransferItem(
                id = UUID.randomUUID().toString(),
                batchId = batchId,
                direction = Direction.SENDING,
                state = TransferState.QUEUED,
                file = file,
                relativePath = relativePaths[file.displayName],
                peerId = peerId,
                sha256 = shaByName[file.displayName],
            )
        }
        queue.add(items, clock())
        summarisedBatches.remove(batchId)
        journalNow()
        logStore?.i("Queued ${items.size} item(s) in batch $batchId")
        startWorker()
        return batchId
    }

    /** §3.6: shared items queue straight into the send flow once a peer accepts. */
    fun holdShare(files: List<TransferFile>) {
        pendingShare = files
        logStore?.i("Holding ${files.size} shared item(s) until a peer is chosen")
    }

    fun hasPendingShare(): Boolean = pendingShare.isNotEmpty()

    fun takePendingShare(): List<TransferFile> {
        val held = pendingShare
        pendingShare = emptyList()
        return held
    }

    // ----- Incoming files (§8.2: owned here, never by a screen) -------------

    /**
     * Registers a file the peer is about to send. It appears in the SAME queue
     * as outgoing work, with direction RECEIVING, so §6.6's "In:" and "Out:"
     * lines and the batch summary read from one list (§20.1).
     */
    fun registerIncoming(
        batchId: String,
        displayName: String,
        sizeBytes: Long,
        mime: String?,
        relativePath: String?,
        peerId: String,
    ): TransferItem {
        val item = TransferItem(
            id = UUID.randomUUID().toString(),
            batchId = batchId,
            direction = Direction.RECEIVING,
            state = TransferState.IN_PROGRESS,
            file = TransferFile(
                displayName = displayName,
                uri = android.net.Uri.EMPTY,
                mime = mime,
                size = sizeBytes,
            ),
            relativePath = relativePath,
            peerId = peerId,
        )
        queue.add(listOf(item), clock())
        summarisedBatches.remove(batchId)
        journalNow()
        return item
    }

    fun incomingProgress(itemId: String, bytes: Long) {
        queue.update(itemId) { it.copy(bytesTransferred = bytes) }
    }

    /**
     * §9.6: the receiving side finishes through the SAME complete() path as
     * the sending side. A receive that leaves no history row while the send
     * side has one is the defect that rule exists to prevent.
     */
    fun incomingResult(
        itemId: String,
        state: TransferState,
        peerName: String,
        error: String? = null,
        path: String? = null,
    ) {
        val item = queue.update(itemId) {
            it.copy(
                state = state,
                bytesTransferred = if (state == TransferState.COMPLETED) it.totalBytes else it.bytesTransferred,
                speedBps = 0,
                lastError = error,
            )
        } ?: return
        journalNow()
        if (state.isTerminal && state != TransferState.CANCELLED) {
            history?.record(item, peerName, path, clock())
        }
        if (state == TransferState.FAILED && error != null) {
            emit(EngineEvent.ItemFailed(itemId, error))
        }
        scheduleBatchSummary(item.batchId)
    }

    // ----- Session lifecycle ------------------------------------------------

    fun onSessionConnected(transportSession: TransportSession) {
        current = transportSession
        sessionFlow.value = SessionState.Connected(transportSession.peerId, transportSession.peerName)
        emit(EngineEvent.PeerConnected(transportSession.peerId, transportSession.peerName))
        // INV-3: a new session re-queues everything the last one left paused.
        val requeued = queue.requeuePaused(userPaused.toSet())
        if (requeued > 0) logStore?.i("Re-queued $requeued paused item(s) on the new session")
        journalNow()
        startWorker()
    }

    /**
     * INV-3 and INV-1(a). Called when the transport reports a loss and when
     * the user ends the session; the queue is left PAUSED and resumable, never
     * FAILED and never stuck IN_PROGRESS.
     */
    fun onSessionLost(reason: String) {
        val previous = sessionFlow.value
        current = null
        worker?.cancel()
        worker = null
        val paused = queue.pauseAllForConnectionLoss(reason)
        journalNow()
        if (previous is SessionState.Connected) {
            sessionFlow.value = SessionState.Closed(previous.peerId, previous.peerName, reason)
            emit(EngineEvent.PeerDisconnected(previous.peerId, reason))
        }
        logStore?.w("Session lost ($reason) — $paused item(s) paused")
    }

    // ----- User actions (INV-2) --------------------------------------------

    /** Pauses ONE item. Everything else keeps moving. */
    fun pause(itemId: String) {
        val item = queue.item(itemId) ?: return
        userPaused.add(itemId)
        if (item.state == TransferState.IN_PROGRESS) {
            pauseRequested.add(itemId)
            scope.launch { runCatching { current?.pauseOutgoing(itemId) } }
        } else {
            queue.update(itemId) {
                it.copy(state = TransferState.PAUSED, resumeOffset = it.bytesTransferred, speedBps = 0)
            }
            journalNow()
        }
    }

    fun resume(itemId: String) {
        userPaused.remove(itemId)
        queue.update(itemId) {
            if (it.state == TransferState.PAUSED) {
                it.copy(state = TransferState.QUEUED, lastError = null)
            } else {
                it
            }
        }
        journalNow()
        startWorker()
    }

    /** Cancels ONE item. Everything else keeps moving. */
    fun cancel(itemId: String) {
        val item = queue.item(itemId) ?: return
        if (item.state == TransferState.IN_PROGRESS) {
            cancelRequested.add(itemId)
            scope.launch { runCatching { current?.cancelTransfer(itemId) } }
        } else {
            queue.setState(itemId, TransferState.CANCELLED)
            journalNow()
            scheduleBatchSummary(item.batchId)
        }
    }

    /** §9.6 [Retry failed] — only the failed items of that batch. */
    fun retryFailed(batchId: String) {
        val count = queue.retryFailed(batchId)
        if (count > 0) {
            summarisedBatches.remove(batchId)
            journalNow()
            logStore?.i("Retrying $count failed item(s) in batch $batchId")
            startWorker()
        }
    }

    // ----- The worker (§9.2) ------------------------------------------------

    private fun startWorker() {
        if (worker?.isActive == true) return
        if (current == null) return
        worker = scope.launch {
            while (true) {
                val active = current ?: break
                if (!active.isAlive) {
                    onSessionLost("Connection lost")
                    break
                }
                val next = queue.nextQueued(Direction.SENDING) ?: break
                transferOne(active, next)
            }
        }
    }

    private suspend fun transferOne(transportSession: TransportSession, item: TransferItem) {
        queue.update(item.id) { it.copy(state = TransferState.IN_PROGRESS, lastError = null) }
        journalNow()

        // §9.3: each file is wrapped in its own try/catch, so a failure on file
        // 3 of 6 logs, marks that item FAILED and continues with 4-6.
        // CancellationException is rethrown unmodified; nothing else is allowed
        // to reach the coroutine's default handler, which would crash both ends.
        val result = try {
            awaitSend(transportSession, item)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            SendResult.Failed(error.javaClass.simpleName)
        }

        pauseRequested.remove(item.id)
        cancelRequested.remove(item.id)
        applyResult(transportSession, item.id, result)
        journalNow()
        scheduleBatchSummary(item.batchId)
    }

    /**
     * INV-1: the bounded await. The transport's own completion and the watchdog
     * race to complete one deferred; whichever arrives first wins and the other
     * is cancelled.
     */
    private suspend fun awaitSend(
        transportSession: TransportSession,
        item: TransferItem,
    ): SendResult = coroutineScope {
        val outcome = CompletableDeferred<SendResult>()
        var lastProgressAt = clock()

        // Children of this scope, so cancelling the worker (a lost session, a
        // stopped service) cannot leave a send or a watchdog running.
        val sendJob = launch {
            val result = try {
                transportSession.sendFile(item, item.sha256) { sent, speed ->
                    lastProgressAt = clock()
                    queue.setProgress(item.id, sent, speed)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                SendResult.Failed(error.message ?: error.javaClass.simpleName)
            }
            outcome.complete(result)
        }

        val watchdog = launch {
            var interruptedAt = 0L
            while (!outcome.isCompleted) {
                delay(timings.watchTickMs)

                // (a) the session died under us.
                if (!transportSession.isAlive) {
                    outcome.complete(SendResult.Failed("Connection lost"))
                    break
                }

                // (b) pause/cancel asked for, transport silent: force the
                // terminal outcome after the fallback window.
                val interrupting = item.id in pauseRequested || item.id in cancelRequested
                if (interrupting) {
                    if (interruptedAt == 0L) interruptedAt = clock()
                    if (clock() - interruptedAt >= timings.terminalFallbackMs) {
                        val forced = if (item.id in cancelRequested) {
                            SendResult.Cancelled
                        } else {
                            SendResult.Paused
                        }
                        logStore?.w("Terminal fallback fired for ${item.file.displayName}")
                        outcome.complete(forced)
                        break
                    }
                } else {
                    interruptedAt = 0L
                }

                // (c) nothing has moved for the idle timeout.
                if (clock() - lastProgressAt >= timings.idleTimeoutMs) {
                    outcome.complete(SendResult.Failed("No progress"))
                    break
                }
            }
        }

        val result = outcome.await()
        sendJob.cancel()
        watchdog.cancel()
        result
    }

    /** §9.3 OUTCOMES AND RETRY, exactly as specified. */
    private suspend fun applyResult(
        transportSession: TransportSession,
        itemId: String,
        result: SendResult,
    ) {
        when (result) {
            is SendResult.Completed -> {
                val done = queue.update(itemId) {
                    it.copy(
                        state = TransferState.COMPLETED,
                        bytesTransferred = it.totalBytes,
                        speedBps = 0,
                        lastError = null,
                    )
                }
                complete(done, transportSession.peerName)
            }

            is SendResult.SkippedAlreadyPresent -> {
                // SKIPPED is never folded into FAILED (§9.6).
                val skipped = queue.update(itemId) {
                    it.copy(state = TransferState.SKIPPED, speedBps = 0, lastError = result.reason)
                }
                complete(skipped, transportSession.peerName)
            }

            is SendResult.Paused -> {
                queue.update(itemId) {
                    it.copy(
                        state = TransferState.PAUSED,
                        resumeOffset = it.bytesTransferred,
                        speedBps = 0,
                    )
                }
            }

            is SendResult.Cancelled -> {
                // A cancel is the user's own action, so it is not history.
                queue.update(itemId) {
                    it.copy(state = TransferState.CANCELLED, speedBps = 0)
                }
                userPaused.remove(itemId)
            }

            is SendResult.Failed -> applyFailure(transportSession, itemId, result.error)
        }
    }

    private suspend fun applyFailure(
        transportSession: TransportSession,
        itemId: String,
        error: String,
    ) {
        val item = queue.item(itemId) ?: return
        val sessionAlive = transportSession.isAlive && current != null

        if (!sessionAlive) {
            // §9.3 / INV-3: a dead session PAUSES, it never FAILS.
            queue.update(itemId) {
                it.copy(
                    state = TransferState.PAUSED,
                    resumeOffset = it.bytesTransferred,
                    speedBps = 0,
                    lastError = "Connection lost",
                )
            }
            return
        }

        if (item.retryCount < timings.maxRetries) {
            queue.update(itemId) {
                it.copy(
                    state = TransferState.QUEUED,
                    retryCount = it.retryCount + 1,
                    resumeOffset = it.bytesTransferred,
                    speedBps = 0,
                    lastError = error,
                )
            }
            logStore?.w("Retry ${item.retryCount + 1}/${timings.maxRetries} for ${item.file.displayName}: $error")
            delay(timings.retryDelayMs)
            return
        }

        val failed = queue.update(itemId) {
            it.copy(state = TransferState.FAILED, speedBps = 0, lastError = error)
        }
        logStore?.e("Failed ${item.file.displayName} after ${timings.maxRetries} retries: $error")
        emit(EngineEvent.ItemFailed(itemId, error))
        complete(failed, transportSession.peerName)
    }

    /**
     * §9.6: BOTH directions record history through this one path. A send that
     * leaves no history row while the receive side has one is a defect, so
     * there is exactly one caller-visible completion path and it is here.
     */
    private fun complete(item: TransferItem?, peerName: String) {
        if (item == null) return
        history?.record(item, peerName, nowMillis = clock())
    }

    // ----- Batch summary (§9.6) --------------------------------------------

    private fun scheduleBatchSummary(batchId: String) {
        batchTimers.remove(batchId)?.cancel()
        batchTimers[batchId] = scope.launch {
            // Coalescing window: one summary per batch, ~2.5 s after the last
            // activity — never one popup per file.
            delay(timings.batchWindowMs)
            if (!queue.isBatchSettled(batchId)) return@launch
            if (!summarisedBatches.add(batchId)) return@launch
            val summary = queue.summaryOf(batchId, clock())
            logStore?.i(
                "Batch $batchId complete: ${summary.sent} sent, " +
                    "${summary.failed} failed, ${summary.skipped} skipped",
            )
            emit(EngineEvent.BatchCompleted(summary))
            journalNow()
        }
    }

    // ----- Restart recovery (§9.7) -----------------------------------------

    /**
     * §9.7: if an unfinished journal entry exists at launch, the UI asks
     * "Resume interrupted transfer?" naming the peer and the remaining items.
     * Nothing is resumed or discarded without that answer, which is why this
     * only REPORTS and [restoreJournal] / [discardJournal] are separate calls.
     */
    fun pendingResume(): EngineEvent.ResumeAvailable? {
        val entries = journal?.read().orEmpty()
        if (entries.isEmpty()) return null
        val peerName = entries.firstOrNull { it.peerName != null }?.peerName ?: UNKNOWN_PEER
        return EngineEvent.ResumeAvailable(peerName, entries.size)
    }

    /** [Resume]: the items come back PAUSED and resume from their offsets. */
    fun restoreJournal(): Int {
        val entries = journal?.read().orEmpty()
        if (entries.isEmpty()) return 0
        queue.add(entries.map { it.item }, clock())
        logStore?.i("Restored ${entries.size} journaled item(s) as paused")
        return entries.size
    }

    /** [Discard]: the journal goes, the queue does not silently inherit it. */
    fun discardJournal() {
        journal?.clear()
        logStore?.i("Discarded the interrupted transfer journal")
    }

    private fun journalNow() {
        val peerName = when (val state = sessionFlow.value) {
            is SessionState.Connected -> state.peerName
            is SessionState.Closed -> state.peerName
            else -> null
        }
        journal?.write(queue.snapshot(), peerName, clock())
    }

    private fun emit(event: EngineEvent) {
        if (!eventFlow.tryEmit(event)) {
            scope.launch { eventFlow.emit(event) }
        }
    }

    private companion object {
        const val UNKNOWN_PEER = "the other phone"
    }
}
