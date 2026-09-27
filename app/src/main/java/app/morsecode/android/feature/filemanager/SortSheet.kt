package app.morsecode.android.feature.filemanager

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R
import app.morsecode.android.core.media.SortRules
import app.morsecode.android.core.model.SortKey
import app.morsecode.android.core.model.SortOrder
import app.morsecode.android.core.ui.BottomSheet
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.util.ThemeColors

/**
 * §6.9's sort sheet. "THE SORT ICON IS A WORKING CONTROL [CHANGED], not
 * decoration": Date modified · Name · Size · Type with the current key in the
 * accent and a tick, plus a Descending / Ascending pair, and the active order
 * echoed in the subtitle.
 *
 * The same sheet serves every directory listing (§6.10), which is why it takes
 * the current order and hands back a new one rather than owning any state.
 */
class SortSheet(
    private val current: SortOrder,
    private val onChosen: (SortOrder) -> Unit,
) : BottomSheet() {

    override fun onCreateSheetContent(context: Context): View {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL

        val title = AppCompatTextView(context)
        title.setTextAppearance(context, R.style.TextAppearance_Morsecode_ScreenTitle)
        title.setText(R.string.files_sort_title)
        root.addView(title)

        val subtitle = AppCompatTextView(context)
        subtitle.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        subtitle.text = SortRules.label(current)
        root.addView(subtitle)

        for (key in SortKey.values()) {
            root.addView(keyRow(context, key), rowParams(context))
        }

        val divider = View(context)
        divider.setBackgroundColor(ThemeColors.resolve(context, R.attr.colorHairline))
        val dividerParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Shapes.dpInt(context, 1f),
        )
        dividerParams.topMargin = Shapes.dpInt(context, 8f)
        root.addView(divider, dividerParams)

        root.addView(directionRow(context, descending = true), rowParams(context))
        root.addView(directionRow(context, descending = false), rowParams(context))
        return root
    }

    private fun keyRow(context: Context, key: SortKey): View {
        val selected = key == current.key
        return row(
            context = context,
            label = context.getString(
                when (key) {
                    SortKey.DATE_MODIFIED -> R.string.files_sort_date
                    SortKey.NAME -> R.string.files_sort_name
                    SortKey.SIZE -> R.string.files_sort_size
                    SortKey.TYPE -> R.string.files_sort_type
                },
            ),
            selected = selected,
        ) {
            onChosen(current.copy(key = key))
            dismiss()
        }
    }

    private fun directionRow(context: Context, descending: Boolean): View {
        val selected = descending == current.descending
        return row(
            context = context,
            label = context.getString(
                if (descending) R.string.files_sort_descending else R.string.files_sort_ascending,
            ),
            selected = selected,
        ) {
            onChosen(current.copy(descending = descending))
            dismiss()
        }
    }

    private fun row(
        context: Context,
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = Shapes.dpInt(context, 48f)
        row.isClickable = true
        row.setOnClickListener { onClick() }

        val text = AppCompatTextView(context)
        text.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        text.text = label
        // The current key is in the accent, with a tick (§6.9).
        if (selected) text.setTextColor(ThemeColors.accent(context))
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (selected) {
            val tick = AppCompatImageView(context)
            tick.setImageResource(R.drawable.ic_check)
            androidx.core.widget.ImageViewCompat.setImageTintList(
                tick,
                android.content.res.ColorStateList.valueOf(ThemeColors.accent(context)),
            )
            val size = Shapes.dpInt(context, 24f)
            row.addView(tick, LinearLayout.LayoutParams(size, size))
        }
        row.contentDescription = label
        return row
    }

    private fun rowParams(context: Context): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(context, 4f)
        return params
    }
}
