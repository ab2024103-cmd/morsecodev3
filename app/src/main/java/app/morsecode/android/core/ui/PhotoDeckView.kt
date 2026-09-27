package app.morsecode.android.core.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import app.morsecode.android.core.util.DeviceTier

/**
 * §6.10's photo deck.
 *
 * "dragging moves the deck 1:1 with the finger and the neighbouring photos come
 * in from the edges (three slides live at a time: prev/current/next);
 * releasing past ~22 % of the width (or a flick) commits to that neighbour and
 * animates in ~220 ms; anything less snaps back; the deck wraps at both ends;
 * vertical scroll gestures are not captured and pinch-zoom still works on the
 * current photo."
 *
 * The *decision* is [DeckPhysics]; this view only moves pixels, which is what
 * makes A33's rule testable without a screen.
 */
class PhotoDeckView(
    context: Context,
    private val tier: DeviceTier,
) : ViewGroup(context) {

    /** Asked for the bitmap of a position; null while it is still loading. */
    var bitmapProvider: (Int) -> Bitmap? = { null }

    var onIndexChanged: ((Int) -> Unit)? = null

    var count: Int = 0
        set(value) {
            field = value
            requestLayout()
        }

    var index: Int = 0
        private set

    private val previous = slide()
    private val current = slide()
    private val next = slide()

    private var dragX = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    private var scale = 1f
    private val zoomMatrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scale = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                applyZoom()
                return true
            }
        },
    )

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    init {
        addView(previous)
        addView(current)
        addView(next)
    }

    fun show(position: Int) {
        index = if (count == 0) 0 else ((position % count) + count) % count
        scale = 1f
        zoomMatrix.reset()
        current.imageMatrix = zoomMatrix
        rebind()
        dragX = 0f
        requestLayout()
        onIndexChanged?.invoke(index)
    }

    /** Keyboards keep ← → as an equivalent, because a desktop has no thumb. */
    fun goNext() = animateTo(DeckPhysics.Outcome.NEXT)

    fun goPrevious() = animateTo(DeckPhysics.Outcome.PREVIOUS)

    fun refresh() = rebind()

    private fun slide(): AppCompatImageView {
        val view = AppCompatImageView(context)
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        // §15.1: the deck is decorative — the viewer's top bar carries the
        // file name, the index and the size, and TalkBack should read that
        // once rather than three unlabelled images.
        view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        return view
    }

    private fun rebind() {
        if (count == 0) return
        current.setImageBitmap(bitmapProvider(index))
        previous.setImageBitmap(bitmapProvider(DeckPhysics.previousIndex(index, count)))
        next.setImageBitmap(bitmapProvider(DeckPhysics.nextIndex(index, count)))
    }

    private fun applyZoom() {
        zoomMatrix.reset()
        zoomMatrix.postScale(scale, scale, width / 2f, height / 2f)
        current.scaleType = if (scale > 1f) ImageView.ScaleType.MATRIX else ImageView.ScaleType.FIT_CENTER
        current.imageMatrix = zoomMatrix
        current.invalidate()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val offset = dragX.toInt()
        previous.layout(-w + offset, 0, offset, h)
        current.layout(offset, 0, w + offset, h)
        next.layout(w + offset, 0, 2 * w + offset, h)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val spec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        for (child in listOf(previous, current, next)) child.measure(spec, heightSpec)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (scale > 1f) return false // zoomed in: the photo takes the gesture
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(event.x - downX)
                val dy = Math.abs(event.y - downY)
                // Vertical gestures are not captured (§6.10: touch-action pan-y).
                if (dx > touchSlop && dx > dy) dragging = true
            }
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scale > 1f) return true

        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                dragX = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                // 1:1 with the finger.
                dragX = event.x - downX
                requestLayout()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val outcome = DeckPhysics.outcome(dragX, width, vx)
                animateTo(outcome)
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    private fun animateTo(outcome: DeckPhysics.Outcome) {
        val target = when (outcome) {
            DeckPhysics.Outcome.NEXT -> -width.toFloat()
            DeckPhysics.Outcome.PREVIOUS -> width.toFloat()
            DeckPhysics.Outcome.SNAP_BACK -> 0f
        }
        val from = dragX
        val duration = tier.scaled(DeckPhysics.SETTLE_MS)
        if (duration <= 0L) {
            dragX = target
            settle(outcome)
            return
        }
        ValueAnimator.ofFloat(from, target).apply {
            this.duration = duration
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                dragX = it.animatedValue as Float
                requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    settle(outcome)
                }
            })
            start()
        }
    }

    private fun settle(outcome: DeckPhysics.Outcome) {
        dragX = 0f
        if (outcome != DeckPhysics.Outcome.SNAP_BACK) {
            show(DeckPhysics.apply(outcome, index, count))
        } else {
            requestLayout()
        }
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
    }
}
