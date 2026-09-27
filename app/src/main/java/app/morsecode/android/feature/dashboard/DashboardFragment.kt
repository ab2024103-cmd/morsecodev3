package app.morsecode.android.feature.dashboard

import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.morsecode.android.R
import app.morsecode.android.core.ui.BottomNavView
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Purpose
import app.morsecode.android.core.ui.RadarView
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.network.TransportKind
import app.morsecode.android.core.ui.PeerCardView
import app.morsecode.android.core.ui.SectionHeaderView
import app.morsecode.android.core.ui.TransportBadge
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices
import kotlinx.coroutines.launch
import app.morsecode.android.feature.help.HelpFragment
import app.morsecode.android.feature.transfer.TransferFragment
import app.morsecode.android.feature.webshare.WebShareFragment

/**
 * §6.2 CONNECT · DASHBOARD. Title "Connect" + "?" help icon, radar with its
 * caption, [↑ Send] / [↓ Receive], the full-width washed
 * "⇶ Broadcast to several phones" PRIMARY entry, the WebShare card, and
 * "RECENT DEVICES" with a Clear action.
 *
 * Stage 3 builds the chrome and the routes. Discovery has no transport yet, so
 * the radar shows its "Scanning the local network" caption and the recent list
 * shows its empty state (§6.19) — it never shows invented peers.
 *
 * §6.2: Send, the Send long-press and Broadcast all land on Discovery, never
 * on the sender screen.
 */
class DashboardFragment : Screen() {

    override val navTab: BottomNavView.Tab = BottomNavView.Tab.CONNECT

    private lateinit var caption: AppCompatTextView
    private lateinit var recentList: LinearLayout

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()

        toolbar.bind(getString(R.string.title_connect))
        toolbar.addAction(R.drawable.ic_help, R.string.cd_help) { nav().push(HelpFragment()) }

        val radar = RadarView(context)
        val radarSize = Shapes.dpInt(context, 180f)
        val radarParams = LinearLayout.LayoutParams(radarSize, radarSize)
        radarParams.gravity = Gravity.CENTER_HORIZONTAL
        radarParams.topMargin = Shapes.dpInt(context, 8f)
        column.addView(radar, radarParams)

        caption = AppCompatTextView(context)
        caption.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        caption.text = getString(R.string.dashboard_scanning)
        caption.gravity = Gravity.CENTER
        column.addView(caption, wide(Shapes.dpInt(context, 8f)))

        // [↑ Send] · [↓ Receive] — side by side, equal width (§6.2).
        val buttonRow = LinearLayout(context)
        buttonRow.orientation = LinearLayout.HORIZONTAL
        val send = Buttons.accent(context, getString(R.string.dashboard_send)) { openDiscovery(false) }
        // §6.2/§6.8.1: long-pressing Send is an alias for Broadcast.
        send.setOnLongClickListener {
            openDiscovery(true)
            true
        }
        val receive = Buttons.outlined(context, getString(R.string.dashboard_receive)) {
            nav().push(TransferFragment.newInstance(Purpose.RECEIVER))
        }
        buttonRow.addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val gap = Shapes.dpInt(context, 12f)
        val receiveParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        receiveParams.leftMargin = gap
        buttonRow.addView(receive, receiveParams)
        column.addView(buttonRow, wide(Shapes.dpInt(context, 16f)))

        column.addView(
            Buttons.wash(context, getString(R.string.dashboard_broadcast)) { openDiscovery(true) },
            wide(Shapes.dpInt(context, 12f)),
        )

        column.addView(webShareCard(context), wide(Shapes.dpInt(context, 16f)))

        val header = SectionHeaderView(context)
        header.bind(getString(R.string.dashboard_recent_devices), getString(R.string.action_clear)) {
            AppServices.recentDevices.clear()
            renderRecent()
        }
        column.addView(header, wide(Shapes.dpInt(context, 8f)))

        recentList = LinearLayout(context)
        recentList.orientation = LinearLayout.VERTICAL
        column.addView(recentList, wide(0))
        renderRecent()

        observeDiscovery()
    }

    override fun onStart() {
        super.onStart()
        // §11.1: every mechanism runs while a connect surface is on screen.
        AppServices.connections.addListener()
        renderRecent()
    }

    override fun onStop() {
        super.onStop()
        AppServices.connections.removeListener()
    }

    private fun observeDiscovery() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppServices.discovery.peers.collect { peers ->
                    caption.text = when (peers.size) {
                        0 -> getString(R.string.dashboard_scanning)
                        1 -> getString(R.string.discovery_one_device_found)
                        else -> getString(R.string.discovery_devices_found, peers.size)
                    }
                }
            }
        }
    }

    /**
     * §6.2 RECENT DEVICES. Tapping one re-runs discovery for it and, on
     * success, opens the consent handshake — recency shortens discovery, never
     * consent (§17.4), which is why this only opens Discovery.
     */
    private fun renderRecent() {
        val context = context ?: return
        recentList.removeAllViews()
        val recent = AppServices.recentDevices.all()
        if (recent.isEmpty()) {
            recentList.addView(
                emptyState(
                    R.drawable.ic_radar,
                    getString(R.string.dashboard_recent_empty),
                    getString(R.string.dashboard_recent_empty_action),
                ) { openDiscovery(false) },
            )
            return
        }
        for (entry in recent) {
            val card = PeerCardView(context)
            card.bind(
                deviceId = entry.deviceId,
                name = entry.name,
                subtitle = entry.summary ?: entry.transport.name,
                badge = when (entry.transport) {
                    TransportKind.NEARBY -> TransportBadge.NEARBY
                    TransportKind.WEB -> TransportBadge.WEB
                    else -> TransportBadge.LAN
                },
            )
            card.isClickable = true
            card.setOnClickListener { openDiscovery(false) }
            recentList.addView(card)
        }
    }

    /** All three routes land on Discovery; broadcast opens it in multi-select. */
    private fun openDiscovery(multiSelect: Boolean) {
        nav().push(DiscoveryFragment.newInstance(multiSelect))
    }

    /**
     * §6.2 WebShare card: icon, "WebShare · PC", the mono address, and the
     * ON/OFF pill. The card opens the WebShare screen; the pill will start and
     * stop the server directly once it exists (Stage 14). §20.6: until then
     * the pill says OFF, which is the truth.
     */
    private fun webShareCard(context: android.content.Context): LinearLayout {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = Shapes.pressable(
            context,
            Shapes.card(context),
            Shapes.rounded(
                ThemeColors.resolve(context, R.attr.colorSurfacePressed),
                Shapes.dp(context, 16f),
            ),
        )
        val pad = Shapes.dpInt(context, 14f)
        card.setPadding(pad, pad, pad, pad)
        card.isClickable = true
        card.setOnClickListener { nav().push(WebShareFragment()) }

        val icon = AppCompatImageView(context)
        icon.setImageResource(R.drawable.ic_globe)
        androidx.core.widget.ImageViewCompat.setImageTintList(
            icon,
            android.content.res.ColorStateList.valueOf(ThemeColors.accent(context)),
        )
        icon.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val iconSize = Shapes.dpInt(context, 24f)
        card.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))

        val text = LinearLayout(context)
        text.orientation = LinearLayout.VERTICAL
        val title = AppCompatTextView(context)
        title.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        title.text = getString(R.string.dashboard_webshare_title)
        text.addView(title)
        val address = AppCompatTextView(context)
        address.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        address.text = getString(R.string.dashboard_webshare_stopped)
        text.addView(address)
        val textParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        textParams.leftMargin = Shapes.dpInt(context, 12f)
        card.addView(text, textParams)

        val pill = AppCompatTextView(context)
        pill.setTextAppearance(context, R.style.TextAppearance_Morsecode_StateChip)
        pill.text = getString(R.string.dashboard_webshare_off)
        pill.setTextColor(ThemeColors.resolve(context, R.attr.colorTextSecondary))
        pill.background = Shapes.neutralChip(context)
        val pillPadH = Shapes.dpInt(context, 12f)
        val pillPadV = Shapes.dpInt(context, 8f)
        pill.setPadding(pillPadH, pillPadV, pillPadH, pillPadV)
        pill.minHeight = Shapes.dpInt(context, 48f)
        pill.gravity = Gravity.CENTER
        pill.isClickable = true
        pill.contentDescription = getString(R.string.dashboard_webshare_title)
        pill.setOnClickListener { nav().push(WebShareFragment()) }
        card.addView(pill)

        return card
    }

    private fun wide(topMargin: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = topMargin
        return params
    }
}
