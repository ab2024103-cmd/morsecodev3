package app.morsecode.android.core.network

import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.SendResult
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.util.Integrity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections

/**
 * §11.2 one connected LAN peer.
 *
 * Every rule in §11.2 that encodes a production defect is implemented here
 * rather than left to the caller:
 *
 *  - **One data connection PER FILE**, never reused across files or across a
 *    direction turnaround — a stale socket looks connected and yields a
 *    zero-byte file with no error.
 *  - The DATA connection's header line is read with [Framing.readHeaderLine];
 *    the sender writes it the same way. No `BufferedReader` touches a socket
 *    that will carry chunks.
 *  - **Progress advances only AFTER a successful flush**, which is why
 *    `onProgress` is called after `writeFrame` returns.
 *  - A `Socket` is never force-cast to a `SocketChannel`; buffered streams are
 *    used throughout, because `getChannel()` is null for connect() sockets.
 *  - A read timeout is "nothing happened", not "peer gone".
 */
class LanSession(
    override val peerId: String,
    override val peerName: String,
    private val host: String,
    private val dataPort: Int,
    private val control: ControlChannel,
    private val openContent: (TransferItem) -> InputStream?,
    private val logStore: LogStore? = null,
    private val connectTimeoutMs: Int = 8_000,
) : TransportSession {

    override val transport: TransportKind = TransportKind.LAN

    override val isAlive: Boolean get() = !control.isClosed && !closed

    @Volatile
    private var closed = false

    private val pausedFiles: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val cancelledFiles: MutableSet<String> = Collections.synchronizedSet(HashSet())

    override suspend fun sendFile(
        item: TransferItem,
        sha256: String?,
        onProgress: (Long, Long) -> Unit,
    ): SendResult = withContext(Dispatchers.IO) {
        pausedFiles.remove(item.id)
        cancelledFiles.remove(item.id)

        // 1. META → ACK, as one atomic request on the control channel (§11.2).
        logStore?.i("META sent · ${item.file.displayName} · ${item.totalBytes} B")
        val ack = try {
            control.request(
                Protocol.meta(
                    fileId = item.id,
                    name = item.file.displayName,
                    relativePath = item.relativePath,
                    size = item.totalBytes,
                    mime = item.file.mime,
                    sha256 = sha256,
                    resumeOffset = item.resumeOffset,
                ),
            )
        } catch (e: IOException) {
            return@withContext SendResult.Failed(e.message ?: "control channel error")
        } ?: return@withContext SendResult.Failed("no answer to META")

        if (ack.type != Protocol.ACK) {
            return@withContext SendResult.Failed("unexpected ${ack.type} in reply to META")
        }
        when (ack.string("status")) {
            Protocol.STATUS_ALREADY_PRESENT ->
                return@withContext SendResult.SkippedAlreadyPresent(
                    "identical file already present on receiver",
                )
            Protocol.STATUS_REFUSED ->
                return@withContext SendResult.Failed(ack.string("reason") ?: "refused by the other phone")
        }

        // 2. The receiver reports its .part length; the sender seeks there and
        //    continues the seq numbering (§11.2 Resume).
        val offset = Integrity.resumeOffsetFor(ack.long("resumeOffset"), item.totalBytes)
        logStore?.i("ACK received · ${item.file.displayName} · resumeOffset=$offset")
        var sent = offset

        val socket = try {
            Socket().also {
                it.tcpNoDelay = true
                it.soTimeout = READ_TIMEOUT_MS
                it.connect(InetSocketAddress(host, dataPort), connectTimeoutMs)
            }
        } catch (e: IOException) {
            return@withContext SendResult.Failed("Can't reach $host:$dataPort")
        }

        try {
            val output = java.io.BufferedOutputStream(socket.getOutputStream())
            val input = socket.getInputStream()
            Framing.writeHeaderLine(
                output,
                Protocol.dataHeader(item.id, item.file.displayName, item.totalBytes, offset),
            )

            logStore?.i("Data connection opened · ${item.file.displayName} → $host:$dataPort")
            val source = openContent(item)
                ?: return@withContext SendResult.Failed("could not read ${item.file.displayName}")

            source.use { stream ->
                if (offset > 0) skipExactly(stream, offset)
                var seq = Integrity.resumeSequence(offset, Framing.CHUNK_SIZE)
                val buffer = ByteArray(Framing.CHUNK_SIZE)
                val startedAt = System.currentTimeMillis()

                while (sent < item.totalBytes) {
                    if (item.id in cancelledFiles) return@withContext SendResult.Cancelled
                    if (item.id in pausedFiles) return@withContext SendResult.Paused

                    val read = stream.read(buffer)
                    if (read <= 0) break

                    Framing.writeFrame(output, seq, buffer, read)
                    // Only now, after the flush inside writeFrame, has anything
                    // really moved (§11.2).
                    seq++
                    sent += read
                    val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
                    onProgress(sent, ((sent - offset) * 1000L) / elapsed)
                }
            }

            if (sent < item.totalBytes) {
                return@withContext SendResult.Failed("source ended early at $sent of ${item.totalBytes}")
            }

            // 3. One status line back, read byte by byte like every other
            //    header on a binary connection.
            val status = Protocol.parse(Framing.readHeaderLine(input))
            return@withContext if (status.bool("ok")) {
                logStore?.i("File complete · ${item.file.displayName} · $sent B")
                SendResult.Completed
            } else {
                SendResult.Failed(status.string("error") ?: "receiver reported a failure")
            }
        } catch (e: Framing.FramingException) {
            logStore?.e("Framing error sending ${item.file.displayName}: ${e.message}")
            return@withContext SendResult.Failed(e.message ?: "framing error")
        } catch (e: IOException) {
            // A dropped connection mid-file is a pause-worthy loss, and the
            // engine decides which (§9.3); the transport only reports.
            return@withContext SendResult.Failed(e.message ?: "connection error")
        } finally {
            runCatching { socket.close() }
        }
    }

    override suspend fun pauseOutgoing(fileId: String) {
        logStore?.i("Pause requested · file=$fileId")
        pausedFiles.add(fileId)
        runCatching { control.send(Protocol.pauseRequest(fileId)) }
    }

    override suspend fun cancelTransfer(fileId: String) {
        logStore?.i("Cancel requested · file=$fileId")
        cancelledFiles.add(fileId)
        runCatching { control.send(Protocol.cancel(fileId)) }
    }

    override suspend fun close(reason: String) {
        if (closed) return
        closed = true
        // §9.8: a clean BYE, then the socket goes.
        runCatching { control.send(Protocol.bye(reason)) }
        control.close(reason)
    }

    /** Marks a file paused/cancelled because the PEER asked (§11.2 PAUSE_REQ). */
    fun peerRequestedPause(fileId: String) {
        pausedFiles.add(fileId)
    }

    fun peerRequestedCancel(fileId: String) {
        cancelledFiles.add(fileId)
    }

    private fun skipExactly(stream: InputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                // Some content providers refuse to skip; read the bytes away.
                val scratch = ByteArray(minOf(remaining, 64L * 1024).toInt())
                val read = stream.read(scratch)
                if (read <= 0) throw IOException("could not seek to $offset")
                remaining -= read
            } else {
                remaining -= skipped
            }
        }
    }

    private companion object {
        /** §11.2: 25 s, guarded by the engine's idle watchdog. */
        const val READ_TIMEOUT_MS = 25_000
    }
}
