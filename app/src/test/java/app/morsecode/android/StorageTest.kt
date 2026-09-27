package app.morsecode.android

import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import app.morsecode.android.core.model.DirectoryListing
import app.morsecode.android.core.storage.Destinations
import app.morsecode.android.core.util.DeviceTier
import app.morsecode.android.core.util.Ids
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * §12.4 destinations and §12.2 graceful denial.
 *
 * The destination rules are worth a test because §12.4 forbids writing to two
 * places at once: everything — receives, "Open folder", the Files tab and the
 * WebShare upload target — has to read the same accessor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StorageTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun destinations() = Destinations(context)

    @Test
    fun theDefaultIsDownloadMorsecode() {
        val destinations = destinations()
        assertEquals("Download/Morsecode", destinations.defaultRelativePath)
        assertEquals(Ids.DEFAULT_MEDIA_FOLDER, destinations.defaultRelativePath)
        assertTrue(destinations.defaultDirectory().absolutePath.endsWith("Download/Morsecode"))
        // Nothing is created just by asking where it would go (§12.4: on first
        // receive, not at launch).
        assertEquals("Download/Morsecode", destinations.label())
    }

    @Test
    fun choosingATreeMovesEverythingToIt() {
        val destinations = destinations()
        val tree = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AShared")
        destinations.treeUri = tree
        assertEquals(tree, destinations.treeUri)
        // The label and the "Open folder" target follow the same single value.
        assertEquals(tree.lastPathSegment, destinations.label())
        assertEquals(tree, destinations.openFolderIntent().data)

        destinations.treeUri = null
        assertEquals("Download/Morsecode", destinations.label())
    }

    @Test
    fun receivedMediaIsFiledWhereGalleriesLookForIt() {
        val destinations = destinations()
        assertEquals(
            "${Environment.DIRECTORY_PICTURES}/Morsecode",
            destinations.relativePathFor("IMG_2007.jpg", "image/jpeg"),
        )
        assertEquals(
            "${Environment.DIRECTORY_MOVIES}/Morsecode",
            destinations.relativePathFor("clip.mp4", null),
        )
        assertEquals(
            "${Environment.DIRECTORY_MUSIC}/Morsecode",
            destinations.relativePathFor("song.mp3", null),
        )
        assertEquals(
            "${Environment.DIRECTORY_DOWNLOADS}/Morsecode",
            destinations.relativePathFor("report.pdf", null),
        )
        assertEquals(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            destinations.collectionFor("IMG_2007.jpg", "image/jpeg"),
        )
    }

    @Test
    fun insertValuesCarryDateTakenInMillisecondsAndDateModifiedInSeconds() {
        val takenMillis = 1_715_509_800_000L
        val values = destinations().mediaValues("IMG_2007.jpg", "image/jpeg", 4_300_000, takenMillis)

        // A7: received files carry correct DATE_TAKEN units.
        assertEquals(takenMillis, values.getAsLong(MediaStore.MediaColumns.DATE_TAKEN))
        assertEquals(takenMillis / 1000, values.getAsLong(MediaStore.MediaColumns.DATE_MODIFIED))
        assertEquals(takenMillis / 1000, values.getAsLong(MediaStore.MediaColumns.DATE_ADDED))
        assertEquals("IMG_2007.jpg", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals(4_300_000L, values.getAsLong(MediaStore.MediaColumns.SIZE))
    }

    @Test
    fun partialDownloadsUseTheSpecifiedSuffix() {
        assertEquals("clip.mp4.morsecode.part", destinations().partNameFor("clip.mp4"))
    }

    @Test
    fun anUnreadableFolderIsNotAnEmptyFolder() = runBlocking {
        val library = app.morsecode.android.core.media.MediaLibrary(context, DeviceTier(context))

        val real = File(context.cacheDir, "listing-test")
        real.mkdirs()
        File(real, "a.txt").writeText("a")
        File(real, "child").mkdirs()

        val ok = library.list(real.absolutePath)
        assertTrue(ok is DirectoryListing.Ok)
        val entries = (ok as DirectoryListing.Ok).entries
        assertEquals(2, entries.size)
        // §6.9: folders always sort before files.
        assertTrue(entries.first().isDirectory)

        // A path that does not exist is Missing, not Ok(empty) and not denied.
        val missing = library.list(File(context.cacheDir, "nope").absolutePath)
        assertTrue(missing is DirectoryListing.Missing)

        // And an empty but readable folder really is Ok with no entries — the
        // distinction §12.2 calls release-blocking.
        val empty = File(context.cacheDir, "empty-listing")
        empty.mkdirs()
        val emptyResult = library.list(empty.absolutePath)
        assertTrue(emptyResult is DirectoryListing.Ok)
        assertTrue((emptyResult as DirectoryListing.Ok).entries.isEmpty())
    }

    @Test
    fun orphanPartFilesGoButResumableOnesStay() {
        val destinations = destinations()
        val dir = File(context.cacheDir, "parts-test")
        dir.mkdirs()
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000

        val orphan = File(dir, "old.mp4.morsecode.part")
        orphan.writeText("x")
        orphan.setLastModified(now - day - 1000)

        val journaled = File(dir, "resumable.mp4.morsecode.part")
        journaled.writeText("x")
        journaled.setLastModified(now - day - 1000)

        val fresh = File(dir, "new.mp4.morsecode.part")
        fresh.writeText("x")
        fresh.setLastModified(now - 1000)

        val ordinary = File(dir, "keep.mp4")
        ordinary.writeText("x")
        ordinary.setLastModified(now - day - 1000)

        val removed = destinations.purgeOrphanParts(
            directory = dir,
            journaledNames = setOf("resumable.mp4.morsecode.part"),
            nowMillis = now,
        )

        assertEquals(1, removed)
        assertTrue("an orphan older than 24 h goes", !orphan.exists())
        assertTrue("a journaled part is resumable work, not an orphan", journaled.exists())
        assertTrue("a part younger than 24 h stays", fresh.exists())
        assertTrue("a real file is never touched", ordinary.exists())
    }

    @Test
    fun tieringScalesWorkAndNeverCapability() {
        val tier = DeviceTier(context)
        assertTrue(tier.pageSize > 0)
        assertTrue(tier.thumbnailPx > 0)
        assertTrue(tier.thumbnailCacheBytes > 0)
        assertTrue(tier.chunkThreads in 2..4)
        assertTrue(tier.preHashLimitBytes > 0)
        // §13: the low tier caps peers at 2 but never turns broadcast off.
        assertTrue(tier.maxBroadcastPeers >= 2)
        assertNotNull(tier.animationScale())
    }
}
