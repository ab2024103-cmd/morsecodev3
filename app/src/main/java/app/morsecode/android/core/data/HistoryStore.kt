package app.morsecode.android.core.data

import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * §6.12 HISTORY, written by §9.6's single `complete()` path.
 *
 * "Both directions record history through the SAME complete() path: a send
 * that leaves no history row while the receive side has one is a defect." So
 * there is exactly one `record()` here and the engine calls it for every
 * terminal item, whichever direction it was going.
 *
 * A flat append-only JSON file, capped at [MAX_ROWS]. No database: §3.3 keeps
 * the dependency budget small and the history is a list, not a query surface.
 */
class HistoryStore(private val file: File) {

    data class Row(
        val itemId: String,
        val name: String,
        val sizeBytes: Long,
        val peerName: String,
        val direction: Direction,
        val state: TransferState,
        val timestampMillis: Long,
        val path: String?,
    )

    @Synchronized
    fun record(
        item: TransferItem,
        peerName: String,
        path: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val rows = ArrayList(read())
        rows.add(
            Row(
                itemId = item.id,
                name = item.file.displayName,
                sizeBytes = item.file.size,
                peerName = peerName,
                direction = item.direction,
                // SKIPPED is recorded as SKIPPED, never folded into FAILED (§9.6).
                state = item.state,
                timestampMillis = nowMillis,
                path = path,
            ),
        )
        while (rows.size > MAX_ROWS) rows.removeAt(0)
        write(rows)
    }

    @Synchronized
    fun read(): List<Row> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                Row(
                    itemId = json.optString("itemId"),
                    name = json.optString("name"),
                    sizeBytes = json.optLong("size"),
                    peerName = json.optString("peer"),
                    direction = Direction.valueOf(json.optString("direction", Direction.SENDING.name)),
                    state = TransferState.valueOf(json.optString("state", TransferState.COMPLETED.name)),
                    timestampMillis = json.optLong("at"),
                    path = json.optString("path").takeIf { it.isNotEmpty() },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** §6.12 "Remove from history" — distinct from "Delete file" (§6.12). */
    @Synchronized
    fun remove(itemId: String) {
        write(read().filterNot { it.itemId == itemId })
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun write(rows: List<Row>) {
        val array = JSONArray()
        for (row in rows) {
            val json = JSONObject()
            json.put("itemId", row.itemId)
            json.put("name", row.name)
            json.put("size", row.sizeBytes)
            json.put("peer", row.peerName)
            json.put("direction", row.direction.name)
            json.put("state", row.state.name)
            json.put("at", row.timestampMillis)
            json.put("path", row.path ?: "")
            array.put(json)
        }
        file.parentFile?.mkdirs()
        file.writeText(array.toString())
    }

    private companion object {
        const val MAX_ROWS = 2000
    }
}
