package app.morsecode.android.core.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import app.morsecode.android.core.model.TransferItem

/**
 * §15 ACCESSIBILITY, as helpers rather than as good intentions.
 *
 * §15.1 wants meaningful descriptions ("Pause transfer of holiday_2019.mp4",
 * not "Button"), §15.5 wants TalkBack to hear progress "at sensible intervals
 * (not every 100 ms tick)" and an explicit "Transfer complete".
 */
object A11y {

    /** §15.5: announce at most this often per item while it is moving. */
    const val PROGRESS_ANNOUNCE_INTERVAL_MS = 5_000L

    /** Decorative art says so explicitly (§15.1). */
    fun decorative(view: View) {
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.contentDescription = null
    }

    /** An icon-only control: a verb and its object, never a noun on its own. */
    fun label(view: View, description: CharSequence) {
        view.contentDescription = description
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Marks a view as a button for TalkBack even when it is a TextView. */
    fun asButton(view: View) {
        ViewCompat.setAccessibilityDelegate(
            view,
            object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            },
        )
    }

    /**
     * Whether a progress announcement is due. §15.5 forbids announcing every
     * tick; this throttles per item and always lets a terminal state through,
     * because "Transfer complete" must never be swallowed.
     */
    fun shouldAnnounce(
        lastAnnouncedAtMillis: Long,
        nowMillis: Long,
        isTerminal: Boolean,
        intervalMillis: Long = PROGRESS_ANNOUNCE_INTERVAL_MS,
    ): Boolean = isTerminal || nowMillis - lastAnnouncedAtMillis >= intervalMillis

    /** What TalkBack should say about a row (§15.1, §15.5). */
    fun describe(item: TransferItem): String {
        val percent = Math.round(item.progress * 100f)
        return when (item.state) {
            app.morsecode.android.core.model.TransferState.COMPLETED ->
                "Transfer complete: ${item.file.displayName}"
            app.morsecode.android.core.model.TransferState.FAILED ->
                "Failed: ${item.file.displayName}. ${item.lastError ?: ""}".trim()
            app.morsecode.android.core.model.TransferState.PAUSED ->
                "Paused at $percent percent: ${item.file.displayName}"
            app.morsecode.android.core.model.TransferState.QUEUED ->
                "Queued: ${item.file.displayName}"
            app.morsecode.android.core.model.TransferState.SKIPPED ->
                "Skipped, already on the other phone: ${item.file.displayName}"
            app.morsecode.android.core.model.TransferState.CANCELLED ->
                "Cancelled: ${item.file.displayName}"
            app.morsecode.android.core.model.TransferState.IN_PROGRESS ->
                "$percent percent: ${item.file.displayName}"
        }
    }

    /** "Pause transfer of holiday_2019.mp4" — §15.1's own example. */
    fun actionLabel(action: String, fileName: String): String = "$action transfer of $fileName"
}
