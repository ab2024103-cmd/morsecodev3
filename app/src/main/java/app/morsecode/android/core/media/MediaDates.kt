package app.morsecode.android.core.media

import java.util.Calendar
import java.util.TimeZone

/**
 * §12.5 DATE UNITS — the single place the product converts MediaStore time
 * values.
 *
 * DATE_TAKEN is MILLISECONDS. DATE_ADDED and DATE_MODIFIED are SECONDS.
 * Mixing them sends files to 1 Jan 1970 in every gallery, which is why the
 * unit is named in every function here and why no caller is allowed to do the
 * arithmetic itself.
 */
object MediaDates {

    /** DATE_MODIFIED / DATE_ADDED (seconds) → milliseconds. */
    fun secondsToMillis(seconds: Long): Long = if (seconds <= 0) 0 else seconds * 1000L

    /** Milliseconds → DATE_MODIFIED / DATE_ADDED (seconds), for inserts. */
    fun millisToSeconds(millis: Long): Long = if (millis <= 0) 0 else millis / 1000L

    /**
     * INV-9 ORDERING, read side: date-taken when it is present, otherwise
     * date-modified. Both arguments arrive in their NATIVE units — taken in
     * milliseconds, modified in seconds — exactly as the columns store them,
     * and the result is always milliseconds.
     */
    fun effectiveMillis(dateTakenMillis: Long, dateModifiedSeconds: Long): Long =
        if (dateTakenMillis > 0) dateTakenMillis else secondsToMillis(dateModifiedSeconds)

    /**
     * Local midnight for a timestamp, used to build the §6.9 day groups.
     * Day headers must appear exactly once and in order (A7), so grouping is
     * done on this key rather than on a formatted string.
     */
    fun dayStartMillis(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
        val calendar = Calendar.getInstance(timeZone)
        calendar.timeInMillis = millis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun isSameDay(
        firstMillis: Long,
        secondMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Boolean = dayStartMillis(firstMillis, timeZone) == dayStartMillis(secondMillis, timeZone)
}
