package app.morsecode.android.core.transfer

import app.morsecode.android.core.data.HistoryStore
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.EngineEvent
import app.morsecode.android.core.model.PeerDelivery
import app.morsecode.android.core.model.PeerSessionState
import app.morsecode.android.core.model.SendResult
import app.morsecode.android.core.model.TransferFile
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.network.ChunkWindowSession
import app.morsecode.android.core.network.RoundRobinChunkScheduler
import app.morsecode.android.core.network.TransportSession
import app.morsecode.android.core.util.PeerPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID

/**
 * §10 THE BROADCAST ENGINE — one sender, many receivers.
 *
 * §10.1's split is the whole design: the [TransferQueue] is authoritative for
 * *what* is being sent, and one [PeerDelivery] per peer is authoritative for
 * *how far that peer has got*. Nothing derives a second copy of either.
 *
 * §10.2's fan-out is **one independent worker per peer**, each with its own
 * session and its own content stream — never one stream shared across sockets.
 * That is also what makes the invariants hold:
 *
 *  - **INV-B1 PEER ISOLATION** — a peer that rejects, drops, errors or is
 *    cancelled touches only its own delivery row.
 *  - **INV-B2 PER-PEER RESUME** — `resumeOffset` is tracked per (file, peer).
 *  - **INV-B3 NO GLOBAL HANG** — the fast peers never wait for the slow one,
 *    because there is no shared step to wait at.
 *  - **INV-B4 VERIFY EVERY PEER** — "Broadcast complete" needs every accepted
 *    peer to have verified every file it accepted.
 *  - **INV-B5 ONE SUMMARY** — one coalesced summary covering all peers.
 */
class BroadcastEngine(
    private val scope: CoroutineScope,
    val queue: TransferQueue = TransferQueue(),
    private val history: HistoryStore? = null,
    private val logStore: LogStore? = null,
    private val timings: TransferEngine.Timings = TransferEngine.Timings(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val deliveriesFlow = MutableStateFlow<List<PeerDelivery>>(emptyList())
    val deliveries: StateFlow<List<PeerDelivery>> get() = deliveriesFlow

    private val eventFlow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EngineEvent> get() = eventFlow

    val items: StateFlow<List<TransferItem>> get() = queue.items

    private val sessions = LinkedHashMap<String, TransportSession>()
    private val workers = HashMap<String, Job>()
    private val pausedPeers: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val cancelledPeers: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** §10.2: bounded chunk turns for LAN sessions, never OS luck. */
    private var chunkScheduler: RoundRobinChunkScheduler? = null

    private var batchId: String = ""
    private var startedAt: Long = 0
    private var summarised = false

    val batch: String get() = batchId

    /**
     * Starts a broadcast. Every peer begins PENDING and moves to SENDING on its
     * own; §6.8.1's "Pending → Accepted → Sending, or Rejected" is per peer,
     * never a batch-wide gate.
     */
    fun start(peers: List<TransportSession>, files: List<TransferFile>): String {
        batchId = UUID.randomUUID().toString()
        startedAt = clock()
        summarised = false
        for (old in sessions.values) (old as? ChunkWindowSession)?.setChunkScheduler(null)
        sessions.clear()
        pausedPeers.clear()
        cancelledPeers.clear()
        chunkScheduler = RoundRobinChunkScheduler(peers.map { it.peerId })
        for (peer in peers) (peer as? ChunkWindowSession)?.setChunkScheduler(chunkScheduler)

        val items = files.map { file ->
            TransferItem(
                id = UUID.randomUUID().toString(),
                batchId = batchId,
                direction = Direction.SENDING,
                state = TransferState.QUEUED,
                file = file,
            )
        }
        queue.add(items, startedAt)

        deliveriesFlow.value = peers.map { session ->
            sessions[session.peerId] = session
            PeerDelivery(
                peerId = session.peerId,
                peerName = session.peerName,
                avatarColor = PeerPalette.colorResFor(session.peerId),
                sessionState = PeerSessionState.ACCEPTED,
                itemStates = items.associate { it.id to TransferState.QUEUED },
            )
        }

        logStore?.i("Broadcast started · ${peers.size} peers · ${files.size} files")
        for (session in peers) startWorker(session)
        return batchId
    }

    /** §6.8.4's [Retry peer] — only that peer's failed files move. */
    fun retryPeer(peerId: String) {
        val session = sessions[peerId] ?: return
        update(peerId) { delivery ->
            delivery.copy(
                sessionState = PeerSessionState.ACCEPTED,
                lastError = null,
                itemStates = delivery.itemStates.mapValues { (_, state) ->
                    if (state == TransferState.FAILED) TransferState.QUEUED else state
                },
            )
        }
        summarised = false
        logStore?.i("Retrying peer $peerId")
        startWorker(session)
    }

    fun pausePeer(peerId: String) {
        pausedPeers.add(peerId)
        scope.launch { runCatching { sessions[peerId]?.pauseOutgoing(currentFileFor(peerId).orEmpty()) } }
    }

    fun resumePeer(peerId: String) {
        pausedPeers.remove(peerId)
        update(peerId) { delivery ->
            delivery.copy(
                itemStates = delivery.itemStates.mapValues { (_, state) ->
                    if (state == TransferState.PAUSED) TransferState.QUEUED else state
                },
            )
        }
        sessions[peerId]?.let { startWorker(it) }
    }

    /** §6.8.2: Pause and End apply to ALL peers; per-peer lives in the sheet. */
    fun pauseAll() = deliveriesFlow.value.forEach { pausePeer(it.peerId) }

    fun cancelAll() {
        for (delivery in deliveriesFlow.value) {
            cancelledPeers.add(delivery.peerId)
            scope.launch { runCatching { sessions[delivery.peerId]?.close("ended by user") } }
        }
    }

    /** A peer that rejected the invitation never becomes a target (§10.2). */
    fun markRejected(peerId: String, peerName: String, reason: String) {
        val existing = deliveriesFlow.value.any { it.peerId == peerId }
        if (!existing) {
            deliveriesFlow.value = deliveriesFlow.value + PeerDelivery(
                peerId = peerId,
                peerName = peerName,
                avatarColor = PeerPalette.colorResFor(peerId),
                sessionState = PeerSessionState.REJECTED,
                lastError = reason,
            )
        } else {
            update(peerId) { it.copy(sessionState = PeerSessionState.REJECTED, lastError = reason) }
        }
        logStore?.i("Peer $peerName rejected the broadcast: $reason")
        maybeSummarise()
    }

    // ----- Per-peer worker --------------------------------------------------

    private fun startWorker(session: TransportSession) {
        if (workers[session.peerId]?.isActive == true) return
        workers[session.peerId] = scope.launch {
            update(session.peerId) { it.copy(sessionState = PeerSessionState.SENDING) }
            while (true) {
                if (session.peerId in cancelledPeers) break
                if (!session.isAlive) {
                    // INV-B1: this peer is lost; everyone else keeps going.
                    onPeerLost(session.peerId, "Connection lost")
                    break
                }
                val next = nextItemFor(session.peerId) ?: break
                sendToPeer(session, next)
            }
            finishPeerIfDone(session.peerId)
            maybeSummarise()
        }
    }

    private fun nextItemFor(peerId: String): TransferItem? {
        val delivery = deliveriesFlow.value.firstOrNull { it.peerId == peerId } ?: return null
        val items = queue.itemsOf(batchId)
        return items.firstOrNull { delivery.itemStates[it.id] == TransferState.QUEUED }
    }

    private fun currentFileFor(peerId: String): String? =
        deliveriesFlow.value.firstOrNull { it.peerId == peerId }
            ?.itemStates?.entries?.firstOrNull { it.value == TransferState.IN_PROGRESS }?.key

    private suspend fun sendToPeer(session: TransportSession, item: TransferItem) {
        setItemState(session.peerId, item.id, TransferState.IN_PROGRESS)

        val result = try {
            awaitSend(session, item)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            SendResult.Failed(error.message ?: error.javaClass.simpleName)
        }

        when (result) {
            is SendResult.Completed -> {
                // §10.2: a file is done for a peer only when THAT peer has
                // verified it — which is what Completed means on the wire.
                setItemState(session.peerId, item.id, TransferState.COMPLETED)
                addBytes(session.peerId, item.totalBytes - offsetFor(session.peerId, item.id))
                history?.record(item, session.peerName, nowMillis = clock())
            }
            is SendResult.SkippedAlreadyPresent ->
                setItemState(session.peerId, item.id, TransferState.SKIPPED)
            is SendResult.Paused -> setItemState(session.peerId, item.id, TransferState.PAUSED)
            is SendResult.Cancelled -> setItemState(session.peerId, item.id, TransferState.CANCELLED)
            is SendResult.Failed -> {
                if (!session.isAlive) {
                    onPeerLost(session.peerId, result.error)
                } else {
                    setItemState(session.peerId, item.id, TransferState.FAILED, result.error)
                    emit(EngineEvent.ItemFailed(item.id, result.error))
                }
            }
        }
    }

    /** INV-B3: the same bounded await as 1:1, per peer, so nothing hangs. */
    private suspend fun awaitSend(session: TransportSession, item: TransferItem): SendResult =
        coroutineScope {
            val outcome = CompletableDeferred<SendResult>()
            var lastProgressAt = clock()
            val peerId = session.peerId
            val startOffset = offsetFor(peerId, item.id)

            val sendJob = launch {
                val result = try {
                    session.sendFile(item.copy(resumeOffset = startOffset), item.sha256) { sent, speed ->
                        lastProgressAt = clock()
                        onPeerProgress(peerId, item.id, sent, speed)
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
                    if (!session.isAlive) {
                        outcome.complete(SendResult.Failed("Connection lost"))
                        break
                    }
                    val interrupting = peerId in pausedPeers || peerId in cancelledPeers
                    if (interrupting) {
                        if (interruptedAt == 0L) interruptedAt = clock()
                        if (clock() - interruptedAt >= timings.terminalFallbackMs) {
                            outcome.complete(
                                if (peerId in cancelledPeers) SendResult.Cancelled else SendResult.Paused,
                            )
                            break
                        }
                    } else {
                        interruptedAt = 0L
                    }
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

    // ----- Delivery bookkeeping --------------------------------------------

    private fun onPeerProgress(peerId: String, itemId: String, sent: Long, speed: Long) {
        update(peerId) { delivery ->
            // INV-B2: the offset this peer would resume from, for this file.
            delivery.copy(
                resumeOffsets = delivery.resumeOffsets + (itemId to sent),
                speedBps = speed,
            )
        }
    }

    private fun offsetFor(peerId: String, itemId: String): Long =
        deliveriesFlow.value.firstOrNull { it.peerId == peerId }?.resumeOffsets?.get(itemId) ?: 0

    private fun addBytes(peerId: String, bytes: Long) {
        update(peerId) { it.copy(bytesSent = it.bytesSent + bytes.coerceAtLeast(0)) }
    }

    private fun setItemState(
        peerId: String,
        itemId: String,
        state: TransferState,
        error: String? = null,
    ) {
        update(peerId) { delivery ->
            delivery.copy(
                itemStates = delivery.itemStates + (itemId to state),
                lastError = error ?: delivery.lastError,
                speedBps = if (state.isTerminal) 0 else delivery.speedBps,
            )
        }
        // The shared queue shows the most advanced state any peer has reached,
        // because §10.1 makes the queue the authority on the FILES, not on any
        // one peer's progress.
        val states = deliveriesFlow.value.mapNotNull { it.itemStates[itemId] }
        queue.update(itemId) { item ->
            item.copy(
                state = when {
                    states.any { it == TransferState.IN_PROGRESS } -> TransferState.IN_PROGRESS
                    states.all { it.isTerminal } -> if (states.any { it == TransferState.COMPLETED }) {
                        TransferState.COMPLETED
                    } else {
                        states.firstOrNull() ?: item.state
                    }
                    else -> TransferState.QUEUED
                },
            )
        }
    }

    /** INV-B1: losing a peer greys ITS rows and leaves every other peer alone. */
    private fun onPeerLost(peerId: String, reason: String) {
        update(peerId) { delivery ->
            delivery.copy(
                sessionState = PeerSessionState.LOST,
                lastError = reason,
                speedBps = 0,
                itemStates = delivery.itemStates.mapValues { (_, state) ->
                    if (state == TransferState.IN_PROGRESS || state == TransferState.QUEUED) {
                        TransferState.PAUSED
                    } else {
                        state
                    }
                },
            )
        }
        scope.launch { chunkScheduler?.remove(peerId) }
        emit(EngineEvent.PeerDisconnected(peerId, reason))
        logStore?.w("Broadcast peer $peerId lost: $reason")
    }

    /** INV-B4: verified means every file this peer accepted came out right. */
    private fun finishPeerIfDone(peerId: String) {
        var finished = false
        update(peerId) { delivery ->
            if (!delivery.isFinished) return@update delivery
            finished = true
            val verified = delivery.itemStates.values.all {
                it == TransferState.COMPLETED || it == TransferState.SKIPPED
            }
            delivery.copy(
                sessionState = if (verified) PeerSessionState.DONE else delivery.sessionState,
                verified = verified,
                speedBps = 0,
            )
        }
        if (finished) scope.launch { chunkScheduler?.remove(peerId) }
    }

    /** INV-B5: ONE coalesced summary covering all peers. */
    private fun maybeSummarise() {
        val accepted = BroadcastStats.acceptedPeers(deliveriesFlow.value)
        if (accepted.isEmpty()) return
        if (!accepted.all { it.isFinished || it.sessionState == PeerSessionState.LOST }) return
        if (summarised) return
        summarised = true

        val stats = BroadcastStats.complete(queue.itemsOf(batchId), deliveriesFlow.value, clock() - startedAt)
        logStore?.i(
            "Broadcast finished · ${stats.verifiedPhones}/${stats.phones} verified · " +
                "${stats.deliveries} deliveries",
        )
        emit(
            EngineEvent.BatchCompleted(
                app.morsecode.android.core.model.BatchSummary(
                    batchId = batchId,
                    sent = stats.deliveries - stats.failed - stats.skipped,
                    failed = stats.failed,
                    skipped = stats.skipped,
                    totalBytes = stats.sentBytes,
                    elapsedMillis = clock() - startedAt,
                ),
            ),
        )
    }

    private fun update(peerId: String, change: (PeerDelivery) -> PeerDelivery) {
        deliveriesFlow.value = deliveriesFlow.value.map { delivery ->
            if (delivery.peerId == peerId) change(delivery) else delivery
        }
    }

    private fun emit(event: EngineEvent) {
        if (!eventFlow.tryEmit(event)) scope.launch { eventFlow.emit(event) }
    }
}
