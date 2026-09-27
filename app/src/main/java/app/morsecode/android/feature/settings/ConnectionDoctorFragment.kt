package app.morsecode.android.feature.settings

import android.widget.LinearLayout
import app.morsecode.android.R
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Ui

/**
 * §6.15 CONNECTION DOCTOR. Title, the vertical list of checks, and the footer
 * [✓ Request battery exemption] · [⟳ Refresh checks].
 *
 * Reachable from the dashboard "?", Settings → Diagnostics, every Help
 * article and the 15-second discovery timeout — all four routes exist from
 * this stage.
 *
 * §20.6 honest capability: the checks are not faked. Until Stage 16 wires the
 * real Wi-Fi, multicast, Play Services, permission, port and battery probes,
 * the screen says the checks run when opened and shows none, rather than
 * printing green ticks it did not earn.
 */
class ConnectionDoctorFragment : Screen() {

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()

        toolbar.bind(getString(R.string.title_connection_doctor)) { nav().pop() }

        column.addView(emptyState(R.drawable.ic_doctor, getString(R.string.doctor_empty)), params(8))

        column.addView(
            Buttons.wash(context, getString(R.string.doctor_request_battery)) { stub() },
            params(16),
        )
        column.addView(
            Buttons.outlined(context, getString(R.string.doctor_refresh)) { stub() },
            params(8),
        )
    }

    private fun stub() = Ui.snackbar(requireActivity(), getString(R.string.stub_screen))

    private fun params(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        return params
    }
}
