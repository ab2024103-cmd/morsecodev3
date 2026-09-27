package app.morsecode.android.core.transfer

import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.network.Framing
import app.morsecode.android.core.network.Protocol
import app.morsecode.android.core.storage.Conflicts
import app.morsecode.android.core.storage.ReceiveSink
import app.morsecode.android.core.storage.ReceiveSinkFactory
import app.morsecode.android.core.util.Integrity
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The receiving half of a transfer (§9.4, §9.5).
 *
 * §8.2: incoming file handling is owned by the engine and the service, never
 * by a screen — a session whose incoming stream is only collected while a
 * fragment is visible will handshake, look connected, and silently receive
 * nothing. This object is therefore process-scoped and holds no view.
 *
 * It answers META (the conflict decision and the resume offset, §9.5/§11.2)
 * and then consumes the DATA connection for that file: CRC-checked frames
 * appended to `<name>.morsecode.part`, verified by size and (where the tier
 * allows) sha256, then published under the final name.
 */
class IncomingFiles(
    private val engine: TransferEngine,
    private val sinks: ReceiveSinkFactory,
    private val conflictPolicy: Conflicts.BatchPolicy,
    private val logStore: LogStore? = null,
    private val verifyWithSha: Boolean = true,
) {

    private class Pending(
        val fileId: String,
        val displayName: String,
        val size: Long,
        val sha256: String?,
        val sink: ReceiveSink,
        val peerName: String,
        val itemId: String,
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    /**
     * Answers a META. Everything is decided BEFORE the first byte hits the
     * destination (§9.5), and a conflict on one file never blocks the rest of
     * the batch — this returns immediately either way.
     */
    fun onMeta(message: Protocol.Message, peerId: String, peerName: String, batchId: String): Protocol.Message {
        val fileId = message.string("fileId") ?: return Protocol.ack("", Protocol.STATUS_REFUSED, reason = "no fileId")
        val name = message.string("name") ?: return Protocol.ack(fileId, Protocol.STATUS_REFUSED, reason = "no name")
        val size = message.long("size")
        val sha = message.string("sha256")
        val relativePath = message.string("relativePath")
        val mime = message.string("mime")

        var sink = sinks.create(name, mime)
        var finalName = name
        val existingFinal = sink.finalLength()

        // §9.5 detection order: name → size → sha256, and the hash is computed
        // only when it is actually needed.
        if (existingFinal > 0) {
            val identical = existingFinal == size && sha != null &&
                Conflicts.isAlreadyPresent(size, existingFinal, sha, sink.finalSha())
            if (identical) {
                logStore?.i("Already present: $name")
                return Protocol.ack(fileId, Protocol.STATUS_ALREADY_PRESENT)
            }
            when (val decision = Conflicts.decide(
                relativePath = relativePath ?: name,
                exists = true,
                policy = conflictPolicy.policyFor(batchId),
                takenNames = setOf(name),
                incomingSize = size,
                existingSize = existingFinal,
                incomingSha = sha,
                existingSha = null,
            )) {
                is Conflicts.Decision.Skip ->
                    return Protocol.ack(fileId, Protocol.STATUS_ALREADY_PRESENT, reason = decision.reason)
                is Conflicts.Decision.KeepBoth -> {
                    finalName = decision.name
                    sink = sink.renamedTo(finalName)
                }
                // Overwrite and Write both append into the .part and replace
                // the target when it verifies; Ask cannot reach here yet
                // because the default policy is "Rename duplicates" (§6.13)
                // and the dialog lands with the transfer screens.
                else -> Unit
            }
        }

        logStore?.i("META received · $finalName · $size B · resume ${sink.existingLength()}")
        val item = engine.registerIncoming(
            batchId = batchId,
            displayName = finalName,
            sizeBytes = size,
            mime = mime,
            relativePath = relativePath,
            peerId = peerId,
        )
        pending[fileId] = Pending(fileId, finalName, size, sha, sink, peerName, item.id)

        // The resume offset is the length of our .part file (§11.2 Resume).
        val resumeOffset = Integrity.resumeOffsetFor(sink.existingLength(), size)
        return Protocol.ack(fileId, Protocol.STATUS_READY, resumeOffset)
    }

    /**
     * Consumes one DATA connection. The header was already read byte by byte
     * by the caller and is passed in, so this never wraps the stream in a
     * reader (§11.2, A6).
     */
    fun onData(
        fileId: String,
        offset: Long,
        input: InputStream,
        statusOut: OutputStream,
    ) {
        val file = pending.remove(fileId)
        if (file == null) {
            Framing.writeHeaderLine(statusOut, Protocol.dataStatus(false, "unknown fileId"))
            return
        }

        var written = offset
        logStore?.i("File start · ${file.displayName} · from $offset")
        try {
            file.sink.openAppend().use { sink ->
                while (written < file.size) {
                    // A CRC mismatch throws here and FAILS the file; it is
                    // never "repaired" by appending (§9.4).
                    val frame = Framing.readFrame(input)
                    sink.write(frame.payload)
                    written += frame.payload.size
                    engine.incomingProgress(file.itemId, written)
                }
                sink.flush()
            }

            val problem = Integrity.verify(
                expectedSize = file.size,
                actualSize = written,
                expectedSha = if (verifyWithSha) file.sha256 else null,
                actualSha = null,
            )
            if (problem != null) throw IOException(problem)

            val location = file.sink.finish()
            logStore?.i("File complete · ${file.displayName} · $written B")
            engine.incomingResult(file.itemId, TransferState.COMPLETED, file.peerName, path = location)
            Framing.writeHeaderLine(statusOut, Protocol.dataStatus(true))
        } catch (e: Exception) {
            logStore?.e("Receive failed for ${file.displayName}: ${e.message}")
            // The partial stays only if it can still be resumed; a CRC failure
            // means the bytes on disk are suspect, so it goes.
            if (e is Framing.FramingException) file.sink.discard()
            engine.incomingResult(
                file.itemId,
                TransferState.FAILED,
                file.peerName,
                error = e.message ?: e.javaClass.simpleName,
            )
            runCatching {
                Framing.writeHeaderLine(statusOut, Protocol.dataStatus(false, e.message ?: "receive failed"))
            }
        }
    }

    /**
     * The Nearby path (§11.3). The SDK owns the chunking, so there are no MRSC
     * frames and no per-chunk CRC here; §11.3 compensates by comparing the
     * pre-payload metadata's size on every tier and sha256 additionally except
     * on the lowest, which is exactly what this does while it writes.
     */
    fun onStream(fileId: String, input: InputStream) {
        val file = pending.remove(fileId) ?: return
        var written = 0L
        val digest = if (verifyWithSha && file.sha256 != null) {
            java.security.MessageDigest.getInstance("SHA-256")
        } else {
            null
        }
        try {
            file.sink.openAppend().use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    digest?.update(buffer, 0, read)
                    written += read
                    engine.incomingProgress(file.itemId, written)
                }
                sink.flush()
            }

            val actualSha = digest?.let { hex(it.digest()) }
            val problem = Integrity.verify(file.size, written, file.sha256, actualSha)
            if (problem != null) throw IOException(problem)

            val location = file.sink.finish()
            engine.incomingResult(file.itemId, TransferState.COMPLETED, file.peerName, path = location)
        } catch (e: Exception) {
            logStore?.e("Nearby receive failed for ${file.displayName}: ${e.message}")
            file.sink.discard()
            engine.incomingResult(
                file.itemId,
                TransferState.FAILED,
                file.peerName,
                error = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value < 0x10) out.append('0')
            out.append(Integer.toHexString(value))
        }
        return out.toString()
    }

    /** §9.8: a rejected or ended session leaves no half-written files behind. */
    fun abandonAll(reason: String) {
        for (file in pending.values) {
            engine.incomingResult(file.itemId, TransferState.PAUSED, file.peerName, error = reason)
        }
        pending.clear()
    }
}
