package app.morsecode.android.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.ImageViewCompat
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * §4.12b TransferRow: type icon tile, file name, mono meta line
 * ("48.9 / 144 MB · 6.2 MB/s"), right state chip, thin progress bar underneath,
 * optional trailing ✕.
 *
 * Variants (§4.12b):
 *   SENDER / RECEIVER — the ordinary row;
 *   PEER_SUB          — the broadcast per-peer sub-row: indented, letter avatar
 *                       instead of the icon tile, right-aligned percent.
 *
 * Long file names use MIDDLE ellipsis so the extension stays visible (§4.7).
 */
class TransferRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Variant { SENDER, RECEIVER, PEER_SUB }

    private val leadingTile = TypeTileView(context)
    private val leadingAvatar = AvatarView(context)
    private val nameView = AppCompatTextView(context)
    private val metaView = AppCompatTextView(context)
    private val chip = StateChipView(context)
    private val percentView = AppCompatTextView(context)
    private val cancelButton = AppCompatImageButton(context)
    private val progressBar = ThinProgressBar(context)

    private var variant = Variant.SENDER

    init {
        orientation = VERTICAL
        val padV = Shapes.dpInt(context, 12f)   // §4.8 list row vertical padding
        setPadding(0, padV, 0, padV)

        val top = LinearLayout(context)
        top.orientation = HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val tileSize = Shapes.dpInt(context, 40f)
        val leadingParams = LayoutParams(tileSize, tileSize)
        leadingParams.rightMargin = Shapes.dpInt(context, 12f)
        top.addView(leadingTile, leadingParams)
        top.addView(leadingAvatar, LayoutParams(leadingParams))
        leadingAvatar.visibility = GONE

        val textColumn = LinearLayout(context)
        textColumn.orientation = VERTICAL
        nameView.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        nameView.maxLines = 1
        nameView.ellipsize = TextUtils.TruncateAt.MIDDLE
        metaView.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        textColumn.addView(nameView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        textColumn.addView(metaView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        top.addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        percentView.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        percentView.gravity = Gravity.END
        percentView.visibility = GONE
        top.addView(percentView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        top.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        cancelButton.setImageResource(R.drawable.ic_end)
        cancelButton.background = null
        ImageViewCompat.setImageTintList(
            cancelButton,
            ColorStateList.valueOf(ThemeColors.resolve(context, R.attr.colorTextSecondary)),
        )
        val touch = Shapes.dpInt(context, 48f)   // §15.2 — padding, not bigger art
        top.addView(cancelButton, LayoutParams(touch, touch))

        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val barParams = LayoutParams(LayoutParams.MATCH_PARENT, Shapes.dpInt(context, 4f))
        barParams.topMargin = Shapes.dpInt(context, 8f)
        addView(progressBar, barParams)
    }

    fun bindVariant(variant: Variant, deviceId: String = "", deviceName: String = "") {
        this.variant = variant
        if (variant == Variant.PEER_SUB) {
            leadingTile.visibility = GONE
            leadingAvatar.visibility = VISIBLE
            leadingAvatar.bind(deviceId, deviceName)
            percentView.visibility = VISIBLE
            chip.visibility = GONE
            setPadding(Shapes.dpInt(context, 24f), paddingTop, 0, paddingBottom)
        } else {
            leadingTile.visibility = VISIBLE
            leadingAvatar.visibility = GONE
            percentView.visibility = GONE
            chip.visibility = VISIBLE
            setPadding(0, paddingTop, 0, paddingBottom)
        }
    }

    /**
     * @param meta the mono line, already formatted by Fmt ("48.9 / 144 MB · 6.2 MB/s").
     * @param onCancel null hides the trailing ✕.
     */
    fun bind(
        name: CharSequence,
        meta: CharSequence,
        kind: FileKind,
        state: ChipState,
        progress: Float,
        onCancel: (() -> Unit)? = null,
    ) {
        nameView.text = name
        metaView.text = meta
        leadingTile.bind(kind)
        chip.bind(state)
        percentView.text = Math.round(progress * 100f).toString() + "%"

        progressBar.setMode(
            when (state) {
                ChipState.DONE -> ThinProgressBar.Mode.COMPLETE
                ChipState.PAUSED -> ThinProgressBar.Mode.PAUSED
                ChipState.FAILED -> ThinProgressBar.Mode.FAILED
                else -> ThinProgressBar.Mode.RUNNING
            },
        )
        progressBar.setProgress(progress)

        if (onCancel == null) {
            cancelButton.visibility = GONE
            cancelButton.setOnClickListener(null)
        } else {
            cancelButton.visibility = VISIBLE
            cancelButton.contentDescription = context.getString(R.string.cd_cancel_item, name)
            cancelButton.setOnClickListener { onCancel() }
        }

        contentDescription = "$name $meta ${chip.text}"
    }

    fun setProgress(value: Float) = progressBar.setProgress(value)
}
