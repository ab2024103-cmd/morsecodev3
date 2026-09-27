package app.morsecode.android.core.webshare

/**
 * §7.2: "/download streams a file, MUST support Range", and §7.6's player
 * depends on it — a seek sets `<video>.currentTime`, the browser issues a
 * fresh Range request and the server must answer 206 Partial Content from the
 * new offset (A32's browser half).
 *
 * Parsing is here, pure, because an off-by-one in a Content-Range is invisible
 * until a video refuses to seek.
 */
object HttpRange {

    data class Span(val start: Long, val endInclusive: Long, val totalLength: Long) {

        val length: Long get() = endInclusive - start + 1

        /** "bytes 200-1023/1024" */
        val contentRange: String get() = "bytes $start-$endInclusive/$totalLength"
    }

    /**
     * Parses a `Range: bytes=…` header against a known content length.
     *
     * Returns null when the header is absent or not a byte range — the caller
     * then answers 200 with the whole file, which is what a browser expects.
     * Returns a span clamped to the file when the range is satisfiable.
     */
    fun parse(header: String?, totalLength: Long): Span? {
        if (header == null || totalLength <= 0) return null
        val value = header.trim()
        if (!value.startsWith(PREFIX, ignoreCase = true)) return null
        val spec = value.substring(PREFIX.length).substringBefore(',').trim()
        if (spec.isEmpty()) return null

        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()

        return when {
            // "bytes=-500": the LAST 500 bytes.
            startText.isEmpty() -> {
                val suffix = endText.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                val start = (totalLength - suffix).coerceAtLeast(0)
                Span(start, totalLength - 1, totalLength)
            }
            // "bytes=500-": from 500 to the end.
            endText.isEmpty() -> {
                val start = startText.toLongOrNull() ?: return null
                if (start >= totalLength) return null
                Span(start, totalLength - 1, totalLength)
            }
            else -> {
                val start = startText.toLongOrNull() ?: return null
                val end = endText.toLongOrNull() ?: return null
                if (start > end || start >= totalLength) return null
                Span(start, end.coerceAtMost(totalLength - 1), totalLength)
            }
        }
    }

    /** True when the header was present but cannot be satisfied → 416. */
    fun isUnsatisfiable(header: String?, totalLength: Long): Boolean {
        if (header == null) return false
        if (!header.trim().startsWith(PREFIX, ignoreCase = true)) return false
        return parse(header, totalLength) == null
    }

    private const val PREFIX = "bytes="
}
