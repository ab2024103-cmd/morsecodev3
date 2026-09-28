package app.morsecode.android.feature.dashboard

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.morsecode.android.R
import app.morsecode.android.core.model.DiscoveredPeer
import app.morsecode.android.core.network.Discovery
import app.morsecode.android.core.network.ManualAddress
import app.morsecode.android.core.network.TransportKind
import app.morsecode.android.core.transfer.BroadcastStats
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Nav
import app.morsecode.android.core.ui.PeerCardView
import app.morsecode.android.core.ui.Purpose
import app.morsecode.android.core.ui.RadarView
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.SectionHeaderView
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.TransportBadge
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.util.Fmt
import app.morsecode.android.core.util.Permissions
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices
import app.morsecode.android.feature.settings.ConnectionDoctorFragment
import app.morsecode.android.feature.transfer.BroadcastFragment
import app.morsecode.android.feature.transfer.TransferFragment
import kotlinx.coroutines.launch

/**
 * §6.3 SEND · DISCOVERY, now live.
 *
 * One UNIFIED list of every visible peer regardless of transport (§11.1), each
 * row an avatar, a name, a mono subtitle and a transport badge with a
 * [Connect] button. The TRANSPORT row is a preference, not a filter: changing
 * it never removes a peer from this list.
 *
 * §11.5 / G12: there is no QR scanner. [Manual IP] is the only typed path.
 *
 * Multi-select is an explicit argument (§5.4), and in it the bottom area holds
 * exactly ONE control, [Broadcast to (n)] — with fewer than two phones picked
 * it refuses, which is A28's second half.
 */
class DiscoveryFragment : Screen() {

    private val multiSelect: Boolean
        get() = arguments?.getBoolean(Nav.ARG_MULTI_SELECT, false) ?: false

    private lateinit var caption: AppCompatTextView
    private lateinit var peerList: LinearLayout
    private lateinit var emptyHolder: FrameLayout
    private lateinit var transportValue: AppCompatTextView
    private lateinit var queueValue: AppCompatTextView
    private lateinit var bottomAction: AppCompatTextView
    private var radar: RadarView? = null

    private val selected = LinkedHashSet<String>()
    private var peers: List<DiscoveredPeer> = emptyList()
    private var searchStartedAt = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { startDiscovering() }

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()
        val purpose = Nav.purposeOf(this)
        check(purpose == Purpose.SENDER || purpose == Purpose.BROADCAST) {
            "Discovery only serves SENDER and BROADCAST purposes"
        }

        toolbar.bind(getString(R.string.title_send_files)) { nav().pop() }
        toolbar.addAction(R.drawable.ic_overflow, R.string.cd_overflow) { showTransportSheet() }

        val radarView = RadarView(context)
        radar = radarView
        val size = Shapes.dpInt(context, 150f)
        val radarParams = LinearLayout.LayoutParams(size, size)
        radarParams.gravity = Gravity.CENTER_HORIZONTAL
        column.addView(radarView, radarParams)

        caption = centeredMeta(getString(R.string.discovery_searching))
        column.addView(caption, wide(8))
        column.addView(centeredMeta(getString(R.string.discovery_searching_hint)), wide(2))

        val transportRow = infoRow(getString(R.string.discovery_transport_label), getString(R.string.discovery_transport_auto))
        transportValue = transportRow.second
        transportRow.first.setOnClickListener { showTransportSheet() }
        column.addView(transportRow.first, wide(16))

        val queueRow = infoRow(getString(R.string.discovery_queue_label), getString(R.string.discovery_queue_empty))
        queueValue = queueRow.second
        column.addView(queueRow.first, wide(4))

        val header = SectionHeaderView(context)
        header.bind(getString(R.string.discovery_discovered), getString(R.string.action_refresh)) {
            searchStartedAt = System.currentTimeMillis()
            render(peers)
        }
        column.addView(header, wide(8))

        peerList = LinearLayout(context)
        peerList.orientation = LinearLayout.VERTICAL
        column.addView(peerList, wide(0))

        emptyHolder = FrameLayout(context)
        column.addView(emptyHolder, wide(0))

        // §6.3: exactly ONE bottom control, and which one is decided by the
        // explicit argument, never by what happens to be queued.
        bottomAction = if (multiSelect) {
            Buttons.accent(context, getString(R.string.broadcast_connect_and_send, 0)) { startBroadcast() }
        } else {
            Buttons.outlined(context, getString(R.string.discovery_manual_ip)) { showManualDialog() }
        }
        column.addView(bottomAction, wide(12))

        searchStartedAt = System.currentTimeMillis()
        observe()
    }

    override fun onStart() {
        super.onStart()
        startDiscovering()
    }

    override fun onStop() {
        super.onStop()
        AppServices.connections.removeListener()
    }

    /**
     * §6.1's skipped permissions are requested here, contextually, the first
     * time they are actually needed — with discovery starting either way, so
     * a refused Bluetooth prompt still leaves Wi-Fi LAN working (§20.6).
     */
    private fun startDiscovering() {
        val missing = Permissions.missing(requireContext(), Permissions.nearby())
        if (missing.isNotEmpty() && !askedForPermissions) {
            askedForPermissions = true
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        AppServices.connections.addListener()
        if (!AppServices.connections.isNearbyAvailable()) {
            Ui.snackbar(requireActivity(), getString(R.string.discovery_nearby_unavailable))
        }
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppServices.discovery.peers.collect { found ->
                    peers = found
                    render(found)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppServices.transferEngine.items.collect { items ->
                    val pending = items.filter { !it.state.isTerminal }
                    queueValue.text = if (pending.isEmpty()) {
                        getString(R.string.discovery_queue_empty)
                    } else {
                        getString(
                            R.string.discovery_queue_files,
                            pending.size,
                            Fmt.size(pending.sumOf { it.totalBytes }),
                        )
                    }
                }
            }
        }
    }

    private fun render(found: List<DiscoveredPeer>) {
        val context = context ?: return
        caption.text = when (found.size) {
            0 -> getString(R.string.discovery_searching)
            1 -> getString(R.string.discovery_one_device_found)
            else -> getString(R.string.discovery_devices_found, found.size)
        }
        // §6.2/§6.3: once peers exist the radar shrinks and the list takes the
        // space — the radar is never the only way to disambiguate peers.
        radar?.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE

        peerList.removeAllViews()
        for (peer in found) peerList.addView(peerRow(peer))

        emptyHolder.removeAllViews()
        val waitedLongEnough = System.currentTimeMillis() - searchStartedAt >= EMPTY_STATE_DELAY_MS
        if (found.isEmpty() && waitedLongEnough) {
            // §6.3: after 15 s, offer the Doctor and the other transport.
            emptyHolder.addView(
                emptyState(
                    R.drawable.ic_radar,
                    getString(R.string.discovery_empty),
                    getString(R.string.discovery_empty_action),
                ) { nav().push(ConnectionDoctorFragment()) },
            )
        }
        updateBottomAction()
    }

    private fun peerRow(peer: DiscoveredPeer): View {
        val context = requireContext()
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        if (multiSelect) {
            val check = AppCompatCheckBox(context)
            check.isChecked = peer.deviceId in selected
            check.contentDescription = getString(R.string.cd_select_peer, peer.name)
            check.minimumWidth = Shapes.dpInt(context, 48f)
            check.minimumHeight = Shapes.dpInt(context, 48f)
            check.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(peer.deviceId) else selected.remove(peer.deviceId)
                updateBottomAction()
            }
            row.addView(check)
        }

        val card = PeerCardView(context)
        card.bind(
            deviceId = peer.deviceId,
            name = peer.name,
            subtitle = when (peer.transport) {
                TransportKind.LAN -> getString(R.string.discovery_peer_lan, peer.hostPort)
                TransportKind.NEARBY -> getString(R.string.discovery_peer_nearby)
                TransportKind.WEB -> getString(R.string.discovery_peer_web, peer.address)
            },
            badge = when (peer.transport) {
                TransportKind.LAN -> TransportBadge.LAN
                TransportKind.NEARBY -> TransportBadge.NEARBY
                TransportKind.WEB -> TransportBadge.WEB
            },
        )
        row.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (!multiSelect) {
            row.addView(Buttons.outlined(context, getString(R.string.discovery_connect)) { connect(peer) })
        }
        return row
    }

    private fun updateBottomAction() {
        if (!multiSelect) return
        // §6.8.1: "the primary button reads [Connect & broadcast to (n)]".
        bottomAction.text = getString(R.string.broadcast_connect_and_send, selected.size)
    }

    private fun connect(peer: DiscoveredPeer) {
        Ui.snackbar(requireActivity(), getString(R.string.discovery_connecting, peer.name))
        viewLifecycleOwner.lifecycleScope.launch {
            val session = AppServices.connections.connect(peer)
            if (session == null) {
                // The other phone rejected, or could not be reached; either way
                // the user is told what happened and what it means (§6.19).
                Ui.snackbar(requireActivity(), getString(R.string.discovery_rejected, peer.name))
                return@launch
            }
            // §3.6: a batch shared into the app queues the moment a peer accepts.
            val held = AppServices.transferEngine.takePendingShare()
            if (held.isNotEmpty()) AppServices.transferEngine.enqueue(held)

            Ui.snackbar(requireActivity(), getString(R.string.discovery_connected, peer.name))
            nav().push(TransferFragment.newInstance(Purpose.SENDER))
        }
    }

    /**
     * A28 / §6.8.1: picking one phone is refused with a snackbar, and the
     * sender screen opens only AFTER the peers accept — never straight from
     * the dashboard, because a broadcast with no chosen peers is meaningless.
     */
    private fun startBroadcast() {
        if (selected.size < 2) {
            Ui.snackbar(requireActivity(), getString(R.string.discovery_broadcast_needs_two))
            return
        }
        // §10.2's cap, and the honest reason for it.
        val usingNearby = peers.filter { it.deviceId in selected }
            .any { it.transport == TransportKind.NEARBY }
        val cap = AppServices.maxBroadcastPeers(usingNearby)
        BroadcastStats.capNote(
            requested = selected.size,
            cap = cap,
            nearby = usingNearby,
            lowTier = AppServices.deviceTier.isLowEndDevice,
        )?.let { Ui.snackbar(requireActivity(), it) }

        val targets = peers.filter { it.deviceId in selected }.take(cap)
        Ui.snackbar(requireActivity(), getString(R.string.broadcast_connecting, targets.size))

        viewLifecycleOwner.lifecycleScope.launch {
            val sessions = ArrayList<app.morsecode.android.core.network.TransportSession>()
            for (peer in targets) {
                // Each receiver gets its own consent prompt (§6.8.1); one
                // refusal greys that row and the rest carry on (INV-B1).
                val session = AppServices.connections.connect(peer, broadcasting = true)
                if (session == null) {
                    AppServices.broadcastEngine.markRejected(peer.deviceId, peer.name, getString(R.string.discovery_rejected, peer.name))
                } else {
                    sessions.add(session)
                }
            }
            if (sessions.isEmpty()) {
                Ui.snackbar(requireActivity(), getString(R.string.broadcast_none_accepted))
                return@launch
            }
            val held = AppServices.transferEngine.takePendingShare()
            AppServices.broadcastEngine.start(sessions, held)
            nav().push(BroadcastFragment.newInstance())
        }
    }

    /** §11.5: host, host:port or a pasted morsecode:// link. No camera. */
    private fun showManualDialog() {
        val context = requireContext()
        val input = AppCompatEditText(context)
        input.hint = getString(R.string.discovery_manual_hint)
        input.setSingleLine()
        val pad = Shapes.dpInt(context, 20f)
        val wrapper = FrameLayout(context)
        wrapper.setPadding(pad, pad, pad, 0)
        wrapper.addView(input)

        AlertDialog.Builder(context)
            .setTitle(R.string.discovery_manual_title)
            .setView(wrapper)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.discovery_manual_connect) { _, _ ->
                when (val parsed = ManualAddress.parse(input.text?.toString().orEmpty())) {
                    is ManualAddress.Result.Invalid ->
                        Ui.snackbar(requireActivity(), parsed.reason)
                    is ManualAddress.Result.Ok -> dialManually(parsed.target)
                }
            }
            .show()
    }

    private fun dialManually(target: ManualAddress.Target) {
        Ui.snackbar(requireActivity(), getString(R.string.discovery_connecting, target.toString()))
        viewLifecycleOwner.lifecycleScope.launch {
            val session = AppServices.connections.connectManually(target)
            if (session == null) {
                Ui.snackbar(requireActivity(), ManualAddress.unreachable(target))
                return@launch
            }
            val held = AppServices.transferEngine.takePendingShare()
            if (held.isNotEmpty()) AppServices.transferEngine.enqueue(held)
            nav().push(TransferFragment.newInstance(Purpose.SENDER))
        }
    }

    /**
     * §11.1: a PREFERENCE (Auto / Wi-Fi LAN / Nearby), not a hard switch that
     * hides half the network — the list is untouched by this choice.
     */
    private fun showTransportSheet() {
        val labels = arrayOf(
            getString(R.string.discovery_transport_auto),
            getString(R.string.discovery_transport_lan),
            getString(R.string.discovery_transport_nearby),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.discovery_transport_title)
            .setItems(labels) { _, which ->
                AppServices.discovery.preference = when (which) {
                    1 -> Discovery.Preference.LAN
                    2 -> Discovery.Preference.NEARBY
                    else -> Discovery.Preference.AUTO
                }
                transportValue.text = labels[which]
            }
            .show()
    }

    private fun centeredMeta(text: CharSequence): AppCompatTextView {
        val view = AppCompatTextView(requireContext())
        view.setTextAppearance(requireContext(), R.style.TextAppearance_Morsecode_ItemMeta)
        view.gravity = Gravity.CENTER
        view.text = text
        return view
    }

    private fun infoRow(label: CharSequence, value: CharSequence): Pair<LinearLayout, AppCompatTextView> {
        val context = requireContext()
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Shapes.pressable(
            context,
            Shapes.card(context, 12f),
            Shapes.rounded(
                ThemeColors.resolve(context, R.attr.colorSurfacePressed),
                Shapes.dp(context, 12f),
            ),
        )
        row.minimumHeight = Shapes.dpInt(context, 48f)
        row.isClickable = true
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
        row.contentDescription = "$label $value"
        return row to valueView
    }

    private fun wide(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        return params
    }

    private var askedForPermissions = false

    companion object {

        /** §6.3: the empty state appears after 15 s of finding nothing. */
        const val EMPTY_STATE_DELAY_MS = 15_000L

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
