package app.morsecode.android.core.util

import java.util.Locale

/**
 * Rendering of every technical meta value (§4.7 mono meta line).
 *
 * The mocks' copy is the contract: "4.1 MB", "48.9 / 144 MB · 6.2 MB/s",
 * "357.1 KB", "3:42", "24:12", "1080p · 144 MB · 24:12". Units are binary
 * (1 KB = 1024 B) with the familiar KB/MB/GB labels, one decimal place, and a
 * trailing ".0" is always dropped so "144 MB" never renders as "144.0 MB".
 *
 * Covered by `FmtTest` (§21.1).
 */
object Fmt {

    private const val KB = 1024.0
    private const val MB = KB * 1024
    private const val GB = MB * 1024
    private const val TB = GB * 1024

    /** "4.1 MB" · "357.1 KB" · "144 MB" · "0 B" */
    fun size(bytes: Long): String {
        val unit = unitFor(bytes)
        return value(bytes, unit) + " " + unit.label
    }

    /**
     * "48.9 / 144 MB" — both halves rendered in the LARGER value's unit so the
     * pair reads as one quantity and the unit is printed once (§6.4).
     */
    fun progress(done: Long, total: Long): String {
        val unit = unitFor(total)
        return value(done, unit) + " / " + value(total, unit) + " " + unit.label
    }

    /** "6.2 MB/s" · "812 KB/s" */
    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return "0 B/s"
        val unit = unitFor(bytesPerSecond)
        return value(bytesPerSecond, unit) + " " + unit.label + "/s"
    }

    /** "3:42" under an hour, "1:02:03" over it. Input is milliseconds. */
    fun duration(millis: Long): String {
        val totalSeconds = (if (millis < 0) 0 else millis) / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** "0%" … "100%", clamped; never 101% from a rounding error. */
    fun percent(done: Long, total: Long): String {
        if (total <= 0L) return "0%"
        val pct = (done.toDouble() / total.toDouble() * 100.0).toInt()
        return (if (pct < 0) 0 else if (pct > 100) 100 else pct).toString() + "%"
    }

    /** Spoken form for TalkBack progress announcements (§15.5). */
    fun spokenDuration(millis: Long): String {
        val totalSeconds = (if (millis < 0) 0 else millis) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val minutePart = if (minutes == 1L) "1 minute" else "$minutes minutes"
        val secondPart = if (seconds == 1L) "1 second" else "$seconds seconds"
        return "$minutePart $secondPart"
    }

    private enum class Unit(val label: String, val scale: Double) {
        B("B", 1.0), KILO("KB", KB), MEGA("MB", MB), GIGA("GB", GB), TERA("TB", TB)
    }

    private fun unitFor(bytes: Long): Unit {
        val abs = if (bytes < 0) 0.0 else bytes.toDouble()
        return when {
            abs >= TB -> Unit.TERA
            abs >= GB -> Unit.GIGA
            abs >= MB -> Unit.MEGA
            abs >= KB -> Unit.KILO
            else -> Unit.B
        }
    }

    private fun value(bytes: Long, unit: Unit): String {
        val scaled = (if (bytes < 0) 0L else bytes).toDouble() / unit.scale
        if (unit == Unit.B) return scaled.toLong().toString()
        val rounded = Math.round(scaled * 10.0) / 10.0
        return if (rounded == Math.floor(rounded)) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
    }
}
