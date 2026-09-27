package app.morsecode.android.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R

/**
 * §4.12d StatTile: value + mono uppercase label on surface/raised.
 * Used by the broadcast tiles (PEERS · BATCH MB · TO SEND MB, then
 * PHONES · FILES EACH · MB SENT) — §10.4.
 */
class StatTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val valueView = AppCompatTextView(context)
    private val labelView = AppCompatTextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = Shapes.raised(context, 8f)
        val pad = Shapes.dpInt(context, 10f)
        setPadding(pad, pad, pad, pad)

        valueView.setTextAppearance(context, R.style.TextAppearance_Morsecode_StatValue)
        valueView.gravity = Gravity.CENTER
        addView(valueView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        labelView.setTextAppearance(context, R.style.TextAppearance_Morsecode_StatLabel)
        labelView.gravity = Gravity.CENTER
        addView(labelView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(value: CharSequence, label: CharSequence) {
        valueView.text = value
        labelView.text = label
        contentDescription = "$value $label"
    }
}
