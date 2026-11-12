package io.leancast.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.sin

/**
 * Minimal loading indicator: three white dots in a row, bouncing up in
 * sequence like a wave. No text, no background — just the dots, centered
 * on screen while the first frames load. Drawn by hand rather than using
 * the platform's indeterminate ProgressBar so it looks identical on
 * every TV skin.
 */
class LoadingSpinnerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    /** Horizontal centers of the three dots, and their resting height. */
    private val dotCx = FloatArray(DOT_COUNT)
    private var baselineCy = 0f
    private var bounceAmplitude = 0f
    private var progress = 0f
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = DOT_RADIUS_DP * density
        val gap = DOT_GAP_DP * density
        val totalWidth = DOT_COUNT * 2 * radius + (DOT_COUNT - 1) * gap
        var cx = (w - totalWidth) / 2f + radius
        for (i in 0 until DOT_COUNT) {
            dotCx[i] = cx
            cx += 2 * radius + gap
        }
        // The dots' travel band (bounce + dot height) sits centered in
        // the view; the rest position is half a bounce below center.
        bounceAmplitude = BOUNCE_DP * density
        baselineCy = h / 2f + bounceAmplitude / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = DOT_RADIUS_DP * density
        for (i in 0 until DOT_COUNT) {
            // Each dot rises for half the cycle, staggered by a third of
            // a cycle, so the bounce travels left to right in a wave.
            val phase = progress * TWO_PI - i * TWO_PI / DOT_COUNT
            val lift = max(0f, sin(phase))
            val cy = baselineCy - bounceAmplitude * lift
            dotPaint.alpha = ((MIN_ALPHA + (1f - MIN_ALPHA) * lift) * 255).toInt()
            canvas.drawCircle(dotCx[i], cy, radius, dotPaint)
        }
    }

    /** Starts the bounce; safe to call when already running. */
    fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Stops the bounce and settles the dots back on the baseline. */
    fun stop() {
        animator?.cancel()
        animator = null
        progress = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val DOT_COUNT = 3

        /** Dot radius and the gap between dots. */
        private const val DOT_RADIUS_DP = 5f
        private const val DOT_GAP_DP = 10f

        /** How high a dot rises at the peak of its bounce. */
        private const val BOUNCE_DP = 8f

        /** One full wave per 1.2 s — calm, not frantic. */
        private const val CYCLE_MS = 1200L

        /** A resting dot is dimmer; it brightens as it rises. */
        private const val MIN_ALPHA = 0.45f

        private const val TWO_PI = 6.2831855f
    }
}
