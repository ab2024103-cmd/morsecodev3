package app.morsecode.android.core.network

import app.morsecode.android.core.model.SendResult
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * §11.3's bookkeeping, kept free of every Play Services type so the rules that
 * actually break transfers can be tested without a device.
 *
 * It owns the three maps §11.3 names — `outgoingWaiters` keyed by fileId,
 * payloadId → fileId, and the pause/cancel flags — and the translation of a
 * `PayloadTransferUpdate` into a [SendResult]:
 *
 *     SUCCESS → Completed
 *     FAILURE → Failed
 *     CANCELED → Paused or Cancelled, depending on which flag was set
 *     IN_PROGRESS → progress
 *
 * **SESSION-END RULE (§11.3).** `onTransportDisconnected` and `close()` must
 * FIRST fail every outgoing waiter and clear the maps, and only then tear
 * anything down. That ordering is what makes INV-1 (never hang) and INV-3
 * (restart unsticks everything) hold on this transport, so it is implemented
 * in [failAllAndClear] and the session is not allowed to do it by hand.
 */
class NearbyTransfers {

    enum class PayloadStatus { SUCCESS, FAILURE, CANCELED, IN_PROGRESS }

    /** The reason a CANCELED update arrived, decided by who asked for it. */
    private enum class Interruption { PAUSE, CANCEL }

    private val waiters = ConcurrentHashMap<String, CompletableDeferred<SendResult>>()
    private val payloadToFile = ConcurrentHashMap<Long, String>()
    private val interruptions = ConcurrentHashMap<String, Interruption>()

    val pendingCount: Int get() = waiters.size

    fun register(fileId: String): CompletableDeferred<SendResult> {
        val waiter = CompletableDeferred<SendResult>()
        waiters[fileId] = waiter
        return waiter
    }

    fun bindPayload(payloadId: Long, fileId: String) {
        payloadToFile[payloadId] = fileId
    }

    fun fileFor(payloadId: Long): String? = payloadToFile[payloadId]

    fun payloadFor(fileId: String): Long? =
        payloadToFile.entries.firstOrNull { it.value == fileId }?.key

    fun markPaused(fileId: String) {
        interruptions[fileId] = Interruption.PAUSE
    }

    fun markCancelled(fileId: String) {
        interruptions[fileId] = Interruption.CANCEL
    }

    fun isInterrupted(fileId: String): Boolean = interruptions.containsKey(fileId)

    /**
     * Applies one transfer update. Returns the terminal result when the file
     * is finished, or null while it is still moving.
     */
    fun onUpdate(
        payloadId: Long,
        status: PayloadStatus,
        bytesTransferred: Long,
        totalBytes: Long,
        onProgress: (String, Long, Long) -> Unit,
    ): SendResult? {
        val fileId = payloadToFile[payloadId] ?: return null
        return when (status) {
            PayloadStatus.IN_PROGRESS -> {
                onProgress(fileId, bytesTransferred, totalBytes)
                null
            }
            PayloadStatus.SUCCESS -> complete(fileId, SendResult.Completed)
            PayloadStatus.FAILURE -> complete(fileId, SendResult.Failed("Nearby reported a failure"))
            PayloadStatus.CANCELED -> {
                // The SDK reports one CANCELED for both cases, so the flag the
                // user's action set is the only way to tell a pause from a
                // cancel. Without it a pause would look like an abandonment
                // and INV-3 would never resume the file.
                val result = when (interruptions[fileId]) {
                    Interruption.CANCEL -> SendResult.Cancelled
                    else -> SendResult.Paused
                }
                complete(fileId, result)
            }
        }
    }

    /** Completes one waiter and forgets everything about that file. */
    fun complete(fileId: String, result: SendResult): SendResult {
        waiters.remove(fileId)?.complete(result)
        payloadToFile.entries.removeAll { it.value == fileId }
        interruptions.remove(fileId)
        return result
    }

    /**
     * The session-end rule. Every pending waiter is failed BEFORE anything is
     * torn down, and the maps are cleared, so no caller can be left awaiting a
     * payload whose transport no longer exists.
     */
    fun failAllAndClear(reason: String = "session closed"): Int {
        val failed = waiters.size
        for ((_, waiter) in waiters) waiter.complete(SendResult.Failed(reason))
        waiters.clear()
        payloadToFile.clear()
        interruptions.clear()
        return failed
    }
}
