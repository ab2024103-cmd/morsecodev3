package app.morsecode.android.feature.onboarding

import android.widget.LinearLayout
import app.morsecode.android.R
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.di.AppServices

/**
 * §6.1 ONBOARDING — four full-bleed slides with a dot indicator, replayable
 * from Settings → About → Replay onboarding.
 *
 * The four slides, their squircle icons and the permission request on slide 2
 * are Stage 8; this stage owns the route and the replay entry point, and the
 * "seen" flag that decides whether first launch shows it.
 *
 * §6.1: skipping is always allowed, and slide 2 never requests
 * MANAGE_EXTERNAL_STORAGE or battery exemption silently.
 */
class OnboardingFragment : Screen() {

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()

        toolbar.bind(getString(R.string.title_onboarding)) { nav().pop() }

        column.addView(
            emptyState(R.drawable.ic_radar, getString(R.string.stub_screen)),
            params(8),
        )

        column.addView(
            Buttons.accent(context, getString(R.string.onboarding_open_app)) {
                AppServices.prefs.onboardingSeen = true
                nav().pop()
            },
            params(12),
        )
        column.addView(
            Buttons.outlined(context, getString(R.string.onboarding_skip)) { nav().pop() },
            params(8),
        )
    }

    private fun params(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        return params
    }
}
