package app.morsecode.android

import android.net.Uri
import app.morsecode.android.core.media.DayGroups
import app.morsecode.android.core.media.PathSegments
import app.morsecode.android.core.media.Selection
import app.morsecode.android.core.media.SortRules
import app.morsecode.android.core.model.DirectoryEntry
import app.morsecode.android.core.model.FileType
import app.morsecode.android.core.model.MediaItem
import app.morsecode.android.core.model.SortKey
import app.morsecode.android.core.model.SortOrder
import app.morsecode.android.core.ui.DeckPhysics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/**
 * §6.9's grouping, sorting, selection and address bar, and §6.10's swipe deck.
 * These are the decisions behind A7, A23, A25, A29, A30, A31, A33 and A34.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FilesPresentationTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_715_558_400_000L // 2024-05-13T00:00:00Z

    private fun photo(id: Long, at: Long, name: String = "IMG_$id.jpg", size: Long = 1000) = MediaItem(
        id = id,
        uri = Uri.parse("content://media/$id"),
        name = name,
        sizeBytes = size,
        dateMillis = at,
        mimeType = "image/jpeg",
        type = FileType.IMAGE,
    )

    // ----- A7: day groups appear once, in order -----------------------------

    @Test
    fun dayGroupsAppearExactlyOnceAndInOrder() {
        val items = listOf(
            photo(1, now - 1 * 3600_000),
            photo(2, now - 2 * 3600_000),
            photo(3, now - day - 3600_000),
            photo(4, now - day - 7200_000),
            photo(5, now - 5 * day),
        )
        val groups = DayGroups.group(items, nowMillis = now, timeZone = utc, locale = Locale.UK)

        assertEquals(3, groups.size)
        assertFalse("a day may never get a second header (A7)", DayGroups.hasDuplicateDays(groups))
        assertTrue("groups must run newest first", DayGroups.isDescending(groups))
        assertEquals("Today · 2 items", groups[0].header)
        assertEquals("Yesterday · 2 items", groups[1].header)
        assertEquals("This week · 1 item", groups[2].header)
    }

    @Test
    fun anOutOfOrderQueryIsNotSilentlyRepaired() {
        // Grouping must not re-sort: hiding a bad query here is exactly how
        // A7's "day groups appear once" regresses unnoticed.
        val items = listOf(photo(1, now), photo(2, now - day), photo(3, now))
        val groups = DayGroups.group(items, nowMillis = now, timeZone = utc)
        assertEquals(3, groups.size)
        assertTrue("the duplicate day is visible, not hidden", DayGroups.hasDuplicateDays(groups))
    }

    @Test
    fun theDayLadderReadsTodayYesterdayThisWeekThenADate() {
        assertEquals("Today", DayGroups.titleFor(dayStart(now), now, utc, Locale.UK))
        assertEquals("Yesterday", DayGroups.titleFor(dayStart(now) - day, now, utc, Locale.UK))
        assertEquals("This week", DayGroups.titleFor(dayStart(now) - 3 * day, now, utc, Locale.UK))
        assertEquals("5 Apr", DayGroups.titleFor(dayStart(now) - 38 * day, now, utc, Locale.UK))
    }

    private fun dayStart(millis: Long) =
        app.morsecode.android.core.media.MediaDates.dayStartMillis(millis, utc)

    // ----- A34: the sort control actually sorts -----------------------------

    @Test
    fun theSortEchoReadsAsTheMockDoes() {
        assertEquals(
            "Size · smallest first",
            SortRules.label(SortOrder(SortKey.SIZE, descending = false)),
        )
        assertEquals("Date modified · newest first", SortRules.label(SortOrder()))
        assertEquals("Name · A to Z", SortRules.label(SortOrder(SortKey.NAME, descending = false)))
    }

    @Test
    fun sortingIsStableAndFoldersAlwaysComeFirst() {
        val entries = listOf(
            DirectoryEntry("b.txt", "/root/b.txt", false, 10, 100, FileType.DOCUMENT),
            DirectoryEntry("Photos", "/root/Photos", true, 0, 50, FileType.UNKNOWN, childCount = 4),
            DirectoryEntry("a.txt", "/root/a.txt", false, 900, 200, FileType.DOCUMENT),
            DirectoryEntry("Archive", "/root/Archive", true, 0, 10, FileType.UNKNOWN),
        )
        for (key in SortKey.values()) {
            for (descending in listOf(true, false)) {
                val sorted = SortRules.sortEntries(entries, SortOrder(key, descending))
                assertTrue(
                    "folders must sort before files for $key/$descending",
                    sorted.take(2).all { it.isDirectory },
                )
            }
        }
        val bySize = SortRules.sortEntries(entries, SortOrder(SortKey.SIZE, descending = false))
        assertEquals(listOf("b.txt", "a.txt"), bySize.filterNot { it.isDirectory }.map { it.name })
    }

    @Test
    fun itemSortingHasAStableTiebreak() {
        // Two items with the same key must not swap between renders (A31).
        val same = listOf(photo(1, now, size = 10), photo(2, now, size = 10), photo(3, now, size = 10))
        val first = SortRules.sortItems(same, SortOrder(SortKey.SIZE))
        val second = SortRules.sortItems(same, SortOrder(SortKey.SIZE))
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    // ----- A23/A24/A25: one selection basket --------------------------------

    @Test
    fun oneBasketIsSharedByEveryTabAndCountsItems() {
        val selection = Selection()
        val image = Selection.of(photo(1, now, "IMG_1.jpg", 100_000))
        val track = Selection.of(photo(2, now, "song.mp3", 8_400_000).copy(type = FileType.AUDIO))
        val apk = Selection.of(photo(3, now, "app.apk", 20_000_000).copy(type = FileType.APK))

        assertFalse(selection.isActive)
        selection.toggle(image)
        selection.toggle(track)
        selection.toggle(apk)

        // A mixed send is one basket with a count and a byte total (§6.9).
        assertEquals(3, selection.count)
        assertEquals(28_500_000L, selection.totalBytes)
        assertTrue(selection.isActive)

        selection.toggle(track)
        assertEquals(2, selection.count)
        assertFalse(selection.contains(track.key))
    }

    @Test
    fun aSelectedFolderIsOneItemWithItsRecursiveSize() {
        // A25: a folder is sendable as one item at its recursive size.
        val folder = DirectoryEntry("Camera", "/root/Camera", true, 0, 0, FileType.UNKNOWN, childCount = 812)
        val entry = Selection.of(folder, recursiveSize = 1_200_000_000)
        val selection = Selection()
        selection.toggle(entry)
        assertEquals(1, selection.count)
        assertEquals(1_200_000_000L, selection.totalBytes)
        assertTrue(selection.snapshot().single().isDirectory)
    }

    @Test
    fun sendTakesTheBasketAndLeavesItEmpty() {
        // A24: the selection does not survive the send; the user must never
        // have to tap ✕ afterwards.
        val selection = Selection()
        selection.toggle(Selection.of(photo(1, now)))
        selection.toggle(Selection.of(photo(2, now)))

        val taken = selection.takeAll()
        assertEquals(2, taken.size)
        assertEquals(0, selection.count)
        assertFalse("a fresh send screen never shows stale ticks", selection.isActive)
    }

    @Test
    fun groupSelectAllTogglesTheWholeGroupOnly() {
        val selection = Selection()
        val group = listOf(Selection.of(photo(1, now)), Selection.of(photo(2, now)))
        val other = Selection.of(photo(3, now))

        selection.selectAll(group)
        assertTrue(selection.isGroupSelected(group))
        assertFalse(selection.contains(other.key))

        selection.deselectAll(group)
        assertFalse(selection.isGroupSelected(group))
        assertEquals(0, selection.count)
    }

    // ----- A30: the address bar --------------------------------------------

    @Test
    fun everyPathSegmentIsJumpable() {
        val root = "/storage/emulated/0"
        val segments = PathSegments.of("$root/DCIM/Camera", root)
        assertEquals(listOf("Internal storage", "DCIM", "Camera"), segments.map { it.label })
        assertEquals(root, segments[0].path)
        assertEquals("$root/DCIM", segments[1].path)
        assertEquals("$root/DCIM/Camera", segments[2].path)
    }

    @Test
    fun theBarSurvivesTheRootAndForeignVolumes() {
        val root = "/storage/emulated/0"
        // "The bar keeps its full height even at the storage root with zero
        // segments" — so the root is itself one segment, never an empty bar.
        assertEquals(1, PathSegments.of(root, root).size)
        assertNull("nothing is above the root", PathSegments.parentOf(root, root))
        assertEquals(root, PathSegments.parentOf("$root/Download", root))

        val sdCard = PathSegments.of("/storage/1A2B-3C4D/Movies", root)
        assertEquals(listOf("storage", "1A2B-3C4D", "Movies"), sdCard.map { it.label })
    }

    // ----- A33: the swipe deck ---------------------------------------------

    @Test
    fun releasingPastTwentyTwoPercentCommits() {
        val width = 1000
        assertEquals(DeckPhysics.Outcome.NEXT, DeckPhysics.outcome(-230f, width, 0f))
        assertEquals(DeckPhysics.Outcome.PREVIOUS, DeckPhysics.outcome(230f, width, 0f))
        // Anything less snaps back.
        assertEquals(DeckPhysics.Outcome.SNAP_BACK, DeckPhysics.outcome(-210f, width, 0f))
        assertEquals(DeckPhysics.Outcome.SNAP_BACK, DeckPhysics.outcome(210f, width, 0f))
        // A flick commits regardless of distance.
        assertEquals(DeckPhysics.Outcome.NEXT, DeckPhysics.outcome(-40f, width, -2_000f))
        assertEquals(DeckPhysics.Outcome.PREVIOUS, DeckPhysics.outcome(40f, width, 2_000f))
        // A flick against the drag is a change of mind, not a commit.
        assertEquals(DeckPhysics.Outcome.SNAP_BACK, DeckPhysics.outcome(-40f, width, 2_000f))
    }

    @Test
    fun theDeckWrapsAtBothEnds() {
        assertEquals(0, DeckPhysics.nextIndex(4, 5))
        assertEquals(4, DeckPhysics.previousIndex(0, 5))
        assertEquals(1, DeckPhysics.apply(DeckPhysics.Outcome.NEXT, 0, 5))
        assertEquals(3, DeckPhysics.apply(DeckPhysics.Outcome.SNAP_BACK, 3, 5))
        // A single photo has nowhere to go and must not divide by zero.
        assertEquals(0, DeckPhysics.nextIndex(0, 1))
        assertEquals(0, DeckPhysics.nextIndex(0, 0))
    }

    @Test
    fun thePositionLabelIsOneBased() {
        assertEquals("5 of 15", DeckPhysics.positionLabel(4, 15))
        assertEquals("1 of 1", DeckPhysics.positionLabel(0, 1))
    }
}
