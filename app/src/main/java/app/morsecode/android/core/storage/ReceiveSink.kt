package app.morsecode.android.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.util.Ids
import app.morsecode.android.core.util.Integrity
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Where a received file's bytes actually go (§9.4, §12.1, §12.4).
 *
 * §9.4 is explicit: partial writes go to `<name>.morsecode.part`, are appended
 * on resume, verified, then atomically renamed to the final name. That shape
 * is preserved on both storage paths:
 *
 *  - **Legacy (API ≤ 28) and any writable raw path**: a real `.morsecode.part`
 *    file next to the destination, renamed with `File.renameTo` when it
 *    verifies.
 *  - **Scoped storage (API 29+)**: MediaStore owns the file, so the partial is
 *    an `IS_PENDING = 1` row whose display name still carries the
 *    `.morsecode.part` suffix — same resumable, visibly-unfinished object,
 *    expressed the only way the platform allows. Finishing it renames the row
 *    and clears the pending flag in one update, which is the atomic publish
 *    §9.4 asks for.
 *
 * A sink is opened at an offset and appends; it never truncates, because
 * truncating on resume is how a "completed" transfer ends up half a file.
 */
interface ReceiveSink {

    /** Bytes already on disk for this file — the resume offset (§11.2). */
    fun existingLength(): Long

    /** Size of a FINAL file already at the destination, or 0 (§9.5 detection). */
    fun finalLength(): Long

    /** Hash of that final file, computed only when the decision needs it (§9.5). */
    fun finalSha(): String?

    /** Re-points this sink at another name, for "keep both" (§9.5). */
    fun renamedTo(displayName: String): ReceiveSink

    /** Opens the partial for appending at [existingLength]. */
    @Throws(IOException::class)
    fun openAppend(): OutputStream

    /** Publishes the verified file under its final name. Returns its location. */
    @Throws(IOException::class)
    fun finish(): String

    /** Removes the partial: used when a receive is cancelled or fails CRC. */
    fun discard()
}

open class ReceiveSinkFactory(
    context: Context,
    private val destinations: Destinations,
    private val logStore: LogStore? = null,
) {

    private val appContext = context.applicationContext

    /**
     * @param displayName the final name, after conflict resolution (§9.5).
     */
    open fun create(displayName: String, mimeType: String?): ReceiveSink {
        val legacy = Build.VERSION.SDK_INT <= 28
        return if (legacy) {
            FileSink(destinations.ensureDefaultDirectory(), displayName, logStore)
        } else {
            MediaStoreSink(appContext, destinations, displayName, mimeType, logStore)
        }
    }

    /** Used by tests and by the legacy path; writes into a raw directory. */
    fun createInDirectory(directory: File, displayName: String): ReceiveSink =
        FileSink(directory, displayName, logStore)
}

/** The raw-path implementation: `<name>.morsecode.part` → rename. */
internal class FileSink(
    private val directory: File,
    private val displayName: String,
    private val logStore: LogStore?,
) : ReceiveSink {

    private val part = File(directory, displayName + Ids.PART_SUFFIX)
    private val target = File(directory, displayName)

    override fun existingLength(): Long = if (part.exists()) part.length() else 0

    override fun finalLength(): Long = if (target.exists()) target.length() else 0

    override fun finalSha(): String? =
        if (!target.exists()) null
        else java.io.FileInputStream(target).use { Integrity.sha256(it) }

    override fun renamedTo(displayName: String): ReceiveSink =
        FileSink(directory, displayName, logStore)

    override fun openAppend(): OutputStream {
        directory.mkdirs()
        return java.io.FileOutputStream(part, true)
    }

    override fun finish(): String {
        if (!part.exists()) throw IOException("no partial file to publish")
        if (target.exists() && !target.delete()) {
            throw IOException("could not replace ${target.name}")
        }
        if (!part.renameTo(target)) {
            throw IOException("could not rename ${part.name}")
        }
        logStore?.i("Received ${target.name}")
        return target.absolutePath
    }

    override fun discard() {
        if (part.exists() && !part.delete()) logStore?.w("Could not remove ${part.name}")
    }
}

/**
 * The scoped-storage implementation. The pending row IS the `.part` file, and
 * publishing is one update that renames it and clears IS_PENDING.
 */
private class MediaStoreSink(
    private val context: Context,
    private val destinations: Destinations,
    private val displayName: String,
    private val mimeType: String?,
    private val logStore: LogStore?,
) : ReceiveSink {

    private val resolver = context.contentResolver
    private val partName = displayName + Ids.PART_SUFFIX
    private var pendingUri: Uri? = null

    override fun finalLength(): Long {
        val uri = findByName(displayName) ?: return 0
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize.coerceAtLeast(0) } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    override fun finalSha(): String? {
        val uri = findByName(displayName) ?: return null
        return try {
            resolver.openInputStream(uri)?.use { Integrity.sha256(it) }
        } catch (e: Exception) {
            null
        }
    }

    override fun renamedTo(displayName: String): ReceiveSink =
        MediaStoreSink(context, destinations, displayName, mimeType, logStore)

    override fun existingLength(): Long {
        val uri = findPending() ?: return 0
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize.coerceAtLeast(0) } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    override fun openAppend(): OutputStream {
        val uri = findPending() ?: insertPending()
        pendingUri = uri
        // "wa" appends; "w" would truncate and silently restart the file.
        return resolver.openOutputStream(uri, "wa")
            ?: throw IOException("could not open $displayName for writing")
    }

    override fun finish(): String {
        val uri = pendingUri ?: findPending() ?: throw IOException("no pending row to publish")
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        val updated = resolver.update(uri, values, null, null)
        if (updated <= 0) throw IOException("could not publish $displayName")
        logStore?.i("Received $displayName")
        return uri.toString()
    }

    override fun discard() {
        val uri = pendingUri ?: findPending() ?: return
        try {
            resolver.delete(uri, null, null)
        } catch (e: Exception) {
            logStore?.w("Could not remove the partial for $displayName")
        }
    }

    private fun collection(): Uri = destinations.collectionFor(displayName, mimeType)

    private fun insertPending(): Uri {
        val values = destinations.mediaValues(partName, mimeType, 0, 0)
        if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        return resolver.insert(collection(), values)
            ?: throw IOException("could not create $partName")
    }

    private fun findPending(): Uri? = findByName(partName)

    private fun findByName(name: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        return try {
            resolver.query(collection(), projection, selection, arrayOf(name), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    android.content.ContentUris.withAppendedId(collection(), cursor.getLong(0))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
