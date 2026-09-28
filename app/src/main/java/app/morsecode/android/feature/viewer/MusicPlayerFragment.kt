package app.morsecode.android.feature.viewer

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.morsecode.android.R
import app.morsecode.android.core.media.PlaybackQueue
import app.morsecode.android.core.media.PlaybackService
import app.morsecode.android.core.media.SeekContract
import app.morsecode.android.core.model.MediaItem
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.SeekBarView
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.TypeTileView
import app.morsecode.android.core.ui.FileKind
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * §6.11 MUSIC — the Now-Playing screen.
 *
 * §5.2 [CHANGED]: this is a full destination that KEEPS the bottom nav, with
 * the launching tab still highlighted. Only the viewer and the video player
 * are immersive.
 *
 * Playback itself lives in [PlaybackService]; this screen renders
 * [PlaybackQueue] and sends commands. That is what makes "continues across tab
 * changes, app minimise and screen off" true rather than aspirational — there
 * is no player object here to lose.
 */
class MusicPlayerFragment : Screen() {

    override val navTab = null

    private lateinit var artwork: TypeTileView
    private lateinit var title: AppCompatTextView
    private lateinit var subtitle: AppCompatTextView
    private lateinit var seekBar: SeekBarView
    private lateinit var elapsed: AppCompatTextView
    private lateinit var remaining: AppCompatTextView
    private lateinit var playButton: AppCompatImageView
    private lateinit var likedButton: AppCompatTextView
    private lateinit var upNextHeader: AppCompatTextView
    private lateinit var upNextList: LinearLayout

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()
        toolbar.bind(getString(R.string.title_now_playing)) { nav().pop() }

        artwork = TypeTileView(context)
        artwork.bind(FileKind.AUDIO)
        artwork.contentDescription = getString(R.string.cd_artwork)
        val artSize = Shapes.dpInt(context, 220f)
        val artParams = LinearLayout.LayoutParams(artSize, artSize)
        artParams.gravity = Gravity.CENTER_HORIZONTAL
        artParams.topMargin = Shapes.dpInt(context, 8f)
        column.addView(artwork, artParams)

        title = AppCompatTextView(context)
        title.setTextAppearance(context, R.style.TextAppearance_Morsecode_ScreenTitle)
        title.gravity = Gravity.CENTER
        column.addView(title, wide(16))

        subtitle = AppCompatTextView(context)
        subtitle.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        subtitle.gravity = Gravity.CENTER
        column.addView(subtitle, wide(4))

        seekBar = SeekBarView(context)
        // Live while dragging: the labels follow the thumb (§6.11).
        seekBar.onScrub = { position -> renderClock(position, seekBar.durationMillis) }
        seekBar.onSeek = { position ->
            AppServices.playbackQueue.setPosition(position)
            PlaybackService.start(context, PlaybackService.ACTION_PLAY)
            seekPlayback(position)
        }
        column.addView(seekBar, wide(16))

        val clocks = LinearLayout(context)
        clocks.orientation = LinearLayout.HORIZONTAL
        elapsed = AppCompatTextView(context)
        elapsed.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        clocks.addView(elapsed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        remaining = AppCompatTextView(context)
        remaining.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        remaining.gravity = Gravity.END
        clocks.addView(remaining)
        column.addView(clocks, wide(4))

        column.addView(controlRow(context), wide(12))
        column.addView(secondRow(context), wide(12))

        upNextHeader = AppCompatTextView(context)
        upNextHeader.setTextAppearance(context, R.style.TextAppearance_Morsecode_SectionHeader)
        column.addView(upNextHeader, wide(16))

        upNextList = LinearLayout(context)
        upNextList.orientation = LinearLayout.VERTICAL
        column.addView(upNextList, wide(4))

        observe()
    }

    private fun controlRow(context: android.content.Context): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER

        row.addView(circle(context, R.drawable.ic_refresh, R.string.player_shuffle) {
            AppServices.playbackQueue.toggleShuffle()
        })
        row.addView(circle(context, R.drawable.ic_back, R.string.player_previous) {
            PlaybackService.start(context, PlaybackService.ACTION_PREVIOUS)
        })

        playButton = AppCompatImageView(context)
        playButton.setImageResource(R.drawable.ic_send)
        playButton.contentDescription = getString(R.string.player_play)
        val pad = Shapes.dpInt(context, 16f)
        playButton.setPadding(pad, pad, pad, pad)
        playButton.background = Shapes.circle(ThemeColors.accent(context))
        androidx.core.widget.ImageViewCompat.setImageTintList(
            playButton,
            android.content.res.ColorStateList.valueOf(
                ThemeColors.resolve(context, R.attr.colorInkOnAccent),
            ),
        )
        playButton.setOnClickListener { togglePlayPause() }
        val playSize = Shapes.dpInt(context, 56f)
        val playParams = LinearLayout.LayoutParams(playSize, playSize)
        playParams.marginStart = Shapes.dpInt(context, 12f)
        playParams.marginEnd = playParams.marginStart
        row.addView(playButton, playParams)

        row.addView(circle(context, R.drawable.ic_chevron_right, R.string.player_next) {
            PlaybackService.start(context, PlaybackService.ACTION_NEXT)
        })
        row.addView(circle(context, R.drawable.ic_refresh, R.string.player_repeat) {
            AppServices.playbackQueue.cycleRepeat()
        })
        return row
    }

    /** §6.11 second row: Save · Share · Liked · Queue, all on-device. */
    private fun secondRow(context: android.content.Context): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER

        row.addView(textAction(context, getString(R.string.player_save)) {
            val track = current() ?: return@textAction
            AppServices.playbackPrefs.save(keyOf(track))
            Ui.snackbar(requireActivity(), getString(R.string.player_saved_toast))
        })
        row.addView(textAction(context, getString(R.string.player_share)) { shareCurrent() })

        likedButton = textAction(context, getString(R.string.player_liked)) {
            val track = current() ?: return@textAction
            AppServices.playbackPrefs.toggleLiked(keyOf(track))
            renderLiked(track)
        }
        row.addView(likedButton)

        row.addView(textAction(context, getString(R.string.player_queue)) {
            NowPlayingQueueSheet().show(parentFragmentManager, "now-playing-queue")
        })
        return row
    }

    // ----- State ------------------------------------------------------------

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppServices.playbackQueue.state.collect { render(it) }
            }
        }
        // §6.11.0: a corrupt/unsupported item must be explained and skipped,
        // not silently stop the player or crash its service.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppServices.playbackQueue.failure.collect { failure ->
                    if (failure == null) return@collect
                    Ui.snackbar(requireActivity(), getString(R.string.player_cant_play, failure.trackName))
                    AppServices.playbackQueue.acknowledgeFailure(failure.id)
                }
            }
        }
        // The clock drives the bar only while nobody is dragging it (§6.11).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(CLOCK_TICK_MS)
                    val state = AppServices.playbackQueue.state.value
                    if (state.isPlaying) {
                        seekBar.positionMillis = state.positionMillis
                        renderClock(seekBar.displayedPosition(), seekBar.durationMillis)
                    }
                }
            }
        }
    }

    private fun render(state: PlaybackQueue.State) {
        val track = state.current
        title.text = track?.name ?: getString(R.string.files_empty_music)
        subtitle.text = track?.artist ?: getString(R.string.files_unknown_artist)
        seekBar.durationMillis = track?.durationMillis ?: 0
        seekBar.positionMillis = state.positionMillis
        renderClock(seekBar.displayedPosition(), seekBar.durationMillis)
        playButton.contentDescription =
            getString(if (state.isPlaying) R.string.player_pause else R.string.player_play)
        track?.let { renderLiked(it) }

        upNextHeader.text = if (state.upNext.isEmpty()) {
            getString(R.string.player_up_next_empty)
        } else {
            getString(R.string.player_up_next, state.upNext.size)
        }
        upNextList.removeAllViews()
        for ((offset, next) in state.upNext.withIndex()) {
            upNextList.addView(upNextRow(next, state.index + 1 + offset))
        }
    }

    private fun renderClock(position: Long, duration: Long) {
        elapsed.text = SeekContract.elapsedLabel(position)
        remaining.text = SeekContract.remainingLabel(position, duration)
    }

    private fun renderLiked(track: MediaItem) {
        val liked = AppServices.playbackPrefs.isLiked(keyOf(track))
        likedButton.setTextColor(
            if (liked) {
                ThemeColors.accent(requireContext())
            } else {
                ThemeColors.resolve(requireContext(), R.attr.colorTextSecondary)
            },
        )
    }

    private fun upNextRow(track: MediaItem, index: Int): View {
        val context = requireContext()
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = Shapes.dpInt(context, 48f)
        row.isClickable = true
        row.setOnClickListener {
            AppServices.playbackQueue.moveTo(index)
            PlaybackService.start(context)
        }

        val tile = TypeTileView(context)
        tile.bind(FileKind.AUDIO)
        val tileSize = Shapes.dpInt(context, 40f)
        row.addView(tile, LinearLayout.LayoutParams(tileSize, tileSize))

        val name = AppCompatTextView(context)
        name.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        name.text = track.name
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        params.marginStart = Shapes.dpInt(context, 12f)
        row.addView(name, params)
        row.contentDescription = track.name
        return row
    }

    // ----- Commands ---------------------------------------------------------

    private fun togglePlayPause() {
        val playing = AppServices.playbackQueue.state.value.isPlaying
        PlaybackService.start(
            requireContext(),
            if (playing) PlaybackService.ACTION_PAUSE else PlaybackService.ACTION_PLAY,
        )
    }

    private fun seekPlayback(position: Long) {
        // The service owns the player; the queue carries the position it should
        // resume from, so there is one source of truth either way (§20.1).
        AppServices.playbackQueue.setPosition(position)
    }

    private fun shareCurrent() {
        val track = current() ?: return
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = track.mimeType ?: "audio/*"
        intent.putExtra(Intent.EXTRA_STREAM, track.uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.player_share)))
    }

    private fun current(): MediaItem? = AppServices.playbackQueue.state.value.current

    private fun keyOf(track: MediaItem): String = track.uri.toString()

    private fun circle(
        context: android.content.Context,
        iconRes: Int,
        labelRes: Int,
        onClick: () -> Unit,
    ): View {
        val button = AppCompatImageView(context)
        button.setImageResource(iconRes)
        button.contentDescription = getString(labelRes)
        val pad = Shapes.dpInt(context, 12f)
        button.setPadding(pad, pad, pad, pad)
        androidx.core.widget.ImageViewCompat.setImageTintList(
            button,
            android.content.res.ColorStateList.valueOf(
                ThemeColors.resolve(context, R.attr.colorTextPrimary),
            ),
        )
        button.setOnClickListener { onClick() }
        val size = Shapes.dpInt(context, 48f)
        button.layoutParams = LinearLayout.LayoutParams(size, size)
        return button
    }

    private fun textAction(
        context: android.content.Context,
        label: String,
        onClick: () -> Unit,
    ): AppCompatTextView {
        val view = AppCompatTextView(context)
        view.setTextAppearance(context, R.style.TextAppearance_Morsecode_Button)
        view.text = label
        view.contentDescription = label
        view.gravity = Gravity.CENTER
        view.minHeight = Shapes.dpInt(context, 48f)
        val pad = Shapes.dpInt(context, 12f)
        view.setPadding(pad, pad, pad, pad)
        view.isClickable = true
        view.setOnClickListener { onClick() }
        return view
    }

    private fun wide(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        return params
    }

    private companion object {
        const val CLOCK_TICK_MS = 500L
    }
}
