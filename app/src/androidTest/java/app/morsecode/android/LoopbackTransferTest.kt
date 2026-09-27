package app.morsecode.android

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.morsecode.android.core.data.HistoryStore
import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.SendResult
import app.morsecode.android.core.model.TransferFile
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.network.LanTransport
import app.morsecode.android.core.storage.Conflicts
import app.morsecode.android.core.storage.Destinations
import app.morsecode.android.core.storage.ReceiveSinkFactory
import app.morsecode.android.core.transfer.IncomingFiles
import app.morsecode.android.core.transfer.TransferEngine
import app.morsecode.android.core.util.Integrity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/**
 * §21.3's loopback transfer, on the Android runtime.
 *
 * Stage 6 proved the LAN path on the JVM; this proves it on a device — real
 * sockets, the real `LanTransport` accept loop, the real receive sink writing
 * to real storage, and the engine driving it. Everything that is not the radio
 * is exercised here.
 */
@RunWith(AndroidJUnit4::class)
class LoopbackTransferTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiver: LanTransport? = null
    private var sender: LanTransport? = null

    @After
    fun tearDown() {
        runBlocking {
            runCatching { receiver?.shutdown() }
            runCatching { sender?.shutdown() }
        }
    }

    private class TempSinks(
        context: android.content.Context,
        destinations: Destinations,
        private val directory: File,
    ) : ReceiveSinkFactory(context, destinations, null) {
        override fun create(displayName: String, mimeType: String?) =
            createInDirectory(directory, displayName)
    }

    @Test
    fun aFileCrossesLoopbackOnDeviceAndVerifies() = runBlocking {
        val source = File(context.cacheDir, "device-src.bin")
        source.writeBytes(ByteArray(900 * 1024) { (it % 251).toByte() })
        val inbox = File(context.cacheDir, "device-inbox-${System.nanoTime()}").apply { mkdirs() }

        val receiverEngine = TransferEngine(scope = scope)
        val history = HistoryStore(File(context.cacheDir, "device-history-${System.nanoTime()}.json"))
        val receiverEngineWithHistory = TransferEngine(scope = scope, history = history)

        val incoming = IncomingFiles(
            engine = receiverEngineWithHistory,
            sinks = TempSinks(context, Destinations(context), inbox),
            conflictPolicy = Conflicts.BatchPolicy(Conflicts.Policy.RENAME),
        )
        val receiverTransport = LanTransport(
            scope = scope,
            deviceId = "device-receiver",
            deviceName = "Emulator receiver",
            incoming = incoming,
            openContent = { null },
            port = 0,
        )
        receiverTransport.consent = { true }
        receiverTransport.start()
        receiver = receiverTransport

        val senderTransport = LanTransport(
            scope = scope,
            deviceId = "device-sender",
            deviceName = "Emulator sender",
            incoming = IncomingFiles(
                engine = receiverEngine,
                sinks = TempSinks(context, Destinations(context), File(context.cacheDir, "unused")),
                conflictPolicy = Conflicts.BatchPolicy(Conflicts.Policy.RENAME),
            ),
            openContent = { FileInputStream(source) },
            port = 0,
        )
        senderTransport.start()
        sender = senderTransport

        val session = withTimeout(20_000) {
            senderTransport.connect("127.0.0.1", receiverTransport.boundPort)
        }
        assertNotNull("the on-device handshake produced no session", session)

        val item = TransferItem(
            id = "device-file-1",
            batchId = "device-batch",
            direction = Direction.SENDING,
            state = TransferState.QUEUED,
            file = TransferFile("photo.bin", Uri.fromFile(source), null, source.length()),
        )
        val sha = Integrity.sha256(FileInputStream(source))
        val result = withTimeout(60_000) { session!!.sendFile(item, sha) { _, _ -> } }

        assertTrue("expected Completed, got $result", result is SendResult.Completed)
        val received = File(inbox, "photo.bin")
        assertTrue("the file did not land", received.exists())
        assertEquals(source.length(), received.length())
        assertEquals(sha, Integrity.sha256(FileInputStream(received)))

        // §9.6: the receiving side records history through the same path.
        val rows = history.read()
        assertEquals(1, rows.size)
        assertEquals(Direction.RECEIVING, rows.first().direction)
    }
}
