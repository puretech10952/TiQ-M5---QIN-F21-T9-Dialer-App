package com.puretech.dialer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Google-style "slide to answer" pill for the incoming-call screen. A white
 * call-button sits in the middle of a rounded pill labelled "Decline" on the
 * left and "Answer" on the right: slide it RIGHT to answer or LEFT to decline.
 * Released near the centre, it springs back. Sized to fit the small M5/F21
 * screens. Optional alternative to the round buttons (see [Prefs.swipeToAnswer]).
 */
class SwipeToAnswerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var onAnswer: (() -> Unit)? = null
    var onDecline: (() -> Unit)? = null

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun sp(v: Float) = v * dm.scaledDensity

    private val green = 0xFF1E8E3E.toInt()
    private val red = 0xFFD93025.toInt()
    private val labelGrey = 0xFF3C4043.toInt()

    private val handleRadius = dp(33f)
    // Pill is a pinch taller than the ball (≈3dp margin each side) so the handle
    // nests inside it instead of overflowing.
    private val pillHalfHeight = dp(36f)
    private val inset = dp(2f)
    private val iconHalf = dp(13f)

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEEF0.toInt() }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(5f), 0f, dp(1.5f), 0x40000000)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(15f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val handleIcon = ContextCompat.getDrawable(context, R.drawable.ic_call)!!.mutate()

    private val pillRect = RectF()
    private var restX = 0f
    private var leftX = 0f
    private var rightX = 0f
    private var handleX = 0f
    private var centerY = 0f
    private var declineLabelX = 0f
    private var answerLabelX = 0f

    private var dragging = false
    private var grabDx = 0f
    private var fired = false
    private var settleAnim: ValueAnimator? = null

    init {
        // Software layer so the handle's drop shadow renders.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        handleIcon.setTint(green)
        contentDescription = context.getString(R.string.swipe_to_answer_hint)
    }

    /** Recentre the handle and re-arm the control for the next incoming call. */
    fun reset() {
        settleAnim?.cancel()
        fired = false
        dragging = false
        handleX = restX
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(300f).toInt(), widthMeasureSpec)
        val desiredH = (maxOf(handleRadius * 2, pillHalfHeight * 2) + dp(8f)).toInt()
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerY = h / 2f
        leftX = inset + handleRadius
        rightX = w - inset - handleRadius
        restX = w / 2f
        if (!dragging && settleAnim?.isRunning != true) handleX = restX
        pillRect.set(inset, centerY - pillHalfHeight, w - inset, centerY + pillHalfHeight)
        // Labels centred in the gap between each end and the resting handle.
        declineLabelX = (pillRect.left + (restX - handleRadius)) / 2f
        answerLabelX = ((restX + handleRadius) + pillRect.right) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val r = pillRect.height() / 2f
        canvas.drawRoundRect(pillRect, r, r, pillPaint)

        // Labels fade out as the handle slides over them; the destination label
        // colours in (red for decline, green for answer) as you commit toward it.
        val progress = ((handleX - restX) / (rightX - restX)).coerceIn(-1f, 1f)
        val baseline = centerY - (labelPaint.descent() + labelPaint.ascent()) / 2f

        val declineFade = (1f - (progress.coerceAtMost(0f).let { -it })).coerceIn(0f, 1f)
        labelPaint.color = blend(labelGrey, red, (-progress).coerceIn(0f, 1f))
        labelPaint.alpha = (declineFade * 255).toInt()
        canvas.drawText(context.getString(R.string.ctl_decline), declineLabelX, baseline, labelPaint)

        val answerFade = (1f - progress.coerceAtLeast(0f)).coerceIn(0f, 1f)
        labelPaint.color = blend(labelGrey, green, progress.coerceIn(0f, 1f))
        labelPaint.alpha = (answerFade * 255).toInt()
        canvas.drawText(context.getString(R.string.ctl_answer), answerLabelX, baseline, labelPaint)
        labelPaint.alpha = 255

        // White handle with the green call icon.
        canvas.drawCircle(handleX, centerY, handleRadius, handlePaint)
        handleIcon.setBounds(
            (handleX - iconHalf).toInt(), (centerY - iconHalf).toInt(),
            (handleX + iconHalf).toInt(), (centerY + iconHalf).toInt()
        )
        handleIcon.draw(canvas)
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val inv = 1f - t
        val a = (Color.alpha(from) * inv + Color.alpha(to) * t).toInt()
        val r = (Color.red(from) * inv + Color.red(to) * t).toInt()
        val g = (Color.green(from) * inv + Color.green(to) * t).toInt()
        val b = (Color.blue(from) * inv + Color.blue(to) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (fired) return false
                settleAnim?.cancel()
                dragging = true
                grabDx = event.x - handleX
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                handleX = (event.x - grabDx).coerceIn(leftX, rightX)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val progress = (handleX - restX) / (rightX - restX)
                when {
                    progress >= COMMIT || handleX >= rightX - dp(3f) -> commit(true)
                    progress <= -COMMIT || handleX <= leftX + dp(3f) -> commit(false)
                    else -> settleBack()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun commit(answer: Boolean) {
        if (fired) return
        fired = true
        handleX = if (answer) rightX else leftX
        invalidate()
        if (answer) onAnswer?.invoke() else onDecline?.invoke()
    }

    private fun settleBack() {
        settleAnim?.cancel()
        settleAnim = ValueAnimator.ofFloat(handleX, restX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { handleX = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        settleAnim?.cancel()
    }

    companion object {
        // Fraction of the half-track the handle must cross to count as a commit.
        private const val COMMIT = 0.6f
    }
}
