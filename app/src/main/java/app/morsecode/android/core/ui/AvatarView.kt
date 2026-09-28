package app.morsecode.android.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.morsecode.android.R
import app.morsecode.android.core.util.PeerPalette

/**
 * §4.6 letter avatar: the first letter of the device name, uppercase, on the
 * deterministic peer colour. Radius is a full circle (§4.8). Independent of the
 * accent so peers stay distinguishable.
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private var letter: String = "?"

    init {
        circlePaint.color = ContextCompat.getColor(context, R.color.peer_amber)
    }

    /** Binds a peer. The colour follows the deviceId, the letter the name (§4.6). */
    fun bind(deviceId: String, deviceName: String) {
        letter = PeerPalette.letterFor(deviceName)
        circlePaint.color = ContextCompat.getColor(context, PeerPalette.colorResFor(deviceId))
        contentDescription = context.getString(R.string.cd_peer_avatar, deviceName)
        invalidate()
    }

    /** §6.13's editable profile: letter avatar with an explicit accent choice. */
    fun bindProfile(deviceName: String, colorRes: Int) {
        letter = PeerPalette.letterFor(deviceName)
        circlePaint.color = ContextCompat.getColor(context, colorRes)
        contentDescription = context.getString(R.string.cd_peer_avatar, deviceName)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = Math.min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, size / 2f, circlePaint)
        textPaint.textSize = size * 0.42f
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(letter, cx, baseline, textPaint)
    }
}
