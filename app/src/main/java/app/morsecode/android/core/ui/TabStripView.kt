package app.morsecode.android.core.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors
import com.google.android.material.tabs.TabLayout

/**
 * §6.9's tab strip — now Material's `TabLayout`, restyled (§3.3 [CHANGED]).
 *
 * §6.9 requires five equal cells filling the width with a 2 dp accent
 * underline spanning its own cell, labels that shrink before the strip does,
 * and no horizontal scrolling. `MODE_FIXED` + `GRAVITY_FILL` is exactly that,
 * and it brings the ripples, the indicator animation and the accessibility
 * roles the hand-built strip never had.
 *
 * The public API is unchanged, because §20.1's "ONE tab-index source of truth"
 * is the contract that matters: this view still owns the index and reports
 * changes, and a screen still renders whatever index it reports.
 */
class TabStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tabs = TabLayout(context)
    private var listener: ((Int) -> Unit)? = null

    init {
        tabs.tabMode = TabLayout.MODE_FIXED
        tabs.tabGravity = TabLayout.GRAVITY_FILL
        tabs.isTabIndicatorFullWidth = false
        tabs.setSelectedTabIndicatorHeight(Shapes.dpInt(context, 2f))
        tabs.setSelectedTabIndicatorColor(ThemeColors.accent(context))
        tabs.setTabTextColors(
            ThemeColors.resolve(context, R.attr.colorTextSecondary),
            ThemeColors.accent(context),
        )
        tabs.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        tabs.tabRippleColor = android.content.res.ColorStateList.valueOf(
            ThemeColors.withAlpha(ThemeColors.resolve(context, R.attr.colorTextPrimary), 0.12f),
        )
        addView(
            tabs,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                listener?.invoke(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    fun bind(labels: List<CharSequence>, selectedIndex: Int = 0, onSelected: (Int) -> Unit) {
        listener = null
        tabs.removeAllTabs()
        for (label in labels) {
            val tab = tabs.newTab()
            tab.text = label
            tab.contentDescription = label
            tabs.addTab(tab)
        }
        tabs.getTabAt(selectedIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0)))?.select()
        listener = onSelected
    }

    /** Selecting the tab that is already current must not re-notify (§20.1). */
    fun select(index: Int) {
        if (index == selectedIndex()) return
        tabs.getTabAt(index)?.select()
    }

    fun selectedIndex(): Int = tabs.selectedTabPosition.coerceAtLeast(0)
}
