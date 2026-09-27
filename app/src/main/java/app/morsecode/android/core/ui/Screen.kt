package app.morsecode.android.core.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * Base class for every fragment destination in the shell (§5.2, §8.1).
 *
 * It owns the chrome that must not drift between screens: the toolbar, the
 * screen padding, the §4.14 content-width cap on wide screens, and the two
 * facts the activity needs to lay itself out — which tab stays lit, and
 * whether this destination hides the bottom navigation.
 *
 * §5.2: Transfer, Broadcast and Now-Playing KEEP the bottom nav and leave the
 * launching tab highlighted. Only the photo viewer and the video player hide
 * it, and those are separate immersive activities (§8.1), not screens here.
 */
abstract class Screen : Fragment() {

    /** The tab this destination lights. Null means "keep the launching tab lit". */
    open val navTab: BottomNavView.Tab? = null

    /** Almost always false: hiding the nav is the viewer/player exception (§5.2). */
    open val hidesBottomNav: Boolean = false

    /** Screens with their own list surface turn the shared scroller off. */
    open val scrollable: Boolean = true

    protected lateinit var toolbar: ScreenToolbar
        private set

    /** The column every screen adds its content to. */
    protected lateinit var content: LinearLayout
        private set

    /** Set when the destination was pushed, so the launching tab can stay lit. */
    fun launchTab(): BottomNavView.Tab? =
        arguments?.getString(Nav.ARG_LAUNCH_TAB)?.let { BottomNavView.Tab.valueOf(it) }

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ThemeColors.resolve(context, R.attr.colorBgBase))
        val padH = resources.getDimensionPixelSize(R.dimen.screen_padding)
        root.setPadding(padH, Shapes.dpInt(context, 8f), padH, 0)

        toolbar = ScreenToolbar(context)
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        content = LinearLayout(context)
        content.orientation = LinearLayout.VERTICAL

        val bounded = BoundedWidthLayout(context)
        bounded.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val fill = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        if (scrollable) {
            val scroller = ScrollView(context)
            scroller.isFillViewport = true
            scroller.clipToPadding = false
            // INV-11: nothing in the shell ever forces a list back to the top.
            scroller.addView(
                bounded,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            root.addView(scroller, fill)
        } else {
            root.addView(bounded, fill)
        }

        onBuildScreen(content)
        return root
    }

    /** Build the screen's content into [column]. Called once per view creation. */
    protected abstract fun onBuildScreen(column: LinearLayout)

    protected fun nav(): Navigator = navigator()

    /**
     * Standard empty state for a list (§6.19): icon, one line, one action.
     * Every list in the product gets one — a bare blank area is a defect.
     */
    protected fun emptyState(
        iconRes: Int,
        line: CharSequence,
        actionLabel: CharSequence? = null,
        onAction: (() -> Unit)? = null,
    ): EmptyStateView {
        val view = EmptyStateView(requireContext())
        view.bind(iconRes, line, actionLabel, onAction)
        return view
    }

    /** §4.14: rows, sheets and dialogs cap their width and centre on wide screens. */
    private class BoundedWidthLayout(context: android.content.Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val max = resources.getDimensionPixelSize(R.dimen.content_max_width)
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val spec = if (width > max) {
                MeasureSpec.makeMeasureSpec(max, MeasureSpec.getMode(widthMeasureSpec))
            } else {
                widthMeasureSpec
            }
            super.onMeasure(spec, heightMeasureSpec)
        }
    }
}
