package app.morsecode.android.core.data

import android.net.Uri
import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.TransferFile
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * §8.2 JOURNAL PERSISTENCE: the outgoing queue and in-progress receives are
 * journaled on EVERY state transition, so a crash or a force-kill never loses
 * the queue. §9.7 then offers "Resume interrupted transfer?" at next launch —
 * never a silent resume, never a silent discard.
 *
 * Written as one small JSON file, replaced atomically through a temp file, so
 * a kill mid-write leaves the previous journal intact rather than a truncated
 * one. Only unfinished items are kept: a completed batch is history's job, not
 * the journal's.
 */
class JournalStore(private val file: File) {

    data class Entry(
        val item: TransferItem,
        val peerName: String?,
        val updatedAtMillis: Long,
    )

    @Synchronized
    fun write(items: List<TransferItem>, peerName: String?, nowMillis: Long = System.currentTimeMillis()) {
        val unfinished = items.filterNot { it.state.isTerminal }
        if (unfinished.isEmpty()) {
            clear()
            return
        }
        val array = JSONArray()
        for (item in unfinished) array.put(toJson(item, peerName, nowMillis))
        val root = JSONObject()
        root.put("version", JOURNAL_VERSION)
        root.put("peerName", peerName ?: JSONObject.NULL)
        root.put("items", array)
        writeAtomically(root.toString())
    }

    @Synchronized
    fun read(): List<Entry> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            if (root.optInt("version") != JOURNAL_VERSION) {
                // A journal this build does not understand is discarded rather
                // than guessed at. There is no predecessor product, so no
                // legacy format can legitimately appear here (§1.1.1, G15).
                clear()
                return emptyList()
            }
            val peerName = root.optString("peerName").takeIf { it.isNotEmpty() && it != "null" }
            val array = root.optJSONArray("items") ?: return emptyList()
            (0 until array.length()).mapNotNull { index ->
                fromJson(array.optJSONObject(index) ?: return@mapNotNull null, peerName)
            }
        } catch (e: Exception) {
            // A corrupt journal must not stop the app from starting.
            clear()
            emptyList()
        }
    }

    @Synchronized
    fun hasUnfinishedWork(): Boolean = read().isNotEmpty()

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun writeAtomically(text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            // Rename can fail across some OEM filesystems; the copy still
            // leaves a complete file rather than a truncated one.
            file.writeText(text)
            temp.delete()
        }
    }

    private fun toJson(item: TransferItem, peerName: String?, nowMillis: Long): JSONObject {
        val json = JSONObject()
        json.put("id", item.id)
        json.put("batchId", item.batchId)
        json.put("direction", item.direction.name)
        json.put("state", item.state.name)
        json.put("name", item.file.displayName)
        json.put("uri", item.file.uri.toString())
        json.put("mime", item.file.mime ?: JSONObject.NULL)
        json.put("size", item.file.size)
        json.put("relativePath", item.relativePath ?: JSONObject.NULL)
        json.put("bytesTransferred", item.bytesTransferred)
        json.put("resumeOffset", item.resumeOffset)
        json.put("retryCount", item.retryCount)
        json.put("peerId", item.peerId ?: JSONObject.NULL)
        json.put("sha256", item.sha256 ?: JSONObject.NULL)
        json.put("peerName", peerName ?: JSONObject.NULL)
        json.put("updatedAt", nowMillis)
        return json
    }

    private fun fromJson(json: JSONObject, fallbackPeerName: String?): Entry? {
        val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val item = TransferItem(
            id = id,
            batchId = json.optString("batchId"),
            direction = Direction.valueOf(json.optString("direction", Direction.SENDING.name)),
            // A resumed item always comes back PAUSED: §9.7 asks the user
            // before anything moves, so nothing may return as IN_PROGRESS.
            state = restoredState(json.optString("state")),
            file = TransferFile(
                displayName = json.optString("name"),
                uri = Uri.parse(json.optString("uri")),
                mime = json.optStringOrNull("mime"),
                size = json.optLong("size"),
            ),
            relativePath = json.optStringOrNull("relativePath"),
            bytesTransferred = json.optLong("bytesTransferred"),
            resumeOffset = json.optLong("resumeOffset"),
            retryCount = json.optInt("retryCount"),
            peerId = json.optStringOrNull("peerId"),
            sha256 = json.optStringOrNull("sha256"),
        )
        return Entry(
            item = item,
            peerName = json.optStringOrNull("peerName") ?: fallbackPeerName,
            updatedAtMillis = json.optLong("updatedAt"),
        )
    }

    private fun restoredState(stored: String): TransferState = when (stored) {
        TransferState.IN_PROGRESS.name, TransferState.QUEUED.name -> TransferState.PAUSED
        else -> try {
            TransferState.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            TransferState.PAUSED
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val JOURNAL_VERSION = 1
    }
}
