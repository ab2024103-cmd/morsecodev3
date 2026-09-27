package app.morsecode.android

import android.net.Uri
import app.morsecode.android.core.model.SendResult
import app.morsecode.android.core.model.TransferFile
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.network.TransportKind
import app.morsecode.android.core.network.TransportSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * The test harness's transport (§ Stage 5 is headless: drive it from tests).
 *
 * It can behave like every real-world stack the engine has to survive:
 * a well-behaved peer, a peer that fails a few times before succeeding, a peer
 * that reports "already present", and — the one that matters most — an OEM
 * stack that NEVER emits a terminal update after a cancelled or paused
 * payload, which is exactly the hang INV-1(b)'s 3-second fallback exists for.
 */
class FakeSession(
    override val peerId: String = "peer-1",
    override val peerName: String = "Ravi's Redmi",
    override val transport: TransportKind = TransportKind.LAN,
) : TransportSession {

    /** What [sendFile] should do, per file name. */
    enum class Behaviour {
        COMPLETE,
        /** Suspends forever: the transport never answers (INV-1b). */
        HANG,
        FAIL,
        /** Fails [failuresBeforeSuccess] times, then completes (§9.3 retry). */
        FLAKY,
        ALREADY_PRESENT,
    }

    var defaultBehaviour: Behaviour = Behaviour.COMPLETE
    var failuresBeforeSuccess: Int = 2
    var progressSteps: Int = 0
    override var isAlive: Boolean = true

    private val behaviours = ConcurrentHashMap<String, Behaviour>()
    private val attempts = ConcurrentHashMap<String, Int>()

    val sentFiles = java.util.Collections.synchronizedList(ArrayList<String>())
    val pauseCalls = java.util.Collections.synchronizedList(ArrayList<String>())
    val cancelCalls = java.util.Collections.synchronizedList(ArrayList<String>())
    var closeReason: String? = null
        private set

    /** Lets a test release a HANG deliberately. */
    val released = CompletableDeferred<Unit>()

    fun behave(fileName: String, behaviour: Behaviour) {
        behaviours[fileName] = behaviour
    }

    fun attemptsFor(fileName: String): Int = attempts[fileName] ?: 0

    override suspend fun sendFile(
        item: TransferItem,
        sha256: String?,
        onProgress: (Long, Long) -> Unit,
    ): SendResult {
        val name = item.file.displayName
        sentFiles.add(name)
        val attempt = (attempts[name] ?: 0) + 1
        attempts[name] = attempt

        repeat(progressSteps) { step ->
            delay(1)
            val sent = item.totalBytes * (step + 1) / progressSteps
            onProgress(sent, 1_000_000)
        }

        return when (behaviours[name] ?: defaultBehaviour) {
            Behaviour.COMPLETE -> SendResult.Completed
            Behaviour.ALREADY_PRESENT ->
                SendResult.SkippedAlreadyPresent("identical file already present on receiver")
            Behaviour.FAIL -> SendResult.Failed("io error")
            Behaviour.FLAKY ->
                if (attempt > failuresBeforeSuccess) SendResult.Completed else SendResult.Failed("io error")
            Behaviour.HANG -> {
                // Never returns on its own; only the engine's watchdog or a
                // deliberate release ends this wait.
                released.await()
                SendResult.Completed
            }
        }
    }

    override suspend fun pauseOutgoing(fileId: String) {
        pauseCalls.add(fileId)
    }

    override suspend fun cancelTransfer(fileId: String) {
        cancelCalls.add(fileId)
    }

    override suspend fun close(reason: String) {
        closeReason = reason
        isAlive = false
    }

    companion object {
        fun file(name: String, size: Long = 1024): TransferFile = TransferFile(
            displayName = name,
            uri = Uri.parse("content://test/$name"),
            mime = null,
            size = size,
        )
    }
}
