package app.morsecode.android

import app.morsecode.android.core.media.MediaQueries
import app.morsecode.android.core.model.SortKey
import app.morsecode.android.core.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INV-9 and INV-10 (§12.5), which together are the query half of A7.
 *
 * INV-10 in particular is why this is a test and not a comment: Android 14's
 * MediaProvider rejects a sort string containing LIMIT with "Invalid token
 * LIMIT", and the failure only appears on one OS version at runtime.
 */
class MediaQueriesTest {

    @Test
    fun orderingIsDateTakenFallingBackToDateModifiedWithAnIdTiebreak() {
        val order = MediaQueries.mediaOrderBy(SortOrder())
        assertTrue(
            "INV-9 expression missing: $order",
            order.startsWith(MediaQueries.EFFECTIVE_DATE_EXPRESSION),
        )
        assertTrue(order.contains("CASE WHEN datetaken > 0"))
        // DATE_MODIFIED is seconds, so the expression must scale it to millis.
        assertTrue(order.contains("date_modified * 1000"))
        assertTrue("tiebreak missing: $order", order.endsWith("_id DESC"))
    }

    @Test
    fun everySortKeyAndDirectionStaysFreeOfPaginationTokens() {
        for (key in SortKey.values()) {
            for (descending in listOf(true, false)) {
                val order = MediaQueries.mediaOrderBy(SortOrder(key, descending))
                assertFalse(
                    "INV-10 violated for $key descending=$descending: $order",
                    MediaQueries.containsPaginationTokens(order),
                )
                assertTrue(order.contains(if (descending) "DESC" else "ASC"))
            }
        }
    }

    @Test
    fun thePaginationDetectorActuallyDetects() {
        // Proving the guard is not vacuous.
        assertTrue(MediaQueries.containsPaginationTokens("date_modified DESC LIMIT 20"))
        assertTrue(MediaQueries.containsPaginationTokens("date_modified DESC limit 20 offset 40"))
        assertTrue(MediaQueries.containsPaginationTokens("_id DESC OFFSET 10"))
        assertFalse(MediaQueries.containsPaginationTokens(null))
        // A column whose name merely contains the letters must not trip it.
        assertFalse(MediaQueries.containsPaginationTokens("unlimited_column DESC"))
    }

    @Test
    fun pageBoundsClampToTheCursor() {
        assertEquals(0 until 20, MediaQueries.pageBounds(cursorCount = 100, offset = 0, limit = 20))
        assertEquals(80 until 100, MediaQueries.pageBounds(100, 80, 20))
        // Last partial page.
        assertEquals(90 until 100, MediaQueries.pageBounds(100, 90, 20))
        // Past the end, empty cursor, nonsense limit.
        assertTrue(MediaQueries.pageBounds(100, 100, 20).isEmpty())
        assertTrue(MediaQueries.pageBounds(0, 0, 20).isEmpty())
        assertTrue(MediaQueries.pageBounds(100, 0, 0).isEmpty())
    }

    @Test
    fun walkingEveryPageVisitsEveryRowExactlyOnce() {
        // The property day headers depend on: pages tile the cursor with no
        // gap and no overlap, so an item cannot appear under two day groups.
        val count = 457
        val limit = 60
        val seen = ArrayList<Int>(count)
        var offset = 0
        while (offset < count) {
            val range = MediaQueries.pageBounds(count, offset, limit)
            if (range.isEmpty()) break
            seen.addAll(range)
            offset += limit
        }
        assertEquals(count, seen.size)
        assertEquals((0 until count).toList(), seen)
    }
}
