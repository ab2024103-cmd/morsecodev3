package app.morsecode.android.core.data

import android.content.Context

/**
 * §6.11's on-device-only playback state.
 *
 * [GAP, §6.11]: "Save = add to a local playlist; Liked = toggle a local
 * favourite (accent when on); both are stored on-device only." Nothing here
 * leaves the phone — there is no account and no sync anywhere in the product
 * (§17.1).
 *
 * Also holds the video player's per-file resume position (§6.11).
 */
class PlaybackPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("morsecode.playback", Context.MODE_PRIVATE)

    // ----- Liked (§6.11) ----------------------------------------------------

    fun isLiked(key: String): Boolean = prefs.getBoolean(likeKey(key), false)

    fun setLiked(key: String, liked: Boolean) {
        prefs.edit().putBoolean(likeKey(key), liked).apply()
    }

    fun toggleLiked(key: String): Boolean {
        val next = !isLiked(key)
        setLiked(key, next)
        return next
    }

    // ----- Save to a local playlist (§6.11) ---------------------------------

    fun savedTracks(): Set<String> = prefs.getStringSet(KEY_SAVED, emptySet()).orEmpty()

    fun save(key: String): Boolean {
        val saved = HashSet(savedTracks())
        val added = saved.add(key)
        prefs.edit().putStringSet(KEY_SAVED, saved).apply()
        return added
    }

    fun unsave(key: String) {
        val saved = HashSet(savedTracks())
        saved.remove(key)
        prefs.edit().putStringSet(KEY_SAVED, saved).apply()
    }

    fun isSaved(key: String): Boolean = key in savedTracks()

    // ----- Resume position (§6.11 video) ------------------------------------

    /** Per file, so returning to a half-watched video picks it up. */
    fun resumePosition(key: String): Long = prefs.getLong(resumeKey(key), 0)

    /**
     * A position within a few seconds of the start or the end is not worth
     * remembering: resuming 2 s from the end would replay nothing.
     */
    fun setResumePosition(key: String, positionMillis: Long, durationMillis: Long) {
        val editor = prefs.edit()
        val tooEarly = positionMillis < RESUME_EDGE_MS
        val tooLate = durationMillis > 0 && positionMillis > durationMillis - RESUME_EDGE_MS
        if (tooEarly || tooLate) {
            editor.remove(resumeKey(key))
        } else {
            editor.putLong(resumeKey(key), positionMillis)
        }
        editor.apply()
    }

    private fun likeKey(key: String) = "liked.$key"

    private fun resumeKey(key: String) = "resume.$key"

    private companion object {
        const val KEY_SAVED = "saved_tracks"
        const val RESUME_EDGE_MS = 5_000L
    }
}
