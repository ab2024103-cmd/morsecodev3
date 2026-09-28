package app.morsecode.android.core.webshare

import app.morsecode.android.core.model.SendResult
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * §7.8 PUSH (phone → browser).
 *
 * An accepted offer is deliberately not complete. Browsers and media elements
 * commonly request byte ranges out of order, so delivery becomes Completed only
 * after the union of ranges actually read by the HTTP response covers the file.
 * A whole-file download is merely the one-range special case.
 */
class PushOffers(private val clock: () -> Long = { System.currentTimeMillis() }) {

    enum class State { OFFERED, DOWNLOADING, DELIVERED, DISMISSED, EXPIRED }

    data class Offer(
        val id: String,
        val sessionId: String,
        val fileId: String,
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val state: State,
        val offeredAt: Long,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id)
            .put("fileId", fileId)
            .put("name", name)
            .put("size", sizeBytes)
            .put("state", state.name.lowercase())
    }

    /** End-exclusive byte span; the empty span carries no delivery progress. */
    data class Range(val start: Long, val endExclusive: Long)

    private val offers = ConcurrentHashMap<String, Offer>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<SendResult>>()
    private val deliveredRanges = HashMap<String, MutableList<Range>>()
    private val listeners = ArrayList<(String) -> Unit>()
    private val lock = Any()

    /** The SSE hub listens here; callbacks receive only the owning session id. */
    fun addListener(listener: (String) -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    /** Called by the transport when the phone sends to a browser peer. */
    fun offer(
        sessionId: String,
        fileId: String,
        name: String,
        path: String,
        sizeBytes: Long,
    ): Pair<Offer, CompletableDeferred<SendResult>> {
        val offer = Offer(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            fileId = fileId,
            name = name,
            path = path,
            sizeBytes = sizeBytes,
            state = State.OFFERED,
            offeredAt = clock(),
        )
        offers[offer.id] = offer
        val waiter = CompletableDeferred<SendResult>()
        waiters[offer.id] = waiter
        notifySession(sessionId)
        return offer to waiter
    }

    /** What the browser's SSE stream should currently show. */
    fun pendingFor(sessionId: String): List<Offer> = offers.values
        .filter { it.sessionId == sessionId && (it.state == State.OFFERED || it.state == State.DOWNLOADING) }
        .sortedBy { it.offeredAt }

    fun get(id: String): Offer? = offers[id]

    /** The browser pressed [Download]; the ranged download follows. */
    fun accepted(id: String): Offer? {
        var changed = false
        val next = synchronized(lock) {
            val offer = offers[id] ?: return null
            if (offer.state != State.OFFERED) return@synchronized offer
            offer.copy(state = State.DOWNLOADING).also {
                offers[id] = it
                changed = true
            }
        }
        if (changed) notifySession(next.sessionId)
        return next
    }

    /**
     * Records a range only once NanoHTTPD has read all bytes in that response.
     * This fixes `/push-download` completing a phone row merely because a GET
     * lacked a Range header. Several partial range requests may jointly finish
     * the offer; duplicate and overlapping ranges are harmless.
     */
    fun downloadedRange(id: String, start: Long, length: Long): Offer? {
        if (start < 0) return get(id)
        var delivered: Offer? = null
        synchronized(lock) {
            val offer = offers[id] ?: return null
            if (offer.state != State.DOWNLOADING) return offer
            // A zero-byte response already covers the complete [0, 0) file.
            if (offer.sizeBytes == 0L && start == 0L && length == 0L) {
                val next = offer.copy(state = State.DELIVERED)
                offers[id] = next
                waiters.remove(id)?.complete(SendResult.Completed)
                delivered = next
                return@synchronized
            }
            if (length <= 0 || start >= offer.sizeBytes) return offer
            val available = offer.sizeBytes - start
            val end = if (length >= available) offer.sizeBytes else start + length
            if (end <= start) return offer
            val ranges = deliveredRanges.getOrPut(id) { ArrayList() }
            ranges.add(Range(start, end))
            val merged = merge(ranges)
            ranges.clear()
            ranges.addAll(merged)
            if (merged.size == 1 && merged[0].start == 0L && merged[0].endExclusive >= offer.sizeBytes) {
                val next = offer.copy(state = State.DELIVERED)
                offers[id] = next
                deliveredRanges.remove(id)
                waiters.remove(id)?.complete(SendResult.Completed)
                delivered = next
            }
        }
        delivered?.let { notifySession(it.sessionId) }
        return delivered ?: get(id)
    }

    /** The ranged download finished — retained for direct state-machine tests. */
    fun delivered(id: String): Offer? {
        val offer = get(id) ?: return null
        return downloadedRange(id, 0, offer.sizeBytes)
    }

    /** The browser pressed [Dismiss]: the offer is cancelled, not failed. */
    fun dismissed(id: String): Offer? {
        val existing = get(id) ?: return null
        if (existing.state == State.DELIVERED) return existing
        val offer = transition(id, State.DISMISSED) ?: return null
        waiters.remove(id)?.complete(SendResult.Cancelled)
        return offer
    }

    /**
     * INV-1 applies here too: an offer nobody answers must not leave the
     * engine's worker waiting for ever.
     */
    fun expireOlderThan(ageMillis: Long): Int {
        var expired = 0
        for (offer in offers.values) {
            if (offer.state != State.OFFERED) continue
            if (clock() - offer.offeredAt < ageMillis) continue
            transition(offer.id, State.EXPIRED)
            waiters.remove(offer.id)?.complete(SendResult.Failed("the browser did not answer"))
            expired++
        }
        return expired
    }

    /** A revoked or ended session cancels everything it was offered. */
    fun cancelSession(sessionId: String, reason: String) {
        for (offer in offers.values.filter { it.sessionId == sessionId }) {
            if (offer.state == State.DELIVERED) continue
            transition(offer.id, State.DISMISSED)
            waiters.remove(offer.id)?.complete(SendResult.Failed(reason))
        }
    }

    fun clear() {
        for ((_, waiter) in waiters) waiter.complete(SendResult.Failed("WebShare stopped"))
        waiters.clear()
        offers.clear()
        synchronized(lock) { deliveredRanges.clear() }
    }

    private fun transition(id: String, state: State): Offer? {
        val next = synchronized(lock) {
            val offer = offers[id] ?: return null
            val changed = offer.copy(state = state)
            offers[id] = changed
            if (state != State.DOWNLOADING) deliveredRanges.remove(id)
            changed
        }
        notifySession(next.sessionId)
        return next
    }

    /** Merge ordered inclusive/exclusive spans without Java-8 collection APIs. */
    private fun merge(input: List<Range>): List<Range> {
        val sorted = input.sortedWith(compareBy<Range> { it.start }.thenBy { it.endExclusive })
        val out = ArrayList<Range>()
        for (span in sorted) {
            val previous = out.lastOrNull()
            if (previous == null || span.start > previous.endExclusive) {
                out.add(span)
            } else if (span.endExclusive > previous.endExclusive) {
                out[out.lastIndex] = Range(previous.start, span.endExclusive)
            }
        }
        return out
    }

    private fun notifySession(sessionId: String) {
        val snapshot = synchronized(lock) { ArrayList(listeners) }
        for (listener in snapshot) runCatching { listener(sessionId) }
    }
}
