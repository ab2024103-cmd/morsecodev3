package app.morsecode.android.core.media

import android.provider.MediaStore
import app.morsecode.android.core.model.SortKey
import app.morsecode.android.core.model.SortOrder

/**
 * The SQL fragments the media library hands to MediaStore, isolated here so
 * INV-9 and INV-10 are testable without a device.
 *
 * INV-9 ORDERING (§12.5): sort by date-taken falling back to date-modified, as
 * `CASE WHEN DATE_TAKEN > 0 THEN DATE_TAKEN ELSE DATE_MODIFIED * 1000 END`
 * with `_ID DESC` as tiebreak, so day headers appear exactly once, in order.
 *
 * INV-10 PAGINATION (§12.5): never put LIMIT/OFFSET inside the sort string.
 * Android 14's MediaProvider rejects it with "Invalid token LIMIT". Paging is
 * done by opening the cursor, `moveToPosition(offset)` and reading `limit`
 * rows in a do-while — see [MediaLibrary].
 */
object MediaQueries {

    /** INV-9, verbatim. The `* 1000` converts DATE_MODIFIED seconds to millis. */
    const val EFFECTIVE_DATE_EXPRESSION =
        "CASE WHEN ${MediaStore.MediaColumns.DATE_TAKEN} > 0 " +
            "THEN ${MediaStore.MediaColumns.DATE_TAKEN} " +
            "ELSE ${MediaStore.MediaColumns.DATE_MODIFIED} * 1000 END"

    /**
     * Ordering for photo and video listings, which are the surfaces with day
     * groups. `_ID DESC` breaks ties so two items with the same timestamp
     * cannot swap places between pages and duplicate a day header (A7).
     */
    fun mediaOrderBy(order: SortOrder): String {
        val direction = if (order.descending) "DESC" else "ASC"
        val column = when (order.key) {
            SortKey.DATE_MODIFIED -> EFFECTIVE_DATE_EXPRESSION
            SortKey.NAME -> "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE"
            SortKey.SIZE -> MediaStore.MediaColumns.SIZE
            SortKey.TYPE -> "${MediaStore.MediaColumns.MIME_TYPE} COLLATE NOCASE"
        }
        return "$column $direction, ${MediaStore.MediaColumns._ID} DESC"
    }

    /**
     * True when a sort string would be rejected by Android 14's MediaProvider.
     * The library asserts this on every query it builds; the test asserts it
     * over every sort key and direction (A7).
     */
    fun containsPaginationTokens(sortOrder: String?): Boolean {
        if (sortOrder == null) return false
        val upper = sortOrder.uppercase()
        return Regex("\\bLIMIT\\b").containsMatchIn(upper) ||
            Regex("\\bOFFSET\\b").containsMatchIn(upper)
    }

    /**
     * Page bounds for the cursor walk. Returns the inclusive start position and
     * the number of rows to read, clamped to what the cursor actually holds, so
     * `moveToPosition` is never asked for a row that does not exist.
     */
    fun pageBounds(cursorCount: Int, offset: Int, limit: Int): IntRange {
        if (cursorCount <= 0 || limit <= 0 || offset >= cursorCount) return IntRange.EMPTY
        val start = offset.coerceAtLeast(0)
        val endExclusive = (start + limit).coerceAtMost(cursorCount)
        return start until endExclusive
    }
}
