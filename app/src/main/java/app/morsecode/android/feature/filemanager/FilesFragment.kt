package app.morsecode.android.feature.filemanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.morsecode.android.R
import app.morsecode.android.core.media.DayGroups
import app.morsecode.android.core.media.PathSegments
import app.morsecode.android.core.media.Selection
import app.morsecode.android.core.media.SortRules
import app.morsecode.android.core.media.ViewerSession
import app.morsecode.android.core.model.DirectoryEntry
import app.morsecode.android.core.model.DirectoryListing
import app.morsecode.android.core.model.FileType
import app.morsecode.android.core.model.MediaCategory
import app.morsecode.android.core.model.MediaItem
import app.morsecode.android.core.model.SortOrder
import app.morsecode.android.core.model.TransferFile
import app.morsecode.android.core.ui.BottomNavView
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Purpose
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.TabStripView
import app.morsecode.android.core.ui.TipView
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.util.Fmt
import app.morsecode.android.core.util.Permissions
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices
import app.morsecode.android.feature.dashboard.DiscoveryFragment
import app.morsecode.android.feature.transfer.TransferFragment
import app.morsecode.android.feature.viewer.ViewerActivity
import app.morsecode.android.feature.viewer.VideoPlayerActivity
import kotlinx.coroutines.launch

/**
 * §6.9 FILES — five tabs over ONE selection basket, a working sort control and
 * a real file browser.
 *
 * The rules this screen exists to honour:
 *  - ONE tab-index source of truth ([TabStripView]); the rendered data comes
 *    from whatever index it reports (§20.1).
 *  - Per-item selection on every tab, plus the group select-all in the header
 *    (§6.9) — a tab that only offers select-all is a bug.
 *  - A body tap OPENS while nothing is selected and toggles once something is.
 *  - [Send] enqueues, CLEARS the selection, navigates to the transfer screen
 *    and confirms with a snackbar (§6.9, A24) — staying on the picker is a
 *    defect.
 *  - Categories and folders open REAL listings with an address bar (§6.9.1);
 *    a row that only raises a toast is a defect.
 *  - A folder that cannot be read renders §12.2's "Access needed" state, never
 *    an empty folder.
 */
class FilesFragment : Screen() {

    override val navTab: BottomNavView.Tab = BottomNavView.Tab.FILES
    override val scrollable = false

    private lateinit var tabs: TabStripView
    private lateinit var sortEcho: AppCompatTextView
    private lateinit var list: RecyclerView
    private lateinit var emptyHolder: FrameLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var addressBar: LinearLayout

    private val selection get() = AppServices.selection
    private var order = SortOrder()
    private var currentPath: String? = null
    private var lastItems: List<MediaItem> = emptyList()

    private val gridAdapter by lazy {
        MediaGridAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            selection = selection,
            onOpen = { item, siblings -> open(item, siblings) },
            onSelectionChanged = { renderSelectionBar() },
        )
    }
    private val listAdapter by lazy {
        MediaListAdapter(
            selection = selection,
            onOpen = { item -> open(item, lastItems) },
            onSelectionChanged = { renderSelectionBar() },
        )
    }
    private val directoryAdapter by lazy {
        DirectoryAdapter(
            selection = selection,
            onOpenFolder = { entry -> openDirectory(entry.path) },
            onOpenFile = { entry -> openDirectoryFile(entry) },
            onSelectionChanged = { renderSelectionBar() },
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { renderTab(tabs.selectedIndex()) }

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()

        toolbar.bind(getString(R.string.title_files))
        toolbar.addAction(R.drawable.ic_search, R.string.cd_search) { stub() }
        toolbar.addAction(R.drawable.ic_sort, R.string.cd_sort) { showSortSheet() }
        toolbar.addAction(R.drawable.ic_view_grid, R.string.cd_view_toggle) { stub() }

        tabs = TabStripView(context)
        tabs.bind(
            listOf(
                getString(R.string.files_tab_photos),
                getString(R.string.files_tab_videos),
                getString(R.string.files_tab_music),
                getString(R.string.files_tab_apps),
                getString(R.string.files_tab_files),
            ),
        ) { index ->
            currentPath = null
            renderTab(index)
        }
        column.addView(tabs, wide(0))

        // §6.9: the active order is echoed under the tab strip (A34).
        sortEcho = AppCompatTextView(context)
        sortEcho.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        sortEcho.text = SortRules.label(order)
        column.addView(sortEcho, wide(4))

        val tip = TipView(context)
        tip.bind(AppServices.prefs, TIP_ID, getString(R.string.files_hint))
        column.addView(tip, wide(8))

        addressBar = LinearLayout(context)
        addressBar.orientation = LinearLayout.HORIZONTAL
        addressBar.gravity = Gravity.CENTER_VERTICAL
        addressBar.visibility = View.GONE
        column.addView(addressBar, wide(8))

        list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        // INV-11: item-level updates, never a wholesale rebind that scrolls.
        list.itemAnimator?.changeDuration = 0
        column.addView(
            list,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        emptyHolder = FrameLayout(context)
        column.addView(emptyHolder, wide(0))

        selectionBar = buildSelectionBar(context)
        column.addView(selectionBar, wide(8))

        observeSelection()
        renderTab(tabs.selectedIndex())
    }

    override fun onStart() {
        super.onStart()
        // §6.9's counts need read access; §6.1's skipped grant is asked for
        // here, contextually, the first time it is actually needed.
        val missing = Permissions.missing(requireContext(), Permissions.mediaRead())
        if (missing.isNotEmpty() && !askedForPermissions) {
            askedForPermissions = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ----- Tabs -------------------------------------------------------------

    private fun renderTab(index: Int) {
        val context = context ?: return
        emptyHolder.removeAllViews()
        addressBar.visibility = View.GONE

        when (index) {
            0 -> renderGrid(MediaCategory.PHOTOS, R.string.files_empty_photos)
            1 -> renderGrid(MediaCategory.VIDEOS, R.string.files_empty_videos)
            2 -> renderList(MediaCategory.MUSIC, R.string.files_empty_music)
            3 -> renderList(MediaCategory.APPS, R.string.files_empty_apps)
            else -> {
                val path = currentPath
                if (path == null) renderFilesRoot() else openDirectory(path)
            }
        }
    }

    private fun renderGrid(category: MediaCategory, emptyRes: Int) {
        val context = requireContext()
        val columns = spanCount(context)
        list.layoutManager = GridLayoutManager(context, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (gridAdapter.isHeader(position)) columns else 1
            }
        }
        list.adapter = gridAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            val page = AppServices.mediaLibrary.page(category, order)
            lastItems = SortRules.sortItems(page.items, order)
            // §6.9.3: day headers appear exactly once, in order — the grouping
            // folds consecutive runs of the query's own ordering (A7).
            gridAdapter.submit(DayGroups.group(lastItems))
            showEmptyIfNeeded(lastItems.isEmpty(), emptyRes)
        }
    }

    private fun renderList(category: MediaCategory, emptyRes: Int) {
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = listAdapter
        viewLifecycleOwner.lifecycleScope.launch {
            val page = AppServices.mediaLibrary.page(category, order)
            lastItems = SortRules.sortItems(page.items, order)
            listAdapter.submit(lastItems)
            showEmptyIfNeeded(lastItems.isEmpty(), emptyRes)
        }
    }

    /** §6.9's Files tab root: CATEGORIES, then FOLDERS, then "+ Add a folder". */
    private fun renderFilesRoot() {
        val context = requireContext()
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = directoryAdapter
        addressBar.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val counts = AppServices.mediaLibrary.counts().associateBy { it.category }
            val rows = ArrayList<DirectoryEntry>()
            // Category rows open a real listing (A30), so they are entries too.
            for (category in CATEGORY_ROWS) {
                rows.add(
                    DirectoryEntry(
                        name = categoryLabel(category),
                        path = CATEGORY_PREFIX + category.name,
                        isDirectory = true,
                        sizeBytes = 0,
                        dateMillis = 0,
                        type = FileType.UNKNOWN,
                        childCount = counts[category]?.count ?: 0,
                    ),
                )
            }
            for (folder in AppServices.mediaLibrary.quickFolders()) {
                rows.add(
                    DirectoryEntry(
                        name = folder.name,
                        path = folder.path,
                        isDirectory = true,
                        sizeBytes = folder.sizeBytes,
                        dateMillis = 0,
                        type = FileType.UNKNOWN,
                        childCount = folder.itemCount,
                    ),
                )
            }
            directoryAdapter.submit(rows)
            showEmptyIfNeeded(rows.isEmpty(), R.string.files_empty_files)
        }
    }

    /**
     * A real listing with the §6.9.1 address bar. §12.2: a folder that cannot
     * be read renders "Access needed to view this folder — Grant access", never
     * an empty folder.
     */
    private fun openDirectory(path: String) {
        currentPath = path
        tabs.select(FILES_TAB_INDEX)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = directoryAdapter

        if (path.startsWith(CATEGORY_PREFIX)) {
            renderCategoryListing(MediaCategory.valueOf(path.removePrefix(CATEGORY_PREFIX)))
            return
        }

        renderAddressBar(path)
        viewLifecycleOwner.lifecycleScope.launch {
            when (val listing = AppServices.mediaLibrary.list(path)) {
                is DirectoryListing.Ok -> {
                    directoryAdapter.submit(SortRules.sortEntries(listing.entries, order))
                    showEmptyIfNeeded(listing.entries.isEmpty(), R.string.files_empty_files)
                }
                is DirectoryListing.AccessDenied -> {
                    directoryAdapter.submit(emptyList())
                    emptyHolder.removeAllViews()
                    emptyHolder.addView(
                        emptyState(
                            R.drawable.ic_lock,
                            getString(R.string.files_access_needed),
                            getString(R.string.files_grant_access),
                        ) { requestTreeAccess(listing.suggestedTreeUri) },
                    )
                }
                is DirectoryListing.Missing -> {
                    directoryAdapter.submit(emptyList())
                    showEmptyIfNeeded(true, R.string.files_empty_files)
                }
            }
        }
    }

    private fun renderCategoryListing(category: MediaCategory) {
        addressBar.visibility = View.GONE
        list.adapter = listAdapter
        viewLifecycleOwner.lifecycleScope.launch {
            val page = AppServices.mediaLibrary.page(category, order)
            lastItems = SortRules.sortItems(page.items, order)
            listAdapter.submit(lastItems)
            showEmptyIfNeeded(lastItems.isEmpty(), R.string.files_empty_files)
        }
    }

    /** §6.9.1: every segment is its own 48 dp tap target and jumps there. */
    private fun renderAddressBar(path: String) {
        val context = requireContext()
        addressBar.removeAllViews()
        addressBar.visibility = View.VISIBLE
        addressBar.minimumHeight = Shapes.dpInt(context, 48f)

        val up = AppCompatImageView(context)
        up.setImageResource(R.drawable.ic_arrow_up)
        up.contentDescription = getString(R.string.files_up_one_level)
        val touch = Shapes.dpInt(context, 48f)
        val pad = Shapes.dpInt(context, 12f)
        up.setPadding(pad, pad, pad, pad)
        val parent = PathSegments.parentOf(path)
        up.alpha = if (parent == null) 0.35f else 1f
        up.setOnClickListener { parent?.let { openDirectory(it) } }
        addressBar.addView(up, LinearLayout.LayoutParams(touch, touch))

        val scroller = android.widget.HorizontalScrollView(context)
        scroller.isHorizontalScrollBarEnabled = false
        val segments = LinearLayout(context)
        segments.orientation = LinearLayout.HORIZONTAL
        segments.gravity = Gravity.CENTER_VERTICAL

        for ((index, segment) in PathSegments.of(path).withIndex()) {
            if (index > 0) {
                val chevron = AppCompatImageView(context)
                chevron.setImageResource(R.drawable.ic_chevron_right)
                chevron.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                val size = Shapes.dpInt(context, 16f)
                segments.addView(chevron, LinearLayout.LayoutParams(size, size))
            }
            val label = AppCompatTextView(context)
            label.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
            label.text = segment.label
            label.minHeight = touch
            label.gravity = Gravity.CENTER_VERTICAL
            label.setPadding(pad, 0, pad, 0)
            label.isClickable = true
            label.contentDescription = getString(R.string.cd_address_segment, segment.label)
            label.setOnClickListener { openDirectory(segment.path) }
            segments.addView(label)
        }
        scroller.addView(segments)
        addressBar.addView(
            scroller,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val copy = AppCompatImageView(context)
        copy.setImageResource(R.drawable.ic_copy)
        copy.contentDescription = getString(R.string.files_copy_path)
        copy.setPadding(pad, pad, pad, pad)
        copy.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("path", PathSegments.display(path)))
            Ui.snackbar(requireActivity(), getString(R.string.files_path_copied))
        }
        addressBar.addView(copy, LinearLayout.LayoutParams(touch, touch))
    }

    // ----- Selection --------------------------------------------------------

    private fun buildSelectionBar(context: Context): LinearLayout {
        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.visibility = View.GONE
        bar.background = Shapes.card(context, 12f)
        val pad = Shapes.dpInt(context, 8f)
        bar.setPadding(pad, pad, pad, pad)
        return bar
    }

    private fun observeSelection() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                selection.items.collect { renderSelectionBar() }
            }
        }
    }

    private fun renderSelectionBar() {
        val context = context ?: return
        selectionBar.removeAllViews()
        if (!selection.isActive) {
            selectionBar.visibility = View.GONE
            return
        }
        selectionBar.visibility = View.VISIBLE

        val send = Buttons.accent(
            context,
            getString(R.string.files_send_selection, selection.count, Fmt.size(selection.totalBytes)),
        ) { sendSelection() }
        selectionBar.addView(send, cell(context, weight = 2f))

        selectionBar.addView(
            Buttons.outlined(context, getString(R.string.action_share)) { shareSelection() },
            cell(context),
        )
        selectionBar.addView(
            Buttons.outlined(context, getString(R.string.action_delete)) { stub() },
            cell(context),
        )
        selectionBar.addView(
            Buttons.outlined(context, getString(R.string.action_cancel)) { selection.clear() },
            cell(context),
        )
    }

    /**
     * §6.9 WHAT [Send] MUST DO — it is a navigation action, not a toast:
     * enqueue, CLEAR the selection, navigate to the transfer screen, and
     * confirm with a snackbar naming the peer (A24).
     */
    private fun sendSelection() {
        val taken = selection.takeAll()
        if (taken.isEmpty()) return
        val bytes = taken.sumOf { it.sizeBytes }
        val files = taken.mapNotNull { entry ->
            val uri = entry.uri ?: entry.path?.let { android.net.Uri.fromFile(java.io.File(it)) }
            uri?.let {
                TransferFile(
                    displayName = if (entry.isDirectory) "${entry.displayName}.zip" else entry.displayName,
                    uri = it,
                    mime = null,
                    size = entry.sizeBytes,
                )
            }
        }

        val peerName = (AppServices.transferEngine.session.value as? app.morsecode.android.core.model.SessionState.Connected)?.peerName
        if (peerName == null) {
            // §6.9: with no session, open Discovery with the batch held, then
            // enqueue the moment a peer accepts.
            AppServices.transferEngine.holdShare(files)
            Ui.snackbar(
                requireActivity(),
                getString(R.string.files_queued_no_peer, taken.size, Fmt.size(bytes)),
            )
            nav().push(DiscoveryFragment.newInstance(multiSelect = false))
            return
        }

        AppServices.transferEngine.enqueue(files)
        Ui.snackbar(
            requireActivity(),
            getString(R.string.files_queued_snackbar, taken.size, Fmt.size(bytes), peerName),
        )
        nav().push(TransferFragment.newInstance(Purpose.SENDER))
    }

    /** §6.9: the real Android chooser, never an in-app imitation. */
    private fun shareSelection() {
        val uris = ArrayList<android.net.Uri>()
        for (entry in selection.snapshot()) {
            val uri = entry.uri ?: continue
            uris.add(uri)
        }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
    }

    // ----- Opening ----------------------------------------------------------

    private fun open(item: MediaItem, siblings: List<MediaItem>) {
        // §6.10/§6.11: the viewer and the player page through the SAME list the
        // grid rendered, bound to the item that was tapped.
        val index = siblings.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
        ViewerSession.open(siblings, index)
        when (item.type) {
            FileType.VIDEO -> startActivity(Intent(requireContext(), VideoPlayerActivity::class.java))
            FileType.IMAGE -> startActivity(Intent(requireContext(), ViewerActivity::class.java))
            else -> openExternally(item)
        }
    }

    private fun openDirectoryFile(entry: DirectoryEntry) {
        val uri = android.net.Uri.fromFile(java.io.File(entry.path))
        val item = MediaItem(
            id = entry.path.hashCode().toLong(),
            uri = uri,
            name = entry.name,
            sizeBytes = entry.sizeBytes,
            dateMillis = entry.dateMillis,
            mimeType = null,
            type = entry.type,
            path = entry.path,
        )
        open(item, listOf(item))
    }

    private fun openExternally(item: MediaItem) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(item.uri, item.mimeType ?: "*/*")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }
            .onFailure { Ui.snackbar(requireActivity(), getString(R.string.stub_screen)) }
    }

    private fun requestTreeAccess(hint: android.net.Uri?) {
        runCatching { startActivity(AppServices.safStore.openTreeIntent(hint)) }
    }

    // ----- Sort -------------------------------------------------------------

    private fun showSortSheet() {
        SortSheet(order) { chosen ->
            order = chosen
            sortEcho.text = SortRules.label(chosen)
            // Re-orders the CURRENT tab immediately (§6.9, A34).
            renderTab(tabs.selectedIndex())
        }.show(parentFragmentManager, "sort")
    }

    // ----- Plumbing ---------------------------------------------------------

    private fun showEmptyIfNeeded(isEmpty: Boolean, messageRes: Int) {
        emptyHolder.removeAllViews()
        if (!isEmpty) return
        emptyHolder.addView(
            emptyState(R.drawable.ic_empty_box, getString(messageRes), getString(R.string.files_empty_action)) {
                requestTreeAccess(null)
            },
        )
    }

    private fun spanCount(context: Context): Int {
        // §4.14: grids use a target cell width, never a hard-coded span.
        val target = resources.getDimensionPixelSize(R.dimen.grid_cell_target)
        val width = resources.displayMetrics.widthPixels
        return (width / target).coerceAtLeast(3)
    }

    private fun categoryLabel(category: MediaCategory): String = getString(
        when (category) {
            MediaCategory.DOCUMENTS -> R.string.files_category_documents
            MediaCategory.EBOOKS -> R.string.files_category_ebooks
            MediaCategory.ARCHIVES -> R.string.files_category_archives
            MediaCategory.APKS -> R.string.files_category_apks
            else -> R.string.files_category_large
        },
    )

    private fun cell(context: Context, weight: Float = 1f): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        params.marginEnd = Shapes.dpInt(context, 6f)
        return params
    }

    private fun wide(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        return params
    }

    private fun stub() = Ui.snackbar(requireActivity(), getString(R.string.stub_screen))

    private var askedForPermissions = false

    private companion object {
        const val TIP_ID = "files.tap-to-open"
        const val FILES_TAB_INDEX = 4
        const val CATEGORY_PREFIX = "category://"
        val CATEGORY_ROWS = listOf(
            MediaCategory.DOCUMENTS,
            MediaCategory.EBOOKS,
            MediaCategory.ARCHIVES,
            MediaCategory.APKS,
            MediaCategory.LARGE_FILES,
        )
    }
}
