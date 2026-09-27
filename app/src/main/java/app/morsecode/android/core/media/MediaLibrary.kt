package app.morsecode.android.core.media

import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.net.toUri
import app.morsecode.android.core.model.CategoryCount
import app.morsecode.android.core.model.DirectoryEntry
import app.morsecode.android.core.model.DirectoryListing
import app.morsecode.android.core.model.FileType
import app.morsecode.android.core.model.FolderInfo
import app.morsecode.android.core.model.MediaCategory
import app.morsecode.android.core.model.MediaItem
import app.morsecode.android.core.model.MediaPage
import app.morsecode.android.core.model.SortOrder
import app.morsecode.android.core.model.TransferFile
import app.morsecode.android.core.util.DeviceTier
import app.morsecode.android.core.logging.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * §12.5 MEDIA LIBRARY — the one repository behind every listing in the
 * product. No screen may query MediaStore directly, so every rule that is easy
 * to get wrong lives in exactly one place:
 *
 *  - INV-9 ORDERING: date-taken falling back to date-modified, `_ID DESC`
 *    tiebreak, via [MediaQueries.mediaOrderBy].
 *  - INV-10 PAGINATION: no LIMIT/OFFSET in the sort string, ever. Pages are
 *    read by walking the cursor with `moveToPosition`, which is what keeps
 *    Android 14 from answering "Invalid token LIMIT" (A7).
 *  - DATE UNITS: DATE_TAKEN is milliseconds, DATE_MODIFIED is seconds; both
 *    are converted at the cursor by [MediaDates], so a [MediaItem] always
 *    carries milliseconds.
 *  - §20.10: counts come from indexed MediaStore queries, never a recursive
 *    walk of the filesystem.
 *  - §12.2: a folder that cannot be read is [DirectoryListing.AccessDenied],
 *    a distinct type from an empty [DirectoryListing.Ok].
 */
class MediaLibrary(
    context: Context,
    private val tier: DeviceTier,
    private val logStore: LogStore? = null,
) {

    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    // ----- Categories -------------------------------------------------------

    /**
     * Counts for the §6.9 category rows. Each is a projection of `_ID` over an
     * indexed MediaStore query and returns in milliseconds, not the seconds a
     * recursive walk would take (§20.10).
     */
    suspend fun counts(): List<CategoryCount> = withContext(Dispatchers.IO) {
        listOf(
            CategoryCount(MediaCategory.PHOTOS, countOf(imagesUri(), null, null)),
            CategoryCount(MediaCategory.VIDEOS, countOf(videosUri(), null, null)),
            CategoryCount(MediaCategory.MUSIC, countOf(audioUri(), MUSIC_ONLY, null)),
            CategoryCount(MediaCategory.APPS, installedApps().size),
            CategoryCount(MediaCategory.DOCUMENTS, countOfFileCategory(MediaCategory.DOCUMENTS)),
            CategoryCount(MediaCategory.EBOOKS, countOfFileCategory(MediaCategory.EBOOKS)),
            CategoryCount(MediaCategory.ARCHIVES, countOfFileCategory(MediaCategory.ARCHIVES)),
            CategoryCount(MediaCategory.APKS, countOfFileCategory(MediaCategory.APKS)),
            CategoryCount(MediaCategory.LARGE_FILES, countOfFileCategory(MediaCategory.LARGE_FILES)),
        )
    }

    /**
     * One page of a category. [limit] defaults to the tier's page size (§13);
     * the caller asks for more pages as the list scrolls — nothing here ever
     * loads a whole 10 000-item folder into memory (§20.10).
     */
    suspend fun page(
        category: MediaCategory,
        order: SortOrder = SortOrder(),
        offset: Int = 0,
        limit: Int = tier.pageSize,
    ): MediaPage = withContext(Dispatchers.IO) {
        when (category) {
            MediaCategory.PHOTOS -> queryPage(imagesUri(), null, null, order, offset, limit, FileType.IMAGE)
            MediaCategory.VIDEOS -> queryPage(videosUri(), null, null, order, offset, limit, FileType.VIDEO)
            MediaCategory.MUSIC -> queryPage(audioUri(), MUSIC_ONLY, null, order, offset, limit, FileType.AUDIO)
            MediaCategory.APPS -> appsPage(offset, limit)
            MediaCategory.FILES -> MediaPage(emptyList(), offset, false)
            else -> filesCategoryPage(category, order, offset, limit)
        }
    }

    /**
     * §12.5 folderInfo(): name, count and size for the folder panels, plus the
     * newest item so a row can show a preview instead of a list of words
     * (§7.5). Built from the indexed media tables, not a directory walk.
     */
    suspend fun folders(category: MediaCategory): List<FolderInfo> = withContext(Dispatchers.IO) {
        val uri = when (category) {
            MediaCategory.PHOTOS -> imagesUri()
            MediaCategory.VIDEOS -> videosUri()
            MediaCategory.MUSIC -> audioUri()
            else -> return@withContext emptyList()
        }
        val type = when (category) {
            MediaCategory.PHOTOS -> FileType.IMAGE
            MediaCategory.VIDEOS -> FileType.VIDEO
            else -> FileType.AUDIO
        }
        val buckets = LinkedHashMap<String, MutableList<MediaItem>>()
        val projection = mediaProjection() + arrayOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        query(uri, projection, if (category == MediaCategory.MUSIC) MUSIC_ONLY else null, null, MediaQueries.mediaOrderBy(SortOrder()))?.use { cursor ->
            val bucketColumn = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val item = readItem(cursor, uri, type) ?: continue
                val bucket = if (bucketColumn >= 0) cursor.getString(bucketColumn) else null
                val name = bucket ?: parentNameOf(item.path) ?: UNKNOWN_FOLDER
                buckets.getOrPut(name) { ArrayList() }.add(item)
            }
        }
        buckets.map { (name, items) ->
            FolderInfo(
                name = name,
                path = items.firstOrNull()?.path?.let { File(it).parent } ?: name,
                itemCount = items.size,
                sizeBytes = items.sumOf { it.sizeBytes },
                // The panel preview is the most recently modified item (§7.5);
                // the query is already in INV-9 order, so that is the first.
                newestItem = items.firstOrNull(),
            )
        }
    }

    // ----- Apps -------------------------------------------------------------

    /**
     * §12.5 APPS come from PackageManager — real icon, label, APK path and
     * size. `<queries>` is declared in the manifest for API 30 visibility.
     */
    fun installedApps(): List<MediaItem> {
        val pm = appContext.packageManager
        val packages = try {
            pm.getInstalledApplications(0)
        } catch (e: Exception) {
            logStore?.w("Installed apps unavailable: ${e.javaClass.simpleName}")
            emptyList<ApplicationInfo>()
        }
        return packages.asSequence()
            .filter { it.sourceDir != null }
            .map { info ->
                val apk = File(info.sourceDir)
                MediaItem(
                    id = info.packageName.hashCode().toLong(),
                    uri = Uri.fromFile(apk),
                    name = pm.getApplicationLabel(info).toString(),
                    sizeBytes = apk.length(),
                    dateMillis = apk.lastModified(),
                    mimeType = APK_MIME,
                    type = FileType.APK,
                    packageName = info.packageName,
                    path = info.sourceDir,
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    private fun appsPage(offset: Int, limit: Int): MediaPage {
        val all = installedApps()
        val range = MediaQueries.pageBounds(all.size, offset, limit)
        val items = if (range.isEmpty()) emptyList() else all.subList(range.first, range.last + 1)
        return MediaPage(items, offset, offset + items.size < all.size)
    }

    // ----- Shared items (§3.6) ---------------------------------------------

    /**
     * Describes a URI handed to the app by ACTION_SEND / ACTION_SEND_MULTIPLE
     * (§3.6). The destination is unresolved at this point: all the send flow
     * needs is a name, a size and a MIME type, and those come from the
     * provider that owns the URI — never from a guessed path.
     */
    fun describe(uri: Uri): TransferFile? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        var name: String? = null
        var size = 0L
        try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                }
            }
        } catch (e: Exception) {
            logStore?.w("Shared item could not be described: ${e.javaClass.simpleName}")
        }
        val displayName = name ?: uri.lastPathSegment ?: return null
        return TransferFile(
            displayName = displayName,
            uri = uri,
            mime = resolver.getType(uri),
            size = size,
        )
    }

    // ----- Directory browsing (§6.9 Files tab, §12.2) -----------------------

    /**
     * §6.9 "FOLDERS": Download · Internal storage · every SAF-granted tree.
     * Returned as info rows so the tab can show size and count without the
     * caller touching the filesystem itself.
     */
    fun quickFolders(): List<FolderInfo> {
        val roots = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory(),
        )
        return roots.mapNotNull { dir ->
            if (!dir.exists()) return@mapNotNull null
            val children = dir.listFiles()
            FolderInfo(
                name = dir.name,
                path = dir.absolutePath,
                itemCount = children?.size ?: 0,
                sizeBytes = 0,
            )
        }
    }

    /**
     * Lists a directory.
     *
     * §12.2 GRACEFUL DENIAL: "unreadable because of scoped storage" is
     * returned as [DirectoryListing.AccessDenied] — a different type from an
     * empty [DirectoryListing.Ok]. `listFiles()` returning null is exactly the
     * case that used to render as "empty folder", which §12.2 calls
     * release-blocking because the user cannot tell the two apart.
     */
    suspend fun list(path: String): DirectoryListing = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.exists()) return@withContext DirectoryListing.Missing(path)
        if (!dir.isDirectory) return@withContext DirectoryListing.Missing(path)
        if (!dir.canRead()) {
            logStore?.w("Access needed for $path")
            return@withContext DirectoryListing.AccessDenied(path, treeUriHintFor(path))
        }
        val children = dir.listFiles()
            ?: return@withContext DirectoryListing.AccessDenied(path, treeUriHintFor(path))

        val entries = children.map { child ->
            DirectoryEntry(
                name = child.name,
                path = child.absolutePath,
                isDirectory = child.isDirectory,
                sizeBytes = if (child.isDirectory) 0 else child.length(),
                dateMillis = child.lastModified(),
                type = if (child.isDirectory) FileType.UNKNOWN else FileTypes.typeOf(child.name),
                childCount = if (child.isDirectory) (child.list()?.size ?: 0) else 0,
            )
        }
        // §6.9: folders always sort before files, whichever key is active.
        val sorted = entries.sortedWith(
            compareByDescending<DirectoryEntry> { it.isDirectory }
                .thenByDescending { it.dateMillis },
        )
        DirectoryListing.Ok(path, sorted)
    }

    /**
     * The tree a SAF grant should be scoped to when a listing is denied. The
     * platform only accepts a document tree URI, so this is the closest
     * documented starting point rather than a guess (§12.2, §20.6).
     */
    private fun treeUriHintFor(path: String): Uri? = try {
        "content://com.android.externalstorage.documents/tree/primary%3A".toUri()
            .takeIf { path.startsWith(Environment.getExternalStorageDirectory().absolutePath) }
    } catch (e: Exception) {
        null
    }

    // ----- Query plumbing ---------------------------------------------------

    private fun queryPage(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
        order: SortOrder,
        offset: Int,
        limit: Int,
        type: FileType,
    ): MediaPage {
        val sort = MediaQueries.mediaOrderBy(order)
        val items = ArrayList<MediaItem>(limit)
        var hasMore = false
        query(uri, mediaProjection(), selection, selectionArgs, sort)?.use { cursor ->
            // INV-10: page by walking the cursor, never by LIMIT/OFFSET in the
            // sort string.
            val range = MediaQueries.pageBounds(cursor.count, offset, limit)
            if (!range.isEmpty() && cursor.moveToPosition(range.first)) {
                var position = range.first
                do {
                    readItem(cursor, uri, type)?.let { items.add(it) }
                    position++
                } while (position <= range.last && cursor.moveToNext())
            }
            hasMore = offset + items.size < cursor.count
        }
        return MediaPage(items, offset, hasMore)
    }

    /**
     * Documents / Ebooks / Archives / APKs / Large files. MediaStore.Files is
     * queried once per page and classified by [FileTypes]; the selection keeps
     * the cursor small so the classification never becomes a full-volume walk.
     */
    private fun filesCategoryPage(
        category: MediaCategory,
        order: SortOrder,
        offset: Int,
        limit: Int,
    ): MediaPage {
        val items = ArrayList<MediaItem>(limit)
        val uri = filesUri()
        var matched = 0
        var hasMore = false
        query(uri, mediaProjection(), NON_MEDIA_ONLY, null, MediaQueries.mediaOrderBy(order))?.use { cursor ->
            while (cursor.moveToNext()) {
                val item = readItem(cursor, uri, FileType.UNKNOWN) ?: continue
                if (FileTypes.categoryOf(item.name, item.mimeType, item.sizeBytes) != category) continue
                if (matched >= offset && items.size < limit) items.add(item)
                matched++
                if (matched > offset + limit) {
                    // One row past the page is enough to know there is more;
                    // reading the rest would be a full-volume walk (§20.10).
                    hasMore = true
                    break
                }
            }
        }
        return MediaPage(items, offset, hasMore)
    }

    private fun countOf(uri: Uri, selection: String?, args: Array<String>?): Int =
        query(uri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { it.count } ?: 0

    private fun countOfFileCategory(category: MediaCategory): Int {
        var count = 0
        query(
            filesUri(),
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
            ),
            NON_MEDIA_ONLY,
            null,
            null,
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                val name = if (nameColumn >= 0) cursor.getString(nameColumn).orEmpty() else ""
                val mime = if (mimeColumn >= 0) cursor.getString(mimeColumn) else null
                val size = if (sizeColumn >= 0) cursor.getLong(sizeColumn) else 0L
                if (FileTypes.categoryOf(name, mime, size) == category) count++
            }
        }
        return count
    }

    private fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        // INV-10, enforced rather than remembered.
        check(!MediaQueries.containsPaginationTokens(sortOrder)) {
            "LIMIT/OFFSET must never appear in a MediaStore sort string (INV-10)"
        }
        return try {
            resolver.query(uri, projection, selection, selectionArgs, sortOrder)
        } catch (e: SecurityException) {
            // Denied reads are a condition, not a crash (§12.2).
            logStore?.w("MediaStore read denied for $uri")
            null
        } catch (e: Exception) {
            logStore?.e("MediaStore query failed for $uri: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun mediaProjection(): Array<String> = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.MIME_TYPE,
        // DATE_TAKEN is MILLISECONDS, DATE_MODIFIED is SECONDS (§12.5).
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.DURATION,
        MediaStore.Audio.AudioColumns.ARTIST,
    )

    private fun readItem(cursor: Cursor, baseUri: Uri, type: FileType): MediaItem? {
        val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
        if (idColumn < 0) return null
        val id = cursor.getLong(idColumn)
        val name = cursor.stringOr(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()
        val mime = cursor.stringOr(MediaStore.MediaColumns.MIME_TYPE)
        val takenMillis = cursor.longOr(MediaStore.MediaColumns.DATE_TAKEN)
        val modifiedSeconds = cursor.longOr(MediaStore.MediaColumns.DATE_MODIFIED)
        val artist = cursor.stringOr(MediaStore.Audio.AudioColumns.ARTIST)
        return MediaItem(
            id = id,
            uri = ContentUris.withAppendedId(baseUri, id),
            name = name,
            sizeBytes = cursor.longOr(MediaStore.MediaColumns.SIZE),
            // One conversion, one place (§12.5 DATE UNITS).
            dateMillis = MediaDates.effectiveMillis(takenMillis, modifiedSeconds),
            mimeType = mime,
            type = if (type == FileType.UNKNOWN) FileTypes.typeOf(name, mime) else type,
            durationMillis = cursor.longOr(MediaStore.MediaColumns.DURATION),
            artist = artist?.takeIf { it.isNotBlank() && it != UNKNOWN_ARTIST_RAW },
            path = cursor.stringOr(MediaStore.MediaColumns.DATA),
        )
    }

    private fun Cursor.stringOr(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.longOr(column: String): Long {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) 0L else getLong(index)
    }

    private fun parentNameOf(path: String?): String? =
        path?.let { File(it).parentFile?.name }

    private fun imagesUri(): Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun videosUri(): Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private fun audioUri(): Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    private fun filesUri(): Uri = MediaStore.Files.getContentUri(EXTERNAL_VOLUME)

    private companion object {
        const val EXTERNAL_VOLUME = "external"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val UNKNOWN_FOLDER = "Internal storage"

        /** MediaStore writes this literal when a track has no artist tag. */
        const val UNKNOWN_ARTIST_RAW = "<unknown>"

        /** Ringtones and notification blips are not "Music" (§6.9). */
        const val MUSIC_ONLY = "${MediaStore.Audio.AudioColumns.IS_MUSIC} != 0"

        /** Everything MediaStore has not already filed as image/video/audio. */
        const val NON_MEDIA_ONLY =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE}"
    }
}
