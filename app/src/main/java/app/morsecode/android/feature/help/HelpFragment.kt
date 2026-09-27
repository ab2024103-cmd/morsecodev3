package app.morsecode.android.feature.help

import android.widget.LinearLayout
import app.morsecode.android.R
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.feature.settings.ConnectionDoctorFragment

/**
 * §6.17 HELP & FAQ. The accordion, the five questions and the searchable
 * troubleshooting articles arrive with Stage 12.
 *
 * §6.17 requires every article to end in a real action button; the first of
 * those routes — "→ Connection Doctor" — is live from this stage, so the empty
 * state is useful rather than decorative (§6.19).
 */
class HelpFragment : Screen() {

    override fun onBuildScreen(column: LinearLayout) {
        toolbar.bind(getString(R.string.title_help)) { nav().pop() }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), 8f)
        column.addView(
            emptyState(
                R.drawable.ic_help,
                getString(R.string.help_empty),
                getString(R.string.help_open_doctor),
            ) { nav().push(ConnectionDoctorFragment()) },
            params,
        )
    }
}
