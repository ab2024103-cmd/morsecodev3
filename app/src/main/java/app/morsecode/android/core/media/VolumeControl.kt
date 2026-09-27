package app.morsecode.android.core.media

/**
 * §6.11 VOLUME IS A REAL CONTROL, NOT A MUTE TOGGLE [CHANGED].
 *
 * "Tapping the speaker opens an inline slider (0–100 %) that raises and lowers
 * playback volume continuously; the icon reflects the level (off / low /
 * high), dragging updates it live, and tapping the icon a second time
 * mutes-and-remembers so the previous level is restored on unmute. Hardware
 * volume keys move the same slider while the player is in the foreground. The
 * slider dismisses on the next outside tap or after 3 s idle."
 *
 * The behaviour is here, free of views, because "mute and remember" is the
 * part that gets quietly dropped.
 */
class VolumeControl(initialPercent: Int = 70) {

    enum class Level { OFF, LOW, HIGH }

    /** 0–100, as §6.11 specifies the slider. */
    var percent: Int = initialPercent.coerceIn(0, 100)
        private set

    /** The level to restore on unmute; never 0, or unmuting would do nothing. */
    private var rememberedPercent: Int = initialPercent.coerceIn(1, 100)

    val isMuted: Boolean get() = percent == 0

    /** What the player should multiply its output by. */
    val gain: Float get() = percent / 100f

    val level: Level
        get() = when {
            percent == 0 -> Level.OFF
            percent < LOW_THRESHOLD -> Level.LOW
            else -> Level.HIGH
        }

    /** Dragging the slider: continuous, live. */
    fun set(newPercent: Int) {
        percent = newPercent.coerceIn(0, 100)
        if (percent > 0) rememberedPercent = percent
    }

    /** Tapping the icon a second time: mute and remember, or restore. */
    fun toggleMute() {
        percent = if (percent == 0) rememberedPercent else 0
    }

    /** Hardware keys move the SAME slider (§6.11). */
    fun step(up: Boolean, stepPercent: Int = KEY_STEP) {
        set(percent + if (up) stepPercent else -stepPercent)
    }

    /** §6.11: the slider dismisses on an outside tap or after 3 s idle. */
    fun shouldDismiss(idleMillis: Long): Boolean = idleMillis >= IDLE_DISMISS_MS

    companion object {
        const val IDLE_DISMISS_MS = 3_000L
        const val KEY_STEP = 10
        private const val LOW_THRESHOLD = 50
    }
}
