package app.morsecode.android.core.webshare

import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * A small, held Server-Sent Events stream for NanoHTTPD.
 *
 * NanoHTTPD closes a fixed-length response as soon as it has written it, which
 * made the former endpoint a two-second poll wearing an SSE content type. Each
 * [open] call instead returns a pipe whose writer stays registered until the
 * browser disconnects or WebShare stops. Updates are pushed immediately; a
 * quiet heartbeat keeps local proxies from deciding an idle stream is dead.
 */
class SseHub(
    private val snapshot: (String) -> String,
    private val heartbeatMillis: Long = HEARTBEAT_MILLIS,
) {

    private class Client(
        val sessionId: String,
        val output: PipedOutputStream,
    ) {
        @Volatile
        var open: Boolean = true
    }

    private val lock = Any()
    private val clients = HashMap<String, MutableList<Client>>()

    /** Opens one enduring stream and emits its current session snapshot first. */
    fun open(sessionId: String): InputStream {
        val output = PipedOutputStream()
        val input = PipedInputStream(output, PIPE_BYTES)
        val client = Client(sessionId, output)
        synchronized(lock) {
            val list = clients[sessionId] ?: ArrayList<Client>().also { clients[sessionId] = it }
            list.add(client)
        }
        write(client, frame(snapshot(sessionId)))
        startHeartbeat(client)
        return input
    }

    /** Pushes an event to only the browser session that owns it. */
    fun publish(sessionId: String) {
        val targets = synchronized(lock) { ArrayList(clients[sessionId] ?: emptyList<Client>()) }
        val body = frame(snapshot(sessionId))
        for (client in targets) write(client, body)
    }

    fun closeAll() {
        val all = synchronized(lock) {
            val copy = ArrayList<Client>()
            for (list in clients.values) copy.addAll(list)
            clients.clear()
            copy
        }
        for (client in all) close(client)
    }

    fun clientCount(sessionId: String): Int = synchronized(lock) { clients[sessionId]?.size ?: 0 }

    private fun startHeartbeat(client: Client) {
        val heartbeat = Thread({
            while (client.open) {
                try {
                    Thread.sleep(heartbeatMillis)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                write(client, ": keep-alive\n\n")
            }
        }, "morsecode-sse")
        // The owning response/service manages its lifetime; a stale browser
        // must never keep a test process or stopped WebShare process alive.
        heartbeat.isDaemon = true
        heartbeat.start()
    }

    private fun write(client: Client, body: String) {
        if (!client.open) return
        try {
            synchronized(client) {
                if (!client.open) return
                client.output.write(body.toByteArray(Charsets.UTF_8))
                client.output.flush()
            }
        } catch (error: Exception) {
            close(client)
        }
    }

    private fun close(client: Client) {
        if (!client.open) return
        client.open = false
        runCatching { client.output.close() }
        synchronized(lock) {
            val list = clients[client.sessionId] ?: return
            list.remove(client)
            if (list.isEmpty()) clients.remove(client.sessionId)
        }
    }

    private fun frame(payload: String): String = "data: $payload\n\n"

    private companion object {
        const val PIPE_BYTES = 64 * 1024
        const val HEARTBEAT_MILLIS = 15_000L
    }
}
