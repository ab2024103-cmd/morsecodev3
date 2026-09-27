package app.morsecode.android.core.media

import app.morsecode.android.core.model.MediaItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * §6.9's day-grouped grid and §6.9.3's ordering rule, in one place.
 *
 * A7: "Photo day groups appear once, in order." That is a property of the
 * grouping, not of the adapter, so it is decided here and asserted by tests:
 * items arrive in INV-9 order (date-taken falling back to date-modified, newest
 * first) and are folded into consecutive runs — a day can therefore never get
 * a second header, and the headers keep the list's order.
 */
object DayGroups {

    data class Group(
        val dayStartMillis: Long,
        val title: String,
        val items: List<MediaItem>,
    ) {
        /** "Today · 6 items" (§6.9). */
        val header: String get() = "$title · ${items.size} ${if (items.size == 1) "item" else "items"}"
    }

    /**
     * @param items already ordered newest-first by [MediaLibrary]; this does
     *   NOT re-sort, because re-sorting here would hide an ordering bug in the
     *   query that A7 exists to catch.
     */
    fun group(
        items: List<MediaItem>,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<Group> {
        if (items.isEmpty()) return emptyList()
        val groups = ArrayList<Group>()
        var currentDay = MediaDates.dayStartMillis(items.first().dateMillis, timeZone)
        var bucket = ArrayList<MediaItem>()

        for (item in items) {
            val day = MediaDates.dayStartMillis(item.dateMillis, timeZone)
            if (day != currentDay) {
                groups.add(Group(currentDay, titleFor(currentDay, nowMillis, timeZone, locale), bucket))
                currentDay = day
                bucket = ArrayList()
            }
            bucket.add(item)
        }
        groups.add(Group(currentDay, titleFor(currentDay, nowMillis, timeZone, locale), bucket))
        return groups
    }

    /** TODAY / YESTERDAY / THIS WEEK / dd MMM (§6.9, §6.12 use the same ladder). */
    fun titleFor(
        dayStartMillis: Long,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val today = MediaDates.dayStartMillis(nowMillis, timeZone)
        val dayMillis = 24L * 60 * 60 * 1000
        return when {
            dayStartMillis == today -> "Today"
            dayStartMillis == today - dayMillis -> "Yesterday"
            dayStartMillis > today - 7 * dayMillis -> "This week"
            else -> {
                val format = SimpleDateFormat("d MMM", locale)
                format.timeZone = timeZone
                format.format(Date(dayStartMillis))
            }
        }
    }

    /** Sanity check used by the tests: a day may appear at most once. */
    fun hasDuplicateDays(groups: List<Group>): Boolean =
        groups.map { it.dayStartMillis }.let { it.size != it.toSet().size }

    /** True when the groups run newest-first, as §6.9.3 requires. */
    fun isDescending(groups: List<Group>): Boolean =
        groups.zipWithNext().all { (first, second) -> first.dayStartMillis > second.dayStartMillis }

    /** The calendar day of a timestamp, for callers that only need the key. */
    fun dayOf(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): Long =
        MediaDates.dayStartMillis(millis, timeZone).also { Calendar.getInstance(timeZone) }
}
