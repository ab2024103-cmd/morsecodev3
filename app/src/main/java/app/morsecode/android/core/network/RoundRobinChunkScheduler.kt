package app.morsecode.android.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * §10.2's LAN send window.
 *
 * Broadcast peers intentionally own independent sockets and independent source
 * streams. That gives isolation, but without a gate a fast worker can keep
 * reading and flushing chunks while a slower one is ready but never scheduled.
 * This scheduler grants at most [windowChunks] consecutive flushed chunks to a
 * peer, then hands the permit to the next waiting peer. It never waits for an
 * idle peer: an absent reader is skipped, preserving INV-B1 and INV-B3.
 */
class RoundRobinChunkScheduler(
    peerIds: List<String>,
    private val windowChunks: Int = DEFAULT_WINDOW_CHUNKS,
) {

    class Permit internal constructor(
        private val scheduler: RoundRobinChunkScheduler,
        private val peerId: String,
    ) {
        private var released = false

        suspend fun release() {
            if (released) return
            released = true
            scheduler.release(peerId)
        }
    }

    private val mutex = Mutex()
    private val active = ArrayList<String>()
    private val waiters = HashMap<String, ArrayList<CompletableDeferred<Unit>>>()
    private var heldBy: String? = null
    private var preferredNext: String? = null
    private var chunksInTurn = 0

    init {
        require(windowChunks > 0) { "chunk window must be positive" }
        for (peer in peerIds) if (!active.contains(peer)) active.add(peer)
    }

    /** Suspends only behind another currently-writing peer; never behind idle work. */
    suspend fun acquire(peerId: String): Permit? {
        while (true) {
            var removed = false
            var acquired = false
            var waiter: CompletableDeferred<Unit>? = null
            mutex.withLock {
                when {
                    !active.contains(peerId) -> removed = true
                    canAcquire(peerId) -> {
                        heldBy = peerId
                        acquired = true
                    }
                    else -> {
                        val next = CompletableDeferred<Unit>()
                        val list = waiters[peerId]
                            ?: ArrayList<CompletableDeferred<Unit>>().also { waiters[peerId] = it }
                        list.add(next)
                        waiter = next
                    }
                }
            }
            if (removed) return null
            if (acquired) return Permit(this, peerId)
            waiter?.await()
        }
    }

    /** Removes a lost/rejected peer and wakes the next waiting peer, if any. */
    suspend fun remove(peerId: String) {
        val wake = mutex.withLock {
            active.remove(peerId)
            waiters.remove(peerId)?.forEach { it.cancel() }
            if (heldBy == peerId) {
                heldBy = null
                chunksInTurn = 0
            }
            if (preferredNext == peerId) preferredNext = null
            val next = chooseWaitingPeer(after = peerId)
            if (next == null) {
                null
            } else {
                preferredNext = next.first
                next.second
            }
        }
        wake?.complete(Unit)
    }

    private fun canAcquire(peerId: String): Boolean {
        if (heldBy != null) return false
        // A waiter that was explicitly woken owns the next turn. A peer that
        // never queued a waiter leaves preferredNext null, so idle work never
        // stalls a healthy peer.
        return preferredNext == null || preferredNext == peerId
    }

    private suspend fun release(peerId: String) {
        var wake: CompletableDeferred<Unit>? = null
        mutex.withLock {
            if (heldBy != peerId) return@withLock
            heldBy = null
            chunksInTurn++
            if (chunksInTurn >= windowChunks) chunksInTurn = 0
            // Do not reserve a turn for a peer which has not actually queued
            // its next frame. That is what keeps a peer which finished after
            // this frame from blocking a ready receiver. With the production
            // one-chunk window, every ready peer gets a turn before any peer
            // can flush another chunk.
            val next = chooseWaitingPeer(after = peerId)
            if (next == null) {
                preferredNext = null
            } else {
                preferredNext = next.first
                wake = next.second
            }
        }
        wake?.complete(Unit)
    }

    /** Returns the next waiting peer plus exactly one waiter to wake. */
    private fun chooseWaitingPeer(after: String): Pair<String, CompletableDeferred<Unit>>? {
        if (active.isEmpty()) return null
        val afterIndex = active.indexOf(after)
        val start = if (afterIndex < 0) 0 else (afterIndex + 1) % active.size
        for (offset in active.indices) {
            val peer = active[(start + offset) % active.size]
            val waiter = takeWaiter(peer)
            if (waiter != null) return peer to waiter
        }
        return null
    }

    private fun takeWaiter(peerId: String): CompletableDeferred<Unit>? {
        val list = waiters[peerId] ?: return null
        if (list.isEmpty()) return null
        val waiter = list.removeAt(0)
        if (list.isEmpty()) waiters.remove(peerId)
        return waiter
    }

    companion object {
        /** One 256 KB frame per peer is the bounded, starvation-free LAN window. */
        const val DEFAULT_WINDOW_CHUNKS = 1
    }
}

/** LAN sessions opt into the shared §10.2 chunk window; Nearby owns its chunks. */
interface ChunkWindowSession {
    fun setChunkScheduler(scheduler: RoundRobinChunkScheduler?)
}
