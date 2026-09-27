package app.morsecode.android.feature.viewer

import android.widget.LinearLayout
import app.morsecode.android.R
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Shapes

/**
 * §6.11 MUSIC — the Now-Playing screen.
 *
 * §5.2 [CHANGED]: Now-Playing is a full destination that KEEPS the bottom nav
 * visible, with the launching tab still highlighted. Only the photo viewer and
 * the video player are immersive, and those are separate activities.
 *
 * The art, the draggable seek bar, UP NEXT and the MediaSession service are
 * Stage 11; this stage owns the route and the nav behaviour.
 */
class MusicPlayerFragment : Screen() {

    override val navTab = null

    override fun onBuildScreen(column: LinearLayout) {
        toolbar.bind(getString(R.string.title_now_playing)) { nav().pop() }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), 8f)
        column.addView(
            emptyState(R.drawable.ic_type_audio, getString(R.string.stub_screen)),
            params,
        )
    }
}
