package app.morsecode.android.core.data

import app.morsecode.android.core.media.DayGroups
import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.util.Fmt
import java.util.TimeZone

/**
 * §6.12 HISTORY's reactive stream, as a pure transformation.
 *
 * "The direction filter is part of the same reactive stream that produces the
 * list — never an external flag that only refreshes on the next unrelated
 * change." So direction, search and filters are arguments to ONE function, and
 * the screen renders whatever it returns.
 */
object HistoryPresentation {

    data class Filter(
        val direction: Direction = Direction.RECEIVING,
        /** Matches file name AND peer name (§6.12). */
        val query: String = "",
        /** Completed / Failed / Skipped; empty means all (§6.12). */
        val states: Set<TransferState> = emptySet(),
    )

    data class Group(val title: String, val rows: List<HistoryStore.Row>)

    fun apply(rows: List<HistoryStore.Row>, filter: Filter): List<HistoryStore.Row> {
        val query = filter.query.trim().lowercase()
        return rows
            .asSequence()
            .filter { it.direction == filter.direction }
            .filter { filter.states.isEmpty() || it.state in filter.states }
            .filter {
                query.isEmpty() ||
                    it.name.lowercase().contains(query) ||
                    it.peerName.lowercase().contains(query)
            }
            .sortedByDescending { it.timestampMillis }
            .toList()
    }

    /** TODAY / YESTERDAY / THIS WEEK / dd MMM, newest first (§6.12). */
    fun group(
        rows: List<HistoryStore.Row>,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<Group> {
        if (rows.isEmpty()) return emptyList()
        val groups = ArrayList<Group>()
        var currentTitle = DayGroups.titleFor(
            app.morsecode.android.core.media.MediaDates.dayStartMillis(rows.first().timestampMillis, timeZone),
            nowMillis,
            timeZone,
        ).uppercase()
        var bucket = ArrayList<HistoryStore.Row>()

        for (row in rows) {
            val title = DayGroups.titleFor(
                app.morsecode.android.core.media.MediaDates.dayStartMillis(row.timestampMillis, timeZone),
                nowMillis,
                timeZone,
            ).uppercase()
            if (title != currentTitle) {
                groups.add(Group(currentTitle, bucket))
                currentTitle = title
                bucket = ArrayList()
            }
            bucket.add(row)
        }
        groups.add(Group(currentTitle, bucket))
        return groups
    }

    /** "Ravi's Redmi · 131.1 MB · 15:13" (§6.12). */
    fun metaLine(row: HistoryStore.Row): String {
        val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(row.timestampMillis))
        return "${row.peerName} · ${Fmt.size(row.sizeBytes)} · $clock"
    }

    /** A green ✓ or a red ✗, always with the state in text too (§4.13). */
    fun statusGlyph(state: TransferState): String = when (state) {
        TransferState.COMPLETED -> "✓"
        TransferState.SKIPPED -> "↷"
        else -> "✗"
    }
}
