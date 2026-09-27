package app.morsecode.android.feature.transfer

import android.content.Context
import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.morsecode.android.R
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.transfer.QueuePresentation
import app.morsecode.android.core.transfer.QueuePresentation.Action
import app.morsecode.android.core.ui.BottomSheet
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.ChipState
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.StateChipView
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices
import kotlinx.coroutines.launch

/**
 * §6.7 QUEUE SHEET — the full outgoing queue over a dimmed transfer screen.
 *
 * Each row is a state chip, the file name, the mono progress line and the
 * contextual buttons for that state; the footer carries Pause all / Resume
 * all, Clear completed and Retry all failed, each shown ONLY when it applies.
 *
 * Long-press a QUEUED row to drag-reorder. An active or finished row cannot be
 * dragged and says so when you try — §6.7 forbids the silent no-op.
 */
class QueueSheet : BottomSheet() {

    private lateinit var title: AppCompatTextView
    private lateinit var list: RecyclerView
    private lateinit var footer: LinearLayout
    private val adapter = QueueAdapter()

    override fun onCreateSheetContent(context: Context): View {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL

        title = AppCompatTextView(context)
        title.setTextAppearance(context, R.style.TextAppearance_Morsecode_ScreenTitle)
        root.addView(title)

        list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        // INV-11: item changes animate in place and never jump the sheet back
        // to the top.
        list.itemAnimator?.changeDuration = 0
        ItemTouchHelper(reorderCallback).attachToRecyclerView(list)
        val listParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )
        listParams.topMargin = Shapes.dpInt(context, 8f)
        root.addView(list, listParams)

        footer = LinearLayout(context)
        footer.orientation = LinearLayout.HORIZONTAL
        root.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        viewLifecycleOwnerOrNull()?.lifecycleScope?.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppServices.transferEngine.items.collect { render(it) }
            }
        }
        render(AppServices.transferEngine.items.value)
        return root
    }

    private fun viewLifecycleOwnerOrNull() = this

    private fun render(items: List<TransferItem>) {
        val context = context ?: return
        title.text = getString(R.string.queue_title, items.size)
        adapter.submit(items)

        footer.removeAllViews()
        val engine = AppServices.transferEngine
        val anyPausable = items.any {
            it.state == TransferState.IN_PROGRESS || it.state == TransferState.QUEUED
        }
        val anyPaused = items.any { it.state == TransferState.PAUSED }
        val anyCompleted = items.any { it.state.isTerminal }
        val anyFailed = items.any { it.state == TransferState.FAILED }

        if (anyPausable) {
            footer.addView(
                Buttons.outlined(context, getString(R.string.transfer_pause_all)) {
                    items.filter { it.state == TransferState.IN_PROGRESS || it.state == TransferState.QUEUED }
                        .forEach { engine.pause(it.id) }
                },
                footerCell(context),
            )
        }
        if (anyPaused) {
            footer.addView(
                Buttons.outlined(context, getString(R.string.transfer_resume_all)) {
                    items.filter { it.state == TransferState.PAUSED }.forEach { engine.resume(it.id) }
                },
                footerCell(context),
            )
        }
        if (anyCompleted) {
            footer.addView(
                Buttons.outlined(context, getString(R.string.queue_clear_completed)) {
                    engine.queue.clearCompleted()
                },
                footerCell(context),
            )
        }
        if (anyFailed) {
            footer.addView(
                Buttons.accent(context, getString(R.string.queue_retry_all_failed)) {
                    items.map { it.batchId }.distinct().forEach { engine.retryFailed(it) }
                },
                footerCell(context),
            )
        }
    }

    private fun footerCell(context: Context): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        params.topMargin = Shapes.dpInt(context, 8f)
        params.marginEnd = Shapes.dpInt(context, 8f)
        return params
    }

    // ----- Rows -------------------------------------------------------------

    private inner class QueueAdapter : RecyclerView.Adapter<QueueHolder>() {

        private var items: List<TransferItem> = emptyList()

        fun submit(next: List<TransferItem>) {
            items = next
            notifyDataSetChanged()
        }

        fun itemAt(position: Int): TransferItem? = items.getOrNull(position)

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueHolder {
            val context = parent.context
            val row = LinearLayout(context)
            row.orientation = LinearLayout.VERTICAL
            val pad = Shapes.dpInt(context, 12f)
            row.setPadding(0, pad, 0, pad)
            row.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            )
            return QueueHolder(row)
        }

        override fun onBindViewHolder(holder: QueueHolder, position: Int) {
            items.getOrNull(position)?.let { holder.bind(it) }
        }
    }

    private inner class QueueHolder(private val row: LinearLayout) : RecyclerView.ViewHolder(row) {

        fun bind(item: TransferItem) {
            val context = row.context
            row.removeAllViews()

            val top = LinearLayout(context)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL

            val chip = StateChipView(context)
            chip.bind(chipFor(item.state))
            top.addView(chip)

            val name = AppCompatTextView(context)
            name.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
            name.text = item.file.displayName
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            val nameParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            nameParams.marginStart = Shapes.dpInt(context, 8f)
            top.addView(name, nameParams)
            row.addView(top)

            val meta = AppCompatTextView(context)
            meta.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
            meta.text = QueuePresentation.metaLine(item)
            if (item.state == TransferState.FAILED) {
                // §6.7: the error text in the error colour, and never colour
                // alone — the FAILED chip says it too (§4.13).
                meta.setTextColor(
                    androidx.core.content.ContextCompat.getColor(context, R.color.state_error),
                )
            }
            row.addView(meta)

            val actions = LinearLayout(context)
            actions.orientation = LinearLayout.HORIZONTAL
            for (action in QueuePresentation.actionsFor(item.state)) {
                actions.addView(actionButton(context, action, item))
            }
            row.addView(actions)

            row.contentDescription = if (QueuePresentation.isReorderable(item.state)) {
                getString(R.string.cd_reorder_item, item.file.displayName)
            } else {
                item.file.displayName
            }
        }

        private fun actionButton(context: Context, action: Action, item: TransferItem): View {
            val engine = AppServices.transferEngine
            val label = when (action) {
                Action.PAUSE -> getString(R.string.action_pause)
                Action.RESUME -> getString(R.string.queue_resume)
                Action.RETRY -> getString(R.string.queue_retry)
                Action.CANCEL -> getString(R.string.action_cancel)
                Action.SEND_NOW -> getString(R.string.queue_send_now)
                Action.REMOVE -> getString(R.string.queue_remove)
            }
            val button = Buttons.outlined(context, label) {
                when (action) {
                    Action.PAUSE -> engine.pause(item.id)
                    Action.RESUME -> engine.resume(item.id)
                    Action.RETRY -> engine.retryFailed(item.batchId)
                    Action.CANCEL -> engine.cancel(item.id)
                    Action.SEND_NOW -> engine.queue.sendNow(item.id)
                    Action.REMOVE -> engine.queue.remove(item.id)
                }
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.marginEnd = Shapes.dpInt(context, 8f)
            params.topMargin = Shapes.dpInt(context, 8f)
            button.layoutParams = params
            return button
        }
    }

    /**
     * §6.7 drag-reorder, queued rows only. `getDragDirs` returns 0 for
     * anything else, and the attempt is answered out loud rather than ignored.
     */
    private val reorderCallback = object : ItemTouchHelper.Callback() {

        override fun isLongPressDragEnabled(): Boolean = true

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ): Int {
            val item = adapter.itemAt(viewHolder.bindingAdapterPosition)
            val canDrag = item != null && QueuePresentation.isReorderable(item.state)
            if (!canDrag) viewHolder.itemView.alpha = DISABLED_ALPHA
            return makeMovementFlags(if (canDrag) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val item = adapter.itemAt(viewHolder.bindingAdapterPosition) ?: return false
            val moved = AppServices.transferEngine.queue.move(item.id, target.bindingAdapterPosition)
            if (!moved) {
                // Visibly refused, never a silent no-op (§6.7).
                activity?.let { Ui.snackbar(it, getString(R.string.queue_reorder_disabled)) }
            }
            return moved
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onChildDraw(
            canvas: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                viewHolder.itemView.background = Shapes.rounded(
                    ThemeColors.resolve(recyclerView.context, R.attr.colorSurfaceRaised),
                    Shapes.dp(recyclerView.context, 12f),
                )
            }
            super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            viewHolder.itemView.background = null
            viewHolder.itemView.alpha = 1f
            super.clearView(recyclerView, viewHolder)
        }
    }

    private fun chipFor(state: TransferState): ChipState = when (state) {
        TransferState.QUEUED -> ChipState.QUEUED
        TransferState.IN_PROGRESS -> ChipState.SENDING
        TransferState.PAUSED -> ChipState.PAUSED
        TransferState.COMPLETED -> ChipState.DONE
        TransferState.SKIPPED -> ChipState.SKIPPED
        TransferState.FAILED, TransferState.CANCELLED -> ChipState.FAILED
    }

    private companion object {
        const val DISABLED_ALPHA = 0.5f
    }
}
