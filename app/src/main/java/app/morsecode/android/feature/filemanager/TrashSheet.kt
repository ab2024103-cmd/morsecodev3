package app.morsecode.android.feature.filemanager

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R
import app.morsecode.android.core.ui.BottomSheet
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.di.AppServices

/** §6.9's in-app Trash: restore or permanently delete locally moved files. */
class TrashSheet : BottomSheet() {

    override fun onCreateSheetContent(context: Context): View {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        val entries = AppServices.trash.entries()

        val title = AppCompatTextView(context)
        title.setTextAppearance(context, R.style.TextAppearance_Morsecode_ScreenTitle)
        title.text = getString(R.string.files_trash_title, entries.size)
        root.addView(title)

        if (entries.isEmpty()) {
            val empty = AppCompatTextView(context)
            empty.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
            empty.text = getString(R.string.files_trash_empty)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.topMargin = Shapes.dpInt(context, 12f)
            root.addView(empty, params)
            return root
        }

        for (entry in entries) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = Shapes.dpInt(context, 56f)

            val labels = LinearLayout(context)
            labels.orientation = LinearLayout.VERTICAL
            val name = AppCompatTextView(context)
            name.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
            name.text = entry.displayName
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            labels.addView(name)
            val expiry = AppCompatTextView(context)
            expiry.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
            expiry.text = getString(R.string.files_trash_auto_purge)
            labels.addView(expiry)
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(
                Buttons.outlined(context, getString(R.string.files_restore)) {
                    if (AppServices.trash.restore(entry.id) != null) {
                        Ui.snackbar(requireActivity(), getString(R.string.files_restored, entry.displayName))
                    }
                    dismiss()
                },
            )
            row.addView(
                Buttons.outlined(context, getString(R.string.files_delete_forever)) {
                    AppServices.trash.purge(entry.id)
                    dismiss()
                },
            )
            root.addView(row)
        }
        return root
    }
}
