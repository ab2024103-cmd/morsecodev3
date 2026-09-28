package app.morsecode.android.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors
import com.google.android.material.chip.Chip

/**
 * §4.12 state chip — now a Material `Chip`, restyled (§3.3 [CHANGED]).
 *
 * §4.5's colours and §4.7's 10 sp mono uppercase type are applied as tokens;
 * the shape, ripple and accessibility role come from Material. §4.13 still
 * holds: the state is in the TEXT, never in the colour alone.
 */
enum class ChipState(@StringRes val labelRes: Int, @ColorRes val colorRes: Int, val usesAccent: Boolean) {
    QUEUED(R.string.chip_queued, R.color.text_meta, false),
    SENDING(R.string.chip_sending, R.color.accent_sunflower, true),
    RECEIVING(R.string.chip_receiving, R.color.state_receiving, false),
    RECV(R.string.chip_recv, R.color.state_receiving, false),
    PAUSED(R.string.chip_paused, R.color.state_warning, false),
    DONE(R.string.chip_done, R.color.state_success, false),
    FAILED(R.string.chip_failed, R.color.state_error, false),
    SKIPPED(R.string.chip_skipped, R.color.text_meta, false),
    QUEUE(R.string.chip_queue, R.color.text_meta, false),
}

class StateChipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : Chip(context, attrs, defStyleAttr) {

    private var state: ChipState = ChipState.QUEUED

    init {
        setTextAppearance(R.style.TextAppearance_Morsecode_StateChip)
        isClickable = false
        isCheckable = false
        isFocusable = false
        chipMinHeight = Shapes.dp(context, 24f)
        chipStartPadding = Shapes.dp(context, 6f)
        chipEndPadding = Shapes.dp(context, 6f)
        shapeAppearanceModel = shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(Shapes.dp(context, 8f))   // §4.8 chip radius
            .build()
        chipStrokeWidth = Shapes.dp(context, 1f)
        bind(state)
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
        chipStrokeColor = ColorStateList.valueOf(ThemeColors.withAlpha(color, 0.45f))
        chipBackgroundColor = ColorStateList.valueOf(
            if (state == ChipState.RECV || state == ChipState.RECEIVING) {
                ContextCompat.getColor(context, R.color.state_receiving_bg)
            } else {
                ThemeColors.withAlpha(color, 0.12f)
            },
        )
        contentDescription = text
    }

    fun currentState(): ChipState = state
}
