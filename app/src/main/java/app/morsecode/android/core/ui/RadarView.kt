package app.morsecode.android.core.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import app.morsecode.android.R
import app.morsecode.android.core.util.DeviceTier
import app.morsecode.android.core.util.ThemeColors

/**
 * §4.11 MOTION: radar sweep 2.4 s linear with 3 pulsing blips (1.6 s).
 * Obeys ANIMATOR_DURATION_SCALE; on a low-tier device (§13) it degrades to a
 * static ring — the host then shows the "Searching…" caption (§6.2).
 *
 * The sweep uses the accent; blips are the semantic success green (§4.5).
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tier = DeviceTier(context)

    private var sweepAngle = 0f
    private var blipPhase = 0f
    private var sweepAnimator: ValueAnimator? = null
    private var blipAnimator: ValueAnimator? = null

    /** Blip positions as (angle degrees, radius fraction) — three, as specified. */
    private val blips = floatArrayOf(
        35f, 0.55f,
        150f, 0.78f,
        265f, 0.38f,
    )

    /** True when the radar is drawn as a static ring instead of animating (§13). */
    val isStatic: Boolean get() = !tier.animationsEnabled()

    init {
        ringPaint.color = ThemeColors.resolve(context, R.attr.colorHairline)
        ringPaint.strokeWidth = Shapes.dp(context, 1f)
        blipPaint.color = ContextCompat.getColor(context, R.color.state_success)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO   // decorative (§15.1)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isStatic) return
        sweepAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2400L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                sweepAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        blipAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                blipPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        sweepAnimator?.cancel()
        blipAnimator?.cancel()
        sweepAnimator = null
        blipAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = Math.min(width, height) / 2f - ringPaint.strokeWidth

        for (step in 1..3) {
            canvas.drawCircle(cx, cy, radius * step / 3f, ringPaint)
        }

        if (isStatic) return

        sweepPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                ThemeColors.withAlpha(ThemeColors.accent(context), 0f),
                ThemeColors.withAlpha(ThemeColors.accent(context), 0.55f),
            ),
            floatArrayOf(0f, 1f),
        )
        canvas.save()
        canvas.rotate(sweepAngle, cx, cy)
        canvas.drawCircle(cx, cy, radius, sweepPaint)
        canvas.restore()

        var index = 0
        while (index < blips.size) {
            val angle = Math.toRadians(blips[index].toDouble())
            val distance = radius * blips[index + 1]
            val bx = cx + (Math.cos(angle) * distance).toFloat()
            val by = cy + (Math.sin(angle) * distance).toFloat()
            blipPaint.alpha = (120 + 135 * blipPhase).toInt().coerceIn(0, 255)
            canvas.drawCircle(bx, by, Shapes.dp(context, 3.5f) * (0.8f + 0.4f * blipPhase), blipPaint)
            index += 2
        }
    }
}
