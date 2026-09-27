package app.morsecode.android.core.media

import app.morsecode.android.core.model.DirectoryEntry
import app.morsecode.android.core.model.FileType
import app.morsecode.android.core.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * §20.1: "Selection has ONE canonical set read by the renderer and by the send
 * queue. Clearing it clears it everywhere, including a queue already handed
 * off. A fresh send screen never shows stale ticks."
 *
 * §6.9: one basket is shared by all five tabs, so a send can mix a photo, a
 * track and an APK — which is why the bar counts "items" and sums bytes rather
 * than counting photos.
 *
 * Folders are first-class members (§6.9, A25): a selected folder counts as ONE
 * item and contributes its recursive size.
 */
class Selection {

    data class Entry(
        val key: String,
        val displayName: String,
        val sizeBytes: Long,
        val type: FileType,
        val isDirectory: Boolean,
        val path: String?,
        val uri: android.net.Uri?,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val stateFlow = MutableStateFlow<List<Entry>>(emptyList())

    val items: StateFlow<List<Entry>> get() = stateFlow

    val count: Int get() = entries.size

    val totalBytes: Long get() = entries.values.sumOf { it.sizeBytes }

    /** True once anything is selected: the surface switches to selection mode. */
    val isActive: Boolean get() = entries.isNotEmpty()

    fun contains(key: String): Boolean = entries.containsKey(key)

    fun toggle(entry: Entry): Boolean {
        val nowSelected = if (entries.containsKey(entry.key)) {
            entries.remove(entry.key)
            false
        } else {
            entries[entry.key] = entry
            true
        }
        publish()
        return nowSelected
    }

    fun select(entry: Entry) {
        entries[entry.key] = entry
        publish()
    }

    fun deselect(key: String) {
        if (entries.remove(key) != null) publish()
    }

    /** §6.9's group select-all control, and the WebShare page equivalent. */
    fun selectAll(group: List<Entry>) {
        for (entry in group) entries[entry.key] = entry
        publish()
    }

    fun deselectAll(group: List<Entry>) {
        for (entry in group) entries.remove(entry.key)
        publish()
    }

    fun isGroupSelected(group: List<Entry>): Boolean =
        group.isNotEmpty() && group.all { entries.containsKey(it.key) }

    /**
     * §6.9: [Send] clears the selection — selection mode ends with the send and
     * the user must never have to tap ✕ afterwards. Returns what was taken.
     */
    fun takeAll(): List<Entry> {
        val taken = entries.values.toList()
        entries.clear()
        publish()
        return taken
    }

    fun clear() {
        if (entries.isEmpty()) return
        entries.clear()
        publish()
    }

    fun snapshot(): List<Entry> = entries.values.toList()

    private fun publish() {
        stateFlow.value = entries.values.toList()
    }

    companion object {

        fun of(item: MediaItem): Entry = Entry(
            key = "media:${item.uri}",
            displayName = item.name,
            sizeBytes = item.sizeBytes,
            type = item.type,
            isDirectory = false,
            path = item.path,
            uri = item.uri,
        )

        /**
         * A directory entry. A folder keeps its recursive size, because §6.9
         * says the Size column never shows a dash and the bar counts the
         * folder as one item at that size.
         */
        fun of(entry: DirectoryEntry, recursiveSize: Long = entry.sizeBytes): Entry = Entry(
            key = "path:${entry.path}",
            displayName = entry.name,
            sizeBytes = if (entry.isDirectory) recursiveSize else entry.sizeBytes,
            type = entry.type,
            isDirectory = entry.isDirectory,
            path = entry.path,
            uri = null,
        )
    }
}
