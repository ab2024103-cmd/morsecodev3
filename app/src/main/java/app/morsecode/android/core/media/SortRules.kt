package app.morsecode.android.core.media

import app.morsecode.android.core.model.DirectoryEntry
import app.morsecode.android.core.model.MediaItem
import app.morsecode.android.core.model.SortKey
import app.morsecode.android.core.model.SortOrder

/**
 * §6.9's sort sheet, which is a WORKING control: "Choosing re-orders the
 * CURRENT tab immediately and the order persists while the app is open; the
 * active order is echoed under the tab strip and in the sheet's subtitle."
 *
 * Two rules are absolute and live here so no listing can forget them:
 *  - **Folders always sort before files**, whichever key is active.
 *  - Day groups keep their headers and are ordered inside (that falls out of
 *    sorting the items and regrouping, which [DayGroups] then does).
 */
object SortRules {

    /** "Size · smallest first" — the echo under the tab strip (§6.9, A34). */
    fun label(order: SortOrder): String {
        val key = when (order.key) {
            SortKey.DATE_MODIFIED -> "Date modified"
            SortKey.NAME -> "Name"
            SortKey.SIZE -> "Size"
            SortKey.TYPE -> "Type"
        }
        val direction = when (order.key) {
            SortKey.DATE_MODIFIED -> if (order.descending) "newest first" else "oldest first"
            SortKey.NAME -> if (order.descending) "Z to A" else "A to Z"
            SortKey.SIZE -> if (order.descending) "largest first" else "smallest first"
            SortKey.TYPE -> if (order.descending) "Z to A" else "A to Z"
        }
        return "$key · $direction"
    }

    fun sortItems(items: List<MediaItem>, order: SortOrder): List<MediaItem> {
        val comparator: Comparator<MediaItem> = when (order.key) {
            SortKey.DATE_MODIFIED -> compareBy { it.dateMillis }
            SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortKey.SIZE -> compareBy { it.sizeBytes }
            SortKey.TYPE -> compareBy<MediaItem> { it.type.name }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        // `_ID DESC`'s UI equivalent: a stable tiebreak so two items with the
        // same key cannot swap between renders (A7, A31).
        val stable = comparator.thenBy { it.id }
        return if (order.descending) items.sortedWith(stable.reversed()) else items.sortedWith(stable)
    }

    /** Folders first, always — then the chosen key inside each part (§6.9). */
    fun sortEntries(entries: List<DirectoryEntry>, order: SortOrder): List<DirectoryEntry> {
        val comparator: Comparator<DirectoryEntry> = when (order.key) {
            SortKey.DATE_MODIFIED -> compareBy { it.dateMillis }
            SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortKey.SIZE -> compareBy { it.sizeBytes }
            SortKey.TYPE -> compareBy<DirectoryEntry> { it.type.name }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val stable = comparator.thenBy(String.CASE_INSENSITIVE_ORDER) { it.path }
        val directed = if (order.descending) stable.reversed() else stable
        return entries.sortedWith(compareByDescending<DirectoryEntry> { it.isDirectory }.then(directed))
    }
}
