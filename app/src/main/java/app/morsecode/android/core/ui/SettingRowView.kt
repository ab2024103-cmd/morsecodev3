package app.morsecode.android.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.ImageViewCompat
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * §6.13 settings row: label, subtitle, and either a chevron (opens something)
 * or a switch (toggles something).
 *
 * §6.13 is explicit that every switch must be wired to behaviour; this view
 * therefore has no "display only" mode — a row is given either a click action
 * or a toggle listener, and a switch without one cannot be constructed.
 */
class SettingRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val labelView = AppCompatTextView(context)
    private val subtitleView = AppCompatTextView(context)
    // §15.1: decorative — the row's own label and subtitle are the content.
    private val chevron = AppCompatImageView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val toggle = com.google.android.material.materialswitch.MaterialSwitch(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Shapes.dpInt(context, 56f)
        val padH = Shapes.dpInt(context, 14f)
        val padV = Shapes.dpInt(context, 12f)
        setPadding(padH, padV, padH, padV)

        val column = LinearLayout(context)
        column.orientation = VERTICAL

        labelView.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        column.addView(labelView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        subtitleView.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        subtitleView.visibility = GONE
        column.addView(
            subtitleView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        chevron.setImageResource(R.drawable.ic_chevron_right)
        ImageViewCompat.setImageTintList(
            chevron,
            ColorStateList.valueOf(ThemeColors.resolve(context, R.attr.colorTextMeta)),
        )
        chevron.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        chevron.visibility = GONE
        val iconSize = Shapes.dpInt(context, 24f)
        addView(chevron, LayoutParams(iconSize, iconSize))

        toggle.visibility = GONE
        addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun bindAction(label: CharSequence, subtitle: CharSequence? = null, onClick: () -> Unit) {
        paintText(label, subtitle)
        chevron.visibility = VISIBLE
        toggle.visibility = GONE
        isClickable = true
        isFocusable = true
        contentDescription = if (subtitle == null) label else "$label. $subtitle"
        background = Shapes.pressable(
            context,
            Shapes.rounded(android.graphics.Color.TRANSPARENT, Shapes.dp(context, 12f)),
            Shapes.rounded(
                ThemeColors.resolve(context, R.attr.colorSurfacePressed),
                Shapes.dp(context, 12f),
            ),
        )
        setOnClickListener { onClick() }
    }

    fun bindToggle(
        label: CharSequence,
        subtitle: CharSequence? = null,
        checked: Boolean,
        enabled: Boolean = true,
        onChanged: (Boolean) -> Unit,
    ) {
        paintText(label, subtitle)
        chevron.visibility = GONE
        toggle.visibility = VISIBLE
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = checked
        toggle.isEnabled = enabled
        labelView.alpha = if (enabled) 1f else 0.5f
        subtitleView.alpha = labelView.alpha
        toggle.contentDescription = label
        toggle.setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        isClickable = enabled
        if (enabled) setOnClickListener { toggle.toggle() } else setOnClickListener(null)
    }

    fun setToggleChecked(checked: Boolean) {
        if (toggle.isChecked != checked) toggle.isChecked = checked
    }

    fun setRowEnabled(enabled: Boolean) {
        toggle.isEnabled = enabled
        isClickable = enabled
        labelView.alpha = if (enabled) 1f else 0.5f
        subtitleView.alpha = labelView.alpha
    }

    private fun paintText(label: CharSequence, subtitle: CharSequence?) {
        labelView.text = label
        if (subtitle == null) {
            subtitleView.visibility = GONE
        } else {
            subtitleView.visibility = VISIBLE
            subtitleView.text = subtitle
        }
    }
}
