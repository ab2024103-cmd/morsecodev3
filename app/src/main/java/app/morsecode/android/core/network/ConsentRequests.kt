package app.morsecode.android.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * §17.2 CONSENT GATES and §6.16's dialogs, brokered in one process-scoped
 * place.
 *
 * A transport asks [ask] and suspends until the person answers. The UI
 * observes [current] and shows the dialog; when no UI is in the foreground the
 * host raises the heads-up notification instead ([onBackgroundRequest]), which
 * is what §6.16's [GAP] note asks for.
 *
 * Two rules are structural here:
 *  - **Nothing crosses the wire before Accept** (§6.16): the transport is
 *    suspended inside `ask`, so there is no code path that could send a
 *    listing, a thumbnail or a byte while the question is on screen.
 *  - **An already-trusted peer in the SAME live session does not re-prompt; a
 *    new peer always does** (§6.16). [trustInSession] records the former and
 *    [endSession] forgets it, so trust cannot outlive the session.
 */
class ConsentRequests {

    enum class Kind { PEER, BROWSER }

    data class Request(
        val id: String,
        val kind: Kind,
        /** "Ravi's Redmi" or "Chrome". */
        val title: String,
        /** "Phone · Wi-Fi LAN · 192.168.1.42" or "Chrome · 192.168.1.88". */
        val detail: String,
        val peerId: String,
    )

    private val queue = ConcurrentLinkedQueue<Pair<Request, CompletableDeferred<Boolean>>>()
    private val currentFlow = MutableStateFlow<Request?>(null)
    private val trusted = HashSet<String>()

    /** The request the UI should be showing, or null. */
    val current: StateFlow<Request?> get() = currentFlow

    /** Set by the host so a backgrounded app can raise a heads-up instead. */
    var onBackgroundRequest: ((Request) -> Unit)? = null

    /** True while the app has a screen that can show the dialog. */
    @Volatile
    var uiVisible: Boolean = false

    /**
     * Suspends the calling transport until the person answers. Returns false
     * for a reject, which the transport turns into a clean REJECT (§9.8).
     */
    suspend fun ask(request: Request): Boolean {
        if (isTrusted(request.peerId)) return true
        val answer = CompletableDeferred<Boolean>()
        queue.add(request to answer)
        pump()
        return answer.await()
    }

    /** Called by the dialog (or the notification action). */
    fun answer(requestId: String, accepted: Boolean) {
        val head = queue.peek() ?: return
        if (head.first.id != requestId) return
        queue.poll()
        if (accepted) trustInSession(head.first.peerId)
        head.second.complete(accepted)
        currentFlow.value = null
        pump()
    }

    /** Rejects everything pending, e.g. when the session is torn down. */
    fun rejectAll() {
        while (true) {
            val pending = queue.poll() ?: break
            pending.second.complete(false)
        }
        currentFlow.value = null
    }

    fun isTrusted(peerId: String): Boolean = synchronized(trusted) { peerId in trusted }

    fun trustInSession(peerId: String) {
        synchronized(trusted) { trusted.add(peerId) }
    }

    /** Trust dies with the session — a new session always re-prompts. */
    fun endSession(peerId: String) {
        synchronized(trusted) { trusted.remove(peerId) }
    }

    fun endAllSessions() {
        synchronized(trusted) { trusted.clear() }
    }

    private fun pump() {
        val head = queue.peek() ?: return
        if (currentFlow.value?.id == head.first.id) return
        currentFlow.value = head.first
        if (!uiVisible) onBackgroundRequest?.invoke(head.first)
    }
}
