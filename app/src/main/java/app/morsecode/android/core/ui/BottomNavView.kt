package app.morsecode.android.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.ImageViewCompat
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * §4.12f BottomNav: Connect (◎) · Files (▭) · History (◷) · Settings (≡).
 * The selected item gets an accent-washed rounded tile behind the icon and an
 * accent label.
 *
 * §5.1: exactly four tabs, no secondary shortcut row. The nav stays visible on
 * the transfer, broadcast and Now-Playing destinations and the launching tab
 * stays highlighted (§5.2); the photo viewer and the video player are the
 * documented exception and hide it.
 */
class BottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Tab(val iconRes: Int, val labelRes: Int) {
        CONNECT(R.drawable.ic_nav_connect, R.string.nav_connect),
        FILES(R.drawable.ic_nav_files, R.string.nav_files),
        HISTORY(R.drawable.ic_nav_history, R.string.nav_history),
        SETTINGS(R.drawable.ic_nav_settings, R.string.nav_settings),
    }

    private val icons = HashMap<Tab, AppCompatImageView>()
    private val labels = HashMap<Tab, AppCompatTextView>()
    private var selected: Tab = Tab.CONNECT
    private var listener: ((Tab) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        background = Shapes.rounded(ThemeColors.resolve(context, R.attr.colorSurfaceCard), 0f)
        val padV = Shapes.dpInt(context, 6f)
        setPadding(0, padV, 0, padV)
        for (tab in Tab.values()) addTab(tab)
        select(Tab.CONNECT, notify = false)
    }

    fun setOnTabSelected(listener: (Tab) -> Unit) {
        this.listener = listener
    }

    fun select(tab: Tab, notify: Boolean = true) {
        selected = tab
        val accent = ThemeColors.accent(context)
        val idle = ThemeColors.resolve(context, R.attr.colorTextSecondary)
        for (candidate in Tab.values()) {
            val isSelected = candidate == tab
            val icon = icons[candidate] ?: continue
            val label = labels[candidate] ?: continue
            ImageViewCompat.setImageTintList(
                icon,
                ColorStateList.valueOf(if (isSelected) accent else idle),
            )
            icon.background = if (isSelected) Shapes.accentWash(context, 12f) else null
            label.setTextColor(if (isSelected) accent else idle)
        }
        if (notify) listener?.invoke(tab)
    }

    fun selectedTab(): Tab = selected

    private fun addTab(tab: Tab) {
        val cell = LinearLayout(context)
        cell.orientation = VERTICAL
        cell.gravity = Gravity.CENTER
        cell.minimumHeight = Shapes.dpInt(context, 48f)
        cell.isClickable = true
        cell.isFocusable = true
        cell.background = Shapes.pressable(
            context,
            Shapes.rounded(Color.TRANSPARENT, 0f),
            Shapes.rounded(ThemeColors.resolve(context, R.attr.colorSurfacePressed), 0f),
        )

        val icon = AppCompatImageView(context)
        icon.setImageResource(tab.iconRes)
        val pad = Shapes.dpInt(context, 6f)
        icon.setPadding(pad * 2, pad, pad * 2, pad)
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        cell.addView(
            icon,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )

        val label = AppCompatTextView(context)
        label.setText(tab.labelRes)
        label.textSize = 11f
        label.gravity = Gravity.CENTER
        cell.addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        cell.contentDescription = context.getString(tab.labelRes)
        cell.setOnClickListener { select(tab) }

        icons[tab] = icon
        labels[tab] = label
        addView(cell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }
}
