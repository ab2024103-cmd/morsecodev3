package app.morsecode.android.feature.transfer

import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import app.morsecode.android.R
import app.morsecode.android.core.ui.Nav
import app.morsecode.android.core.ui.Purpose
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.SummaryCardView
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.di.AppServices

/**
 * §6.8.2 BROADCAST · SENDER — the hub view, one row per receiving phone.
 *
 * Like the transfer screen it keeps the bottom nav (§5.2) and treats Back as
 * Minimize (§5.3). Its purpose is explicit and is always BROADCAST.
 *
 * Stage 3 renders the chrome and the empty state; the per-peer rows, the
 * §10.4 stat tiles and the INV-B4 degraded header arrive with the broadcast
 * engine.
 */
class BroadcastFragment : Screen() {

    override val navTab = null

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()
        val purpose = Nav.purposeOf(this)
        check(purpose == Purpose.BROADCAST) { "BroadcastFragment only serves the BROADCAST purpose" }

        toolbar.bind(getString(R.string.title_broadcast)) { minimize() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = minimize()
            },
        )

        // The totals card exists from the start so the layout does not jump
        // when the first peer connects; its tiles are filled by §10.4.
        val summary = SummaryCardView(context)
        summary.bind(getString(R.string.summary_batch_in_progress), emptyList(), emptyList())
        column.addView(summary, params(8))

        column.addView(
            emptyState(
                R.drawable.ic_broadcast,
                getString(R.string.broadcast_empty),
                getString(R.string.broadcast_empty_action),
            ) { Ui.snackbar(requireActivity(), getString(R.string.stub_screen)) },
            params(8),
        )
    }

    private fun minimize() {
        AppServices.logStore.i("Broadcast minimized")
        Ui.snackbar(requireActivity(), getString(R.string.transfer_minimized))
        nav().pop()
    }

    private fun params(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        return params
    }

    companion object {
        fun newInstance(): BroadcastFragment {
            val fragment = BroadcastFragment()
            fragment.arguments = Nav.args(Purpose.BROADCAST)
            return fragment
        }
    }
}
