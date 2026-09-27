package app.morsecode.android.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * §6.9 tab strip: equal flex cells filling the width, each centred, with the
 * 2 dp accent underline spanning its own cell. No leftover gap at any width and
 * no horizontal scrolling — labels ellipsise before the strip does.
 *
 * ONE tab-index source of truth: this view owns the index and reports changes;
 * a screen that kept its own copy is exactly the mismatch §6.9 calls
 * release-blocking, because it makes people send the wrong files.
 */
class TabStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val cells = ArrayList<AppCompatTextView>()
    private val underline = Paint(Paint.ANTI_ALIAS_FLAG)
    private val underlineHeight = Shapes.dp(context, 2f)
    private var selected = 0
    private var listener: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
        underline.color = ThemeColors.accent(context)
    }

    fun bind(labels: List<CharSequence>, selectedIndex: Int = 0, onSelected: (Int) -> Unit) {
        removeAllViews()
        cells.clear()
        listener = null
        labels.forEachIndexed { index, label ->
            val cell = AppCompatTextView(context)
            cell.setTextAppearance(context, R.style.TextAppearance_Morsecode_Button)
            cell.text = label
            cell.contentDescription = label
            cell.gravity = Gravity.CENTER
            cell.maxLines = 1
            cell.ellipsize = TextUtils.TruncateAt.END
            cell.minHeight = Shapes.dpInt(context, 48f)
            cell.isClickable = true
            cell.isFocusable = true
            cell.setOnClickListener { select(index) }
            cells.add(cell)
            addView(cell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        selected = selectedIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        paint()
        listener = onSelected
    }

    fun select(index: Int) {
        if (index == selected) return
        selected = index
        paint()
        invalidate()
        listener?.invoke(index)
    }

    fun selectedIndex(): Int = selected

    private fun paint() {
        val accent = ThemeColors.accent(context)
        val idle = ThemeColors.resolve(context, R.attr.colorTextSecondary)
        cells.forEachIndexed { index, cell ->
            cell.setTextColor(if (index == selected) accent else idle)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cell = cells.getOrNull(selected) ?: return
        underline.color = ThemeColors.accent(context)
        canvas.drawRect(
            cell.left.toFloat(),
            height - underlineHeight,
            cell.right.toFloat(),
            height.toFloat(),
            underline,
        )
    }
}
