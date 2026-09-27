package app.morsecode.android.core.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.media.FileTypes
import app.morsecode.android.core.media.MediaDates
import app.morsecode.android.core.model.FileType
import app.morsecode.android.core.util.Ids
import java.io.File

/**
 * §12.4 DESTINATIONS.
 *
 * The default download location is `Download/Morsecode`, created on first
 * receive (§1.1.1). It is user-selectable, and when the user picks a different
 * folder EVERYTHING follows that choice — receives, "Open folder", the Files
 * tab and the WebShare upload target. The app never writes to two
 * destinations at once, which is why there is exactly one accessor here and
 * no caller is allowed to compose its own path.
 *
 * Received media is additionally indexed into MediaStore so galleries see it,
 * with DATE_TAKEN in MILLISECONDS and DATE_MODIFIED in SECONDS (§12.5).
 */
class Destinations(
    context: Context,
    private val logStore: LogStore? = null,
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("morsecode.destinations", Context.MODE_PRIVATE)

    /**
     * The chosen SAF tree, or null when the default folder is in force.
     * Stored as a string because a Uri cannot go into preferences.
     */
    var treeUri: Uri?
        get() = prefs.getString(KEY_TREE, null)?.toUri()
        set(value) {
            prefs.edit().putString(KEY_TREE, value?.toString()).apply()
            logStore?.i("Destination set to ${value?.toString() ?: DEFAULT_RELATIVE_PATH}")
        }

    /** `Download/Morsecode`, exactly as §1.1.1 prescribes. */
    val defaultRelativePath: String get() = DEFAULT_RELATIVE_PATH

    /** The on-disk default folder. Not created until it is needed (§12.4). */
    fun defaultDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        Ids.APP_NAME,
    )

    /** Created on FIRST RECEIVE, not at launch (§12.4). */
    fun ensureDefaultDirectory(): File {
        val dir = defaultDirectory()
        if (!dir.exists() && !dir.mkdirs()) {
            logStore?.w("Could not create ${dir.absolutePath}")
        }
        return dir
    }

    /** What Settings and the completion screen show as the destination. */
    fun label(): String = treeUri?.lastPathSegment ?: DEFAULT_RELATIVE_PATH

    /**
     * §12.4 "Open folder": the system file viewer first, falling back to the
     * in-app Files tab at that path. The caller starts whichever it can
     * resolve; returning the intent rather than starting it keeps this class
     * free of activity context.
     */
    fun openFolderIntent(): Intent {
        val intent = Intent(Intent.ACTION_VIEW)
        val target = treeUri ?: Uri.fromFile(defaultDirectory())
        intent.setDataAndType(target, "resource/folder")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    /**
     * ContentValues for indexing a received file into MediaStore so galleries
     * see it (§12.4).
     *
     * DATE UNITS (§12.5): DATE_TAKEN is MILLISECONDS, DATE_ADDED and
     * DATE_MODIFIED are SECONDS. Below API 29 the caller must
     * `ContentResolver.insert()` with these values and stream the original
     * bytes — never `Images.Media.insertImage()`, which writes no DATE_TAKEN
     * and re-encodes the bitmap, destroying quality and EXIF.
     */
    fun mediaValues(
        displayName: String,
        mimeType: String?,
        sizeBytes: Long,
        takenMillis: Long,
    ): ContentValues {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        if (mimeType != null) values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        values.put(MediaStore.MediaColumns.SIZE, sizeBytes)
        // MILLISECONDS.
        if (takenMillis > 0) values.put(MediaStore.MediaColumns.DATE_TAKEN, takenMillis)
        // SECONDS.
        val seconds = MediaDates.millisToSeconds(
            if (takenMillis > 0) takenMillis else System.currentTimeMillis(),
        )
        values.put(MediaStore.MediaColumns.DATE_ADDED, seconds)
        values.put(MediaStore.MediaColumns.DATE_MODIFIED, seconds)
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathFor(displayName, mimeType))
        }
        return values
    }

    /**
     * Where a received file is filed on API 29+. Media goes into its own
     * public collection subfolder so galleries group it; everything else goes
     * to the destination folder itself.
     */
    fun relativePathFor(displayName: String, mimeType: String?): String {
        val folder = Ids.APP_NAME
        return when (FileTypes.typeOf(displayName, mimeType)) {
            FileType.IMAGE -> "${Environment.DIRECTORY_PICTURES}/$folder"
            FileType.VIDEO -> "${Environment.DIRECTORY_MOVIES}/$folder"
            FileType.AUDIO -> "${Environment.DIRECTORY_MUSIC}/$folder"
            else -> "${Environment.DIRECTORY_DOWNLOADS}/$folder"
        }
    }

    /** The collection a received file is inserted into (§12.1: MediaStore only). */
    fun collectionFor(displayName: String, mimeType: String?): Uri =
        when (FileTypes.typeOf(displayName, mimeType)) {
            FileType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            FileType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            FileType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

    /** The in-flight name a receive writes to (§9.4). */
    fun partNameFor(displayName: String): String = displayName + Ids.PART_SUFFIX

    /**
     * §9.4: orphan `.morsecode.part` files older than 24 h with NO journal
     * entry are deleted at startup. A part file that still has a journal entry
     * is a resumable transfer and must survive — deleting it would throw away
     * the offsets §9.7 offers to resume from.
     *
     * @param journaledNames the part names the journal still knows about.
     * @return how many orphans were removed.
     */
    fun purgeOrphanParts(
        directory: File = defaultDirectory(),
        journaledNames: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = ORPHAN_MAX_AGE_MILLIS,
    ): Int {
        val children = directory.listFiles() ?: return 0
        var removed = 0
        for (child in children) {
            if (!child.isFile || !child.name.endsWith(Ids.PART_SUFFIX)) continue
            if (child.name in journaledNames) continue
            if (nowMillis - child.lastModified() < maxAgeMillis) continue
            if (child.delete()) {
                removed++
                logStore?.i("Removed orphan part ${child.name}")
            }
        }
        return removed
    }

    private companion object {
        const val KEY_TREE = "destination_tree"
        const val ORPHAN_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        val DEFAULT_RELATIVE_PATH = Ids.DEFAULT_MEDIA_FOLDER
    }
}
