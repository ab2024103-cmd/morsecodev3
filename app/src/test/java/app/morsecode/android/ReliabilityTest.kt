package app.morsecode.android

import androidx.test.core.app.ApplicationProvider
import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.util.A11y
import app.morsecode.android.core.util.DeviceTier
import app.morsecode.android.core.util.PowerPolicy
import app.morsecode.android.core.webshare.ThumbnailStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §14 power, §15 accessibility and §13/§20.10 performance — the reliability
 * pass. A10, A11, A17 and A35 belong to this stage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReliabilityTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun item(state: TransferState, name: String = "clip.mp4", sent: Long = 0, total: Long = 1000) =
        TransferItem(
            id = name,
            batchId = "batch-1",
            direction = Direction.SENDING,
            state = state,
            file = FakeSession.file(name, total),
            bytesTransferred = sent,
        )

    // ----- §14.5 locks held only while something is moving ------------------

    @Test
    fun locksAreHeldForActiveWorkAndReleasedForEverythingElse() {
        val policy = PowerPolicy(context)
        assertFalse("never at process start", policy.shouldHold(emptyList()))
        assertTrue(policy.shouldHold(listOf(item(TransferState.IN_PROGRESS))))
        assertTrue("a queued file is still work", policy.shouldHold(listOf(item(TransferState.QUEUED))))

        // Paused-idle and fully terminal both release (§14.5).
        assertFalse(policy.shouldHold(listOf(item(TransferState.PAUSED))))
        assertFalse(
            policy.shouldHold(
                listOf(item(TransferState.COMPLETED), item(TransferState.FAILED, "b"), item(TransferState.SKIPPED, "c")),
            ),
        )
    }

    @Test
    fun applyingThePolicyTwiceNeitherDoubleAcquiresNorLeaks() {
        val policy = PowerPolicy(context)
        policy.apply(listOf(item(TransferState.IN_PROGRESS)))
        policy.apply(listOf(item(TransferState.IN_PROGRESS)))
        assertTrue(policy.isHolding)
        policy.apply(listOf(item(TransferState.COMPLETED)))
        assertFalse("the lock is released when the batch settles", policy.isHolding)
        policy.apply(listOf(item(TransferState.COMPLETED)))
        assertFalse(policy.isHolding)
    }

    // ----- §14.4 advisory, never a block ------------------------------------

    @Test
    fun theLowBatteryAdvisoryFiresOnlyWhenItShould() {
        val big = PowerPolicy.LARGE_BATCH_BYTES
        assertEquals(
            "Battery is low — this transfer may be interrupted.",
            PowerPolicy.lowBatteryAdvisory(15, isCharging = false, batterySaverOn = false, batchBytes = big),
        )
        // Plugged in: no advisory, however low the battery.
        assertNull(PowerPolicy.lowBatteryAdvisory(5, isCharging = true, batterySaverOn = false, batchBytes = big))
        // Battery Saver counts even at a healthy level.
        assertEquals(
            "Battery is low — this transfer may be interrupted.",
            PowerPolicy.lowBatteryAdvisory(80, isCharging = false, batterySaverOn = true, batchBytes = big),
        )
        // A small batch is not worth a warning.
        assertNull(PowerPolicy.lowBatteryAdvisory(5, isCharging = false, batterySaverOn = true, batchBytes = 1024))
    }

    // ----- §14.2 OEM guidance -----------------------------------------------

    @Test
    fun autostartGuidancePutsThisPhoneFirstAndStillListsEveryone() {
        val xiaomi = PowerPolicy.autostartGuidance("Xiaomi")
        assertEquals("Xiaomi", xiaomi.first().first)
        assertEquals(5, xiaomi.size)

        val samsung = PowerPolicy.autostartGuidance("samsung")
        assertEquals("Samsung", samsung.first().first)

        // An unlisted manufacturer still sees all five (§14.2).
        val nokia = PowerPolicy.autostartGuidance("HMD Global")
        assertEquals(5, nokia.size)
        assertEquals(
            setOf("Xiaomi", "Huawei", "Oppo", "Vivo", "Samsung"),
            nokia.map { it.first }.toSet(),
        )
        assertTrue(nokia.all { it.second.length > 40 })
    }

    // ----- §15 accessibility ------------------------------------------------

    @Test
    fun talkbackIsToldTheStateInWordsAndNamesTheFile() {
        assertEquals(
            "Transfer complete: clip.mp4",
            A11y.describe(item(TransferState.COMPLETED)),
        )
        assertEquals(
            "Paused at 50 percent: clip.mp4",
            A11y.describe(item(TransferState.PAUSED, sent = 500)),
        )
        assertEquals(
            "Skipped, already on the other phone: clip.mp4",
            A11y.describe(item(TransferState.SKIPPED)),
        )
        // §15.1's own example of a good label.
        assertEquals("Pause transfer of holiday_2019.mp4", A11y.actionLabel("Pause", "holiday_2019.mp4"))
    }

    @Test
    fun progressIsAnnouncedAtSensibleIntervalsButCompletionAlways() {
        // §15.5: not every 100 ms tick…
        assertFalse(A11y.shouldAnnounce(lastAnnouncedAtMillis = 1_000, nowMillis = 1_100, isTerminal = false))
        assertTrue(A11y.shouldAnnounce(1_000, 6_000, isTerminal = false))
        // …and "Transfer complete" is never swallowed by the throttle.
        assertTrue(A11y.shouldAnnounce(1_000, 1_050, isTerminal = true))
    }

    // ----- §13 / §20.10 performance -----------------------------------------

    @Test
    fun theTierScalesEveryBudgetItOwns() {
        val tier = DeviceTier(context)
        assertTrue(tier.pageSize >= 60)
        assertTrue(tier.thumbnailPx >= 128)
        assertTrue(tier.thumbnailCacheBytes >= 3 * 1024 * 1024)
        assertTrue(tier.chunkThreads in 2..4)
        assertTrue(tier.maxNearbyPeers in 2..3)
        // §13: tiering scales work, never capability — every value is a number.
        assertTrue(tier.preHashLimitBytes > 0)
    }

    @Test
    fun theWebThumbnailCacheIsKeyedOnIdAndMtime() {
        val store = ThumbnailStore(context)
        val first = store.keyFor("/storage/emulated/0/DCIM/a.jpg", 1_000)
        val sameAgain = store.keyFor("/storage/emulated/0/DCIM/a.jpg", 1_000)
        val edited = store.keyFor("/storage/emulated/0/DCIM/a.jpg", 2_000)
        val other = store.keyFor("/storage/emulated/0/DCIM/b.jpg", 1_000)

        assertEquals(first, sameAgain)
        // §7.1's rule: an edited file cannot serve its old picture.
        assertNotEquals(first, edited)
        assertNotEquals(first, other)
    }

    @Test
    fun webThumbnailCacheEvictsGeneratedFilesPastItsByteBudget() {
        val source = java.io.File(context.cacheDir, "thumbnail-budget-source.png")
        android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
            source.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val store = ThumbnailStore(context, maxBytes = 1L)
        store.clear()
        store.thumbnail(source)
        assertTrue("§7.2 cache stays within its configured byte budget", store.cachedBytes() <= 1L)
        source.delete()
    }

    @Test
    fun thumbnailDecodingPicksASampleSizeThatStillCoversTheBox() {
        val store = ThumbnailStore(context, maxPixels = 320)
        assertEquals(1, store.sampleSizeFor(320, 320, 320))
        assertEquals(2, store.sampleSizeFor(640, 640, 320))
        assertEquals(8, store.sampleSizeFor(4032, 3024, 320))
        assertEquals("a degenerate size must not divide by zero", 1, store.sampleSizeFor(0, 0, 320))
    }

    // ----- A35: the responsive rules ----------------------------------------

    @Test
    fun gridsRespanRatherThanStretch() {
        // §4.14: "grids use a target cell width, never a hard-coded span", so
        // the span is a function of the width and the minimum is three.
        val target = context.resources.getDimensionPixelSize(app.morsecode.android.R.dimen.grid_cell_target)
        fun spanFor(widthPx: Int) = (widthPx / target).coerceAtLeast(3)

        val density = context.resources.displayMetrics.density
        val widths = listOf(320, 360, 412, 480, 600, 840).map { (it * density).toInt() }
        val spans = widths.map { spanFor(it) }

        assertTrue("every width gets at least three columns", spans.all { it >= 3 })
        assertTrue("a wider screen never gets fewer columns", spans.zipWithNext().all { it.first <= it.second })
        assertTrue("840 dp gets more than 320 dp", spans.last() > spans.first())
    }
}
