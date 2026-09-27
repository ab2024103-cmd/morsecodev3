package app.morsecode.android.core.transfer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import app.morsecode.android.core.data.Prefs

/**
 * §6.13's "Sounds · Connect · fail · success" switch, and the thing that reads
 * it.
 *
 * §6.13 is explicit: "Every switch here must actually be wired to behaviour; a
 * stored-but-unread preference is a defect (this bit us before with Sounds)."
 * So this class exists for one reason — to be the reader — and it consults
 * [Prefs.sounds] on every call rather than caching the value, so switching the
 * setting off takes effect on the very next event.
 *
 * Short system tones, no bundled audio assets: §19.4's size budget does not
 * have room for three sound files, and the product needs a cue, not a jingle.
 */
class SoundFx(context: Context, private val prefs: Prefs) {

    enum class Cue { CONNECT, SUCCESS, FAIL }

    private val appContext = context.applicationContext

    fun play(cue: Cue) {
        if (!prefs.sounds.value) return
        val tone = when (cue) {
            Cue.CONNECT -> ToneGenerator.TONE_PROP_BEEP
            Cue.SUCCESS -> ToneGenerator.TONE_PROP_ACK
            Cue.FAIL -> ToneGenerator.TONE_PROP_NACK
        }
        // A cue must never be the reason a transfer dies, so every failure
        // here is swallowed deliberately.
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
            generator.startTone(tone, DURATION_MS)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { runCatching { generator.release() } },
                RELEASE_DELAY_MS,
            )
        }
    }

    /** Whether the switch is currently on; used by the settings row itself. */
    fun isEnabled(): Boolean = prefs.sounds.value

    @Suppress("unused")
    private fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private companion object {
        const val VOLUME = 70
        const val DURATION_MS = 150
        const val RELEASE_DELAY_MS = 400L
    }
}
