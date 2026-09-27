package app.morsecode.android.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * §4.12e ActionBar: five evenly spread tiles — Choose (+, accent-washed tile),
 * Queue (☰), Pause (❚❚), Minimize (—), End (✕, #EF4444). Labels are ALWAYS
 * visible under the icons. Identical on send, receive and broadcast (§6.8/D8).
 *
 * §4.14: the five cells distribute evenly and never leave dead space at an edge.
 */
class ActionBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Action { CHOOSE, QUEUE, PAUSE, MINIMIZE, END }

    private val listeners = HashMap<Action, () -> Unit>()
    private val labels = HashMap<Action, AppCompatTextView>()

    init {
        orientation = HORIZONTAL
        background = Shapes.card(context)
        val pad = Shapes.dpInt(context, 8f)
        setPadding(pad, pad, pad, pad)

        addTile(Action.CHOOSE, R.drawable.ic_choose, R.string.action_choose)
        addTile(Action.QUEUE, R.drawable.ic_queue, R.string.action_queue)
        addTile(Action.PAUSE, R.drawable.ic_pause, R.string.action_pause)
        addTile(Action.MINIMIZE, R.drawable.ic_minimize, R.string.action_minimize)
        addTile(Action.END, R.drawable.ic_end, R.string.action_end)
    }

    fun setOnAction(action: Action, listener: () -> Unit) {
        listeners[action] = listener
    }

    /** The Pause tile doubles as Resume; the label is swapped, never the position. */
    fun setPauseLabel(label: CharSequence) {
        labels[Action.PAUSE]?.text = label
    }

    private fun addTile(action: Action, iconRes: Int, labelRes: Int) {
        val tile = LinearLayout(context)
        tile.orientation = VERTICAL
        tile.gravity = Gravity.CENTER
        tile.minimumHeight = Shapes.dpInt(context, 48f)   // §15.2
        val tilePad = Shapes.dpInt(context, 8f)
        tile.setPadding(tilePad, tilePad, tilePad, tilePad)

        val icon = AppCompatImageView(context)
        icon.setImageResource(iconRes)
        val iconSize = Shapes.dpInt(context, 24f)
        val iconPad = Shapes.dpInt(context, 6f)
        icon.setPadding(iconPad, iconPad, iconPad, iconPad)
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        val tint = when (action) {
            Action.END -> ContextCompat.getColor(context, R.color.state_error)
            Action.CHOOSE -> ThemeColors.accent(context)
            else -> ThemeColors.resolve(context, R.attr.colorTextPrimary)
        }
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(tint))
        if (action == Action.CHOOSE) {
            icon.background = Shapes.accentWash(context, 10f)
        }
        val iconParams = LayoutParams(iconSize + iconPad * 2, iconSize + iconPad * 2)
        tile.addView(icon, iconParams)

        val label = AppCompatTextView(context)
        label.setTextAppearance(context, R.style.TextAppearance_Morsecode_StatLabel)
        label.setText(labelRes)
        label.gravity = Gravity.CENTER
        if (action == Action.END) label.setTextColor(ContextCompat.getColor(context, R.color.state_error))
        val labelParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        labelParams.topMargin = Shapes.dpInt(context, 4f)
        tile.addView(label, labelParams)
        labels[action] = label

        tile.isClickable = true
        tile.isFocusable = true
        tile.background = Shapes.pressable(
            context,
            Shapes.rounded(android.graphics.Color.TRANSPARENT, Shapes.dp(context, 12f)),
            Shapes.rounded(ThemeColors.resolve(context, R.attr.colorSurfacePressed), Shapes.dp(context, 12f)),
        )
        tile.contentDescription = context.getString(labelRes)
        tile.setOnClickListener { listeners[action]?.invoke() }

        addView(tile, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }
}
