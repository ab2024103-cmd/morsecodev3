package app.morsecode.android.core.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import android.content.res.ColorStateList
import android.graphics.Color
import app.morsecode.android.R

/**
 * §4.9: category and file-type icons are the only filled artwork — solid
 * rounded squares (22% radius) with a white glyph, one colour per concept.
 */
enum class FileKind(val colorRes: Int, val iconRes: Int) {
    IMAGE(R.color.icon_image, R.drawable.ic_type_image),
    VIDEO(R.color.icon_video, R.drawable.ic_type_video),
    AUDIO(R.color.icon_audio, R.drawable.ic_type_audio),
    DOC(R.color.icon_doc, R.drawable.ic_type_doc),
    ARCHIVE(R.color.icon_archive, R.drawable.ic_type_archive),
    APK(R.color.icon_apk, R.drawable.ic_type_apk),
    UNKNOWN(R.color.icon_unknown, R.drawable.ic_type_unknown),
}

class TypeTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val glyph = AppCompatImageView(context)
    private var sizeDp = 40f

    init {
        val pad = Shapes.dpInt(context, 8f)
        glyph.setPadding(pad, pad, pad, pad)
        ImageViewCompat.setImageTintList(glyph, ColorStateList.valueOf(Color.WHITE))
        glyph.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(
            glyph,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        bind(FileKind.UNKNOWN)
    }

    fun bind(kind: FileKind) {
        background = Shapes.rounded(
            ContextCompat.getColor(context, kind.colorRes),
            Shapes.dp(context, sizeDp * 0.22f),
        )
        glyph.setImageResource(kind.iconRes)
        contentDescription = context.getString(R.string.cd_file_type_icon)
    }

    fun setTileSizeDp(value: Float) {
        sizeDp = value
        requestLayout()
    }
}
