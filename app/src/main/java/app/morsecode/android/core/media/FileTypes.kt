package app.morsecode.android.core.media

import app.morsecode.android.core.model.FileType
import app.morsecode.android.core.model.MediaCategory
import java.util.Locale

/**
 * Classification rules for §4.9's type tiles and §6.9's "CATEGORIES" rows
 * (Documents · Ebooks · Archives · APKs · Large files).
 *
 * MIME type first, extension second: a file picked through SAF often has a
 * useful MIME and a meaningless name, while a file on disk is often the other
 * way round. Both paths end in the same [FileType], so the tile colour cannot
 * disagree with the category a file was listed under.
 */
object FileTypes {

    /** §6.9 "Large files" threshold. 100 MB is the size at which people care. */
    const val LARGE_FILE_BYTES = 100L * 1024 * 1024

    private val documentExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
        "txt", "rtf", "csv", "md", "log", "json", "xml", "html", "htm",
    )
    private val ebookExtensions = setOf("epub", "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr")
    private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "dng", "tiff")
    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "m4v", "flv", "ts")
    private val audioExtensions = setOf("mp3", "aac", "wav", "flac", "ogg", "opus", "m4a", "amr", "mid")

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.US)
    }

    fun typeOf(name: String, mimeType: String? = null): FileType {
        val mime = mimeType?.lowercase(Locale.US).orEmpty()
        when {
            mime.startsWith("image/") -> return FileType.IMAGE
            mime.startsWith("video/") -> return FileType.VIDEO
            mime.startsWith("audio/") -> return FileType.AUDIO
            mime == "application/vnd.android.package-archive" -> return FileType.APK
        }

        return when (extensionOf(name)) {
            "apk" -> FileType.APK
            in imageExtensions -> FileType.IMAGE
            in videoExtensions -> FileType.VIDEO
            in audioExtensions -> FileType.AUDIO
            in archiveExtensions -> FileType.ARCHIVE
            in ebookExtensions -> FileType.DOCUMENT
            in documentExtensions -> FileType.DOCUMENT
            else -> if (mime.startsWith("text/")) FileType.DOCUMENT else FileType.UNKNOWN
        }
    }

    /**
     * Which §6.9 CATEGORIES row a file belongs to, or null when it belongs to
     * none of them. Large files is a size rule, so it is checked first and a
     * 900 MB film is a large file rather than a video buried in Documents.
     */
    fun categoryOf(name: String, mimeType: String?, sizeBytes: Long): MediaCategory? {
        if (sizeBytes >= LARGE_FILE_BYTES) return MediaCategory.LARGE_FILES
        val extension = extensionOf(name)
        return when {
            extension == "apk" || mimeType == "application/vnd.android.package-archive" ->
                MediaCategory.APKS
            extension in ebookExtensions -> MediaCategory.EBOOKS
            extension in archiveExtensions -> MediaCategory.ARCHIVES
            typeOf(name, mimeType) == FileType.DOCUMENT -> MediaCategory.DOCUMENTS
            else -> null
        }
    }
}
