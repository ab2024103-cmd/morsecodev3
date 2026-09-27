package app.morsecode.android.core.network

import app.morsecode.android.core.logging.LogStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * §11.2 the control channel: ONE TCP connection carrying newline-terminated
 * UTF-8 JSON.
 *
 * Three rules from §11.2, each of which encodes a real production defect:
 *
 *  - **One shared mutex guards every read and every write, and it is held
 *    across a write-then-read-reply pair as one atomic unit.** A "busy"
 *    boolean is not sufficient, so [request] takes the lock once and does not
 *    release it until the reply is in hand.
 *  - **Idle is not closed.** A read timeout means "nothing happened", never
 *    "peer gone"; only an explicit close/reset or a genuine I/O error ends a
 *    session. [poll] returns null on a timeout and the caller keeps going.
 *  - A `BufferedReader` is permitted here because this channel is pure text.
 *    It is forbidden on a DATA connection, where [Framing.readHeaderLine] does
 *    the byte-by-byte read instead.
 */
class ControlChannel(
    private val socket: Socket,
    private val logStore: LogStore? = null,
) {

    private val mutex = Mutex()
    private val reader: BufferedReader
    private val output: OutputStream

    @Volatile
    var isClosed: Boolean = false
        private set

    init {
        socket.tcpNoDelay = true
        socket.soTimeout = READ_TIMEOUT_MS
        reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        output = socket.getOutputStream()
    }

    val remoteAddress: String get() = socket.inetAddress?.hostAddress ?: "unknown"

    /** Fire-and-forget: BYE, PONG, PAUSE_REQ, CANCEL. */
    suspend fun send(message: Protocol.Message) = mutex.withLock {
        writeLine(message)
    }

    /**
     * Write then read the reply as ONE atomic unit — the lock is not released
     * between the two halves, so two coroutines can never interleave their
     * requests and read each other's answers.
     */
    suspend fun request(message: Protocol.Message, timeoutMs: Int = REQUEST_TIMEOUT_MS): Protocol.Message? =
        mutex.withLock {
            val previousTimeout = socket.soTimeout
            socket.soTimeout = timeoutMs
            try {
                writeLine(message)
                readLine()
            } finally {
                socket.soTimeout = previousTimeout
            }
        }

    /**
     * Reads one message if the peer has sent something. Returns null when
     * nothing arrived within the read timeout — which is not a disconnection.
     */
    suspend fun poll(): Protocol.Message? = mutex.withLock { readLine() }

    fun close(reason: String) {
        if (isClosed) return
        isClosed = true
        try {
            socket.close()
        } catch (e: IOException) {
            // Closing a socket that is already gone is not an error.
        }
        logStore?.i("Control channel closed · reason=$reason")
    }

    private fun writeLine(message: Protocol.Message) {
        if (isClosed) throw IOException("control channel is closed")
        output.write((message.encode() + "\n").toByteArray(Charsets.UTF_8))
        // §11.2: nothing advances until the flush has succeeded.
        output.flush()
    }

    private fun readLine(): Protocol.Message? {
        return try {
            val line = reader.readLine()
            if (line == null) {
                // A real end of stream IS the peer going away.
                isClosed = true
                logStore?.w("Control channel ended by peer")
                null
            } else {
                Protocol.parse(line)
            }
        } catch (timeout: SocketTimeoutException) {
            // Idle is not closed (§11.2).
            null
        } catch (e: Protocol.ProtocolException) {
            logStore?.w("Unparseable control line: ${e.message}")
            null
        }
    }

    private companion object {
        /** Short, so an idle poll returns quickly without holding the lock. */
        const val READ_TIMEOUT_MS = 500

        /** A request waits longer: the peer may be showing a consent dialog. */
        const val REQUEST_TIMEOUT_MS = 60_000
    }
}
