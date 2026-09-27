package app.morsecode.android.core.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.morsecode.android.R
import app.morsecode.android.core.util.DeviceTier
import app.morsecode.android.core.util.ThemeColors

/**
 * §4.12b's thin progress bar and §4.5's fill rules:
 *   running   — left-to-right gradient from the accent to #84CC16
 *   completed — solid #22C55E
 *   paused    — solid #F59E0B
 *   failed    — solid #EF4444 at 40%, width-frozen
 *
 * §4.11 MOTION: the bar lerps to its new value over 150 ms rather than jumping,
 * obeys ANIMATOR_DURATION_SCALE, and does not animate at all on a low-tier
 * device (§13) — the value still updates, only the tween is dropped.
 */
class ThinProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode { RUNNING, COMPLETE, PAUSED, FAILED }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val tier = DeviceTier(context)

    private var mode: Mode = Mode.RUNNING
    private var progress: Float = 0f
    private var animator: ValueAnimator? = null

    init {
        trackPaint.color = ThemeColors.resolve(context, R.attr.colorSurfaceRaised)
    }

    /** Sets 0f..1f. Animated by default; a state change never jumps the bar (§4.11). */
    fun setProgress(value: Float, animate: Boolean = true) {
        val target = value.coerceIn(0f, 1f)
        animator?.cancel()
        val duration = tier.scaled(150L)
        if (!animate || duration <= 0L || !isAttachedToWindow) {
            progress = target
            invalidate()
            return
        }
        val from = progress
        animator = ValueAnimator.ofFloat(from, target).apply {
            this.duration = duration
            addUpdateListener { anim ->
                progress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setMode(mode: Mode) {
        this.mode = mode
        if (mode == Mode.FAILED) {
            animator?.cancel()
            progress = 0.4f   // width-frozen at 40% (§4.5)
        }
        invalidate()
    }

    fun progress(): Float = progress

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val filled = width * progress
        if (filled <= 0f) return

        when (mode) {
            Mode.RUNNING -> {
                fillPaint.shader = LinearGradient(
                    0f, 0f, filled, 0f,
                    ThemeColors.accent(context),
                    ContextCompat.getColor(context, R.color.progress_gradient_end),
                    Shader.TileMode.CLAMP,
                )
            }
            Mode.COMPLETE -> solid(R.color.state_success)
            Mode.PAUSED -> solid(R.color.state_warning)
            Mode.FAILED -> solid(R.color.state_error)
        }
        rect.set(0f, 0f, filled, height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
    }

    private fun solid(colorRes: Int) {
        fillPaint.shader = null
        fillPaint.color = ContextCompat.getColor(context, colorRes)
    }
}
