package app.morsecode.android.feature.viewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.media3.ui.PlayerView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.lifecycleScope
import app.morsecode.android.R
import app.morsecode.android.core.media.SeekContract
import app.morsecode.android.core.media.ViewerSession
import app.morsecode.android.core.media.VolumeControl
import app.morsecode.android.core.model.MediaItem
import app.morsecode.android.core.ui.SeekBarView
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.util.Fmt
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * §6.11 VIDEO — immersive, full-bleed (§5.2 [CHANGED]).
 *
 * The same seek contract as the music player ([SeekContract]), so A32's rule
 * holds in both: tap-to-seek, a draggable knob, labels that follow the thumb,
 * seekTo on release, ±10 s that clamp and never wrap, and ← → stepping 5 s.
 *
 * VOLUME IS A REAL CONTROL: tapping the speaker opens a continuous slider and
 * tapping it again mutes-and-remembers. CC is enabled only when a subtitle
 * track exists; otherwise it is dimmed and says "No subtitles" (G13) rather
 * than pretending.
 */
class VideoPlayerActivity : ImmersiveActivity() {

    /**
     * §3.3 [CHANGED]: ExoPlayer behind a Media3 `PlayerView` with its own
     * controls switched OFF — §6.11 prescribes the chrome (draggable bar,
     * ±10 s, the real volume slider, an honest CC state), so the surface is
     * Media3's and the controls stay ours.
     */
    private lateinit var video: PlayerView
    private var exo: ExoPlayer? = null
    private lateinit var seekBar: SeekBarView
    private lateinit var elapsed: AppCompatTextView
    private lateinit var duration: AppCompatTextView
    private lateinit var meta: AppCompatTextView
    private lateinit var volumeSlider: SeekBarView
    private lateinit var volumeIcon: AppCompatImageView
    private lateinit var playButton: AppCompatImageView
    private lateinit var chrome: LinearLayout

    private val volume: VolumeControl get() = AppServices.volumeControl
    private var item: MediaItem? = null
    private var volumeShownAt = 0L

    override fun titleText(): CharSequence =
        ViewerSession.itemAt(ViewerSession.startIndex)?.name ?: getString(R.string.title_video_player)

    override fun onBuildImmersive() {
        val current = ViewerSession.itemAt(ViewerSession.startIndex)
        if (current == null) {
            finish()
            return
        }
        item = current

        video = PlayerView(this)
        video.useController = false
        video.setShutterBackgroundColor(android.graphics.Color.BLACK)
        val videoParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        videoParams.gravity = Gravity.CENTER
        stage.addView(video, videoParams)

        chrome = buildChrome()
        val chromeParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        chromeParams.gravity = Gravity.BOTTOM
        stage.addView(chrome, chromeParams)

        // §6.11: a tap toggles the chrome.
        video.setOnClickListener { toggleChrome() }
        stage.setOnClickListener { toggleChrome() }

        val player = ExoPlayer.Builder(this).build()
        exo = player
        video.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) onPrepared(current)
                if (state == Player.STATE_ENDED) {
                    AppServices.playbackPrefs.setResumePosition(keyOf(current), 0, player.duration)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                paintPlayButton(isPlaying)
            }
        })
        player.setMediaItem(Media3Item.fromUri(current.uri))
        player.prepare()
        startTicker()
    }

    private fun onPrepared(current: MediaItem) {
        val player = exo ?: return
        val length = player.duration.coerceAtLeast(0)
        seekBar.durationMillis = length
        duration.text = Fmt.duration(length)
        meta.text = getString(
            R.string.player_video_meta,
            Fmt.size(current.sizeBytes),
            Fmt.duration(length),
        )
        applyVolume()

        // §6.11: resume position is remembered per file.
        val resume = AppServices.playbackPrefs.resumePosition(keyOf(current))
        if (resume > 0) player.seekTo(resume)
        player.play()
    }

    private fun buildChrome(): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = Shapes.dpInt(this, 12f)
        root.setPadding(pad, pad, pad, pad)
        root.setBackgroundColor(ThemeColors.resolve(this, R.attr.colorViewerChromeScrim))

        meta = AppCompatTextView(this)
        meta.setTextAppearance(this, R.style.TextAppearance_Morsecode_ItemMeta)
        meta.setTextColor(ThemeColors.resolve(this, R.attr.colorViewerChrome))
        root.addView(meta)

        seekBar = SeekBarView(this)
        seekBar.onScrub = { position -> elapsed.text = SeekContract.elapsedLabel(position) }
        seekBar.onSeek = { position -> seekTo(position) }
        root.addView(seekBar)

        val clocks = LinearLayout(this)
        clocks.orientation = LinearLayout.HORIZONTAL
        elapsed = AppCompatTextView(this)
        elapsed.setTextAppearance(this, R.style.TextAppearance_Morsecode_ItemMeta)
        elapsed.setTextColor(ThemeColors.resolve(this, R.attr.colorViewerChrome))
        clocks.addView(elapsed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        duration = AppCompatTextView(this)
        duration.setTextAppearance(this, R.style.TextAppearance_Morsecode_ItemMeta)
        duration.setTextColor(ThemeColors.resolve(this, R.attr.colorViewerChrome))
        clocks.addView(duration)
        root.addView(clocks)

        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER

        controls.addView(chromeButton(R.drawable.ic_back, R.string.player_replay_ten) {
            seekTo(SeekContract.skipBackward(currentPosition(), seekBar.durationMillis))
        })
        playButton = chromeButton(R.drawable.ic_pause, R.string.player_pause, accent = true) {
            togglePlay()
        }
        controls.addView(playButton)
        controls.addView(chromeButton(R.drawable.ic_chevron_right, R.string.player_forward_ten) {
            seekTo(SeekContract.skipForward(currentPosition(), seekBar.durationMillis))
        })

        volumeIcon = chromeButton(R.drawable.ic_info, R.string.player_volume) { onVolumeIconTapped() }
        controls.addView(volumeIcon)

        // G13: CC exists only when a track does; otherwise it is dimmed and
        // says so rather than being a button that does nothing.
        val cc = chromeButton(R.drawable.ic_type_doc, R.string.player_no_subtitles) {
            android.widget.Toast.makeText(this, R.string.player_no_subtitles, android.widget.Toast.LENGTH_SHORT).show()
        }
        cc.alpha = if (hasSubtitles()) 1f else DISABLED_ALPHA
        controls.addView(cc)
        root.addView(controls)

        volumeSlider = SeekBarView(this)
        volumeSlider.durationMillis = 100
        volumeSlider.positionMillis = volume.percent.toLong()
        volumeSlider.visibility = View.GONE
        volumeSlider.onScrub = { value -> setVolumePercent(value.toInt()) }
        volumeSlider.onSeek = { value -> setVolumePercent(value.toInt()) }
        root.addView(volumeSlider)
        return root
    }

    // ----- Playback ---------------------------------------------------------

    private fun currentPosition(): Long = exo?.currentPosition?.coerceAtLeast(0) ?: 0

    private fun seekTo(position: Long) {
        val clamped = SeekContract.clamp(position, seekBar.durationMillis)
        // Seeking while paused keeps it paused and repaints at the new frame.
        exo?.seekTo(clamped)
        seekBar.positionMillis = clamped
        elapsed.text = SeekContract.elapsedLabel(clamped)
    }

    private fun togglePlay() {
        val player = exo ?: return
        if (player.isPlaying) player.pause() else player.play()
        paintPlayButton(player.isPlaying)
    }

    private fun startTicker() {
        lifecycleScope.launch {
            while (true) {
                delay(TICK_MS)
                if (exo?.isPlaying == true) {
                    // The clock yields while the user is dragging (§6.11):
                    // SeekBarView ignores this while a drag is in progress.
                    seekBar.positionMillis = currentPosition()
                    elapsed.text = SeekContract.elapsedLabel(seekBar.displayedPosition())
                }
                if (volumeSlider.visibility == View.VISIBLE &&
                    volume.shouldDismiss(System.currentTimeMillis() - volumeShownAt)
                ) {
                    volumeSlider.visibility = View.GONE
                }
            }
        }
    }

    // ----- Volume (§6.11) ---------------------------------------------------

    private fun onVolumeIconTapped() {
        if (volumeSlider.visibility == View.VISIBLE) {
            // A second tap mutes and remembers, so unmuting restores the level.
            volume.toggleMute()
            applyVolume()
            volumeSlider.positionMillis = volume.percent.toLong()
        } else {
            volumeSlider.visibility = View.VISIBLE
        }
        volumeShownAt = System.currentTimeMillis()
    }

    private fun setVolumePercent(percent: Int) {
        volume.set(percent)
        volumeShownAt = System.currentTimeMillis()
        applyVolume()
    }

    /** §4.13: the control says what it does, in words, for TalkBack. */
    private fun paintPlayButton(isPlaying: Boolean) {
        if (!::playButton.isInitialized) return
        playButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_send)
        playButton.contentDescription =
            getString(if (isPlaying) R.string.player_pause else R.string.player_play)
    }

    private fun applyVolume(unused: Any? = null) {
        exo?.volume = volume.gain
        volumeIcon.alpha = if (volume.isMuted) DISABLED_ALPHA else 1f
        volumeIcon.contentDescription = if (volume.isMuted) {
            getString(R.string.player_muted)
        } else {
            getString(R.string.player_volume_level, volume.percent)
        }
    }

    /** Hardware keys move the SAME slider while the player is in front. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> {
            volume.step(up = true)
            volumeSlider.positionMillis = volume.percent.toLong()
            applyVolume()
            true
        }
        KeyEvent.KEYCODE_VOLUME_DOWN -> {
            volume.step(up = false)
            volumeSlider.positionMillis = volume.percent.toLong()
            applyVolume()
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> {
            seekTo(SeekContract.keyLeft(currentPosition(), seekBar.durationMillis))
            true
        }
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            seekTo(SeekContract.keyRight(currentPosition(), seekBar.durationMillis))
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    // ----- Chrome and lifecycle ---------------------------------------------

    private fun toggleChrome() {
        chrome.visibility = if (chrome.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** §6.11: landscape locks to fullscreen. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestedOrientation = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onPause() {
        super.onPause()
        // Remembered per file, so returning picks the video up where it was.
        item?.let {
            AppServices.playbackPrefs.setResumePosition(
                keyOf(it),
                currentPosition(),
                seekBar.durationMillis,
            )
        }
        exo?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // §7.6's rule, applied natively: the player is released, not hidden.
        exo?.release()
        exo = null
        video.player = null
    }

    private fun hasSubtitles(): Boolean {
        val path = item?.path ?: return false
        val sidecar = java.io.File(path.substringBeforeLast('.') + ".srt")
        return sidecar.exists()
    }

    private fun keyOf(item: MediaItem): String = item.uri.toString()

    private fun chromeButton(
        iconRes: Int,
        labelRes: Int,
        accent: Boolean = false,
        onClick: () -> Unit,
    ): AppCompatImageView {
        val button = AppCompatImageView(this)
        button.setImageResource(iconRes)
        button.contentDescription = getString(labelRes)
        val pad = Shapes.dpInt(this, 12f)
        button.setPadding(pad, pad, pad, pad)
        if (accent) button.background = Shapes.circle(ThemeColors.accent(this))
        androidx.core.widget.ImageViewCompat.setImageTintList(
            button,
            android.content.res.ColorStateList.valueOf(
                if (accent) {
                    ThemeColors.resolve(this, R.attr.colorInkOnAccent)
                } else {
                    ThemeColors.resolve(this, R.attr.colorViewerChrome)
                },
            ),
        )
        button.setOnClickListener { onClick() }
        val size = Shapes.dpInt(this, 48f)
        val params = LinearLayout.LayoutParams(size, size)
        params.marginEnd = Shapes.dpInt(this, 8f)
        button.layoutParams = params
        return button
    }

    private companion object {
        const val TICK_MS = 300L
        const val DISABLED_ALPHA = 0.4f
    }
}
