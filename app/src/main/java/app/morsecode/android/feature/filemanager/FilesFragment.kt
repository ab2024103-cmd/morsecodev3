package app.morsecode.android.feature.filemanager

import android.widget.FrameLayout
import android.widget.LinearLayout
import app.morsecode.android.R
import app.morsecode.android.core.ui.BottomNavView
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.TabStripView
import app.morsecode.android.core.ui.TipView
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.di.AppServices

/**
 * §6.9 FILES. Toolbar "Files" + search · SORT · view toggle, then EXACTLY five
 * tabs — Photos | Videos | Music | Apps | Files — in a strip that fills the
 * width with equal cells, then the one-line hint, then the tab's content.
 *
 * ONE tab-index source of truth: [TabStripView] owns the index and this screen
 * renders whatever index it reports. §6.9 calls a mismatch release-blocking
 * because it makes people send the wrong files, so there is deliberately no
 * second copy of the index here.
 *
 * Stage 3 renders each tab's empty state (§6.19). The media library, the
 * selection basket, the address bar and the working sort sheet arrive with
 * Stage 4 and Stage 10.
 */
class FilesFragment : Screen() {

    override val navTab: BottomNavView.Tab = BottomNavView.Tab.FILES

    private lateinit var tabContent: FrameLayout

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()

        toolbar.bind(getString(R.string.title_files))
        toolbar.addAction(R.drawable.ic_search, R.string.cd_search) { stub() }
        toolbar.addAction(R.drawable.ic_sort, R.string.cd_sort) { stub() }
        toolbar.addAction(R.drawable.ic_view_grid, R.string.cd_view_toggle) { stub() }

        val tabs = TabStripView(context)
        tabs.bind(
            listOf(
                getString(R.string.files_tab_photos),
                getString(R.string.files_tab_videos),
                getString(R.string.files_tab_music),
                getString(R.string.files_tab_apps),
                getString(R.string.files_tab_files),
            ),
        ) { index -> renderTab(index) }
        column.addView(
            tabs,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // §6.9: a one-line hint states the tap rule for the grids.
        val tip = TipView(context)
        tip.bind(AppServices.prefs, TIP_ID, getString(R.string.files_hint))
        val tipParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        tipParams.topMargin = Shapes.dpInt(context, 8f)
        column.addView(tip, tipParams)

        tabContent = FrameLayout(context)
        val contentParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        contentParams.topMargin = Shapes.dpInt(context, 8f)
        column.addView(tabContent, contentParams)

        renderTab(tabs.selectedIndex())
    }

    /** Renders whatever the strip says is selected — no second index (§6.9). */
    private fun renderTab(index: Int) {
        val line = when (index) {
            0 -> R.string.files_empty_photos
            1 -> R.string.files_empty_videos
            2 -> R.string.files_empty_music
            3 -> R.string.files_empty_apps
            else -> R.string.files_empty_files
        }
        val icon = when (index) {
            0 -> R.drawable.ic_type_image
            1 -> R.drawable.ic_type_video
            2 -> R.drawable.ic_type_audio
            3 -> R.drawable.ic_type_apk
            else -> R.drawable.ic_folder
        }
        tabContent.removeAllViews()
        tabContent.addView(
            emptyState(icon, getString(line), getString(R.string.files_empty_action)) { stub() },
        )
    }

    private fun stub() = Ui.snackbar(requireActivity(), getString(R.string.stub_screen))

    private companion object {
        const val TIP_ID = "files.tap-to-open"
    }
}
