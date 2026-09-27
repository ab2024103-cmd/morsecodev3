package app.morsecode.android.feature.history

import android.widget.FrameLayout
import android.widget.LinearLayout
import app.morsecode.android.R
import app.morsecode.android.core.ui.BottomNavView
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.SegmentedControl
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.feature.dashboard.DiscoveryFragment

/**
 * §6.12 HISTORY. Title "History" + search · filter · clear-all, then the
 * full-width [Received | Sent] pill.
 *
 * §6.12 is explicit that the direction filter is part of the same reactive
 * stream that produces the list, never an external flag refreshed by an
 * unrelated change. The control therefore owns the direction and re-renders
 * through one path; when the history store arrives it will feed that same
 * path rather than a second one.
 */
class HistoryFragment : Screen() {

    override val navTab: BottomNavView.Tab = BottomNavView.Tab.HISTORY

    private lateinit var listArea: FrameLayout

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()

        toolbar.bind(getString(R.string.title_history))
        toolbar.addAction(R.drawable.ic_search, R.string.cd_search) { stub() }
        toolbar.addAction(R.drawable.ic_filter, R.string.cd_filter) { stub() }
        toolbar.addAction(R.drawable.ic_trash, R.string.cd_clear_all) { stub() }

        val direction = SegmentedControl(context)
        direction.bind(
            listOf(getString(R.string.history_received), getString(R.string.history_sent)),
        ) { index -> render(index) }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(context, 8f)
        column.addView(direction, params)

        listArea = FrameLayout(context)
        val listParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        listParams.topMargin = Shapes.dpInt(context, 8f)
        column.addView(listArea, listParams)

        render(direction.selectedIndex())
    }

    private fun render(directionIndex: Int) {
        val received = directionIndex == 0
        listArea.removeAllViews()
        listArea.addView(
            emptyState(
                if (received) R.drawable.ic_receive else R.drawable.ic_send,
                getString(
                    if (received) R.string.history_empty_received else R.string.history_empty_sent,
                ),
                getString(R.string.history_empty_action),
            ) { nav().push(DiscoveryFragment.newInstance(multiSelect = false)) },
        )
    }

    private fun stub() = Ui.snackbar(requireActivity(), getString(R.string.stub_screen))
}
