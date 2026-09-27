package app.morsecode.android.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * Full-width pill segmented control (§6.12 History "Received | Sent"). The
 * active half is accent filled with near-black text.
 *
 * §4.8: the full pill radius is reserved for segmented controls, which is why
 * this is the only place that asks for it.
 *
 * The selected index is this view's single source of truth; callers read it
 * back rather than keeping a parallel flag (§6.9's tab rule, same reasoning).
 */
class SegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val segments = ArrayList<AppCompatTextView>()
    private var selected = 0
    private var listener: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        background = Shapes.pill(context, ThemeColors.resolve(context, R.attr.colorSurfaceCard))
        val pad = Shapes.dpInt(context, 4f)
        setPadding(pad, pad, pad, pad)
    }

    fun bind(labels: List<CharSequence>, selectedIndex: Int = 0, onSelected: (Int) -> Unit) {
        removeAllViews()
        segments.clear()
        listener = null
        labels.forEachIndexed { index, label ->
            val segment = AppCompatTextView(context)
            segment.setTextAppearance(context, R.style.TextAppearance_Morsecode_Button)
            segment.text = label
            segment.contentDescription = label
            segment.gravity = Gravity.CENTER
            segment.minHeight = Shapes.dpInt(context, 40f)
            segment.isClickable = true
            segment.isFocusable = true
            segment.setOnClickListener { select(index) }
            segments.add(segment)
            addView(segment, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        selected = selectedIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        paint()
        listener = onSelected
    }

    fun select(index: Int) {
        if (index == selected) return
        selected = index
        paint()
        listener?.invoke(index)
    }

    fun selectedIndex(): Int = selected

    private fun paint() {
        val accent = ThemeColors.accent(context)
        val inkOnAccent = ThemeColors.resolve(context, R.attr.colorInkOnAccent)
        val idle = ThemeColors.resolve(context, R.attr.colorTextSecondary)
        segments.forEachIndexed { index, segment ->
            val isSelected = index == selected
            segment.background = if (isSelected) Shapes.pill(context, accent) else null
            segment.setTextColor(if (isSelected) inkOnAccent else idle)
        }
    }
}
