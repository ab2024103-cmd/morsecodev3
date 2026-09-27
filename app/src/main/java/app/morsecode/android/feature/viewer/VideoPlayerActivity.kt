package app.morsecode.android.feature.viewer

import android.widget.FrameLayout
import app.morsecode.android.R
import app.morsecode.android.core.ui.EmptyStateView

/**
 * §6.11 VIDEO PLAYER — immersive, full-bleed (§5.2 [CHANGED]).
 *
 * The surface, the draggable seek bar, the volume control, the CC button with
 * its "No subtitles" disabled state (G13) and rotation-safe playback are
 * Stage 11; this stage owns the immersive frame and the hidden bottom nav.
 */
class VideoPlayerActivity : ImmersiveActivity() {

    override fun titleText(): CharSequence = getString(R.string.title_video_player)

    override fun onBuildImmersive() {
        val placeholder = EmptyStateView(this)
        placeholder.bind(R.drawable.ic_type_video, getString(R.string.stub_screen))
        stage.addView(
            placeholder,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }
}
