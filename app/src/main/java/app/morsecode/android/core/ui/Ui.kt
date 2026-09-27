package app.morsecode.android.core.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * Shared UI affordances (§8.1 core/ui/Ui): the snackbar the product confirms
 * actions with ("Queued 2 items · 43.6 GB → Ravi's Redmi", §6.9) and the
 * confirm dialog used by End session (§5.3).
 *
 * Implemented locally because there is no Material Components dependency
 * (§3.3); it is a plain view added to the activity's content root, auto-removed
 * after its duration, with a 48 dp action target (§15.2).
 */
object Ui {

    private const val DEFAULT_DURATION_MS = 3200L

    fun snackbar(
        activity: Activity,
        message: CharSequence,
        actionLabel: CharSequence? = null,
        onAction: (() -> Unit)? = null,
        durationMs: Long = DEFAULT_DURATION_MS,
    ) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val context: Context = activity

        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = Shapes.raised(context, 12f)
        val pad = Shapes.dpInt(context, 14f)
        bar.setPadding(pad, pad, if (actionLabel == null) pad else 0, pad)

        val text = AppCompatTextView(context)
        text.setTextAppearance(context, R.style.TextAppearance_Morsecode_Body)
        text.setTextColor(ThemeColors.resolve(context, R.attr.colorTextPrimary))
        text.text = message
        bar.addView(
            text,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        if (actionLabel != null) {
            val action = AppCompatTextView(context)
            action.setTextAppearance(context, R.style.TextAppearance_Morsecode_Button)
            action.setTextColor(ThemeColors.accent(context))
            action.text = actionLabel
            action.contentDescription = actionLabel
            action.gravity = Gravity.CENTER
            action.minHeight = Shapes.dpInt(context, 48f)
            action.minWidth = Shapes.dpInt(context, 48f)
            val actionPad = Shapes.dpInt(context, 14f)
            action.setPadding(actionPad, actionPad, actionPad, actionPad)
            action.setOnClickListener {
                root.removeView(bar)
                onAction?.invoke()
            }
            bar.addView(
                action,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.gravity = Gravity.BOTTOM
        val margin = Shapes.dpInt(context, 16f)
        params.setMargins(margin, margin, margin, margin)
        bar.layoutParams = params

        // Announced to TalkBack as a live region, not silently painted (§15.5).
        bar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        root.addView(bar)
        bar.announceForAccessibility(message)

        bar.postDelayed({
            if (bar.parent != null) root.removeView(bar)
        }, durationMs)
    }
}
