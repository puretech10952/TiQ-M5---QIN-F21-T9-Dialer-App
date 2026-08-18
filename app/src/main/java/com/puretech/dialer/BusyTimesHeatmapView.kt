package com.puretech.dialer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A weekday x time-of-day heatmap grid (no external charting library, same
 * hand-rolled house style as [BarChartView]). Weekday columns run left to
 * right along the top; daypart rows run top to bottom down the left edge.
 * Each cell's fill lerps from the surface-variant color (empty) to the
 * primary color (busiest), so it adapts to light/dark/dynamic theme.
 */
class BusyTimesHeatmapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Cell(val weekday: Int, val daypart: Int, val value: Long)

    private var cells: List<Cell> = emptyList()
    private var weekdayLabels: List<String> = emptyList()
    private var daypartLabels: List<String> = emptyList()

    private val emptyColor = context.themeColor(com.google.android.material.R.attr.colorSurfaceVariant)
    private val fullColor = context.themeColor(com.google.android.material.R.attr.colorPrimary)
    private val textColor = context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 10f * scaledDensity
    }
    private val daypartLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.LEFT
        textSize = 10f * scaledDensity
    }
    private val rect = RectF()

    /** [rows] is 7 weekdays x 6 dayparts (42 cells); missing combinations count as 0. */
    fun setData(rows: List<Cell>, weekdayLabels: List<String>, daypartLabels: List<String>) {
        cells = rows
        this.weekdayLabels = weekdayLabels
        this.daypartLabels = daypartLabels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cols = weekdayLabels.size
        val rows = daypartLabels.size
        if (cols == 0 || rows == 0 || cells.isEmpty()) return
        val maxVal = (cells.maxOfOrNull { it.value } ?: 0L).coerceAtLeast(1L)
        val grid = HashMap<Pair<Int, Int>, Long>()
        cells.forEach { grid[it.weekday to it.daypart] = it.value }

        val leftGutter = 34f * density   // daypart row labels
        val topGutter = 16f * density    // weekday column labels
        val gap = 3f * density
        val gridW = (width - paddingStart - paddingEnd - leftGutter).coerceAtLeast(1f)
        val gridH = (height - paddingTop - paddingBottom - topGutter).coerceAtLeast(1f)
        val cellW = gridW / cols
        val cellH = gridH / rows
        val radius = 6f * density

        for (wd in 0 until cols) {
            val cx = paddingStart + leftGutter + cellW * wd + cellW / 2f
            canvas.drawText(weekdayLabels[wd], cx, paddingTop + topGutter - 5f * density, labelPaint)
        }

        for (dp in 0 until rows) {
            val top = paddingTop + topGutter + cellH * dp
            canvas.drawText(
                daypartLabels[dp], paddingStart.toFloat(),
                top + cellH / 2f + 4f * density, daypartLabelPaint
            )
            for (wd in 0 until cols) {
                val value = grid[wd to dp] ?: 0L
                val intensity = value.toFloat() / maxVal
                cellPaint.color = lerpColor(emptyColor, fullColor, intensity)
                val left = paddingStart + leftGutter + cellW * wd
                rect.set(left + gap / 2f, top + gap / 2f, left + cellW - gap / 2f, top + cellH - gap / 2f)
                canvas.drawRoundRect(rect, radius, radius, cellPaint)
            }
        }
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        return Color.rgb(r, g, b)
    }
}
