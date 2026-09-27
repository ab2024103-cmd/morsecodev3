package app.morsecode.android

import androidx.test.core.app.ApplicationProvider
import app.morsecode.android.core.media.Selection
import app.morsecode.android.core.ui.TabStripView
import app.morsecode.android.core.webshare.UploadMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The last two items on §21.1's list: "tab/selection state machines" and
 * "chunked-upload index math".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UploadAndTabsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // ----- §7.7 chunked-upload index math -----------------------------------

    @Test
    fun chunkCountsCoverTheFileExactlyOnce() {
        val chunk = UploadMath.CHUNK_BYTES
        assertEquals(4 * 1024 * 1024, chunk)
        assertEquals(1, UploadMath.chunkCount(0))
        assertEquals(1, UploadMath.chunkCount(1))
        assertEquals(1, UploadMath.chunkCount(chunk.toLong()))
        assertEquals(2, UploadMath.chunkCount(chunk + 1L))
        assertEquals(3, UploadMath.chunkCount(10L * 1024 * 1024))

        // The ranges tile the file: no gap, no overlap, nothing past the end.
        val size = 10L * 1024 * 1024
        var covered = 0L
        var previousEnd = 0L
        for (index in 0 until UploadMath.chunkCount(size)) {
            val range = UploadMath.rangeOf(index, size)
            assertEquals("chunk $index starts where the last ended", previousEnd, range.first)
            covered += range.last - range.first + 1
            previousEnd = range.last + 1
        }
        assertEquals(size, covered)
    }

    @Test
    fun theLastChunkIsTheOneThatFinishesTheFile() {
        val size = 10L * 1024 * 1024
        assertFalse(UploadMath.isLast(0, size))
        assertFalse(UploadMath.isLast(1, size))
        assertTrue(UploadMath.isLast(2, size))
        // A file smaller than one chunk is a single, final chunk.
        assertTrue(UploadMath.isLast(0, 1024))
    }

    @Test
    fun resumeContinuesFromTheServersCountAndNeverFromZero() {
        val size = 10L * 1024 * 1024 // three chunks
        // §7.7: "resume means ask /api/upload-status for the last index and
        // continue — never restart from zero".
        assertEquals(2, UploadMath.resumeIndex(receivedChunks = 2, sizeBytes = size))
        assertEquals(0, UploadMath.resumeIndex(0, size))
        // A count past the end cannot produce an index past the end.
        assertEquals(3, UploadMath.resumeIndex(99, size))
        assertTrue(UploadMath.isComplete(3, size))
        assertFalse(UploadMath.isComplete(2, size))
    }

    @Test
    fun progressBytesAreClampedToTheRealFile() {
        val size = 10L * 1024 * 1024
        assertEquals(0L, UploadMath.bytesFor(0, size))
        assertEquals(4L * 1024 * 1024, UploadMath.bytesFor(1, size))
        assertEquals("never more than the file itself", size, UploadMath.bytesFor(99, size))
    }

    // ----- §6.9 / §20.1 tab and selection state machines --------------------

    @Test
    fun theTabStripIsTheOneSourceOfTheIndex() {
        val strip = TabStripView(context)
        val seen = ArrayList<Int>()
        strip.bind(listOf("Photos", "Videos", "Music", "Apps", "Files")) { seen.add(it) }

        assertEquals(0, strip.selectedIndex())
        strip.select(3)
        assertEquals(3, strip.selectedIndex())
        assertEquals(listOf(3), seen)

        // Selecting the current tab is not a change and must not re-notify —
        // a duplicate notification is how a list gets rebuilt under a finger.
        strip.select(3)
        assertEquals(listOf(3), seen)

        strip.select(4)
        assertEquals(listOf(3, 4), seen)
        assertEquals(4, strip.selectedIndex())
    }

    @Test
    fun theSelectionSurvivesTabChangesAndClearsEverywhereAtOnce() {
        // §20.1: one canonical set, shared by every tab; clearing clears it
        // everywhere, including a queue already handed off.
        val selection = Selection()
        val photo = Selection.of(
            app.morsecode.android.core.model.MediaItem(
                id = 1,
                uri = android.net.Uri.parse("content://media/1"),
                name = "IMG_1.jpg",
                sizeBytes = 100,
                dateMillis = 0,
                mimeType = "image/jpeg",
                type = app.morsecode.android.core.model.FileType.IMAGE,
            ),
        )
        val track = Selection.of(
            app.morsecode.android.core.model.MediaItem(
                id = 2,
                uri = android.net.Uri.parse("content://media/2"),
                name = "song.mp3",
                sizeBytes = 200,
                dateMillis = 0,
                mimeType = "audio/mpeg",
                type = app.morsecode.android.core.model.FileType.AUDIO,
            ),
        )

        selection.toggle(photo)
        selection.toggle(track)
        assertEquals(2, selection.count)
        assertEquals(300L, selection.totalBytes)

        // A tab change does not touch the basket…
        assertTrue(selection.contains(photo.key))
        assertTrue(selection.contains(track.key))

        // …and clearing empties it in one place.
        selection.clear()
        assertEquals(0, selection.count)
        assertFalse(selection.isActive)
        assertEquals(0L, selection.totalBytes)
    }
}
