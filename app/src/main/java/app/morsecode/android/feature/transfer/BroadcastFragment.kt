package app.morsecode.android.feature.transfer

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.morsecode.android.R
import app.morsecode.android.core.model.PeerDelivery
import app.morsecode.android.core.model.PeerSessionState
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.transfer.BroadcastStats
import app.morsecode.android.core.transfer.QueuePresentation
import app.morsecode.android.core.ui.ActionBarView
import app.morsecode.android.core.ui.BottomNavView
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.ChipState
import app.morsecode.android.core.ui.FileKind
import app.morsecode.android.core.ui.Nav
import app.morsecode.android.core.ui.Purpose
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.SectionHeaderView
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.SummaryCardView
import app.morsecode.android.core.ui.TransferRowView
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.util.Fmt
import app.morsecode.android.di.AppServices
import kotlinx.coroutines.launch

/**
 * §6.8.2 BROADCAST · SENDER — the hub.
 *
 * "For every file, one parent TransferRow followed by one indented sub-row PER
 * PEER: letter avatar, its own thin progress bar, right-aligned percent or '–'
 * while queued."
 *
 * §10.1's split is visible here: the parent rows come from the queue (what is
 * being sent) and the sub-rows from each `PeerDelivery` (how far that peer has
 * got). This screen derives neither.
 *
 * INV-B1 shows up as a greyed row with a reason and a [Retry peer] action —
 * one peer failing never marks the batch failed.
 */
class BroadcastFragment : Screen() {

    override val navTab = null

    private lateinit var header: SectionHeaderView
    private lateinit var rows: LinearLayout
    private lateinit var summary: SummaryCardView
    private lateinit var note: AppCompatTextView

    private val engine get() = AppServices.broadcastEngine

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()
        check(Nav.purposeOf(this) == Purpose.BROADCAST) {
            "BroadcastFragment only serves the BROADCAST purpose"
        }

        toolbar.bind(getString(R.string.title_broadcasting)) { minimize() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = minimize()
            },
        )

        header = SectionHeaderView(context)
        header.bind(getString(R.string.broadcast_delivering), getString(R.string.transfer_pause_all)) {
            engine.pauseAll()
        }
        column.addView(header, params(8))

        rows = LinearLayout(context)
        rows.orientation = LinearLayout.VERTICAL
        column.addView(rows, params(0))

        summary = SummaryCardView(context)
        column.addView(summary, params(12))

        note = AppCompatTextView(context)
        note.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        note.visibility = View.GONE
        column.addView(note, params(4))

        val actions = ActionBarView(context)
        actions.setOnAction(ActionBarView.Action.CHOOSE) {
            nav().selectTab(BottomNavView.Tab.FILES)
        }
        actions.setOnAction(ActionBarView.Action.QUEUE) {
            QueueSheet().show(parentFragmentManager, "queue")
        }
        // §6.8.2: Pause and End apply to ALL peers; per-peer lives in the sheet.
        actions.setOnAction(ActionBarView.Action.PAUSE) { engine.pauseAll() }
        actions.setOnAction(ActionBarView.Action.MINIMIZE) { minimize() }
        actions.setOnAction(ActionBarView.Action.END) { confirmEnd() }
        column.addView(actions, params(16))

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.deliveries.collect { render() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.items.collect { render() }
            }
        }
    }

    private fun render() {
        val context = context ?: return
        val items = engine.items.value
        val deliveries = engine.deliveries.value
        if (items.isEmpty()) {
            rows.removeAllViews()
            rows.addView(
                emptyState(
                    R.drawable.ic_broadcast,
                    getString(R.string.broadcast_empty),
                    getString(R.string.broadcast_empty_action),
                ) { nav().pop() },
            )
            return
        }

        rows.removeAllViews()
        for (item in items) {
            rows.addView(parentRow(item, deliveries))
            for (delivery in deliveries) rows.addView(peerSubRow(item, delivery))
        }

        val finished = deliveries.isNotEmpty() &&
            BroadcastStats.acceptedPeers(deliveries).all { it.isFinished || it.sessionState == PeerSessionState.LOST }

        if (finished) renderComplete(items, deliveries) else renderRunning(items, deliveries)
    }

    /** §6.8.2's running card: three tiles and the combined throughput. */
    private fun renderRunning(items: List<TransferItem>, deliveries: List<PeerDelivery>) {
        val stats = BroadcastStats.running(items, deliveries)
        val sending = deliveries.count { it.sessionState == PeerSessionState.SENDING }
        val queued = deliveries.size - sending
        summary.bind(
            getString(R.string.broadcast_summary_title, stats.peers),
            listOf(
                getString(
                    R.string.broadcast_summary_detail,
                    sending,
                    queued,
                    Fmt.speed(stats.throughputBps),
                ),
            ),
            BroadcastStats.runningTiles(stats),
        )
        header.bind(
            getString(R.string.broadcast_delivering),
            getString(R.string.transfer_pause_all),
        ) { engine.pauseAll() }

        // §10.6 HONESTY: say that the speed is shared rather than let someone
        // think the app is broken.
        val perPeer = if (stats.peers == 0) 0 else stats.throughputBps / stats.peers
        val shared = BroadcastStats.sharedSpeedNote(perPeer, singlePeerBaseline(), stats.peers)
        note.visibility = if (shared == null) View.GONE else View.VISIBLE
        note.text = shared.orEmpty()
    }

    /** §6.8.4's completion header and tiles, including INV-B4's degraded form. */
    private fun renderComplete(items: List<TransferItem>, deliveries: List<PeerDelivery>) {
        val stats = BroadcastStats.complete(items, deliveries, elapsedMillis())
        summary.bind(
            BroadcastStats.header(stats),
            listOf(
                getString(
                    R.string.broadcast_complete_detail,
                    stats.deliveries,
                    stats.failed,
                    stats.skipped,
                    Fmt.speed(stats.averageBps),
                ),
            ),
            BroadcastStats.completeTiles(stats),
        )
        header.bind(getString(R.string.broadcast_delivered_to), getString(R.string.action_clear)) {
            nav().pop()
        }
        note.visibility = View.GONE
    }

    private fun parentRow(item: TransferItem, deliveries: List<PeerDelivery>): View {
        val row = TransferRowView(requireContext())
        row.bindVariant(TransferRowView.Variant.SENDER)
        val states = deliveries.mapNotNull { it.itemStates[item.id] }
        row.bind(
            name = item.file.displayName,
            meta = QueuePresentation.metaLine(item),
            kind = FileKind.UNKNOWN,
            state = when {
                states.any { it == TransferState.IN_PROGRESS } -> ChipState.SENDING
                states.isNotEmpty() && states.all { it == TransferState.COMPLETED } -> ChipState.DONE
                states.any { it == TransferState.FAILED } -> ChipState.FAILED
                else -> ChipState.QUEUED
            },
            progress = item.progress,
            onCancel = null,
        )
        return row
    }

    /**
     * One indented sub-row per peer: avatar, its own bar, and a right-aligned
     * percent — or "–" while queued, exactly as §6.8.2 describes.
     */
    private fun peerSubRow(item: TransferItem, delivery: PeerDelivery): View {
        val context = requireContext()
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL

        val row = TransferRowView(context)
        row.bindVariant(TransferRowView.Variant.PEER_SUB, delivery.peerId, delivery.peerName)
        val state = delivery.itemStates[item.id] ?: TransferState.QUEUED
        val offset = delivery.resumeOffsets[item.id] ?: 0
        val progress = if (item.totalBytes <= 0) {
            0f
        } else {
            (offset.toFloat() / item.totalBytes).coerceIn(0f, 1f)
        }
        row.bind(
            name = delivery.peerName,
            meta = if (state == TransferState.QUEUED) {
                getString(R.string.broadcast_peer_queued)
            } else {
                "${Fmt.size(offset)} · ${Fmt.speed(delivery.speedBps)}"
            },
            kind = FileKind.UNKNOWN,
            state = when (state) {
                TransferState.COMPLETED -> ChipState.DONE
                TransferState.FAILED -> ChipState.FAILED
                TransferState.PAUSED -> ChipState.PAUSED
                TransferState.IN_PROGRESS -> ChipState.SENDING
                else -> ChipState.QUEUED
            },
            progress = if (state == TransferState.COMPLETED) 1f else progress,
            onCancel = null,
        )
        // INV-B1: a peer that failed or dropped greys out with its reason.
        val degraded = delivery.sessionState == PeerSessionState.REJECTED ||
            delivery.sessionState == PeerSessionState.LOST ||
            state == TransferState.FAILED
        row.alpha = if (degraded) DEGRADED_ALPHA else 1f
        container.addView(row)

        if (degraded) {
            val reason = AppCompatTextView(context)
            reason.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
            reason.text = delivery.lastError ?: getString(R.string.broadcast_peer_failed)
            reason.setPadding(Shapes.dpInt(context, 24f), 0, 0, 0)
            container.addView(reason)

            container.addView(
                Buttons.outlined(context, getString(R.string.broadcast_retry_peer)) {
                    engine.retryPeer(delivery.peerId)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.marginStart = Shapes.dpInt(context, 24f) },
            )
        }
        container.contentDescription = "${delivery.peerName} ${row.contentDescription}"
        return container
    }

    /** A single-peer LAN baseline, used only for §10.6's honesty note. */
    private fun singlePeerBaseline(): Long = SINGLE_PEER_BASELINE_BPS

    private fun elapsedMillis(): Long =
        engine.queue.summaryOf(engine.batch).elapsedMillis

    private fun minimize() {
        Ui.snackbar(requireActivity(), getString(R.string.transfer_minimized))
        nav().pop()
    }

    private fun confirmEnd() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.transfer_end_title)
            .setMessage(R.string.transfer_end_body)
            .setNegativeButton(R.string.transfer_end_cancel, null)
            .setPositiveButton(R.string.transfer_end_confirm) { _, _ ->
                engine.cancelAll()
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.connections.endSession("ended by user")
                    nav().pop()
                }
            }
            .show()
    }

    private fun params(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        params.gravity = Gravity.CENTER_HORIZONTAL
        return params
    }

    companion object {

        private const val DEGRADED_ALPHA = 0.45f

        /** ~8 MB/s: a plain Wi-Fi single-peer transfer on the reference phone. */
        private const val SINGLE_PEER_BASELINE_BPS = 8L * 1024 * 1024

        fun newInstance(): BroadcastFragment {
            val fragment = BroadcastFragment()
            fragment.arguments = Nav.args(Purpose.BROADCAST)
            return fragment
        }
    }
}
