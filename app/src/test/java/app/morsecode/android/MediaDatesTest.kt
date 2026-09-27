package app.morsecode.android

import app.morsecode.android.core.media.MediaDates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * §21.1 "MediaStore date-unit conversion" and the read half of A7.
 *
 * DATE_TAKEN is MILLISECONDS; DATE_ADDED and DATE_MODIFIED are SECONDS.
 * Mixing them sends files to 1 Jan 1970 in every gallery, so the conversion
 * is asserted in both directions and at the fallback boundary.
 */
class MediaDatesTest {

    private val utc = TimeZone.getTimeZone("UTC")

    // 2024-05-12T10:30:00Z
    private val takenMillis = 1_715_509_800_000L
    private val modifiedSeconds = 1_715_509_800L

    @Test
    fun secondsAndMillisecondsConvertBothWays() {
        assertEquals(takenMillis, MediaDates.secondsToMillis(modifiedSeconds))
        assertEquals(modifiedSeconds, MediaDates.millisToSeconds(takenMillis))
        assertEquals(0L, MediaDates.secondsToMillis(0))
        assertEquals(0L, MediaDates.secondsToMillis(-1))
        assertEquals(0L, MediaDates.millisToSeconds(0))
    }

    @Test
    fun dateTakenWinsAndDateModifiedIsTheFallback() {
        // Both present: DATE_TAKEN, already in milliseconds, is used as is.
        assertEquals(takenMillis, MediaDates.effectiveMillis(takenMillis, 1L))
        // No DATE_TAKEN: DATE_MODIFIED is promoted from seconds.
        assertEquals(takenMillis, MediaDates.effectiveMillis(0, modifiedSeconds))
        assertEquals(0L, MediaDates.effectiveMillis(0, 0))
    }

    @Test
    fun aSecondsValueTreatedAsMillisWouldLandIn1970() {
        // The bug this conversion exists to prevent: 1.7 billion milliseconds
        // is January 1970, not 2024.
        val wrong = modifiedSeconds
        assertTrue("sanity: raw seconds are a 1970 timestamp in millis", wrong < 2_000_000_000L)
        assertEquals(takenMillis, MediaDates.effectiveMillis(0, wrong))
    }

    @Test
    fun dayGroupingIsStableWithinADayAndSplitsAcrossMidnight() {
        val morning = 1_715_509_800_000L // 10:30 UTC
        val evening = morning + 8 * 3_600_000L // 18:30 UTC, same day
        val nextDay = morning + 24 * 3_600_000L

        assertEquals(
            MediaDates.dayStartMillis(morning, utc),
            MediaDates.dayStartMillis(evening, utc),
        )
        assertTrue(MediaDates.isSameDay(morning, evening, utc))
        assertFalse(MediaDates.isSameDay(morning, nextDay, utc))
    }

    @Test
    fun dayStartIsMidnightExactly() {
        val start = MediaDates.dayStartMillis(1_715_509_800_000L, utc)
        assertEquals(0L, start % 86_400_000L)
    }
}
