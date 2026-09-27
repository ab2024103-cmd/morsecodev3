package app.morsecode.android.core.ui

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * §4.12g StateChip.
 *
 * §4.13 HARD UI RULE: status is NEVER communicated by colour alone — every
 * state carries a word. The semantic colours here do NOT follow the accent
 * (§4.5); only SENDING, which is defined as "the current accent".
 */
enum class ChipState(@StringRes val labelRes: Int, @ColorRes val colorRes: Int, val usesAccent: Boolean) {
    QUEUED(R.string.chip_queued, R.color.text_secondary, false),
    SENDING(R.string.chip_sending, R.color.accent_sunflower, true),
    RECEIVING(R.string.chip_receiving, R.color.state_receiving, false),
    RECV(R.string.chip_recv, R.color.state_receiving, false),
    PAUSED(R.string.chip_paused, R.color.state_warning, false),
    DONE(R.string.chip_done, R.color.state_success, false),
    FAILED(R.string.chip_failed, R.color.state_error, false),
    SKIPPED(R.string.chip_skipped, R.color.text_secondary, false),
    QUEUE(R.string.chip_queue, R.color.text_secondary, false),
}

class StateChipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var state: ChipState = ChipState.QUEUED

    init {
        setTextAppearance(context, R.style.TextAppearance_Morsecode_StateChip)
        val padH = Shapes.dpInt(context, 8f)
        val padV = Shapes.dpInt(context, 4f)
        setPadding(padH, padV, padH, padV)
        includeFontPadding = false
        bind(ChipState.QUEUED)
    }

    fun bind(state: ChipState) {
        this.state = state
        val color = if (state.usesAccent) {
            ThemeColors.accent(context)
        } else {
            ContextCompat.getColor(context, state.colorRes)
        }
        setText(state.labelRes)
        setTextColor(color)
        // RECV rows carry the violet-on-deep-violet pair (§4.5); everything else
        // is a tinted wash of its own semantic colour on surface/raised.
        val fill = if (state == ChipState.RECV || state == ChipState.RECEIVING) {
            ContextCompat.getColor(context, R.color.state_receiving_bg)
        } else {
            ThemeColors.withAlpha(color, 0.12f)
        }
        background = Shapes.chip(context, fill, ThemeColors.withAlpha(color, 0.45f))
        contentDescription = text
    }

    fun currentState(): ChipState = state
}
