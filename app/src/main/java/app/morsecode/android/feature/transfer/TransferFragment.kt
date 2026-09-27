package app.morsecode.android.feature.transfer

import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import app.morsecode.android.R
import app.morsecode.android.core.ui.ActionBarView
import app.morsecode.android.core.ui.Nav
import app.morsecode.android.core.ui.Purpose
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.di.AppServices

/**
 * §6.4 TRANSFER · SENDING, §6.5 RECEIVE · LISTENING and §6.6 the two-way view.
 *
 * The purpose is an explicit argument (§5.4, §20.4) — SENDER, RECEIVER or
 * RESUME. Nothing here asks "is something queued right now"; the same screen
 * renders different chrome because it was TOLD which job it is doing.
 *
 * §5.2: this destination KEEPS the bottom nav and leaves the launching tab
 * highlighted, which is why the mocks show "Files" lit during a transfer.
 *
 * §5.3: Back is Minimize — the session survives in the service and the screen
 * returns to where it came from. End asks first, then closes cleanly.
 */
class TransferFragment : Screen() {

    // Null: keep the launching tab lit (§5.2).
    override val navTab = null

    private val purpose: Purpose get() = Nav.purposeOf(this)

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()

        val title = when (purpose) {
            Purpose.RECEIVER -> R.string.title_receive
            else -> R.string.title_transfer
        }
        toolbar.bind(getString(title)) { minimize() }

        // §5.3: Back on a transfer screen is Minimize, never a silent kill.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = minimize()
            },
        )

        val emptyLine = when (purpose) {
            Purpose.RECEIVER -> getString(R.string.transfer_listening)
            else -> getString(R.string.transfer_empty_queue)
        }
        val emptyAction = when (purpose) {
            Purpose.RECEIVER -> null
            else -> getString(R.string.transfer_empty_action)
        }
        val emptyIcon = when (purpose) {
            Purpose.RECEIVER -> R.drawable.ic_receive
            else -> R.drawable.ic_send
        }
        column.addView(
            emptyState(emptyIcon, emptyLine, emptyAction) {
                Ui.snackbar(requireActivity(), getString(R.string.stub_screen))
            },
            params(0),
        )

        // §4.12e action bar: Choose · Queue · Pause · Minimize · End, labels
        // always visible. Minimize and End are real from this stage.
        val actions = ActionBarView(context)
        actions.setOnAction(ActionBarView.Action.CHOOSE) { stub() }
        actions.setOnAction(ActionBarView.Action.QUEUE) { stub() }
        actions.setOnAction(ActionBarView.Action.PAUSE) { stub() }
        actions.setOnAction(ActionBarView.Action.MINIMIZE) { minimize() }
        actions.setOnAction(ActionBarView.Action.END) { confirmEnd() }
        column.addView(actions, params(16))
    }

    private fun stub() = Ui.snackbar(requireActivity(), getString(R.string.stub_screen))

    /** Minimize = back: the session survives, the screen does not (§5.3). */
    private fun minimize() {
        AppServices.logStore.i("Transfer minimized · purpose=${purpose.name}")
        Ui.snackbar(requireActivity(), getString(R.string.transfer_minimized))
        nav().pop()
    }

    /** §5.3 End: confirm, then a clean BYE to every peer (wired with the engine). */
    private fun confirmEnd() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.transfer_end_title)
            .setMessage(R.string.transfer_end_body)
            .setNegativeButton(R.string.transfer_end_cancel, null)
            .setPositiveButton(R.string.transfer_end_confirm) { _, _ ->
                AppServices.logStore.i("Session ended by user · purpose=${purpose.name}")
                nav().pop()
            }
            .show()
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
        fun newInstance(purpose: Purpose): TransferFragment {
            val fragment = TransferFragment()
            fragment.arguments = Nav.args(purpose)
            return fragment
        }
    }
}
