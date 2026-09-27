package app.morsecode.android.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * §4.12g SectionHeader: 11 sp medium, UPPERCASE, +0.08 em, text/secondary
 * ("ACCENT COLOUR", "DISCOVERED", "SYSTEM") with an optional right-aligned
 * action ("Clear", "Refresh", "Pause all").
 */
class SectionHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val titleView = AppCompatTextView(context)
    private val actionView = AppCompatTextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padV = Shapes.dpInt(context, 12f)
        setPadding(0, padV, 0, Shapes.dpInt(context, 8f))

        titleView.setTextAppearance(context, R.style.TextAppearance_Morsecode_SectionHeader)
        addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        actionView.setTextAppearance(context, R.style.TextAppearance_Morsecode_Button)
        actionView.setTextColor(ThemeColors.accent(context))
        val padH = Shapes.dpInt(context, 12f)
        actionView.setPadding(padH, Shapes.dpInt(context, 12f), padH, Shapes.dpInt(context, 12f))
        actionView.minHeight = Shapes.dpInt(context, 48f)   // §15.2
        actionView.gravity = Gravity.CENTER
        actionView.visibility = GONE
        addView(actionView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(title: CharSequence, actionLabel: CharSequence? = null, onAction: (() -> Unit)? = null) {
        titleView.text = title
        if (actionLabel == null) {
            actionView.visibility = GONE
            actionView.setOnClickListener(null)
        } else {
            actionView.visibility = VISIBLE
            actionView.text = actionLabel
            actionView.contentDescription = actionLabel
            actionView.setOnClickListener { onAction?.invoke() }
        }
    }
}
