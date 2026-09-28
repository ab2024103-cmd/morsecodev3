package app.morsecode.android.core.media

import android.content.Context
import app.morsecode.android.core.logging.LogStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * §6.9 Files trash: destructive file actions first move a recoverable copy to
 * app storage, Undo restores it, and entries are permanently purged after the
 * retention period. Metadata is persisted so the trash survives process death.
 *
 * Content URIs cannot reliably be moved or restored to their provider-owned
 * location. For those providers we deliberately return a failure rather than
 * claim an in-app Trash that cannot honour Undo.
 */
class TrashStore(
    context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val logStore: LogStore? = null,
) {
    data class Entry(
        val id: String,
        val displayName: String,
        val originalPath: String,
        val trashedPath: String,
        val deletedAt: Long,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val root = File(appContext.filesDir, "trash")
    private val lock = Any()
    private val records = ArrayList<Entry>()

    init {
        root.mkdirs()
        load()
        purgeExpired()
    }

    fun entries(): List<Entry> = synchronized(lock) { records.sortedByDescending { it.deletedAt } }

    /** Moves a local file or folder tree to the app-managed trash location. */
    fun trash(path: String, displayName: String = File(path).name): Entry? = synchronized(lock) {
        purgeExpiredLocked()
        val source = File(path)
        if (!source.exists()) return null
        val entry = Entry(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            originalPath = source.absolutePath,
            trashedPath = File(root, UUID.randomUUID().toString()).absolutePath,
            deletedAt = clock(),
        )
        val target = File(entry.trashedPath)
        val moved = source.renameTo(target) || copyThenDelete(source, target)
        if (!moved) {
            deleteTree(target)
            logStore?.w("Trash could not move ${source.name}")
            return null
        }
        records.add(entry)
        saveLocked()
        entry
    }

    /** Restores to its original path; a name clash uses " (restored N)". */
    fun restore(id: String): Entry? = synchronized(lock) {
        val entry = records.firstOrNull { it.id == id } ?: return null
        val source = File(entry.trashedPath)
        if (!source.exists()) {
            records.remove(entry)
            saveLocked()
            return null
        }
        val intended = File(entry.originalPath)
        intended.parentFile?.mkdirs()
        val destination = availableRestorePath(intended)
        val restored = source.renameTo(destination) || copyThenDelete(source, destination)
        if (!restored) return null
        records.remove(entry)
        saveLocked()
        entry
    }

    /** Permanently deletes a trashed entry, never its restored original. */
    fun purge(id: String): Boolean = synchronized(lock) {
        val entry = records.firstOrNull { it.id == id } ?: return false
        deleteTree(File(entry.trashedPath))
        records.remove(entry)
        saveLocked()
        true
    }

    fun purgeExpired(): Int = synchronized(lock) { purgeExpiredLocked() }

    private fun purgeExpiredLocked(): Int {
        val cutoff = clock() - RETENTION_MILLIS
        val old = records.filter { it.deletedAt < cutoff }
        for (entry in old) deleteTree(File(entry.trashedPath))
        if (old.isNotEmpty()) {
            records.removeAll(old)
            saveLocked()
            logStore?.i("Purged ${old.size} expired Files trash entries")
        }
        return old.size
    }

    private fun copyThenDelete(source: File, destination: File): Boolean = try {
        val copied = if (source.isDirectory) copyDirectory(source, destination) else copyFile(source, destination)
        if (copied && deleteTree(source)) true else {
            deleteTree(destination)
            false
        }
    } catch (error: Exception) {
        deleteTree(destination)
        false
    }

    private fun copyDirectory(source: File, destination: File): Boolean {
        if (!destination.mkdirs()) return false
        val children = source.listFiles() ?: return false
        for (child in children) {
            val target = File(destination, child.name)
            if (child.isDirectory) {
                if (!copyDirectory(child, target)) return false
            } else if (!copyFile(child, target)) {
                return false
            }
        }
        return true
    }

    private fun copyFile(source: File, destination: File): Boolean {
        destination.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
        return true
    }

    private fun deleteTree(file: File): Boolean {
        if (file.isDirectory) file.listFiles()?.forEach { deleteTree(it) }
        return !file.exists() || file.delete()
    }

    private fun availableRestorePath(intended: File): File {
        if (!intended.exists()) return intended
        val extension = intended.extension.let { if (it.isEmpty()) "" else ".${it}" }
        val stem = intended.name.removeSuffix(extension)
        var attempt = 1
        while (true) {
            val candidate = File(intended.parentFile, "$stem (restored $attempt)$extension")
            if (!candidate.exists()) return candidate
            attempt++
        }
    }

    private fun load() {
        val raw = prefs.getString(KEY_RECORDS, "[]") ?: "[]"
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                records.add(
                    Entry(
                        id = item.getString("id"),
                        displayName = item.getString("name"),
                        originalPath = item.getString("original"),
                        trashedPath = item.getString("trashed"),
                        deletedAt = item.getLong("deletedAt"),
                    ),
                )
            }
        }.onFailure { prefs.edit().remove(KEY_RECORDS).apply() }
    }

    private fun saveLocked() {
        val array = JSONArray()
        for (entry in records) {
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.displayName)
                    .put("original", entry.originalPath)
                    .put("trashed", entry.trashedPath)
                    .put("deletedAt", entry.deletedAt),
            )
        }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "morsecode.trash"
        const val KEY_RECORDS = "records"
        const val RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
    }
}
