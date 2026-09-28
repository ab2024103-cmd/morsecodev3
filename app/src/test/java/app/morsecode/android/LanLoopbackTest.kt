package app.morsecode.android

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.morsecode.android.core.data.HistoryStore
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.SendResult
import app.morsecode.android.core.model.TransferFile
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.network.LanTransport
import app.morsecode.android.core.storage.Conflicts
import app.morsecode.android.core.storage.ReceiveSinkFactory
import app.morsecode.android.core.storage.Destinations
import app.morsecode.android.core.transfer.IncomingFiles
import app.morsecode.android.core.transfer.TransferEngine
import app.morsecode.android.core.util.Ids
import app.morsecode.android.core.util.Integrity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream

/**
 * §11.2 end to end over real sockets on the loopback interface: a sender
 * dials, the receiver consents, a file crosses with MRSC framing and lands
 * byte-identical on the other side.
 *
 * This is the closest thing to A1 and A6 that exists without two phones. It
 * exercises the shipping classes — `LanTransport`, `ControlChannel`,
 * `LanSession`, `IncomingFiles`, `Framing` — with nothing stubbed but the
 * content opener and the destination directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LanLoopbackTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = LogStore(context)

    private lateinit var receiverTransport: LanTransport
    private lateinit var senderTransport: LanTransport
    private lateinit var receiveDir: File
    private lateinit var receiverEngine: TransferEngine

    @After
    fun tearDown() {
        runBlocking {
            runCatching { receiverTransport.shutdown() }
            runCatching { senderTransport.shutdown() }
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    // ----- Harness ----------------------------------------------------------

    /** A receiver whose files land in a temp directory, auto-consenting. */
    private fun startReceiver(
        consent: Boolean = true,
        history: HistoryStore? = null,
    ): Int {
        receiveDir = File(context.cacheDir, "recv-${System.nanoTime()}")
        receiveDir.mkdirs()

        receiverEngine = TransferEngine(scope = scope, history = history, logStore = log)
        val incoming = IncomingFiles(
            engine = receiverEngine,
            sinks = TempSinks(context, Destinations(context, log), receiveDir),
            conflictPolicy = Conflicts.BatchPolicy(Conflicts.Policy.RENAME),
            logStore = log,
        )
        receiverTransport = LanTransport(
            scope = scope,
            deviceId = "receiver-id",
            deviceName = "Ravi's Redmi",
            incoming = incoming,
            openContent = { null },
            port = 0, // ephemeral, so the suite never fights :33456
            logStore = log,
        )
        receiverTransport.consent = { consent }
        receiverTransport.start()
        return receiverTransport.boundPort
    }

    private fun startSender(source: File, port: Int = 0): LanTransport {
        senderTransport = LanTransport(
            scope = scope,
            deviceId = "sender-id",
            deviceName = "Pixel 7X",
            incoming = IncomingFiles(
                engine = TransferEngine(scope = scope),
                sinks = TempSinks(context, Destinations(context, log), File(context.cacheDir, "unused")),
                conflictPolicy = Conflicts.BatchPolicy(Conflicts.Policy.RENAME),
            ),
            openContent = { FileInputStream(source) },
            port = port,
            logStore = log,
        )
        senderTransport.start()
        return senderTransport
    }

    /**
     * Routes every sink into one temp directory instead of shared storage.
     * Only the destination is substituted: the `.morsecode.part` handling,
     * the append-on-resume and the rename-on-verify are the shipping ones.
     */
    private class TempSinks(
        context: android.content.Context,
        destinations: Destinations,
        private val directory: File,
    ) : ReceiveSinkFactory(context, destinations, null) {
        override fun create(displayName: String, mimeType: String?) =
            createInDirectory(directory, displayName)
    }

    private fun sourceFile(name: String, sizeBytes: Int): File {
        val file = File(context.cacheDir, "src-${System.nanoTime()}-$name")
        file.writeBytes(ByteArray(sizeBytes) { index -> (index % 251).toByte() })
        return file
    }

    private fun item(source: File, name: String, resumeOffset: Long = 0) = TransferItem(
        id = "file-1",
        batchId = "batch-1",
        direction = Direction.SENDING,
        state = TransferState.QUEUED,
        file = TransferFile(name, Uri.fromFile(source), "application/octet-stream", source.length()),
        resumeOffset = resumeOffset,
    )

    // ----- Tests ------------------------------------------------------------

    @Test
    fun aBusyControlPortIsReportedInsteadOfCrashingDiscovery() {
        val busyPort = startReceiver()
        val rejected = startSender(sourceFile("busy.bin", 1), busyPort)

        assertFalse("a second listener must fail closed, not throw", rejected.isListening)
    }

    @Test
    fun aFileCrossesTheWireByteIdentical() = runBlocking {
        // Deliberately several chunks plus a partial one: 600 KB over 256 KB
        // frames is 2 full frames and a remainder.
        val source = sourceFile("clip.mp4", 600 * 1024)
        val port = startReceiver()
        val sender = startSender(source)

        val session = withTimeout(15_000) { sender.connect("127.0.0.1", port) }
        assertNotNull("the handshake must produce a session", session)
        assertEquals("Ravi's Redmi", session!!.peerName)

        var lastProgress = 0L
        val result = withTimeout(30_000) {
            session.sendFile(item(source, "clip.mp4"), Integrity.sha256(FileInputStream(source))) { sent, _ ->
                lastProgress = sent
            }
        }

        assertTrue("expected Completed, got $result", result is SendResult.Completed)
        val received = File(receiveDir, "clip.mp4")
        assertTrue("the file must exist under its final name", received.exists())
        assertEquals(source.length(), received.length())
        assertEquals(
            Integrity.sha256(FileInputStream(source)),
            Integrity.sha256(FileInputStream(received)),
        )
        assertEquals("progress must reach the full size", source.length(), lastProgress)
        // §9.4: the .part file is gone once the file is published.
        assertTrue(File(receiveDir, "clip.mp4" + Ids.PART_SUFFIX).exists().not())
    }

    @Test
    fun aResumedSendContinuesFromThePartFile() = runBlocking {
        val source = sourceFile("big.bin", 400 * 1024)
        val port = startReceiver()
        val sender = startSender(source)

        // Pretend a previous attempt already wrote the first 150 KB.
        val already = 150 * 1024
        val part = File(receiveDir, "big.bin" + Ids.PART_SUFFIX)
        part.writeBytes(source.readBytes().copyOfRange(0, already))

        val session = withTimeout(15_000) { sender.connect("127.0.0.1", port) }!!
        val result = withTimeout(30_000) {
            session.sendFile(item(source, "big.bin"), null) { _, _ -> }
        }

        assertTrue("expected Completed, got $result", result is SendResult.Completed)
        val received = File(receiveDir, "big.bin")
        assertEquals(source.length(), received.length())
        // Byte-identical, which is what proves the sender seeked rather than
        // restarting and the receiver appended rather than truncating.
        assertTrue(source.readBytes().contentEquals(received.readBytes()))
    }

    @Test
    fun anIdenticalFileIsSkippedNotResent() = runBlocking {
        val source = sourceFile("photo.jpg", 64 * 1024)
        val port = startReceiver()
        val sender = startSender(source)

        // The receiver already holds this exact file. §9.5's detection order
        // is name → size → sha256, and only then is it "already present".
        File(receiveDir, "photo.jpg").writeBytes(source.readBytes())

        val session = withTimeout(15_000) { sender.connect("127.0.0.1", port) }!!
        val result = withTimeout(20_000) {
            session.sendFile(item(source, "photo.jpg"), Integrity.sha256(FileInputStream(source))) { _, _ -> }
        }

        assertTrue("expected SkippedAlreadyPresent, got $result", result is SendResult.SkippedAlreadyPresent)
        assertEquals(
            "identical file already present on receiver",
            (result as SendResult.SkippedAlreadyPresent).reason,
        )
    }

    @Test
    fun aRejectedConnectionYieldsNoSessionAndNoFiles() = runBlocking {
        val source = sourceFile("secret.pdf", 8 * 1024)
        val port = startReceiver(consent = false)
        val sender = startSender(source)

        val session = withTimeout(15_000) { sender.connect("127.0.0.1", port) }

        // §9.8: reject is clean — no session, and nothing written anywhere.
        assertNull(session)
        assertEquals(0, receiveDir.listFiles()?.size ?: 0)
    }

    @Test
    fun theReceiverRecordsHistoryThroughTheSameCompletePath() = runBlocking {
        val historyFile = File(context.cacheDir, "recv-history-${System.nanoTime()}.json")
        val history = HistoryStore(historyFile)
        val source = sourceFile("notes.pdf", 32 * 1024)
        val port = startReceiver(history = history)
        val sender = startSender(source)

        val session = withTimeout(15_000) { sender.connect("127.0.0.1", port) }!!
        withTimeout(20_000) { session.sendFile(item(source, "notes.pdf"), null) { _, _ -> } }

        val rows = history.read()
        assertEquals(1, rows.size)
        assertEquals("notes.pdf", rows.first().name)
        assertEquals(Direction.RECEIVING, rows.first().direction)
        assertEquals(TransferState.COMPLETED, rows.first().state)
        // The receiving item is in the same queue the UI renders (§20.1).
        assertTrue(receiverEngine.items.value.any { it.direction == Direction.RECEIVING })
    }
}
