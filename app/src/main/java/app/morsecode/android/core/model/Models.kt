package app.morsecode.android.core.model

import android.net.Uri

/**
 * The shared vocabulary (§8.1 `core/model/Models.kt`).
 *
 * Everything the media library, the file browser and later the transfer engine
 * hand to a screen is one of these types. Screens never see a `Cursor`, a
 * `MediaStore` column or a raw path string (§12.5: one repository).
 */

/** §6.9 tabs and §12.5 categories. */
enum class MediaCategory {
    PHOTOS,
    VIDEOS,
    MUSIC,
    APPS,
    FILES,

    /** §6.9 "CATEGORIES" rows inside the Files tab. */
    DOCUMENTS,
    EBOOKS,
    ARCHIVES,
    APKS,
    LARGE_FILES,
}

/**
 * §4.9 file-type colour families. Kept separate from [MediaCategory] because a
 * `.pdf` inside the Photos folder is still a document tile.
 */
enum class FileType { IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, UNKNOWN }

/** §6.9.x sort keys. Folders always sort before files, whichever key is active. */
enum class SortKey { DATE_MODIFIED, NAME, SIZE, TYPE }

data class SortOrder(val key: SortKey = SortKey.DATE_MODIFIED, val descending: Boolean = true)

/**
 * One item in any listing.
 *
 * @param dateMillis always MILLISECONDS (§12.5 DATE UNITS). The library
 *   converts at the cursor, so nothing downstream can mix seconds and
 *   milliseconds and send a file to 1 Jan 1970.
 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val dateMillis: Long,
    val mimeType: String?,
    val type: FileType,
    /** Video and audio only; 0 when unknown. */
    val durationMillis: Long = 0,
    /** Music only; §6.9 renders "Unknown artist", never a raw `<unknown>`. */
    val artist: String? = null,
    /** Apps only. */
    val packageName: String? = null,
    /** Present for on-disk items; absent for MediaStore-only or SAF items. */
    val path: String? = null,
)

/** §12.5 folderInfo(): name, count, size — plus the newest item for a preview. */
data class FolderInfo(
    val name: String,
    val path: String,
    val itemCount: Int,
    val sizeBytes: Long,
    val newestItem: MediaItem? = null,
)

/** §6.9 category row: a count that is still being computed is [PENDING]. */
data class CategoryCount(val category: MediaCategory, val count: Int) {
    val isPending: Boolean get() = count == PENDING

    companion object {
        /**
         * §6.9: while a count is still being computed show a shimmer, never a
         * hard "0" and never an empty state until the scan has finished.
         */
        const val PENDING = -1
    }
}

/** One page of a category (§12.5 INV-10). */
data class MediaPage(
    val items: List<MediaItem>,
    val offset: Int,
    /** True when the cursor had more rows after this page. */
    val hasMore: Boolean,
)

/** A row in a directory listing; folders are first-class, selectable objects (§6.9). */
data class DirectoryEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val dateMillis: Long,
    val type: FileType,
    val childCount: Int = 0,
)

/**
 * §12.2 GRACEFUL DENIAL.
 *
 * "Cannot be read because of scoped storage" is a DISTINCT condition from
 * "zero children" and has its own type here, so a screen physically cannot
 * render a denied folder as an empty one — which §12.2 calls release-blocking
 * because the two are indistinguishable to the user.
 */
sealed class DirectoryListing {

    data class Ok(val path: String, val entries: List<DirectoryEntry>) : DirectoryListing()

    /** Render "Access needed to view this folder — Grant access" (§6.19, §12.2). */
    data class AccessDenied(val path: String, val suggestedTreeUri: Uri?) : DirectoryListing()

    data class Missing(val path: String) : DirectoryListing()
}
