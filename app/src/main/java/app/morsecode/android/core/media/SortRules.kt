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
 *
 * Comparators are deliberately hand-written. `Comparator.reversed()` and
 * `Comparator.then()` are Java-8 interface defaults and crash on Android 6
 * without core-library desugaring — caught by the API-23 §21.3 emulator.
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

    fun sortItems(items: List<MediaItem>, order: SortOrder): List<MediaItem> =
        items.sortedWith(Comparator { left, right ->
            val primary = when (order.key) {
                SortKey.DATE_MODIFIED -> left.dateMillis.compareTo(right.dateMillis)
                SortKey.NAME -> String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name)
                SortKey.SIZE -> left.sizeBytes.compareTo(right.sizeBytes)
                SortKey.TYPE -> {
                    val type = left.type.name.compareTo(right.type.name)
                    if (type != 0) type else String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name)
                }
            }
            val stable = if (primary != 0) primary else left.id.compareTo(right.id)
            directed(stable, order.descending)
        })

    /** Folders first, always — then the chosen key inside each part (§6.9). */
    fun sortEntries(entries: List<DirectoryEntry>, order: SortOrder): List<DirectoryEntry> =
        entries.sortedWith(Comparator { left, right ->
            // This comparison is intentionally never reversed: folder-first is
            // structural, not an accidental effect of the user sort direction.
            if (left.isDirectory != right.isDirectory) {
                return@Comparator if (left.isDirectory) -1 else 1
            }
            val primary = when (order.key) {
                SortKey.DATE_MODIFIED -> left.dateMillis.compareTo(right.dateMillis)
                SortKey.NAME -> String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name)
                SortKey.SIZE -> left.sizeBytes.compareTo(right.sizeBytes)
                SortKey.TYPE -> {
                    val type = left.type.name.compareTo(right.type.name)
                    if (type != 0) type else String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name)
                }
            }
            val stable = if (primary != 0) {
                primary
            } else {
                String.CASE_INSENSITIVE_ORDER.compare(left.path, right.path)
            }
            directed(stable, order.descending)
        })

    /** Invert safely without negating Int.MIN_VALUE. */
    private fun directed(result: Int, descending: Boolean): Int = when {
        !descending -> result
        result < 0 -> 1
        result > 0 -> -1
        else -> 0
    }
}
