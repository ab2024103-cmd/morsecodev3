package app.morsecode.android.feature.history

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import app.morsecode.android.R
import app.morsecode.android.core.data.HistoryPresentation
import app.morsecode.android.core.data.HistoryStore
import app.morsecode.android.core.model.Direction
import app.morsecode.android.core.model.TransferFile
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.ui.BottomNavView
import app.morsecode.android.core.ui.Purpose
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.SegmentedControl
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices
import app.morsecode.android.feature.dashboard.DiscoveryFragment
import java.io.File

/**
 * §6.12 HISTORY.
 *
 * The direction filter, the search and the status filters are arguments to ONE
 * transformation ([HistoryPresentation.apply]) — §6.12 forbids an external
 * flag that only refreshes on the next unrelated change.
 *
 * "Remove from history" and "Delete file" are two distinct, unambiguous
 * actions, and opening a file that no longer exists says so rather than
 * crashing or doing nothing.
 */
class HistoryFragment : Screen() {

    override val navTab: BottomNavView.Tab = BottomNavView.Tab.HISTORY
    override val scrollable = false

    private lateinit var listArea: LinearLayout
    private lateinit var search: AppCompatEditText
    private var filter = HistoryPresentation.Filter()

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()

        toolbar.bind(getString(R.string.title_history))
        toolbar.addAction(R.drawable.ic_search, R.string.cd_search) {
            search.visibility = if (search.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        toolbar.addAction(R.drawable.ic_filter, R.string.cd_filter) { showStatusFilter() }
        toolbar.addAction(R.drawable.ic_trash, R.string.cd_clear_all) { confirmClearAll() }

        val direction = SegmentedControl(context)
        direction.bind(
            listOf(getString(R.string.history_received), getString(R.string.history_sent)),
        ) { index ->
            filter = filter.copy(
                direction = if (index == 0) Direction.RECEIVING else Direction.SENDING,
            )
            render()
        }
        column.addView(direction, wide(8))

        search = AppCompatEditText(context)
        search.hint = getString(R.string.history_search_hint)
        search.setSingleLine()
        search.visibility = View.GONE
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(editable: android.text.Editable?) {
                // Part of the same stream, not a flag applied later (§6.12).
                filter = filter.copy(query = editable?.toString().orEmpty())
                render()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        column.addView(search, wide(8))

        val scroller = android.widget.ScrollView(context)
        listArea = LinearLayout(context)
        listArea.orientation = LinearLayout.VERTICAL
        scroller.addView(listArea)
        column.addView(
            scroller,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        render()
    }

    override fun onStart() {
        super.onStart()
        render()
    }

    private fun render() {
        val context = context ?: return
        listArea.removeAllViews()
        val rows = HistoryPresentation.apply(AppServices.historyStore.read(), filter)

        if (rows.isEmpty()) {
            val receiving = filter.direction == Direction.RECEIVING
            listArea.addView(
                emptyState(
                    if (receiving) R.drawable.ic_receive else R.drawable.ic_send,
                    getString(
                        if (receiving) R.string.history_empty_received else R.string.history_empty_sent,
                    ),
                    getString(R.string.history_empty_action),
                ) { nav().push(DiscoveryFragment.newInstance(multiSelect = false)) },
            )
            return
        }

        for (group in HistoryPresentation.group(rows)) {
            val header = AppCompatTextView(context)
            header.setTextAppearance(context, R.style.TextAppearance_Morsecode_SectionHeader)
            header.text = group.title
            val headerParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            headerParams.topMargin = Shapes.dpInt(context, 12f)
            listArea.addView(header, headerParams)
            for (row in group.rows) listArea.addView(rowView(row))
        }
    }

    private fun rowView(row: HistoryStore.Row): View {
        val context = requireContext()
        val container = LinearLayout(context)
        container.orientation = LinearLayout.HORIZONTAL
        container.gravity = Gravity.CENTER_VERTICAL
        container.minimumHeight = Shapes.dpInt(context, 56f)

        val tile = app.morsecode.android.core.ui.TypeTileView(context)
        tile.bind(kindFor(row.name))
        val tileSize = Shapes.dpInt(context, 40f)
        container.addView(tile, LinearLayout.LayoutParams(tileSize, tileSize))

        val text = LinearLayout(context)
        text.orientation = LinearLayout.VERTICAL
        val name = AppCompatTextView(context)
        name.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        name.text = row.name
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        text.addView(name)
        val meta = AppCompatTextView(context)
        meta.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        meta.text = HistoryPresentation.metaLine(row)
        text.addView(meta)
        val textParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        textParams.marginStart = Shapes.dpInt(context, 12f)
        container.addView(text, textParams)

        val status = AppCompatTextView(context)
        status.setTextAppearance(context, R.style.TextAppearance_Morsecode_StateChip)
        status.text = "${HistoryPresentation.statusGlyph(row.state)} ${row.state.name}"
        status.setTextColor(
            ContextCompat.getColor(
                context,
                if (row.state == TransferState.COMPLETED) R.color.state_success else R.color.state_error,
            ),
        )
        container.addView(status)

        val overflow = AppCompatImageView(context)
        overflow.setImageResource(R.drawable.ic_overflow)
        overflow.contentDescription = getString(R.string.cd_overflow)
        val pad = Shapes.dpInt(context, 12f)
        overflow.setPadding(pad, pad, pad, pad)
        overflow.setOnClickListener { showRowMenu(overflow, row) }
        val touch = Shapes.dpInt(context, 48f)
        container.addView(overflow, LinearLayout.LayoutParams(touch, touch))

        container.contentDescription = "${row.name} ${meta.text} ${status.text}"
        container.isClickable = true
        container.setOnClickListener { openFile(row) }
        return container
    }

    /** §6.12's overflow: Open · Share · Send again · Remove · Delete file. */
    private fun showRowMenu(anchor: View, row: HistoryStore.Row) {
        val menu = PopupMenu(requireContext(), anchor)
        menu.menu.add(getString(R.string.history_open)).setOnMenuItemClickListener {
            openFile(row)
            true
        }
        menu.menu.add(getString(R.string.action_share)).setOnMenuItemClickListener {
            shareFile(row)
            true
        }
        menu.menu.add(getString(R.string.history_send_again)).setOnMenuItemClickListener {
            sendAgain(row)
            true
        }
        menu.menu.add(getString(R.string.history_remove)).setOnMenuItemClickListener {
            // Removes the ROW. The file stays exactly where it is (§6.12).
            AppServices.historyStore.remove(row.itemId)
            render()
            true
        }
        menu.menu.add(getString(R.string.history_delete_file)).setOnMenuItemClickListener {
            confirmDeleteFile(row)
            true
        }
        menu.show()
    }

    private fun locate(row: HistoryStore.Row): File? {
        val path = row.path ?: return null
        val file = File(path)
        return if (file.exists()) file else null
    }

    private fun openFile(row: HistoryStore.Row) {
        val file = locate(row)
        if (file == null) {
            // Never a crash and never a silent no-op (§6.12).
            Ui.snackbar(requireActivity(), getString(R.string.history_unavailable))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uriFor(file), "*/*")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }
            .onFailure { Ui.snackbar(requireActivity(), getString(R.string.history_unavailable)) }
    }

    private fun shareFile(row: HistoryStore.Row) {
        val file = locate(row) ?: run {
            Ui.snackbar(requireActivity(), getString(R.string.history_unavailable))
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_STREAM, uriFor(file))
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
    }

    private fun sendAgain(row: HistoryStore.Row) {
        val file = locate(row) ?: run {
            Ui.snackbar(requireActivity(), getString(R.string.history_unavailable))
            return
        }
        val transferFile = TransferFile(row.name, uriFor(file), null, file.length())
        val session = AppServices.transferEngine.session.value
        if (session is app.morsecode.android.core.model.SessionState.Connected) {
            AppServices.transferEngine.enqueue(listOf(transferFile))
            nav().push(app.morsecode.android.feature.transfer.TransferFragment.newInstance(Purpose.SENDER))
        } else {
            AppServices.transferEngine.holdShare(listOf(transferFile))
            nav().push(DiscoveryFragment.newInstance(multiSelect = false))
        }
    }

    private fun confirmDeleteFile(row: HistoryStore.Row) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_delete_file)
            .setMessage(row.name)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                locate(row)?.delete()
                AppServices.historyStore.remove(row.itemId)
                render()
            }
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.cd_clear_all)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                AppServices.historyStore.clear()
                render()
            }
            .show()
    }

    private fun showStatusFilter() {
        val labels = arrayOf(
            getString(R.string.history_filter_all),
            getString(R.string.history_filter_completed),
            getString(R.string.history_filter_failed),
            getString(R.string.history_filter_skipped),
        )
        AlertDialog.Builder(requireContext())
            .setItems(labels) { _, which ->
                filter = filter.copy(
                    states = when (which) {
                        1 -> setOf(TransferState.COMPLETED)
                        2 -> setOf(TransferState.FAILED)
                        3 -> setOf(TransferState.SKIPPED)
                        else -> emptySet()
                    },
                )
                render()
            }
            .show()
    }

    private fun uriFor(file: File) = androidx.core.content.FileProvider.getUriForFile(
        requireContext(),
        "${app.morsecode.android.BuildConfig.APPLICATION_ID}.fileprovider",
        file,
    )

    private fun kindFor(name: String) = when (
        app.morsecode.android.core.media.FileTypes.typeOf(name)
    ) {
        app.morsecode.android.core.model.FileType.IMAGE -> app.morsecode.android.core.ui.FileKind.IMAGE
        app.morsecode.android.core.model.FileType.VIDEO -> app.morsecode.android.core.ui.FileKind.VIDEO
        app.morsecode.android.core.model.FileType.AUDIO -> app.morsecode.android.core.ui.FileKind.AUDIO
        app.morsecode.android.core.model.FileType.DOCUMENT -> app.morsecode.android.core.ui.FileKind.DOC
        app.morsecode.android.core.model.FileType.ARCHIVE -> app.morsecode.android.core.ui.FileKind.ARCHIVE
        app.morsecode.android.core.model.FileType.APK -> app.morsecode.android.core.ui.FileKind.APK
        else -> app.morsecode.android.core.ui.FileKind.UNKNOWN
    }

    private fun wide(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        return params
    }
}
