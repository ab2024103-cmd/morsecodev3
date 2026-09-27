package app.morsecode.android.feature.dashboard

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Nav
import app.morsecode.android.core.ui.Purpose
import app.morsecode.android.core.ui.RadarView
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.SectionHeaderView
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.feature.settings.ConnectionDoctorFragment

/**
 * §6.3 SEND · DISCOVERY. Toolbar back · "Send files" · overflow, radar with
 * its two captions, the mono TRANSPORT / QUEUE rows, "DISCOVERED" with a
 * Refresh action, and ONE [Manual IP] button at the bottom.
 *
 * §11.5 / G12: there is no QR scanner anywhere in the product. Do not add a
 * camera button to this screen.
 *
 * Multi-select is an explicit argument (§5.4), not a mode inferred from
 * whether a broadcast happens to be queued: in multi-select the rows carry
 * checkboxes, the bottom area holds exactly one [Broadcast to (n)] control and
 * Manual IP is hidden, because a broadcast is formed from peers that were
 * actually discovered.
 *
 * Stage 3 has no transport, so the list is honestly empty (§6.19, §20.6); the
 * 15-second empty state's two actions are already wired.
 */
class DiscoveryFragment : Screen() {

    private val multiSelect: Boolean
        get() = arguments?.getBoolean(Nav.ARG_MULTI_SELECT, false) ?: false

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()
        // The purpose is explicit; reading it here also asserts it was passed.
        val purpose = Nav.purposeOf(this)

        toolbar.bind(getString(R.string.title_send_files)) { nav().pop() }
        toolbar.addAction(R.drawable.ic_overflow, R.string.cd_overflow) {
            Ui.snackbar(requireActivity(), getString(R.string.stub_screen))
        }

        val radar = RadarView(context)
        val size = Shapes.dpInt(context, 150f)
        val radarParams = LinearLayout.LayoutParams(size, size)
        radarParams.gravity = Gravity.CENTER_HORIZONTAL
        column.addView(radar, radarParams)

        column.addView(centeredMeta(getString(R.string.discovery_searching)), wide(8))
        column.addView(centeredMeta(getString(R.string.discovery_searching_hint)), wide(2))

        column.addView(
            infoRow(getString(R.string.discovery_transport_label), getString(R.string.discovery_transport_lan)),
            wide(16),
        )
        column.addView(
            infoRow(getString(R.string.discovery_queue_label), getString(R.string.discovery_queue_empty)),
            wide(4),
        )

        val header = SectionHeaderView(context)
        header.bind(getString(R.string.discovery_discovered), getString(R.string.action_refresh)) {
            Ui.snackbar(requireActivity(), getString(R.string.stub_screen))
        }
        column.addView(header, wide(8))

        column.addView(
            emptyState(
                R.drawable.ic_radar,
                getString(R.string.discovery_empty),
                getString(R.string.discovery_empty_action),
            ) { nav().push(ConnectionDoctorFragment()) },
            wide(0),
        )

        column.addView(
            Buttons.outlined(context, getString(R.string.discovery_try_nearby)) {
                Ui.snackbar(requireActivity(), getString(R.string.stub_screen))
            },
            wide(8),
        )

        // Exactly one bottom control, and which one depends on the argument.
        val bottom = if (multiSelect) {
            Buttons.accent(context, getString(R.string.discovery_broadcast_to, 0)) {
                Ui.snackbar(requireActivity(), getString(R.string.stub_screen))
            }
        } else {
            Buttons.outlined(context, getString(R.string.discovery_manual_ip)) {
                Ui.snackbar(requireActivity(), getString(R.string.stub_screen))
            }
        }
        column.addView(bottom, wide(12))

        // Purpose is carried forward, never re-derived downstream (§5.4).
        check(purpose == Purpose.SENDER || purpose == Purpose.BROADCAST) {
            "Discovery only serves SENDER and BROADCAST purposes"
        }
    }

    private fun centeredMeta(text: CharSequence): AppCompatTextView {
        val view = AppCompatTextView(requireContext())
        view.setTextAppearance(requireContext(), R.style.TextAppearance_Morsecode_ItemMeta)
        view.gravity = Gravity.CENTER
        view.text = text
        return view
    }

    /** Mono info row: label on the left, value on the right (§6.3). */
    private fun infoRow(label: CharSequence, value: CharSequence): LinearLayout {
        val context = requireContext()
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Shapes.card(context, 12f)
        row.minimumHeight = Shapes.dpInt(context, 48f)
        val pad = Shapes.dpInt(context, 12f)
        row.setPadding(pad, pad, pad, pad)

        val labelView = AppCompatTextView(context)
        labelView.setTextAppearance(context, R.style.TextAppearance_Morsecode_StatLabel)
        labelView.text = label
        row.addView(labelView)

        val valueView = AppCompatTextView(context)
        valueView.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        valueView.text = value
        valueView.gravity = Gravity.END
        row.addView(
            valueView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        return row
    }

    private fun wide(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        return params
    }

    companion object {
        fun newInstance(multiSelect: Boolean): DiscoveryFragment {
            val extras = Bundle()
            extras.putBoolean(Nav.ARG_MULTI_SELECT, multiSelect)
            val purpose = if (multiSelect) Purpose.BROADCAST else Purpose.SENDER
            val fragment = DiscoveryFragment()
            fragment.arguments = Nav.args(purpose, extras)
            return fragment
        }
    }
}
