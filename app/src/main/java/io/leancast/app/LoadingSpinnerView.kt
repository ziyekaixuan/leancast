package io.leancast.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Minimal loading spinner: a faint full ring with a bright 2/3 arc rotating
 * over it. No text, no background — just the ring, centered on screen while
 * the first frames load. Drawn by hand rather than using the platform's
 * indeterminate ProgressBar so it looks identical on every TV skin.
 */
class LoadingSpinnerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        color = TRACK_COLOR
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
        color = ARC_COLOR
    }

    private val bounds = RectF()
    private var rotation = 0f
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = trackPaint.strokeWidth / 2f + 1f * density
        bounds.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawOval(bounds, trackPaint)
        canvas.drawArc(bounds, rotation, ARC_SWEEP_DEGREES, false, arcPaint)
    }

    /** Starts the rotation; safe to call when already spinning. */
    fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = ROTATION_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { rotation = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Stops the rotation and resets the arc to the top. */
    fun stop() {
        animator?.cancel()
        animator = null
        rotation = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    companion object {
        /** Stroke width of the ring and the arc. */
        private const val STROKE_DP = 4f

        /** The arc covers two thirds of the circle. */
        private const val ARC_SWEEP_DEGREES = 240f

        /** One full turn per 900 ms — calm, not frantic. */
        private const val ROTATION_MS = 900L

        /** Faint white ring, barely there. */
        private val TRACK_COLOR = Color.argb(0x2E, 0xFF, 0xFF, 0xFF)

        /** Near-white arc. */
        private val ARC_COLOR = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
    }
}
