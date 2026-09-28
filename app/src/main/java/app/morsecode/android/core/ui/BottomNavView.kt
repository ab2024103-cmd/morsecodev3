package app.morsecode.android.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.StringRes
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * §5.1's four tabs — now Material's `BottomNavigationView`, restyled
 * (§3.3 [CHANGED]).
 *
 * The hand-built row did the accent-washed tile by hand and had no ripple, no
 * state list animation, no label truncation behaviour and no navigation-bar
 * role for TalkBack. Material has all of them; §4.4's accent wash arrives as
 * `itemActiveIndicatorStyle` tinted from the token.
 *
 * The API is unchanged — §5.2's rule that the launching tab stays lit depends
 * on `select(tab, notify = false)`, and that still means exactly what it did.
 */
class BottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class Tab(val iconRes: Int, @StringRes val labelRes: Int) {
        CONNECT(R.drawable.ic_nav_connect, R.string.nav_connect),
        FILES(R.drawable.ic_nav_files, R.string.nav_files),
        HISTORY(R.drawable.ic_nav_history, R.string.nav_history),
        SETTINGS(R.drawable.ic_nav_settings, R.string.nav_settings),
    }

    private val nav = BottomNavigationView(context)
    private var listener: ((Tab) -> Unit)? = null
    private var selected: Tab = Tab.CONNECT
    private var muted = false

    init {
        for (tab in Tab.values()) {
            val item = nav.menu.add(0, tab.ordinal, tab.ordinal, tab.labelRes)
            item.setIcon(tab.iconRes)
        }
        nav.labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
        nav.setBackgroundColor(ThemeColors.resolve(context, R.attr.colorSurfaceCard))

        val accent = ThemeColors.accent(context)
        val idle = ThemeColors.resolve(context, R.attr.colorTextSecondary)
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked),
        )
        val colors = ColorStateList(states, intArrayOf(accent, idle))
        nav.itemIconTintList = colors
        nav.itemTextColor = colors
        // §4.4's accent wash, as the active indicator rather than a hand-drawn
        // rounded rectangle behind the icon.
        nav.itemActiveIndicatorColor =
            ColorStateList.valueOf(ThemeColors.accentWashFill(context))

        nav.setOnItemSelectedListener { item ->
            val tab = Tab.values()[item.itemId]
            selected = tab
            if (!muted) listener?.invoke(tab)
            true
        }
        nav.selectedItemId = Tab.CONNECT.ordinal

        addView(nav, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setOnTabSelected(listener: (Tab) -> Unit) {
        this.listener = listener
    }

    /** §5.2: lighting the launching tab must not re-navigate. */
    fun select(tab: Tab, notify: Boolean = true) {
        muted = !notify
        nav.selectedItemId = tab.ordinal
        selected = tab
        muted = false
    }

    fun selectedTab(): Tab = selected
}
