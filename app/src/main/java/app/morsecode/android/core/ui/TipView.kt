package app.morsecode.android.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.ImageViewCompat
import app.morsecode.android.R
import app.morsecode.android.core.data.Prefs
import app.morsecode.android.core.util.ThemeColors

/**
 * §4.12g Tip: a dismissible one-time hint (for example the Files rule
 * "Tap a photo to open the viewer · tap its circle to select it", §6.9).
 * Once dismissed it stays dismissed — the flag lives in Prefs.
 */
class TipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val text = AppCompatTextView(context)
    private val dismiss = AppCompatImageButton(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Shapes.raised(context, 12f)
        val pad = Shapes.dpInt(context, 12f)
        setPadding(pad, pad, 0, pad)

        text.setTextAppearance(context, R.style.TextAppearance_Morsecode_Body)
        addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        dismiss.setImageResource(R.drawable.ic_end)
        dismiss.background = null
        dismiss.contentDescription = context.getString(R.string.cd_dismiss_tip)
        ImageViewCompat.setImageTintList(
            dismiss,
            ColorStateList.valueOf(ThemeColors.resolve(context, R.attr.colorTextSecondary)),
        )
        val touch = Shapes.dpInt(context, 48f)
        addView(dismiss, LayoutParams(touch, touch))
    }

    /** Shows the hint only while it has not been dismissed before. */
    fun bind(prefs: Prefs, tipId: String, message: CharSequence) {
        if (prefs.isTipDismissed(tipId)) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        text.text = message
        dismiss.setOnClickListener {
            prefs.dismissTip(tipId)
            visibility = GONE
        }
    }
}
