package app.morsecode.android.core.network

import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.transfer.IncomingFiles
import app.morsecode.android.core.util.Ids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * §11.2 the LAN transport: TCP :33456 serving both the control connection and
 * one data connection per file.
 *
 * Both kinds of connection arrive on the same port, so the accept loop reads
 * the first line BYTE BY BYTE ([Framing.readHeaderLine]) and dispatches on its
 * type. That is also what makes the A6 rule structural: a data connection's
 * header is never consumed by a buffered reader, because the only reader that
 * ever sees a fresh socket is the byte-wise one.
 *
 * §9.8: an incoming connection is offered to [consent] before anything else
 * happens. Reject sends a clean REJECT and nothing is written.
 */
class LanTransport(
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val deviceName: String,
    private val incoming: IncomingFiles,
    private val openContent: (TransferItem) -> InputStream?,
    private val port: Int = Ids.PORT_TCP,
    private val logStore: LogStore? = null,
) : Transport {

    override val kind: TransportKind = TransportKind.LAN

    /** Answered by the §6.16a consent dialog; false sends a clean REJECT. */
    var consent: suspend (DiscoveredHello) -> Boolean = { true }

    /** Raised when a peer's session is established, so the engine can bind it. */
    var onSessionEstablished: ((LanSession) -> Unit)? = null

    /** §11.6: shown to the user instead of a framing error. */
    var onVersionProblem: ((String) -> Unit)? = null

    data class DiscoveredHello(
        val deviceId: String,
        val name: String,
        val address: String,
        val protocolVersion: Int,
    )

    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private val sessions = ConcurrentHashMap<String, LanSession>()
    private val batchByPeer = ConcurrentHashMap<String, String>()

    /** The port actually bound; differs from [port] only when 0 was asked for. */
    val boundPort: Int get() = server?.localPort ?: port

    fun start() {
        if (acceptJob?.isActive == true) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        server = socket
        logStore?.i("TCP control :${socket.localPort} listening")

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (socket.isClosed) break
                    logStore?.w("Accept failed: ${e.message}")
                    delay(100)
                    continue
                }
                launch(Dispatchers.IO) { dispatch(client) }
            }
        }
    }

    override suspend fun connect(peerId: String): TransportSession? = null

    /**
     * Dials a peer found by discovery or typed into [ManualAddress]. The HELLO
     * → ACCEPT/REJECT handshake happens here, so a session handed back is one
     * the other phone has already consented to (§9.8).
     */
    suspend fun connect(host: String, port: Int): LanSession? = withContext(Dispatchers.IO) {
        val socket = try {
            Socket().also {
                it.tcpNoDelay = true
                it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
        } catch (e: IOException) {
            logStore?.w("Can't reach $host:$port")
            return@withContext null
        }

        val control = ControlChannel(socket, logStore)
        val reply = try {
            control.request(Protocol.hello(deviceId, deviceName))
        } catch (e: IOException) {
            control.close("handshake failed")
            return@withContext null
        }

        if (reply == null || reply.type == Protocol.REJECT) {
            control.close(reply?.string("reason") ?: "rejected")
            return@withContext null
        }
        if (reply.type != Protocol.ACCEPT) {
            control.close("unexpected ${reply.type}")
            return@withContext null
        }

        val problem = Protocol.versionProblem(reply.int("protocolVersion", Ids.PROTOCOL_VERSION))
        if (problem != null) {
            onVersionProblem?.invoke(problem)
            control.close("protocol mismatch")
            return@withContext null
        }

        val session = LanSession(
            peerId = reply.string("deviceId") ?: host,
            peerName = reply.string("name") ?: host,
            host = host,
            dataPort = port,
            control = control,
            openContent = openContent,
            logStore = logStore,
        )
        sessions[session.peerId] = session
        batchByPeer[session.peerId] = UUID.randomUUID().toString()
        pump(session, control)
        onSessionEstablished?.invoke(session)
        session
    }

    override suspend fun shutdown() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { server?.close() }
        server = null
        for (session in sessions.values) session.close("shutdown")
        sessions.clear()
    }

    // ----- Accept-side dispatch --------------------------------------------

    private suspend fun dispatch(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = SOCKET_TIMEOUT_MS
        val input = client.getInputStream()

        val first = try {
            // BYTE BY BYTE, always — this socket may be about to carry chunks.
            Protocol.parse(Framing.readHeaderLine(input))
        } catch (e: Exception) {
            runCatching { client.close() }
            return
        }

        when (first.type) {
            Protocol.HELLO -> acceptControl(client, first)
            DATA -> acceptData(client, first, input)
            else -> runCatching { client.close() }
        }
    }

    private suspend fun acceptControl(client: Socket, hello: Protocol.Message) {
        val address = client.inetAddress?.hostAddress ?: "unknown"
        val control = ControlChannel(client, logStore)

        if (!Protocol.isMorsecode(hello)) {
            runCatching { control.send(Protocol.reject("not a Morsecode peer")) }
            control.close("foreign peer")
            return
        }

        val theirVersion = hello.int("protocolVersion", 0)
        val problem = Protocol.versionProblem(theirVersion)
        if (problem != null) {
            // §11.6: a readable sentence, not a framing error.
            runCatching { control.send(Protocol.reject(problem)) }
            onVersionProblem?.invoke(problem)
            control.close("protocol mismatch")
            return
        }

        val peer = DiscoveredHello(
            deviceId = hello.string("deviceId") ?: address,
            name = hello.string("name") ?: address,
            address = address,
            protocolVersion = theirVersion,
        )

        // §9.8 / §6.16: nothing — no listing, no thumbnail, no byte — crosses
        // the wire before Accept.
        if (!consent(peer)) {
            runCatching { control.send(Protocol.reject("rejected")) }
            control.close("rejected by user")
            logStore?.i("Rejected connection from ${peer.name}")
            return
        }

        runCatching { control.send(Protocol.accept(deviceId, deviceName)) }

        val session = LanSession(
            peerId = peer.deviceId,
            peerName = peer.name,
            host = peer.address,
            dataPort = port,
            control = control,
            openContent = openContent,
            logStore = logStore,
        )
        sessions[session.peerId] = session
        batchByPeer[session.peerId] = UUID.randomUUID().toString()
        logStore?.i("TCP control :${boundPort} connected · peer=${peer.name}")
        pump(session, control)
        onSessionEstablished?.invoke(session)
    }

    private fun acceptData(client: Socket, header: Protocol.Message, input: InputStream) {
        val fileId = header.string("fileId")
        val offset = header.long("offset")
        if (fileId == null) {
            runCatching { client.close() }
            return
        }
        try {
            val output = java.io.BufferedOutputStream(client.getOutputStream())
            incoming.onData(fileId, offset, input, output)
        } finally {
            // One data connection per file: it is closed here, never reused.
            runCatching { client.close() }
        }
    }

    /**
     * The control channel's message pump. It polls under the same mutex every
     * request uses, so a PAUSE_REQ arriving mid-handshake cannot interleave
     * with a META/ACK pair.
     */
    private fun pump(session: LanSession, control: ControlChannel) {
        scope.launch(Dispatchers.IO) {
            val batchId = batchByPeer[session.peerId] ?: UUID.randomUUID().toString()
            while (isActive && !control.isClosed) {
                val message = control.poll()
                if (message == null) {
                    // Idle is not closed (§11.2); only a real end of stream is.
                    delay(POLL_IDLE_MS)
                    continue
                }
                when (message.type) {
                    Protocol.META -> {
                        val ack = incoming.onMeta(message, session.peerId, session.peerName, batchId)
                        runCatching { control.send(ack) }
                    }
                    Protocol.PAUSE_REQ -> message.string("fileId")?.let(session::peerRequestedPause)
                    Protocol.CANCEL -> message.string("fileId")?.let(session::peerRequestedCancel)
                    Protocol.PING -> runCatching { control.send(Protocol.pong()) }
                    Protocol.BYE -> {
                        control.close(message.string("reason") ?: "peer said goodbye")
                        incoming.abandonAll("Connection lost")
                    }
                    else -> Unit
                }
            }
            sessions.remove(session.peerId)
        }
    }

    private companion object {
        const val DATA = "DATA"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val SOCKET_TIMEOUT_MS = 25_000
        const val POLL_IDLE_MS = 50L
    }
}
