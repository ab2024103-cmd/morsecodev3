package app.morsecode.android.core.media

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
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

    private var player: MediaPlayer? = null
    private var session: MediaSessionCompat? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false

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
        session = MediaSessionCompat(this, "morsecode.playback").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = skipNext()
                override fun onSkipToPrevious() = skipPrevious()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = stopPlayback()
            })
            isActive = true
        }
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
        releasePlayer()
        if (!requestFocus()) return

        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setDataSource(this@PlaybackService, track.uri)
            setOnCompletionListener {
                // Repeat ONE stays, ALL wraps, OFF stops — all decided by the
                // queue, never re-derived here.
                if (queue.advance()) startTrack() else stopPlayback()
            }
            setOnPreparedListener { prepared ->
                prepared.setVolume(volume.gain, volume.gain)
                prepared.start()
                queue.setPlaying(true)
                updateSession()
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            prepareAsync()
        }
        registerNoisy()
    }

    private fun play() {
        val active = player
        if (active == null) {
            startTrack()
            return
        }
        if (!requestFocus()) return
        active.start()
        queue.setPlaying(true)
        updateSession()
    }

    private fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        queue.setPlaying(false)
        updateSession()
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
        val active = player ?: return
        val clamped = SeekContract.clamp(positionMillis, active.duration.toLong())
        active.seekTo(clamped.toInt())
        queue.setPosition(clamped)
        updateSession()
    }

    fun applyVolume() {
        player?.setVolume(volume.gain, volume.gain)
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
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
        abandonFocus()
        unregisterNoisy()
    }

    // ----- Audio focus ------------------------------------------------------

    private fun requestFocus(): Boolean {
        val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        return if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener { change -> onFocusChange(change) }
                .build()
            focusRequest = request
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                { change -> onFocusChange(change) },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                player?.setVolume(volume.gain * DUCK, volume.gain * DUCK)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(volume.gain, volume.gain)
                if (queue.state.value.isPlaying) player?.start()
            }
        }
    }

    private fun abandonFocus() {
        val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        registerReceiver(becomingNoisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { unregisterReceiver(becomingNoisy) }
        noisyRegistered = false
    }

    // ----- Session and notification ----------------------------------------

    private fun updateSession() {
        val active = player ?: return
        val state = if (queue.state.value.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        session?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO,
                )
                .setState(state, active.currentPosition.toLong(), 1f)
                .build(),
        )
        queue.setPosition(active.currentPosition.toLong())
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
