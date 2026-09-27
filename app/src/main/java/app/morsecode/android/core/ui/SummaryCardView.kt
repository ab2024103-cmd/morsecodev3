package app.morsecode.android.core.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R

/**
 * §4.12c SummaryCard: an accent-wash card with a title line ("Batch in
 * progress", "✓ Batch complete", "Broadcasting to 3 phones"), a mono detail
 * line, and an optional row of up to three StatTiles.
 *
 * §6.6 [GAP]: when both directions have a terminal batch the card shows TWO
 * detail lines, "In: …" and "Out: …", never one line that silently mixes
 * directions — hence `bind` takes a list of detail lines.
 */
class SummaryCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val titleView = AppCompatTextView(context)
    private val detailContainer = LinearLayout(context)
    private val tileRow = LinearLayout(context)

    init {
        orientation = VERTICAL
        background = Shapes.accentWash(context, 16f)
        val pad = Shapes.dpInt(context, 14f)
        setPadding(pad, pad, pad, pad)

        titleView.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        detailContainer.orientation = VERTICAL
        addView(detailContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        tileRow.orientation = HORIZONTAL
        tileRow.visibility = GONE
        val tileParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        tileParams.topMargin = Shapes.dpInt(context, 12f)
        addView(tileRow, tileParams)
    }

    /** `tiles` is a list of value/label pairs, at most three (§4.12c). */
    fun bind(
        title: CharSequence,
        detailLines: List<CharSequence>,
        tiles: List<Pair<CharSequence, CharSequence>> = emptyList(),
    ) {
        titleView.text = title

        detailContainer.removeAllViews()
        for (line in detailLines) {
            val view = AppCompatTextView(context)
            view.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
            view.text = line
            val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            params.topMargin = Shapes.dpInt(context, 4f)
            detailContainer.addView(view, params)
        }

        tileRow.removeAllViews()
        if (tiles.isEmpty()) {
            tileRow.visibility = GONE
        } else {
            tileRow.visibility = VISIBLE
            val capped = if (tiles.size > 3) tiles.subList(0, 3) else tiles
            for ((index, tile) in capped.withIndex()) {
                val tileView = StatTileView(context)
                tileView.bind(tile.first, tile.second)
                val params = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                if (index > 0) params.leftMargin = Shapes.dpInt(context, 8f)
                tileRow.addView(tileView, params)
            }
        }
    }
}
