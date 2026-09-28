package app.morsecode.android.core.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import app.morsecode.android.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * §4.12's bottom sheet — now Material's, restyled with §4 tokens rather than
 * reimplemented (§3.3 [CHANGED]).
 *
 * The hand-built version that stood here had to fake the drag handle, the
 * spring, the scrim and the dismiss gesture; §4.15 names exactly that kind of
 * approximation as the reason a UI ends up "similar but not the same". The
 * shape (20 dp top corners, §4.8) and the surface colour come from
 * `Theme.Morsecode.BottomSheet`.
 *
 * Subclasses keep the same one-method contract they had before.
 */
abstract class BottomSheet : BottomSheetDialogFragment() {

    protected abstract fun onCreateSheetContent(context: Context): View

    override fun getTheme(): Int = R.style.Theme_Morsecode_BottomSheet

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        // §4.12: the sheet opens at a useful height instead of peeking.
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        return dialog
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        val pad = Shapes.dpInt(context, 16f)
        root.setPadding(pad, Shapes.dpInt(context, 8f), pad, pad)
        // Material draws its own drag handle; ours would be a second one.
        root.addView(
            com.google.android.material.bottomsheet.BottomSheetDragHandleView(context),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            onCreateSheetContent(context),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return root
    }
}
