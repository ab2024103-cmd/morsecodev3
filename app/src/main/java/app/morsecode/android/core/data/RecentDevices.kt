package app.morsecode.android.core.data

import android.content.Context
import app.morsecode.android.core.network.TransportKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * §17.4 RECENT DEVICES: a transport-level id plus a display name.
 *
 * "Recency shortens discovery, never consent" — so this store deliberately
 * holds nothing that could be used to skip the §6.16 handshake. There is no
 * token, no key and no "trusted" flag: tapping a recent device re-runs
 * discovery for it and the consent dialog appears exactly as it would for a
 * stranger (§6.2).
 */
class RecentDevices(context: Context, private val limit: Int = 12) {

    data class Entry(
        val deviceId: String,
        val name: String,
        val transport: TransportKind,
        val lastSeenMillis: Long,
        /** "2 min ago · 144 MB video" is rendered from this (§6.2). */
        val summary: String? = null,
    )

    private val prefs = context.applicationContext
        .getSharedPreferences("morsecode.recent", Context.MODE_PRIVATE)

    fun all(): List<Entry> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                Entry(
                    deviceId = json.optString("id"),
                    name = json.optString("name"),
                    transport = runCatching {
                        TransportKind.valueOf(json.optString("transport", TransportKind.LAN.name))
                    }.getOrNull() ?: TransportKind.LAN,
                    lastSeenMillis = json.optLong("at"),
                    summary = json.optString("summary").takeIf { it.isNotEmpty() },
                )
            }.sortedByDescending { it.lastSeenMillis }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun remember(
        deviceId: String,
        name: String,
        transport: TransportKind,
        summary: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (deviceId.isEmpty()) return
        val entries = all().filterNot { it.deviceId == deviceId }.toMutableList()
        entries.add(0, Entry(deviceId, name, transport, nowMillis, summary))
        write(entries.sortedByDescending { it.lastSeenMillis }.take(limit))
    }

    /** §6.2: the "RECENT DEVICES" header's Clear action. */
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    fun forget(deviceId: String) {
        write(all().filterNot { it.deviceId == deviceId })
    }

    private fun write(entries: List<Entry>) {
        val array = JSONArray()
        for (entry in entries) {
            val json = JSONObject()
            json.put("id", entry.deviceId)
            json.put("name", entry.name)
            json.put("transport", entry.transport.name)
            json.put("at", entry.lastSeenMillis)
            json.put("summary", entry.summary ?: "")
            array.put(json)
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "recent_devices"
    }
}
