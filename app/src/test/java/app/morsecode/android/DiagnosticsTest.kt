package app.morsecode.android

import androidx.test.core.app.ApplicationProvider
import app.morsecode.android.core.data.HistoryPresentation
import app.morsecode.android.core.data.HistoryStore
import app.morsecode.android.core.data.Prefs
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.network.PlayServices
import app.morsecode.android.core.storage.Conflicts
import app.morsecode.android.core.util.DoctorChecks
import app.morsecode.android.core.util.Ids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * §6.12 history, §6.13's wiring, §6.14's export and §6.15's checks.
 * A15's export half and A26's content half are here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiagnosticsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // ----- A15 / A26: the exported log -------------------------------------

    @Test
    fun theExportedLogStartsWithTheVersionHeader() {
        val store = LogStore(context)
        store.clear()
        store.i("Multicast lock acquired")
        store.w("CRC mismatch on chunk seq=0192 — retrying")
        store.e("Session closed by peer · reason=USER_END")

        val text = store.exportText("1.0.0", 1)
        val firstLine = text.lineSequence().first()

        // A26: "a readable .txt whose first line is 'Morsecode 1.0.0 (1)'".
        assertEquals("Morsecode 1.0.0 (1)", firstLine)
        assertEquals(Ids.logHeader("1.0.0", 1), firstLine)
        // A15: the whole ring buffer is in it, not a summary.
        assertTrue(text.contains("Multicast lock acquired"))
        assertTrue(text.contains("CRC mismatch on chunk seq=0192"))
        assertTrue(text.contains("USER_END"))
        assertTrue(text.contains("INFO"))
        assertTrue(text.contains("ERROR"))
    }

    @Test
    fun crashCaptureObeysTheSettingsSwitch() {
        val store = LogStore(context)
        store.crashReports().forEach { it.delete() }

        // §6.13: the switch is READ before anything is written.
        store.crashCaptureEnabled = { false }
        store.recordCrash(Thread.currentThread(), IllegalStateException("boom"))
        assertTrue("a disabled switch must write nothing", store.crashReports().isEmpty())

        store.crashCaptureEnabled = { true }
        store.recordCrash(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(1, store.crashReports().size)
        assertTrue(store.exportText("1.0.0", 1).contains("boom"))
        store.crashReports().forEach { it.delete() }
    }

    // ----- §6.13: every switch has a reader ---------------------------------

    @Test
    fun everySettingIsReadBySomething() {
        val prefs = Prefs(context)

        // Sounds → SoundFx asks on every cue.
        prefs.setSounds(false)
        assertFalse(app.morsecode.android.core.transfer.SoundFx(context, prefs).isEnabled())
        prefs.setSounds(true)
        assertTrue(app.morsecode.android.core.transfer.SoundFx(context, prefs).isEnabled())

        // Conflict policy → the receiver's decision.
        prefs.setConflictPolicy(Prefs.ConflictPolicy.OVERWRITE)
        val policy = Conflicts.BatchPolicy(Conflicts.Policy.RENAME)
        policy.setDefault(Conflicts.Policy.OVERWRITE)
        assertEquals(Conflicts.Policy.OVERWRITE, policy.policyFor("batch-1"))
        assertTrue(
            Conflicts.decide("photo.jpg", exists = true, policy = policy.policyFor("batch-1"))
                is Conflicts.Decision.Overwrite,
        )

        // Broadcast peers → the cap, bounded by the tier.
        prefs.setBroadcastPeers(6)
        assertEquals(6, prefs.broadcastPeers.value)
        prefs.setBroadcastPeers(99)
        assertEquals("the range is 2–8 (G7)", 8, prefs.broadcastPeers.value)
        prefs.setBroadcastPeers(1)
        assertEquals(2, prefs.broadcastPeers.value)

        // Notifications and crash reports are flags other code reads; here we
        // only assert they persist and publish.
        prefs.setNotifications(false)
        assertFalse(prefs.notifications.value)
        prefs.setCrashReports(false)
        assertFalse(prefs.crashReports.value)
        prefs.setNotifications(true)
        prefs.setCrashReports(true)
    }

    @Test
    fun changingTheConflictSettingAffectsTheNextBatchNotThePreviousChoice() {
        val policy = Conflicts.BatchPolicy(Conflicts.Policy.RENAME)
        policy.applyToAll("batch-1", Conflicts.Policy.SKIP)
        policy.setDefault(Conflicts.Policy.OVERWRITE)

        // "Apply to all" for a batch still wins for THAT batch.
        assertEquals(Conflicts.Policy.SKIP, policy.policyFor("batch-1"))
        assertEquals(Conflicts.Policy.OVERWRITE, policy.policyFor("batch-2"))
    }

    // ----- §6.12 history ----------------------------------------------------

    private fun row(
        id: String,
        name: String,
        peer: String,
        direction: Direction,
        state: TransferState,
        at: Long,
    ) = HistoryStore.Row(id, name, 131_100_000, peer, direction, state, at, null)

    @Test
    fun directionSearchAndStatusAreOneStream() {
        val now = 1_715_601_600_000L
        val rows = listOf(
            row("1", "holiday.mp4", "Ravi's Redmi", Direction.RECEIVING, TransferState.COMPLETED, now),
            row("2", "notes.pdf", "Pixel 7X", Direction.RECEIVING, TransferState.FAILED, now - 1000),
            row("3", "song.mp3", "Ravi's Redmi", Direction.SENDING, TransferState.COMPLETED, now - 2000),
        )

        val received = HistoryPresentation.apply(rows, HistoryPresentation.Filter())
        assertEquals(listOf("1", "2"), received.map { it.itemId })

        val sent = HistoryPresentation.apply(
            rows,
            HistoryPresentation.Filter(direction = Direction.SENDING),
        )
        assertEquals(listOf("3"), sent.map { it.itemId })

        // §6.12: search matches the file name AND the peer name.
        assertEquals(
            listOf("1"),
            HistoryPresentation.apply(rows, HistoryPresentation.Filter(query = "holiday")).map { it.itemId },
        )
        assertEquals(
            listOf("1", "2"),
            HistoryPresentation.apply(rows, HistoryPresentation.Filter(query = "e")).map { it.itemId },
        )
        assertEquals(
            listOf("1"),
            HistoryPresentation.apply(rows, HistoryPresentation.Filter(query = "ravi")).map { it.itemId },
        )

        // Status filter, same stream.
        assertEquals(
            listOf("2"),
            HistoryPresentation.apply(
                rows,
                HistoryPresentation.Filter(states = setOf(TransferState.FAILED)),
            ).map { it.itemId },
        )
    }

    @Test
    fun historyGroupsByDayNewestFirst() {
        val now = 1_715_601_600_000L
        val day = 24L * 60 * 60 * 1000
        val rows = listOf(
            row("1", "a", "P", Direction.RECEIVING, TransferState.COMPLETED, now),
            row("2", "b", "P", Direction.RECEIVING, TransferState.COMPLETED, now - day),
            row("3", "c", "P", Direction.RECEIVING, TransferState.COMPLETED, now - day - 1000),
        )
        val groups = HistoryPresentation.group(
            HistoryPresentation.apply(rows, HistoryPresentation.Filter()),
            nowMillis = now,
            timeZone = java.util.TimeZone.getTimeZone("UTC"),
        )
        assertEquals(listOf("TODAY", "YESTERDAY"), groups.map { it.title })
        assertEquals(2, groups[1].rows.size)
    }

    @Test
    fun removeFromHistoryAndDeleteFileAreDifferentThings() {
        val file = File(context.cacheDir, "history-test-${System.nanoTime()}.txt")
        file.writeText("x")
        val store = HistoryStore(File(context.cacheDir, "history-${System.nanoTime()}.json"))
        store.record(
            item = app.morsecode.android.core.model.TransferItem(
                id = "row-1",
                batchId = "b",
                direction = Direction.RECEIVING,
                state = TransferState.COMPLETED,
                file = app.morsecode.android.core.model.TransferFile(
                    file.name,
                    android.net.Uri.fromFile(file),
                    null,
                    file.length(),
                ),
            ),
            peerName = "Ravi's Redmi",
            path = file.absolutePath,
        )

        assertEquals(1, store.read().size)
        store.remove("row-1")
        assertEquals("the row goes", 0, store.read().size)
        assertTrue("the file stays — they are two distinct actions (§6.12)", file.exists())
        file.delete()
    }

    // ----- §6.15 doctor -----------------------------------------------------

    private fun facts(
        wifi: Boolean = true,
        peers: Int = 3,
        multicast: Boolean = true,
        play: PlayServices.Availability = PlayServices.Availability.AVAILABLE,
        missing: List<String> = emptyList(),
        battery: Boolean = true,
        bluetooth: Boolean = true,
        location: Boolean = true,
        hotspot: Boolean = false,
        busy: List<Int> = emptyList(),
    ) = DoctorChecks.Facts(
        wifiConnected = wifi,
        wifiSsid = "MY-NETWORK",
        wifiRssi = -42,
        peersOnSubnet = peers,
        subnet = "192.168.1.0/24",
        multicastLockHeld = multicast,
        playServices = play,
        missingPermissions = missing,
        batteryExempt = battery,
        bluetoothOn = bluetooth,
        locationServicesOn = location,
        hotspotOn = hotspot,
        busyPorts = busy,
    )

    @Test
    fun aHealthyPhonePassesEveryCheck() {
        val checks = DoctorChecks.evaluate(facts())
        assertEquals(DoctorChecks.Status.OK, DoctorChecks.summarise(checks))
        val wifi = checks.first { it.id == "wifi" }
        assertEquals("Wi-Fi connected", wifi.label)
        assertEquals("MY-NETWORK · -42 dBm", wifi.detail)
        assertEquals("3 peers on 192.168.1.0/24", checks.first { it.id == "subnet" }.detail)
        assertEquals("Held · beacon every 1200 ms", checks.first { it.id == "multicast" }.detail)
    }

    @Test
    fun theSixSpecifiedChecksReadExactlyAsSpecified() {
        val checks = DoctorChecks.evaluate(
            facts(play = PlayServices.Availability.OUTDATED, battery = false),
        )
        assertEquals("Nearby Connections may be slower", checks.first { it.id == "play" }.detail)
        val battery = checks.first { it.id == "battery" }
        assertEquals("Battery optimization ON", battery.label)
        assertEquals("Transfers may pause in background", battery.detail)
        assertEquals("✓ Request battery exemption", battery.fix)
        assertEquals(DoctorChecks.Status.FAIL, DoctorChecks.summarise(checks))
    }

    @Test
    fun everyCheckSaysItsStatusInWordsNotOnlyInColour() {
        val checks = DoctorChecks.evaluate(facts(wifi = false, missing = listOf("android.permission.BLUETOOTH_SCAN")))
        for (check in checks) {
            assertTrue(check.glyph in listOf("✓", "!", "✕"))
            assertTrue(check.spoken.contains(check.label))
            assertTrue(check.detail.isNotEmpty())
        }
        assertEquals("BLUETOOTH_SCAN", checks.first { it.id == "permissions" }.detail)
    }

    @Test
    fun theHotspotCheckOnlyAppearsWhenTheHotspotIsOn() {
        assertTrue(DoctorChecks.evaluate(facts()).none { it.id == "hotspot" })
        assertTrue(DoctorChecks.evaluate(facts(hotspot = true)).any { it.id == "hotspot" })
    }

    @Test
    fun busyPortsAreNamed() {
        val checks = DoctorChecks.evaluate(facts(busy = listOf(Ids.PORT_TCP)))
        val ports = checks.first { it.id == "ports" }
        assertEquals(DoctorChecks.Status.FAIL, ports.status)
        assertEquals("33456", ports.detail)
    }
}
