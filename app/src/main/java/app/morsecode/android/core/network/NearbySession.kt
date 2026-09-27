package app.morsecode.android.core.network

import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.SendResult
import app.morsecode.android.core.model.TransferItem
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * §11.3 one connected Nearby peer, behind the same [TransportSession]
 * interface as LAN so the engine cannot tell them apart (§8.2).
 *
 * Files travel as STREAM payloads: the SDK owns the chunking, so nothing here
 * reimplements it. A small metadata payload (name, size, sha256, relativePath,
 * mime) precedes every file so §9.4's verification is still possible.
 *
 * HONEST COPY (§11.3, §20.6): Nearby cannot pause mid-payload. A pause cancels
 * the payload and the file resumes from its offset on the next attempt, and
 * the UI says "Pauses after the current file" rather than implying an
 * exact-offset pause this transport cannot deliver.
 */
class NearbySession(
    private val client: ConnectionsClient,
    private val endpointId: String,
    override val peerId: String,
    override val peerName: String,
    private val scope: CoroutineScope,
    private val openContent: (TransferItem) -> InputStream?,
    private val logStore: LogStore? = null,
    private val terminalFallbackMs: Long = 3_000,
) : TransportSession {

    override val transport: TransportKind = TransportKind.NEARBY

    @Volatile
    private var connected = true

    override val isAlive: Boolean get() = connected

    val transfers = NearbyTransfers()

    override suspend fun sendFile(
        item: TransferItem,
        sha256: String?,
        onProgress: (Long, Long) -> Unit,
    ): SendResult = withContext(Dispatchers.IO) {
        if (!connected) return@withContext SendResult.Failed("session closed")

        val waiter = transfers.register(item.id)
        progressCallbacks[item.id] = onProgress

        try {
            // 1. Metadata first, as a small BYTES payload, so the receiver can
            //    do the §9.4 checks the SDK does not do for us.
            client.sendPayload(
                endpointId,
                Payload.fromBytes(
                    NearbyPayloads.metadata(
                        fileId = item.id,
                        name = item.file.displayName,
                        size = item.totalBytes,
                        sha256 = sha256,
                        relativePath = item.relativePath,
                        mime = item.file.mime,
                    ).toByteArray(Charsets.UTF_8),
                ),
            )

            // 2. The file itself as a STREAM payload.
            val source = openContent(item)
                ?: return@withContext transfers.complete(
                    item.id,
                    SendResult.Failed("could not read ${item.file.displayName}"),
                )
            val payload = Payload.fromStream(source)
            transfers.bindPayload(payload.id, item.id)
            client.sendPayload(endpointId, payload)

            waiter.await()
        } catch (e: Exception) {
            transfers.complete(item.id, SendResult.Failed(e.message ?: e.javaClass.simpleName))
        } finally {
            progressCallbacks.remove(item.id)
        }
    }

    /** Called by the transport's payload callback. */
    fun onPayloadUpdate(
        payloadId: Long,
        status: NearbyTransfers.PayloadStatus,
        bytesTransferred: Long,
        totalBytes: Long,
    ) {
        transfers.onUpdate(payloadId, status, bytesTransferred, totalBytes) { fileId, sent, total ->
            progressCallbacks[fileId]?.invoke(sent, total)
        }
    }

    override suspend fun pauseOutgoing(fileId: String) {
        transfers.markPaused(fileId)
        interrupt(fileId, Protocol.pauseRequest(fileId), SendResult.Paused)
    }

    override suspend fun cancelTransfer(fileId: String) {
        transfers.markCancelled(fileId)
        interrupt(fileId, Protocol.cancel(fileId), SendResult.Cancelled)
    }

    /**
     * §11.3: send the control message, call cancelPayload, AND schedule the
     * 3 s terminal fallback — several stacks never emit a terminal update
     * after a cancelled payload, and without the fallback the waiter hangs
     * (INV-1b).
     */
    private fun interrupt(fileId: String, control: Protocol.Message, fallback: SendResult) {
        runCatching {
            client.sendPayload(endpointId, Payload.fromBytes(control.encode().toByteArray(Charsets.UTF_8)))
        }
        transfers.payloadFor(fileId)?.let { payloadId ->
            runCatching { client.cancelPayload(payloadId) }
        }
        scope.launch {
            delay(terminalFallbackMs)
            if (transfers.isInterrupted(fileId)) {
                logStore?.w("Nearby terminal fallback fired for $fileId")
                transfers.complete(fileId, fallback)
            }
        }
    }

    /**
     * §11.3 SESSION-END RULE: fail every outgoing waiter and clear the maps
     * FIRST, then tear down. Doing it the other way round is what leaves a
     * send awaiting a payload whose transport is already gone.
     */
    override suspend fun close(reason: String) {
        if (!connected) return
        connected = false
        val failed = transfers.failAllAndClear("session closed")
        progressCallbacks.clear()
        if (failed > 0) logStore?.w("Nearby session closed with $failed send(s) pending")
        runCatching { client.disconnectFromEndpoint(endpointId) }
        logStore?.i("Nearby session closed · reason=$reason")
    }

    /** The transport calls this on onDisconnected; same ordering rule. */
    fun onTransportDisconnected() {
        connected = false
        transfers.failAllAndClear("session closed")
        progressCallbacks.clear()
    }

    private val progressCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Long, Long) -> Unit>()
}
