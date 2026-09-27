package app.morsecode.android.feature.webshare

import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.SectionHeaderView
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Ui

/**
 * §6.16b / §7.1 WEBSHARE screen: Start/Stop, the bare `http://ip:33455`
 * address as tap-to-copy mono text, and the list of browser sessions with a
 * Revoke action.
 *
 * INV-4 [KEPT]: the server stays up until the user stops it — no idle timeout,
 * no teardown on screen-off, and never while a browser session is connected.
 * That is the server's contract (Stage 14); this screen only ever asks.
 *
 * §11.5 / G12: there is no QR block here. The address is shown as plain text
 * and copied by tapping.
 */
class WebShareFragment : Screen() {

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()

        toolbar.bind(getString(R.string.title_webshare)) { nav().pop() }

        val address = AppCompatTextView(context)
        address.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        address.text = getString(R.string.webshare_address_placeholder)
        address.background = Shapes.card(context, 12f)
        val pad = Shapes.dpInt(context, 14f)
        address.setPadding(pad, pad, pad, pad)
        address.minHeight = Shapes.dpInt(context, 48f)
        column.addView(address, params(8))

        column.addView(
            Buttons.accent(context, getString(R.string.webshare_start)) { stub() },
            params(12),
        )

        val header = SectionHeaderView(context)
        header.bind(getString(R.string.webshare_sessions))
        column.addView(header, params(8))

        column.addView(
            emptyState(
                R.drawable.ic_globe,
                getString(R.string.webshare_sessions_empty),
                getString(R.string.webshare_sessions_empty_action),
            ) { stub() },
            params(0),
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
