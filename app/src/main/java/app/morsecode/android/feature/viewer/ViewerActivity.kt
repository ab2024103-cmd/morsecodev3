package app.morsecode.android.feature.viewer

import android.widget.FrameLayout
import app.morsecode.android.R
import app.morsecode.android.core.ui.EmptyStateView

/**
 * §6.10 VIEWER — full-screen image, immersive (§5.2 [CHANGED]).
 *
 * The pager, the 1:1 drag with neighbours coming in from the edges, the ~22 %
 * commit threshold, pinch-zoom, the page dots and the five bottom actions
 * (Share · Edit · Delete · Info + the accent SEND FAB that really sends) are
 * Stage 10. This stage owns the immersive frame and the fact that the bottom
 * navigation is structurally absent here.
 */
class ViewerActivity : ImmersiveActivity() {

    override fun titleText(): CharSequence = getString(R.string.title_viewer)

    override fun onBuildImmersive() {
        val placeholder = EmptyStateView(this)
        placeholder.bind(R.drawable.ic_type_image, getString(R.string.stub_screen))
        stage.addView(
            placeholder,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }
}
