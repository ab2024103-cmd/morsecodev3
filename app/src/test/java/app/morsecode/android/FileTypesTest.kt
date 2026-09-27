package app.morsecode.android

import app.morsecode.android.core.media.FileTypes
import app.morsecode.android.core.model.FileType
import app.morsecode.android.core.model.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §4.9 type tiles and §6.9 "CATEGORIES" (Documents · Ebooks · Archives ·
 * APKs · Large files). The classification decides both the tile colour and
 * the category a file is counted under, so the two cannot be allowed to
 * disagree.
 */
class FileTypesTest {

    @Test
    fun mimeTypeWinsOverAMeaninglessName() {
        assertEquals(FileType.IMAGE, FileTypes.typeOf("document", "image/jpeg"))
        assertEquals(FileType.VIDEO, FileTypes.typeOf("0001", "video/mp4"))
        assertEquals(FileType.AUDIO, FileTypes.typeOf("track", "audio/mpeg"))
        assertEquals(
            FileType.APK,
            FileTypes.typeOf("download", "application/vnd.android.package-archive"),
        )
    }

    @Test
    fun extensionClassifiesWhenThereIsNoMime() {
        assertEquals(FileType.IMAGE, FileTypes.typeOf("IMG_2007.JPG"))
        assertEquals(FileType.VIDEO, FileTypes.typeOf("VID_0042.mkv"))
        assertEquals(FileType.AUDIO, FileTypes.typeOf("song.flac"))
        assertEquals(FileType.DOCUMENT, FileTypes.typeOf("invoice.pdf"))
        assertEquals(FileType.DOCUMENT, FileTypes.typeOf("novel.epub"))
        assertEquals(FileType.ARCHIVE, FileTypes.typeOf("backup.tar"))
        assertEquals(FileType.APK, FileTypes.typeOf("morsecode.apk"))
        assertEquals(FileType.UNKNOWN, FileTypes.typeOf("mystery"))
        assertEquals(FileType.UNKNOWN, FileTypes.typeOf("firmware.bin"))
    }

    @Test
    fun extensionParsingHandlesTheAwkwardNames() {
        assertEquals("gz", FileTypes.extensionOf("archive.tar.gz"))
        assertEquals("", FileTypes.extensionOf(".hidden"))
        assertEquals("", FileTypes.extensionOf("trailing."))
        assertEquals("", FileTypes.extensionOf("none"))
        assertEquals("jpg", FileTypes.extensionOf("Holiday Photo 2024.JPG"))
    }

    @Test
    fun categoriesMatchTheFilesTabRows() {
        val small = 1024L
        assertEquals(MediaCategory.DOCUMENTS, FileTypes.categoryOf("report.docx", null, small))
        assertEquals(MediaCategory.EBOOKS, FileTypes.categoryOf("novel.epub", null, small))
        assertEquals(MediaCategory.ARCHIVES, FileTypes.categoryOf("photos.zip", null, small))
        assertEquals(MediaCategory.APKS, FileTypes.categoryOf("app.apk", null, small))
        assertNull(FileTypes.categoryOf("IMG_1.jpg", "image/jpeg", small))
    }

    @Test
    fun sizeDecidesLargeFilesBeforeTypeDoes() {
        val large = FileTypes.LARGE_FILE_BYTES
        assertEquals(MediaCategory.LARGE_FILES, FileTypes.categoryOf("film.mkv", "video/x-matroska", large))
        assertEquals(MediaCategory.LARGE_FILES, FileTypes.categoryOf("manual.pdf", null, large))
        // One byte under the threshold it is an ordinary document again.
        assertEquals(MediaCategory.DOCUMENTS, FileTypes.categoryOf("manual.pdf", null, large - 1))
    }
}
