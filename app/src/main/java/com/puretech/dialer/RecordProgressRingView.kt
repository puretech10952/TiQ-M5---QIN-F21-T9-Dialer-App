package com.puretech.dialer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws the recording-progress ring: an arc sweeping clockwise from the top
 * as `progress` (0f..1f, elapsed/max duration) increases. Invisible (no
 * stroke) at progress <= 0, i.e. while idle.
 */
class RecordProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val strokeWidthPx = resources.displayMetrics.density * 6
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#D32F2F")
    }
    private val bounds = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokeWidthPx / 2 + 2
        bounds.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f) return
        canvas.drawArc(bounds, -90f, 360f * progress, false, paint)
    }
}
