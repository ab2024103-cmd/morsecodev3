package app.morsecode.android.core.ui

import android.app.Activity
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

    /**
     * §3.3 [CHANGED]: Material's Snackbar, restyled with §4 tokens.
     *
     * The hand-built bar that stood here placed a view in the content root and
     * removed it on a timer — no swipe-to-dismiss, no window insets, no
     * accessibility timeout extension, no queueing. Material has all four.
     */
    fun snackbar(
        activity: Activity,
        message: CharSequence,
        actionLabel: CharSequence? = null,
        onAction: (() -> Unit)? = null,
        durationMs: Int = DEFAULT_DURATION_MS,
    ) {
        val root = activity.findViewById<android.view.View>(android.R.id.content) ?: return
        val bar = com.google.android.material.snackbar.Snackbar.make(root, message, durationMs)
        bar.setBackgroundTint(ThemeColors.resolve(activity, R.attr.colorSurfaceRaised))
        bar.setTextColor(ThemeColors.resolve(activity, R.attr.colorTextPrimary))
        bar.setActionTextColor(ThemeColors.accent(activity))
        if (actionLabel != null) bar.setAction(actionLabel) { onAction?.invoke() }
        bar.show()
    }

    private const val DEFAULT_DURATION_MS = 3200
}
