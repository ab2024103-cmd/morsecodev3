package app.morsecode.android.feature.transfer

import android.view.View
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.morsecode.android.R
import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.SessionState
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.transfer.QueuePresentation
import app.morsecode.android.core.ui.ActionBarView
import app.morsecode.android.core.ui.BottomNavView
import app.morsecode.android.core.ui.ChipState
import app.morsecode.android.core.ui.FileKind
import app.morsecode.android.core.ui.Nav
import app.morsecode.android.core.ui.PeerCardView
import app.morsecode.android.core.ui.Purpose
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.SectionHeaderView
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.SummaryCardView
import app.morsecode.android.core.ui.TransferRowView
import app.morsecode.android.core.ui.TransportBadge
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.util.Ids
import app.morsecode.android.di.AppServices
import app.morsecode.android.feature.filemanager.FilesFragment
import kotlinx.coroutines.launch

/**
 * §6.4 TRANSFER · SENDING, §6.5 RECEIVE · LISTENING and §6.6 receiving while
 * sending back — one screen, told which job it is doing (§5.4).
 *
 * §20.1 / §16.5: every row, count, percentage and speed here is read from the
 * engine's state stream. This screen computes no progress of its own and keeps
 * no copy of the queue.
 *
 * INV-11: rows are updated IN PLACE, keyed by item id. A progress tick does
 * not rebuild the list, so a scroll position — or a half-read row — survives
 * every update.
 */
class TransferFragment : Screen() {

    override val navTab = null

    private val purpose: Purpose get() = Nav.purposeOf(this)

    private lateinit var peerCard: PeerCardView
    private lateinit var sendingHeader: SectionHeaderView
    private lateinit var sendingRows: LinearLayout
    private lateinit var receivingHeader: SectionHeaderView
    private lateinit var receivingRows: LinearLayout
    private lateinit var summary: SummaryCardView
    private lateinit var listeningPanel: LinearLayout

    /** id → row, so an update never replaces a view (INV-11). */
    private val rowViews = HashMap<String, TransferRowView>()

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()

        toolbar.bind(
            getString(if (purpose == Purpose.RECEIVER) R.string.title_receive else R.string.title_transfer),
        ) { minimize() }

        // §5.3: Back on a transfer screen is Minimize, never a silent kill.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = minimize()
            },
        )

        peerCard = PeerCardView(context)
        column.addView(peerCard, params(8))

        listeningPanel = buildListeningPanel(context)
        column.addView(listeningPanel, params(8))

        sendingHeader = SectionHeaderView(context)
        sendingHeader.bind(getString(R.string.transfer_sending_header, 0), getString(R.string.transfer_pause_all)) {
            pauseAll(Direction.SENDING)
        }
        column.addView(sendingHeader, params(8))
        sendingRows = LinearLayout(context)
        sendingRows.orientation = LinearLayout.VERTICAL
        column.addView(sendingRows, params(0))

        // §6.4: the "Receiving" section is ALWAYS part of the layout — it
        // appears the moment the peer sends something back and is never
        // conditionally removed.
        receivingHeader = SectionHeaderView(context)
        receivingHeader.bind(getString(R.string.transfer_receiving_header, 0), getString(R.string.transfer_pause_all)) {
            pauseAll(Direction.RECEIVING)
        }
        column.addView(receivingHeader, params(12))
        receivingRows = LinearLayout(context)
        receivingRows.orientation = LinearLayout.VERTICAL
        column.addView(receivingRows, params(0))

        summary = SummaryCardView(context)
        column.addView(summary, params(12))

        val actions = ActionBarView(context)
        actions.setOnAction(ActionBarView.Action.CHOOSE) {
            nav().selectTab(BottomNavView.Tab.FILES)
        }
        actions.setOnAction(ActionBarView.Action.QUEUE) {
            QueueSheet().show(parentFragmentManager, "queue")
        }
        actions.setOnAction(ActionBarView.Action.PAUSE) { pauseAll(null) }
        actions.setOnAction(ActionBarView.Action.MINIMIZE) { minimize() }
        actions.setOnAction(ActionBarView.Action.END) { confirmEnd() }
        column.addView(actions, params(16))

        observe()
        offerResumeIfInterrupted()
        offerBatteryExemptionOnce()
        adviseIfBatteryLow()
    }

    // ----- State ------------------------------------------------------------

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppServices.transferEngine.items.collect { items -> render(items) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppServices.transferEngine.session.collect { state -> renderPeer(state) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppServices.transferEngine.events.collect { event ->
                    if (event is app.morsecode.android.core.model.EngineEvent.BatchCompleted) {
                        // §9.6: ONE coalesced summary per batch, never one per
                        // file — the engine decides when, this only renders it.
                        Ui.snackbar(requireActivity(), summaryText(event.summary))
                    }
                }
            }
        }
    }

    private fun renderPeer(state: SessionState) {
        val context = context ?: return
        when (state) {
            is SessionState.Connected -> {
                peerCard.visibility = View.VISIBLE
                val transportName = when (state.transport) {
                    app.morsecode.android.core.network.TransportKind.LAN -> getString(R.string.discovery_transport_lan)
                    app.morsecode.android.core.network.TransportKind.NEARBY -> getString(R.string.badge_nearby)
                    app.morsecode.android.core.network.TransportKind.WEB -> getString(R.string.badge_web)
                }
                peerCard.bind(
                    deviceId = state.peerId,
                    name = state.peerName,
                    subtitle = if (state.receivingBroadcast) {
                        // §6.8.3 / §10.5: this phone only learns that the
                        // sender is broadcasting, never who else receives it.
                        getString(R.string.transfer_peer_broadcast, state.peerName, transportName)
                    } else {
                        getString(R.string.transfer_peer_connected, state.peerName, transportName)
                    },
                    badge = if (state.receivingBroadcast) {
                        TransportBadge.FROM
                    } else {
                        when (state.transport) {
                            app.morsecode.android.core.network.TransportKind.LAN -> TransportBadge.LAN
                            app.morsecode.android.core.network.TransportKind.NEARBY -> TransportBadge.NEARBY
                            app.morsecode.android.core.network.TransportKind.WEB -> TransportBadge.WEB
                        }
                    },
                )
                listeningPanel.visibility = View.GONE
            }
            is SessionState.Closed -> {
                peerCard.visibility = View.VISIBLE
                listeningPanel.visibility =
                    if (purpose == Purpose.RECEIVER) View.VISIBLE else View.GONE
            }
            SessionState.NeverConnected -> {
                // §20.3: a fresh screen never announces "connection closed".
                peerCard.visibility = View.GONE
                listeningPanel.visibility =
                    if (purpose == Purpose.RECEIVER) View.VISIBLE else View.GONE
            }
        }
    }

    private fun render(items: List<TransferItem>) {
        val sending = items.filter { it.direction == Direction.SENDING }
        val receiving = items.filter { it.direction == Direction.RECEIVING }

        sendingHeader.bind(
            if (sending.size == 1) {
                getString(R.string.transfer_sending_header_one)
            } else {
                getString(R.string.transfer_sending_header, sending.size)
            },
            getString(R.string.transfer_pause_all),
        ) { pauseAll(Direction.SENDING) }

        receivingHeader.bind(
            if (receiving.size == 1) {
                getString(R.string.transfer_receiving_header_one)
            } else {
                getString(R.string.transfer_receiving_header, receiving.size)
            },
            getString(R.string.transfer_pause_all),
        ) { pauseAll(Direction.RECEIVING) }

        syncRows(sendingRows, sending)
        syncRows(receivingRows, receiving)
        renderSummary(items)
    }

    /**
     * Updates rows in place and only adds or removes when the set of items
     * actually changes — INV-11's requirement that a state change must not
     * move anything the user is looking at.
     */
    private fun syncRows(container: LinearLayout, items: List<TransferItem>) {
        val context = context ?: return
        val wanted = items.map { it.id }.toSet()

        // Remove rows whose items are gone.
        for (index in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(index) as? TransferRowView ?: continue
            val id = child.tag as? String ?: continue
            if (id !in wanted) {
                container.removeViewAt(index)
                rowViews.remove(id)
            }
        }

        items.forEachIndexed { position, item ->
            val row = rowViews.getOrPut(item.id) {
                val view = TransferRowView(context)
                view.tag = item.id
                view.bindVariant(
                    if (item.direction == Direction.SENDING) {
                        TransferRowView.Variant.SENDER
                    } else {
                        TransferRowView.Variant.RECEIVER
                    },
                )
                container.addView(view, position.coerceAtMost(container.childCount))
                view
            }
            row.bind(
                name = item.file.displayName,
                meta = QueuePresentation.metaLine(item),
                kind = kindOf(item),
                state = chipFor(item),
                progress = item.progress,
                onCancel = if (item.state.isTerminal) null else ({ AppServices.transferEngine.cancel(item.id) }),
            )
        }
    }

    private fun renderSummary(items: List<TransferItem>) {
        val settled = items.isNotEmpty() && items.all { it.state.isTerminal }
        if (settled) {
            // §6.6 [GAP]: two lines when both directions finished, never one
            // that silently mixes them.
            summary.bind(
                getString(R.string.summary_batch_complete),
                QueuePresentation.terminalLines(items),
            )
        } else {
            summary.bind(
                getString(R.string.summary_batch_in_progress),
                listOf(QueuePresentation.liveDetail(items)),
            )
        }
    }

    private fun summaryText(summary: app.morsecode.android.core.model.BatchSummary): String =
        "${summary.sent} sent · ${summary.failed} failed · ${summary.skipped} skipped"

    private fun chipFor(item: TransferItem): ChipState = when (item.state) {
        TransferState.QUEUED -> ChipState.QUEUED
        TransferState.IN_PROGRESS ->
            if (item.direction == Direction.SENDING) ChipState.SENDING else ChipState.RECEIVING
        TransferState.PAUSED -> ChipState.PAUSED
        TransferState.COMPLETED -> ChipState.DONE
        TransferState.SKIPPED -> ChipState.SKIPPED
        TransferState.FAILED, TransferState.CANCELLED -> ChipState.FAILED
    }

    private fun kindOf(item: TransferItem): FileKind {
        val type = app.morsecode.android.core.media.FileTypes.typeOf(item.file.displayName, item.file.mime)
        return when (type) {
            app.morsecode.android.core.model.FileType.IMAGE -> FileKind.IMAGE
            app.morsecode.android.core.model.FileType.VIDEO -> FileKind.VIDEO
            app.morsecode.android.core.model.FileType.AUDIO -> FileKind.AUDIO
            app.morsecode.android.core.model.FileType.DOCUMENT -> FileKind.DOC
            app.morsecode.android.core.model.FileType.ARCHIVE -> FileKind.ARCHIVE
            app.morsecode.android.core.model.FileType.APK -> FileKind.APK
            else -> FileKind.UNKNOWN
        }
    }

    // ----- Actions ----------------------------------------------------------

    /** INV-2: pausing many items is still one call per item, never a global flag. */
    private fun pauseAll(direction: Direction?) {
        val engine = AppServices.transferEngine
        engine.items.value
            .filter { direction == null || it.direction == direction }
            .filter { it.state == TransferState.IN_PROGRESS || it.state == TransferState.QUEUED }
            .forEach { engine.pause(it.id) }
    }

    private fun minimize() {
        AppServices.logStore.i("Transfer minimized · purpose=${purpose.name}")
        Ui.snackbar(requireActivity(), getString(R.string.transfer_minimized))
        nav().pop()
    }

    /** §5.3 End: confirm, then a clean BYE to every peer. */
    private fun confirmEnd() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.transfer_end_title)
            .setMessage(R.string.transfer_end_body)
            .setNegativeButton(R.string.transfer_end_cancel, null)
            .setPositiveButton(R.string.transfer_end_confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.connections.endSession("ended by user")
                    nav().pop()
                }
            }
            .show()
    }

    /**
     * §9.7: an unfinished journal is offered back — naming the peer and the
     * remaining items — and never silently resumed or silently discarded.
     */
    private fun offerResumeIfInterrupted() {
        val pending = AppServices.transferEngine.pendingResume() ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.resume_title)
            .setMessage(getString(R.string.resume_body, pending.peerName, pending.remainingItems))
            .setNegativeButton(R.string.resume_discard) { _, _ ->
                AppServices.transferEngine.discardJournal()
            }
            .setPositiveButton(R.string.resume_confirm) { _, _ ->
                AppServices.transferEngine.restoreJournal()
            }
            .setCancelable(false)
            .show()
    }

    // ----- §14 battery ------------------------------------------------------

    /**
     * §14.1: the exemption is offered contextually ONCE, the first time a
     * transfer starts without it. Declining is respected — the flag is set
     * either way, and the Doctor keeps surfacing it as a warning.
     */
    private fun offerBatteryExemptionOnce() {
        if (AppServices.prefs.batteryPromptShown) return
        if (isBatteryExempt()) return
        AppServices.prefs.batteryPromptShown = true
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_battery)
            .setMessage(R.string.battery_exemption_rationale)
            .setNegativeButton(R.string.battery_not_now, null)
            .setPositiveButton(R.string.doctor_request_battery) { _, _ ->
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${requireContext().packageName}"),
                )
                runCatching { startActivity(intent) }
            }
            .show()
    }

    /** §14.4: advisory only, never a hard block. */
    private fun adviseIfBatteryLow() {
        val context = requireContext()
        val status = context.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )
        val level = status?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level < 0) 100 else (level * 100 / scale.coerceAtLeast(1))
        val plugged = (status?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val saver = (context.getSystemService(android.content.Context.POWER_SERVICE)
            as? android.os.PowerManager)?.isPowerSaveMode ?: false
        val batch = AppServices.transferEngine.items.value.sumOf { it.totalBytes }

        app.morsecode.android.core.util.PowerPolicy.lowBatteryAdvisory(percent, plugged, saver, batch)
            ?.let { Ui.snackbar(requireActivity(), it) }
    }

    private fun isBatteryExempt(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 23) return true
        val manager = requireContext().getSystemService(android.content.Context.POWER_SERVICE)
            as? android.os.PowerManager ?: return false
        return manager.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    // ----- §6.5 listening chrome -------------------------------------------

    private fun buildListeningPanel(context: android.content.Context): LinearLayout {
        val panel = LinearLayout(context)
        panel.orientation = LinearLayout.VERTICAL
        panel.visibility = if (purpose == Purpose.RECEIVER) View.VISIBLE else View.GONE

        val title = AppCompatTextView(context)
        title.setTextAppearance(context, R.style.TextAppearance_Morsecode_ScreenTitle)
        title.setText(R.string.transfer_listening_title)
        panel.addView(title)

        val body = AppCompatTextView(context)
        body.setTextAppearance(context, R.style.TextAppearance_Morsecode_Body)
        body.setText(R.string.transfer_listening_body)
        panel.addView(body, spaced(context))

        panel.addView(
            monoRow(context, getString(R.string.transfer_row_broadcasting_as), AppServices.deviceName),
            spaced(context),
        )
        panel.addView(
            monoRow(context, getString(R.string.transfer_row_transport), getString(R.string.discovery_transport_lan)),
            spaced(context),
        )
        panel.addView(
            monoRow(context, getString(R.string.transfer_row_port), Ids.PORT_TCP.toString()),
            spaced(context),
        )
        return panel
    }

    private fun monoRow(context: android.content.Context, label: String, value: String): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.minimumHeight = Shapes.dpInt(context, 48f)
        row.gravity = android.view.Gravity.CENTER_VERTICAL

        val labelView = AppCompatTextView(context)
        labelView.setTextAppearance(context, R.style.TextAppearance_Morsecode_StatLabel)
        labelView.text = label
        row.addView(labelView)

        val valueView = AppCompatTextView(context)
        valueView.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        valueView.text = value
        valueView.gravity = android.view.Gravity.END
        row.addView(
            valueView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.contentDescription = "$label $value"
        return row
    }

    private fun spaced(context: android.content.Context): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(context, 8f)
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

    companion object {
        fun newInstance(purpose: Purpose): TransferFragment {
            val fragment = TransferFragment()
            fragment.arguments = Nav.args(purpose)
            return fragment
        }
    }
}
