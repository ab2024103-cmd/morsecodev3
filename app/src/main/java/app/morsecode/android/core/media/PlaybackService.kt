package app.morsecode.android.core.media

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import app.morsecode.android.MainActivity
import app.morsecode.android.R
import app.morsecode.android.core.util.Ids
import app.morsecode.android.di.AppServices

/**
 * §6.11: "Playback runs in a MediaSession-backed foreground service: it
 * continues across tab changes, app minimise and screen off, with a media
 * notification (prev/play-pause/next) and audio-focus + becoming-noisy
 * handling."
 *
 * The queue and the position live in [PlaybackQueue] (process-scoped), so the
 * Now-Playing screen can come and go without touching playback — §8.2's rule
 * about coordinators, applied to audio.
 */
class PlaybackService : Service() {

    /**
     * §3.3 [CHANGED]: ExoPlayer, not MediaPlayer. §6.11's reason is codec
     * coverage on API 23 devices and seekTo semantics — the reference phone
     * refusing files that play everywhere else is exactly the MediaPlayer
     * failure mode. ExoPlayer also owns audio focus and becoming-noisy, so
     * the hand-rolled versions of both are gone.
     */
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var noisyRegistered = false
    /** Avoid looping forever when Repeat All meets a queue of unreadable files. */
    private val failedTracks = HashSet<String>()

    private val queue get() = AppServices.playbackQueue
    private val volume get() = AppServices.volumeControl

    /** Unplugging headphones pauses, it does not blast the room (§6.11). */
    private val becomingNoisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // handleAudioFocus: ExoPlayer ducks, pauses and resumes for us
                // (§6.11's focus requirement), correctly on every API level.
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                queue.setPlaying(isPlaying)
                updateSession()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // Repeat ONE stays, ALL wraps, OFF stops — the queue
                    // decides, never this listener.
                    if (queue.advance()) startTrack() else stopPlayback()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Registered before any item is prepared: malformed, zero-byte
                // and unsupported/DRM tracks all take this recoverable path.
                handleFailure(error.errorCodeName)
            }
        })
        player = exo
        session = MediaSession.Builder(this, exo).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> skipNext()
            ACTION_PREVIOUS -> skipPrevious()
            ACTION_STOP -> {
                stopPlayback()
                return START_NOT_STICKY
            }
            else -> startTrack()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releasePlayer()
        session?.release()
        session = null
        super.onDestroy()
    }

    // ----- Playback ---------------------------------------------------------

    private fun startTrack() {
        val track = queue.state.value.current ?: return
        // A previously rejected item is skipped synchronously. This matters for
        // Repeat All: error callbacks alone could otherwise cycle forever over
        // a queue containing only unreadable tracks.
        if (failedTracks.contains(track.uri.toString())) {
            if (advancePastFailed()) startTrack() else stopPlayback()
            return
        }
        val exo = player ?: return
        try {
            // The listener is already installed in onCreate, BEFORE this call.
            // The try block additionally catches synchronous URI/prepare faults.
            exo.setMediaItem(Media3Item.fromUri(track.uri))
            exo.volume = volume.gain
            exo.prepare()
            exo.play()
            queue.setPlaying(true)
            updateSession()
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (error: Exception) {
            handleFailure(error.javaClass.simpleName)
        }
    }

    /** §6.11.0: explain, skip and continue; never let one bad file crash audio. */
    private fun handleFailure(detail: String) {
        val track = queue.state.value.current ?: run {
            stopPlayback()
            return
        }
        failedTracks.add(track.uri.toString())
        queue.setPlaying(false)
        queue.reportFailure(track, detail)
        AppServices.logStore.e("Playback failed · ${track.name} · $detail")
        if (advancePastFailed()) {
            startTrack()
        } else {
            stopPlayback()
        }
    }

    /** Finds the next unfailed item, with a hard bound for Repeat All/One. */
    private fun advancePastFailed(): Boolean {
        val attempts = queue.state.value.tracks.size
        repeat(attempts) {
            if (!queue.advance()) return false
            val next = queue.state.value.current ?: return false
            if (!failedTracks.contains(next.uri.toString())) return true
        }
        return false
    }

    private fun play() {
        val exo = player ?: return startTrack()
        if (exo.currentMediaItem == null) return startTrack()
        exo.play()
    }

    private fun pause() {
        player?.pause()
    }

    private fun skipNext() {
        if (queue.advance()) startTrack()
    }

    private fun skipPrevious() {
        queue.rewind()
        startTrack()
    }

    /** §6.11: release commits the seek, and seeking while paused stays paused. */
    private fun seekTo(positionMillis: Long) {
        val exo = player ?: return
        val clamped = SeekContract.clamp(positionMillis, exo.duration.coerceAtLeast(0))
        exo.seekTo(clamped)
        queue.setPosition(clamped)
        updateSession()
    }

    fun applyVolume() {
        player?.volume = volume.gain
    }

    private fun stopPlayback() {
        releasePlayer()
        queue.setPlaying(false)
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        unregisterNoisy()
    }

    /**
     * ExoPlayer holds audio focus and handles ACTION_AUDIO_BECOMING_NOISY
     * itself (see the builder above), so the ~90 lines that did both by hand
     * are gone rather than left beside it (§3.3: replace, do not layer).
     */
    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { unregisterReceiver(becomingNoisy) }
        noisyRegistered = false
    }

    // ----- Session and notification ----------------------------------------

    private fun updateSession() {
        val exo = player ?: return
        queue.setPosition(exo.currentPosition.coerceAtLeast(0))
        androidx.core.app.NotificationManagerCompat.from(this)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification() = NotificationCompat.Builder(this, Ids.CHANNEL_PLAYBACK)
        .setSmallIcon(R.drawable.ic_type_audio)
        .setContentTitle(queue.state.value.current?.name ?: getString(R.string.app_name))
        .setContentText(queue.state.value.current?.artist ?: getString(R.string.files_unknown_artist))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                if (Build.VERSION.SDK_INT >= 23) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                },
            ),
        )
        .addAction(0, getString(R.string.player_previous), command(ACTION_PREVIOUS))
        .addAction(
            0,
            getString(if (queue.state.value.isPlaying) R.string.player_pause else R.string.player_play),
            command(if (queue.state.value.isPlaying) ACTION_PAUSE else ACTION_PLAY),
        )
        .addAction(0, getString(R.string.player_next), command(ACTION_NEXT))
        .setSilent(true)
        .setOngoing(queue.state.value.isPlaying)
        .build()

    private fun command(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java)
        intent.action = action
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            if (Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            },
        )
    }

    companion object {

        private const val NOTIFICATION_ID = 3001
        const val ACTION_PLAY = "app.morsecode.android.action.PLAY"
        const val ACTION_PAUSE = "app.morsecode.android.action.PAUSE"
        const val ACTION_NEXT = "app.morsecode.android.action.NEXT"
        const val ACTION_PREVIOUS = "app.morsecode.android.action.PREVIOUS"
        const val ACTION_STOP = "app.morsecode.android.action.STOP_PLAYBACK"
        private const val DUCK = 0.3f

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, PlaybackService::class.java)
            intent.action = action
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
