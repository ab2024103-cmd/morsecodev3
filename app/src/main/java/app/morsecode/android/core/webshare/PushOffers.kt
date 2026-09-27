package app.morsecode.android.core.webshare

import app.morsecode.android.core.model.SendResult
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * §7.8 PUSH (phone → browser).
 *
 * "A connected browser session appears in the phone's discovery list as
 * 'Office Laptop · WebShare'. Choosing it and sending opens an offer: the
 * browser receives an SSE event and shows an incoming card in the transfer
 * tray with [Download] / [Dismiss]; accepting downloads through the same
 * Range-capable endpoint."
 *
 * The state machine is here, free of HTTP and of the transfer engine, so the
 * part that decides whether a send is Completed, Cancelled or Failed can be
 * tested on its own.
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

    private val offers = ConcurrentHashMap<String, Offer>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<SendResult>>()

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
        return offer to waiter
    }

    /** What the browser's SSE stream should currently show. */
    fun pendingFor(sessionId: String): List<Offer> = offers.values
        .filter { it.sessionId == sessionId && (it.state == State.OFFERED || it.state == State.DOWNLOADING) }
        .sortedBy { it.offeredAt }

    fun get(id: String): Offer? = offers[id]

    /** The browser pressed [Download]; the ranged download follows. */
    fun accepted(id: String): Offer? = transition(id, State.DOWNLOADING)

    /** The ranged download finished — the send is Completed (§7.8). */
    fun delivered(id: String): Offer? {
        val offer = transition(id, State.DELIVERED) ?: return null
        waiters.remove(id)?.complete(SendResult.Completed)
        return offer
    }

    /** The browser pressed [Dismiss]: the offer is cancelled, not failed. */
    fun dismissed(id: String): Offer? {
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
    }

    private fun transition(id: String, state: State): Offer? {
        val offer = offers[id] ?: return null
        val next = offer.copy(state = state)
        offers[id] = next
        return next
    }
}
