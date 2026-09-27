package app.morsecode.android.core.webshare

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * §7.1 access control and §17.3 tokens.
 *
 * "The address is a bare IP:port with NO token in the URL. Access control is
 * consent-based: a new browser session sees only a waiting screen until the
 * phone's owner accepts (§6.16b); on accept the server issues a session token
 * that every subsequent API call must carry; a request without a valid,
 * unexpired token gets 401. The token dies when WebShare stops or the session
 * is revoked."
 *
 * That is A5, and it is enforced here rather than per endpoint, so a new route
 * cannot forget it.
 */
class WebSessions(private val clock: () -> Long = { System.currentTimeMillis() }) {

    enum class State { PENDING, ACCEPTED, REJECTED }

    data class Session(
        val id: String,
        val userAgent: String,
        val address: String,
        val state: State,
        val token: String?,
        val createdAt: Long,
        val lastSeenAt: Long,
    ) {
        /** "Chrome · 192.168.1.88" (§6.16b). */
        val label: String get() = "$userAgent · $address"
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val flow = MutableStateFlow<List<Session>>(emptyList())

    /** The WebShare screen lists these with a Revoke action (§7.1). */
    val active: StateFlow<List<Session>> get() = flow

    /** A browser's first call. It gets an id and a waiting screen, nothing else. */
    fun open(userAgent: String, address: String): Session {
        val existing = sessions.values.firstOrNull {
            it.address == address && it.userAgent == userAgent && it.state != State.REJECTED
        }
        if (existing != null) return touch(existing.id) ?: existing

        val session = Session(
            id = UUID.randomUUID().toString(),
            userAgent = userAgent,
            address = address,
            state = State.PENDING,
            token = null,
            createdAt = clock(),
            lastSeenAt = clock(),
        )
        sessions[session.id] = session
        publish()
        return session
    }

    fun get(id: String): Session? = sessions[id]

    /** On accept the server issues the token every later call must carry. */
    fun accept(id: String): Session? {
        val session = sessions[id] ?: return null
        val accepted = session.copy(
            state = State.ACCEPTED,
            token = UUID.randomUUID().toString().replace("-", ""),
            lastSeenAt = clock(),
        )
        sessions[id] = accepted
        publish()
        return accepted
    }

    /** Reject gets nothing: no token, and the session cannot be revived. */
    fun reject(id: String): Session? {
        val session = sessions[id] ?: return null
        val rejected = session.copy(state = State.REJECTED, token = null, lastSeenAt = clock())
        sessions[id] = rejected
        publish()
        return rejected
    }

    /** §7.1's per-session Revoke. The token dies with it. */
    fun revoke(id: String) {
        sessions.remove(id)
        publish()
    }

    /** The token dies when WebShare stops (§7.1). */
    fun clear() {
        sessions.clear()
        publish()
    }

    /**
     * The gate every endpoint after `/api/hello` passes through: a request
     * without a valid, unexpired token is not served.
     */
    fun authorise(token: String?): Session? {
        if (token.isNullOrEmpty()) return null
        val session = sessions.values.firstOrNull { it.token == token } ?: return null
        if (session.state != State.ACCEPTED) return null
        if (clock() - session.lastSeenAt > TOKEN_IDLE_LIMIT_MS) {
            // Expired: the browser must ask again, and the phone must accept
            // again. Note this is the TOKEN's lifetime, not the server's —
            // INV-4 forbids the server itself idling out.
            revoke(session.id)
            return null
        }
        return touch(session.id)
    }

    private fun touch(id: String): Session? {
        val session = sessions[id] ?: return null
        val touched = session.copy(lastSeenAt = clock())
        sessions[id] = touched
        publish()
        return touched
    }

    private fun publish() {
        flow.value = sessions.values.sortedBy { it.createdAt }
    }

    companion object {
        /** Twelve hours: long enough for a working day, short enough to matter. */
        const val TOKEN_IDLE_LIMIT_MS = 12L * 60 * 60 * 1000
    }
}
