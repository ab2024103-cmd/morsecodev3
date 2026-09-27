package app.morsecode.android.core.ui

/**
 * §6.10's swipe deck, as arithmetic.
 *
 * "dragging moves the deck 1:1 with the finger and the neighbouring photos come
 * in from the edges (three slides live at a time); releasing past ~22 % of the
 * width (or a flick) commits to that neighbour and animates in ~220 ms;
 * anything less snaps back; the deck wraps at both ends."
 *
 * A33 is that behaviour, so the decision lives in a pure function and the view
 * only moves pixels.
 */
object DeckPhysics {

    /** ~22 % of the width commits (§6.10). */
    const val COMMIT_FRACTION = 0.22f

    /** A flick commits regardless of distance, in px/second. */
    const val FLICK_VELOCITY = 1_200f

    /** §4.11's page animation; scaled by DeviceTier at the call site. */
    const val SETTLE_MS = 220L

    enum class Outcome { PREVIOUS, NEXT, SNAP_BACK }

    /**
     * @param dragPx how far the finger moved; negative is a drag to the left,
     *   which advances to the NEXT photo.
     * @param velocityPx per second, same sign convention.
     */
    fun outcome(dragPx: Float, widthPx: Int, velocityPx: Float): Outcome {
        if (widthPx <= 0) return Outcome.SNAP_BACK
        val fraction = dragPx / widthPx
        val flicked = Math.abs(velocityPx) >= FLICK_VELOCITY
        // A flick in the opposite direction to the drag is a change of mind,
        // so the sign that decides is the one that agrees with both.
        return when {
            fraction <= -COMMIT_FRACTION -> Outcome.NEXT
            fraction >= COMMIT_FRACTION -> Outcome.PREVIOUS
            flicked && velocityPx < 0 && dragPx < 0 -> Outcome.NEXT
            flicked && velocityPx > 0 && dragPx > 0 -> Outcome.PREVIOUS
            else -> Outcome.SNAP_BACK
        }
    }

    /** The deck wraps at both ends (§6.10). */
    fun nextIndex(current: Int, count: Int): Int =
        if (count <= 0) 0 else (current + 1) % count

    fun previousIndex(current: Int, count: Int): Int =
        if (count <= 0) 0 else (current - 1 + count) % count

    fun apply(outcome: Outcome, current: Int, count: Int): Int = when (outcome) {
        Outcome.NEXT -> nextIndex(current, count)
        Outcome.PREVIOUS -> previousIndex(current, count)
        Outcome.SNAP_BACK -> current
    }

    /** "5 of 15" (§6.10's top bar). */
    fun positionLabel(index: Int, count: Int): String = "${index + 1} of $count"
}
