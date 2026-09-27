package app.morsecode.android.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import app.morsecode.android.R
import app.morsecode.android.core.media.SeekContract
import app.morsecode.android.core.util.ThemeColors

/**
 * §6.11's seek bar, shared by the music and video players.
 *
 * "the track is a real control, not decoration: press anywhere on it and
 * playback jumps to that point, with a hit area at least 24 dp tall even when
 * the drawn track is 4–5 dp; a KNOB sits at the current position... while the
 * user is dragging, the clock STOPS driving the bar."
 *
 * The arithmetic is [SeekContract]; this view draws it and reports gestures,
 * so the contract cannot drift between the two players (A32).
 */
class SeekBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val trackHeight = Shapes.dp(context, 5f)
    private val knobRadius = Shapes.dp(context, 8f)

    private val drag = SeekContract.DragState()

    var durationMillis: Long = 0
        set(value) {
            field = value.coerceAtLeast(0)
            invalidate()
        }

    /** The clock's position. Ignored while the user is dragging (§6.11). */
    var positionMillis: Long = 0
        set(value) {
            field = value.coerceIn(0, durationMillis.coerceAtLeast(0))
            if (!drag.isDragging) invalidate()
        }

    /** Live during a drag, so the labels follow the thumb. */
    var onScrub: ((Long) -> Unit)? = null

    /** Release commits the seek. */
    var onSeek: ((Long) -> Unit)? = null

    init {
        isFocusable = true
        isClickable = true
        trackPaint.color = ThemeColors.resolve(context, R.attr.colorHairline)
        knobPaint.color = ThemeColors.accent(context)
        contentDescription = context.getString(R.string.cd_seek_bar)
    }

    /** What the bar is showing: the thumb while dragging, the clock otherwise. */
    fun displayedPosition(): Long = drag.displayPosition(positionMillis)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // §6.11: a 24 dp hit area even though the drawn track is 5 dp.
        val height = Shapes.dpInt(context, SeekContract.MIN_TOUCH_HEIGHT_DP.toFloat())
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val centreY = height / 2f
        val left = knobRadius
        val right = width - knobRadius
        val usable = (right - left).coerceAtLeast(1f)
        val fraction = SeekContract.fractionFor(displayedPosition(), durationMillis)
        val knobX = left + usable * fraction

        canvas.drawRoundRect(
            left, centreY - trackHeight / 2f, right, centreY + trackHeight / 2f,
            trackHeight, trackHeight, trackPaint,
        )

        if (fraction > 0f) {
            // §4.5: the progress gradient runs accent → lime.
            fillPaint.shader = LinearGradient(
                left, 0f, knobX, 0f,
                ThemeColors.accent(context),
                ContextCompat.getColor(context, R.color.accent_leaf),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(
                left, centreY - trackHeight / 2f, knobX, centreY + trackHeight / 2f,
                trackHeight, trackHeight, fillPaint,
            )
        }

        // The knob is always visible, not only while dragging.
        canvas.drawCircle(knobX, centreY, knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMillis <= 0) return false
        val position = SeekContract.positionForTouch(
            event.x - knobRadius,
            (width - knobRadius * 2).toInt(),
            durationMillis,
        )
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                drag.begin(position)
                onScrub?.invoke(position)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                drag.update(position)
                // Labels update live during the drag, not only on release.
                onScrub?.invoke(position)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val committed = drag.commit()
                onSeek?.invoke(committed)
                announceForAccessibility(SeekContract.announcement(committed, durationMillis))
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                drag.cancel()
                invalidate()
            }
        }
        return true
    }

    /** §6.11: ← → step 5 s, Home/End jump to the ends. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val next = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> SeekContract.keyLeft(displayedPosition(), durationMillis)
            KeyEvent.KEYCODE_DPAD_RIGHT -> SeekContract.keyRight(displayedPosition(), durationMillis)
            KeyEvent.KEYCODE_MOVE_HOME -> SeekContract.home()
            KeyEvent.KEYCODE_MOVE_END -> SeekContract.end(durationMillis)
            else -> return super.onKeyDown(keyCode, event)
        }
        positionMillis = next
        onSeek?.invoke(next)
        announceForAccessibility(SeekContract.announcement(next, durationMillis))
        return true
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.text.add(SeekContract.announcement(displayedPosition(), durationMillis))
    }
}
