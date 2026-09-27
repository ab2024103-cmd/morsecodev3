package app.morsecode.android.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * §8.1 SessionRegistry: the live sessions, one per peer.
 *
 * It exists so that "am I already connected to this phone?" has one answer.
 * §6.16 leans on it too: "an already-trusted peer in the SAME live session
 * does not re-prompt for a new batch; a new peer always does" — the session
 * being present here IS that trust, and it dies with the session.
 */
class SessionRegistry {

    private val sessions = ConcurrentHashMap<String, TransportSession>()
    private val countFlow = MutableStateFlow(0)

    /** For the broadcast cap and the "n phones" notification text (§10.2). */
    val liveCount: StateFlow<Int> get() = countFlow

    fun add(session: TransportSession) {
        sessions[session.peerId] = session
        publish()
    }

    fun remove(peerId: String) {
        sessions.remove(peerId)
        publish()
    }

    fun get(peerId: String): TransportSession? = sessions[peerId]?.takeIf { it.isAlive }

    fun all(): List<TransportSession> = sessions.values.filter { it.isAlive }

    /** True when this peer is already connected, so consent is not re-asked. */
    fun isConnected(peerId: String): Boolean = get(peerId) != null

    suspend fun closeAll(reason: String) {
        for (session in sessions.values) session.close(reason)
        sessions.clear()
        publish()
    }

    private fun publish() {
        // Dead sessions are not "connected"; the count reflects what is usable.
        countFlow.value = sessions.values.count { it.isAlive }
    }
}
