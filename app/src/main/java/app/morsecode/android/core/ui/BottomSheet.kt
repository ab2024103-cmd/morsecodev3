package app.morsecode.android.core.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.app.AppCompatDialogFragment
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * §4.12g BottomSheet, and the sheet shape rules of §4.8: radius 20 dp, TOP
 * corners only, surface/raised, with a drag handle (§6.7).
 *
 * Built on AppCompatDialogFragment rather than a Material bottom sheet: there
 * is no Material Components dependency (§3.3, §1.5). Subclasses supply their
 * content through [onCreateSheetContent].
 */
abstract class BottomSheet : AppCompatDialogFragment() {

    /** Build the sheet body. The handle and the background are provided here. */
    protected abstract fun onCreateSheetContent(context: Context): View

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val dialog = AppCompatDialog(context, R.style.Theme_Morsecode_Dialog)

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        val background = GradientDrawable()
        background.setColor(ThemeColors.resolve(context, R.attr.colorSurfaceRaised))
        val radius = Shapes.dp(context, 20f)
        background.cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        root.background = background

        root.addView(dragHandle(context))
        root.addView(
            onCreateSheetContent(context),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        dialog.setContentView(root)
        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            window.setGravity(Gravity.BOTTOM)
            window.setWindowAnimations(R.style.Animation_Morsecode_BottomSheet)
        }
        return dialog
    }

    private fun dragHandle(context: Context): View {
        val container = LinearLayout(context)
        container.gravity = Gravity.CENTER
        val pad = Shapes.dpInt(context, 10f)
        container.setPadding(0, pad, 0, pad)

        val handle = View(context)
        handle.background = Shapes.pill(
            context,
            ThemeColors.resolve(context, R.attr.colorHairline),
        )
        handle.contentDescription = context.getString(R.string.cd_drag_handle)
        container.addView(
            handle,
            LinearLayout.LayoutParams(Shapes.dpInt(context, 36f), Shapes.dpInt(context, 4f)),
        )
        return container
    }
}
