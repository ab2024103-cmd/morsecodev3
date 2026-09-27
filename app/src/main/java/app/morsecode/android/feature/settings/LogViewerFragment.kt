package app.morsecode.android.feature.settings

import android.widget.LinearLayout
import app.morsecode.android.R
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Ui

/**
 * §6.14 LOG VIEWER. Toolbar back · "Logs" · trash, then the chip row
 * [Export .txt] · [Errors only] · [Clear].
 *
 * §6.14 is explicit that Export fires the SYSTEM share sheet rather than
 * writing a file silently — a log nobody can send is useless. That wiring, the
 * monospace rows and the tail-following scroll arrive with Stage 12, together
 * with the ring buffer's read side; this stage owns the route and the chrome.
 */
class LogViewerFragment : Screen() {

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()

        toolbar.bind(getString(R.string.title_logs)) { nav().pop() }
        toolbar.addAction(R.drawable.ic_trash, R.string.cd_clear_all) { stub() }

        val chips = LinearLayout(context)
        chips.orientation = LinearLayout.HORIZONTAL
        val export = Buttons.accent(context, getString(R.string.logs_export)) { stub() }
        val errorsOnly = Buttons.outlined(context, getString(R.string.logs_errors_only)) { stub() }
        val clear = Buttons.outlined(context, getString(R.string.logs_clear)) { stub() }
        chips.addView(export, cell(context, first = true))
        chips.addView(errorsOnly, cell(context, first = false))
        chips.addView(clear, cell(context, first = false))
        column.addView(chips, params(8))

        column.addView(emptyState(R.drawable.ic_logs, getString(R.string.logs_empty)), params(8))
    }

    private fun stub() = Ui.snackbar(requireActivity(), getString(R.string.stub_screen))

    private fun cell(context: android.content.Context, first: Boolean): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        if (!first) params.leftMargin = Shapes.dpInt(context, 8f)
        return params
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
