package app.morsecode.android.feature.webshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.morsecode.android.R
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.SectionHeaderView
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.webshare.WebSessions
import app.morsecode.android.di.AppServices
import kotlinx.coroutines.launch

/**
 * §6.16b / §7.1 the WebShare screen: Start/Stop, the bare address as
 * tap-to-copy mono text, and the session list with a Revoke action.
 *
 * INV-4 [KEPT] is visible here as an absence: there is no timer, no
 * screen-off handling and no "stop when idle" path. The server stops when the
 * person presses Stop, and at no other time.
 *
 * §11.5 / G12: the address is text with a [Copy] affordance. There is no QR.
 */
class WebShareFragment : Screen() {

    private lateinit var address: AppCompatTextView
    private lateinit var toggle: android.widget.TextView
    private lateinit var sessionList: LinearLayout

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()
        toolbar.bind(getString(R.string.title_webshare)) { nav().pop() }

        address = AppCompatTextView(context)
        address.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        address.textSize = ADDRESS_SP
        address.background = Shapes.card(context, 12f)
        val pad = Shapes.dpInt(context, 14f)
        address.setPadding(pad, pad, pad, pad)
        address.minHeight = Shapes.dpInt(context, 48f)
        address.isClickable = true
        address.contentDescription = getString(R.string.webshare_copy_address)
        address.setOnClickListener { copyAddress() }
        column.addView(address, params(8))

        toggle = Buttons.accent(context, getString(R.string.webshare_start)) { toggleServer() }
        column.addView(toggle, params(12))

        val header = SectionHeaderView(context)
        header.bind(getString(R.string.webshare_sessions))
        column.addView(header, params(8))

        sessionList = LinearLayout(context)
        sessionList.orientation = LinearLayout.VERTICAL
        column.addView(sessionList, params(0))

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppServices.webShare.isRunning.collect { render() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppServices.webShare.address.collect { render() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppServices.webShare.sessions.active.collect { renderSessions(it) }
            }
        }
    }

    private fun render() {
        val running = AppServices.webShare.isRunning.value
        address.text = AppServices.webShare.address.value
            ?: getString(R.string.webshare_address_placeholder)
        toggle.text = getString(if (running) R.string.webshare_stop else R.string.webshare_start)
    }

    private fun renderSessions(sessions: List<WebSessions.Session>) {
        val context = context ?: return
        sessionList.removeAllViews()
        val accepted = sessions.filter { it.state == WebSessions.State.ACCEPTED }
        if (accepted.isEmpty()) {
            sessionList.addView(
                emptyState(
                    R.drawable.ic_globe,
                    getString(R.string.webshare_sessions_empty),
                    getString(R.string.webshare_sessions_empty_action),
                ) { toggleServer() },
            )
            return
        }
        for (session in accepted) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = Shapes.dpInt(context, 56f)

            val label = AppCompatTextView(context)
            label.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
            label.text = session.label
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(
                Buttons.outlined(context, getString(R.string.webshare_revoke)) {
                    // §7.1: the token dies with the session.
                    AppServices.webShare.revoke(session.id)
                },
            )
            sessionList.addView(row)
        }
    }

    private fun toggleServer() {
        val controller = AppServices.webShare
        if (controller.isRunning.value) {
            controller.stop()
            Ui.snackbar(requireActivity(), getString(R.string.webshare_stopped))
        } else if (controller.start()) {
            Ui.snackbar(requireActivity(), getString(R.string.webshare_started))
        } else {
            Ui.snackbar(requireActivity(), getString(R.string.webshare_failed))
        }
        render()
    }

    /** Every displayed address is tap-to-copy (§11.5). */
    private fun copyAddress() {
        val text = AppServices.webShare.address.value ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("webshare", text))
        Ui.snackbar(requireActivity(), getString(R.string.webshare_address_copied))
    }

    private fun params(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        return params
    }

    private companion object {
        const val ADDRESS_SP = 18f
    }
}
