package com.puretech.dialer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A lightweight vertical bar chart (no external charting library). Feed it a
 * list of labelled values via [setData]; it draws a faint full-height track per
 * slot, a rounded value bar scaled to the largest value, the value above each
 * bar, and the label below. Used by the Call durations screen to visualise how
 * many calls happened per day / month / year.
 */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Bar(val label: String, val value: Int, val displayValue: String = value.toString())

    private var bars: List<Bar> = emptyList()

    private val barColor = context.themeColor(com.google.android.material.R.attr.colorPrimary)
    private val textColor = context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val trackColor = context.themeColor(com.google.android.material.R.attr.colorSurfaceVariant)

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 11f * scaledDensity
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 11f * scaledDensity
        isFakeBoldText = true
    }
    private val rect = RectF()

    fun setData(data: List<Bar>) {
        bars = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = bars.size
        if (n == 0) return
        val maxVal = (bars.maxOfOrNull { it.value } ?: 0).coerceAtLeast(1)

        val labelH = 18f * density   // bottom strip for x-axis labels
        val valueH = 16f * density   // top strip for value text
        val slot = (width - paddingStart - paddingEnd).toFloat() / n
        val barW = (slot * 0.5f).coerceAtMost(26f * density)
        val radius = barW / 2f
        val chartTop = paddingTop + valueH
        val chartBottom = height - paddingBottom - labelH
        val chartH = (chartBottom - chartTop).coerceAtLeast(1f)

        for (i in 0 until n) {
            val cx = paddingStart + slot * i + slot / 2f
            val left = cx - barW / 2f
            val right = cx + barW / 2f

            // Faint full-height track.
            rect.set(left, chartTop, right, chartBottom)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)

            // Value bar.
            val value = bars[i].value
            if (value > 0) {
                val barTop = chartBottom - chartH * (value.toFloat() / maxVal)
                rect.set(left, barTop, right, chartBottom)
                canvas.drawRoundRect(rect, radius, radius, barPaint)
                canvas.drawText(bars[i].displayValue, cx, barTop - 5f * density, valuePaint)
            }

            // X-axis label.
            canvas.drawText(bars[i].label, cx, height - paddingBottom - 4f * density, labelPaint)
        }
    }
}
