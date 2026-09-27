package app.morsecode.android.core.media

import app.morsecode.android.core.util.Fmt

/**
 * §6.11 SCRUBBING IS MANDATORY IN EVERY PLAYER [CHANGED].
 *
 * "phone music, phone video, the WebShare audio bar and the in-browser video
 * player all use the SAME seek contract. A progress bar you cannot grab is a
 * progress bar, not a player."
 *
 * All four players call into this one object, so the rule cannot hold in one
 * player and quietly fail in another — which is what A32 checks. It is pure:
 * positions in, positions out, no view and no player.
 */
object SeekContract {

    /** §6.11: the ±10 s buttons. */
    const val SKIP_MILLIS = 10_000L

    /** §6.11: ← → step 5 s for keyboard and TalkBack. */
    const val KEY_STEP_MILLIS = 5_000L

    /** The drawn track may be 4–5 dp, but the hit area is at least this. */
    const val MIN_TOUCH_HEIGHT_DP = 24

    /**
     * Tap-to-seek: press anywhere on the track and playback jumps there.
     * A tap at 75 % of the width is 75 % of the duration (A32).
     */
    fun positionForTouch(touchX: Float, trackWidth: Int, durationMillis: Long): Long {
        if (trackWidth <= 0 || durationMillis <= 0) return 0
        val fraction = (touchX / trackWidth).coerceIn(0f, 1f)
        return (durationMillis * fraction).toLong()
    }

    fun fractionFor(positionMillis: Long, durationMillis: Long): Float {
        if (durationMillis <= 0) return 0f
        return (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
    }

    /** Every seek lands inside the item: clamped at 0 and at the duration. */
    fun clamp(positionMillis: Long, durationMillis: Long): Long =
        positionMillis.coerceIn(0L, durationMillis.coerceAtLeast(0L))

    /**
     * §6.11: "the ±10 s buttons are real seeks against the same state — they
     * clamp at 0 and at the duration and never wrap around".
     */
    fun skipBackward(positionMillis: Long, durationMillis: Long): Long =
        clamp(positionMillis - SKIP_MILLIS, durationMillis)

    fun skipForward(positionMillis: Long, durationMillis: Long): Long =
        clamp(positionMillis + SKIP_MILLIS, durationMillis)

    /** ← → step 5 s; Home and End jump to the ends. */
    fun keyLeft(positionMillis: Long, durationMillis: Long): Long =
        clamp(positionMillis - KEY_STEP_MILLIS, durationMillis)

    fun keyRight(positionMillis: Long, durationMillis: Long): Long =
        clamp(positionMillis + KEY_STEP_MILLIS, durationMillis)

    fun home(): Long = 0

    fun end(durationMillis: Long): Long = durationMillis.coerceAtLeast(0)

    /** Elapsed on the left, "-2:24" remaining on the right (§6.11). */
    fun elapsedLabel(positionMillis: Long): String = Fmt.duration(positionMillis)

    fun remainingLabel(positionMillis: Long, durationMillis: Long): String =
        "-" + Fmt.duration((durationMillis - positionMillis).coerceAtLeast(0))

    /** "2 minutes 43 seconds of 4 minutes 8 seconds" (§6.11, §15.5). */
    fun announcement(positionMillis: Long, durationMillis: Long): String =
        "${Fmt.spokenDuration(positionMillis)} of ${Fmt.spokenDuration(durationMillis)}"

    /**
     * The drag state machine. §6.11: "while the user is dragging, the clock
     * STOPS driving the bar — playback position follows the thumb, never
     * fights it — and the labels update live during the drag, not only on
     * release".
     *
     * A player asks [displayPosition] what to paint; while a drag is in
     * progress that is the thumb's position and the clock is ignored.
     */
    class DragState {

        var isDragging: Boolean = false
            private set

        private var draggedPosition: Long = 0

        fun begin(positionMillis: Long) {
            isDragging = true
            draggedPosition = positionMillis
        }

        fun update(positionMillis: Long) {
            if (isDragging) draggedPosition = positionMillis
        }

        /** Release commits the seek; the returned value is what to seek to. */
        fun commit(): Long {
            isDragging = false
            return draggedPosition
        }

        fun cancel() {
            isDragging = false
        }

        /** What the bar and the labels should show right now. */
        fun displayPosition(clockPositionMillis: Long): Long =
            if (isDragging) draggedPosition else clockPositionMillis
    }
}
