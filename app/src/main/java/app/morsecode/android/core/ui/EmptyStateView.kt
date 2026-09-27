package app.morsecode.android.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.ImageViewCompat
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * §4.12g EmptyState: icon + one line + one action.
 * §6.19: every list has a friendly empty state, and every failure says what
 * happened AND what to do next.
 */
class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val icon = AppCompatImageView(context)
    private val message = AppCompatTextView(context)
    private val action = AppCompatTextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val pad = Shapes.dpInt(context, 24f)
        setPadding(pad, pad, pad, pad)

        icon.setImageResource(R.drawable.ic_empty_box)
        ImageViewCompat.setImageTintList(
            icon,
            ColorStateList.valueOf(ThemeColors.resolve(context, R.attr.colorTextMeta)),
        )
        icon.contentDescription = context.getString(R.string.cd_empty_state_icon)
        val iconSize = Shapes.dpInt(context, 40f)
        addView(icon, LayoutParams(iconSize, iconSize))

        message.setTextAppearance(context, R.style.TextAppearance_Morsecode_Body)
        message.gravity = Gravity.CENTER
        val messageParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        messageParams.topMargin = Shapes.dpInt(context, 12f)
        addView(message, messageParams)

        action.setTextAppearance(context, R.style.TextAppearance_Morsecode_Button)
        action.setTextColor(ThemeColors.resolve(context, R.attr.colorInkOnAccent))
        action.background = Shapes.accentButton(context)
        action.gravity = Gravity.CENTER
        action.minHeight = Shapes.dpInt(context, 48f)
        val actionPadH = Shapes.dpInt(context, 20f)
        action.setPadding(actionPadH, Shapes.dpInt(context, 12f), actionPadH, Shapes.dpInt(context, 12f))
        action.visibility = GONE
        val actionParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        actionParams.topMargin = Shapes.dpInt(context, 16f)
        addView(action, actionParams)
    }

    fun bind(
        iconRes: Int,
        line: CharSequence,
        actionLabel: CharSequence? = null,
        onAction: (() -> Unit)? = null,
    ) {
        icon.setImageResource(iconRes)
        message.text = line
        if (actionLabel == null) {
            action.visibility = GONE
            action.setOnClickListener(null)
        } else {
            action.visibility = VISIBLE
            action.text = actionLabel
            action.contentDescription = actionLabel
            action.setOnClickListener { onAction?.invoke() }
        }
    }
}
