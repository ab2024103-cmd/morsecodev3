package app.morsecode.android.core.ui

import android.content.Context
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * The three button treatments the product uses (§4.4, §4.8, §6.2): accent
 * filled, neutral outlined, and the full-width accent WASH that §6.2 makes the
 * PRIMARY broadcast entry.
 *
 * Buttons are 14 dp rounded rectangles, never pills (§4.8), always at least
 * 48 dp tall (§15.2), and always labelled — §4.13 forbids icon-only primary
 * actions, so every factory here takes text.
 */
object Buttons {

    fun accent(context: Context, label: CharSequence, onClick: () -> Unit): AppCompatTextView =
        base(context, label, onClick).apply {
            setTextColor(ThemeColors.resolve(context, R.attr.colorInkOnAccent))
            background = Shapes.accentButton(context)
        }

    fun outlined(context: Context, label: CharSequence, onClick: () -> Unit): AppCompatTextView =
        base(context, label, onClick).apply {
            setTextColor(ThemeColors.resolve(context, R.attr.colorTextPrimary))
            background = Shapes.outlinedButton(context)
        }

    /** Accent-washed full-width action ("⇶ Broadcast to several phones", §6.2). */
    fun wash(context: Context, label: CharSequence, onClick: () -> Unit): AppCompatTextView =
        base(context, label, onClick).apply {
            setTextColor(ThemeColors.accent(context))
            background = Shapes.pressable(
                context,
                Shapes.accentWash(context, 14f),
                Shapes.rounded(
                    ThemeColors.resolve(context, R.attr.colorSurfacePressed),
                    Shapes.dp(context, 14f),
                ),
            )
        }

    private fun base(
        context: Context,
        label: CharSequence,
        onClick: () -> Unit,
    ): AppCompatTextView {
        val button = AppCompatTextView(context)
        button.setTextAppearance(context, R.style.TextAppearance_Morsecode_Button)
        button.text = label
        button.contentDescription = label
        button.gravity = Gravity.CENTER
        button.minHeight = Shapes.dpInt(context, 48f)
        val padH = Shapes.dpInt(context, 16f)
        val padV = Shapes.dpInt(context, 12f)
        button.setPadding(padH, padV, padH, padV)
        button.isClickable = true
        button.isFocusable = true
        button.setOnClickListener { onClick() }
        return button
    }
}
