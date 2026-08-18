package com.puretech.dialer

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.puretech.dialer.databinding.ActivityCallStatsBinding
import java.text.DateFormatSymbols
import java.util.Calendar

/** Lifetime call totals (since ever) + a call-activity graph by day/month/year. */
class CallStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallStatsBinding

    /** All calls' (timestamp, duration) pairs, loaded once and re-bucketed
     *  when the range or the Calls/Minutes toggle changes. */
    private var callLog: List<Pair<Long, Long>> = emptyList()

    private enum class Range { DAY, MONTH, YEAR }
    private enum class ValueMode { CALLS, MINUTES }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }

        // Tapping a breakdown card jumps to the recents list, pre-filtered to
        // that call direction.
        binding.cardIncoming.setOnClickListener { openLog(HomeActivity.FILTER_INCOMING) }
        binding.cardOutgoing.setOnClickListener { openLog(HomeActivity.FILTER_OUTGOING) }
        binding.cardMissed.setOnClickListener { openLog(HomeActivity.FILTER_MISSED) }

        binding.cardInsights.setOnClickListener {
            startActivity(Intent(this, CallInsightsActivity::class.java))
        }

        binding.valueToggle.check(R.id.btnValueCalls)
        binding.valueToggle.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) renderChart(rangeFor(binding.rangeToggle.checkedButtonId))
        }

        binding.rangeToggle.check(R.id.btnRangeMonth)
        binding.rangeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) renderChart(rangeFor(checkedId))
        }

        Thread {
            val s = CallLogRepository.stats(applicationContext)
            val log = CallLogRepository.callLog(applicationContext)
            runOnUiThread {
                bind(s)
                callLog = log
                renderChart(rangeFor(binding.rangeToggle.checkedButtonId))
            }
        }.start()
    }

    private fun openLog(filter: String) {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .putExtra(HomeActivity.EXTRA_FILTER, filter)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun bind(s: CallStats) {
        binding.totalTalk.text = s.totalDuration.asTalkTime()
        binding.incomingDuration.text = s.incomingDuration.asTalkTime()
        binding.incomingCount.text =
            resources.getQuantityString(R.plurals.stats_calls, s.incomingCount, s.incomingCount)
        binding.outgoingDuration.text = s.outgoingDuration.asTalkTime()
        binding.outgoingCount.text =
            resources.getQuantityString(R.plurals.stats_calls, s.outgoingCount, s.outgoingCount)
        binding.missedCount.text = s.missedCount.toString()
    }

    private fun rangeFor(checkedId: Int): Range = when (checkedId) {
        R.id.btnRangeDay -> Range.DAY
        R.id.btnRangeYear -> Range.YEAR
        else -> Range.MONTH
    }

    private fun valueModeFor(checkedId: Int): ValueMode =
        if (checkedId == R.id.btnValueMinutes) ValueMode.MINUTES else ValueMode.CALLS

    private fun renderChart(range: Range) {
        val mode = valueModeFor(binding.valueToggle.checkedButtonId)
        val bars = buckets(callLog, range, mode)
        val hasData = bars.any { it.value > 0 }
        binding.chart.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.chartEmpty.visibility = if (hasData) View.GONE else View.VISIBLE
        binding.chart.setData(bars)
    }

    // --- Bucketing -------------------------------------------------------------

    /** Tally calls into the trailing window for [range], oldest → newest --
     *  either a count per bucket, or total talk time when [mode] is MINUTES. */
    private fun buckets(log: List<Pair<Long, Long>>, range: Range, mode: ValueMode): List<BarChartView.Bar> {
        val counts = HashMap<Long, Int>()
        val durations = HashMap<Long, Long>()
        val cal = Calendar.getInstance()
        for ((date, duration) in log) {
            cal.timeInMillis = date
            val key = keyOf(cal, range)
            counts[key] = (counts[key] ?: 0) + 1
            durations[key] = (durations[key] ?: 0L) + duration
        }
        val span = when (range) { Range.DAY -> 7; Range.MONTH -> 12; Range.YEAR -> 6 }
        val out = ArrayList<BarChartView.Bar>(span)
        for (i in span - 1 downTo 0) {
            val c = Calendar.getInstance()
            when (range) {
                Range.DAY -> c.add(Calendar.DAY_OF_YEAR, -i)
                Range.MONTH -> c.add(Calendar.MONTH, -i)
                Range.YEAR -> c.add(Calendar.YEAR, -i)
            }
            val key = keyOf(c, range)
            out.add(
                if (mode == ValueMode.MINUTES) {
                    val secs = durations[key] ?: 0L
                    BarChartView.Bar(labelOf(c, range), (secs / 60).toInt(), secs.asTalkTime())
                } else {
                    BarChartView.Bar(labelOf(c, range), counts[key] ?: 0)
                }
            )
        }
        return out
    }

    private fun keyOf(c: Calendar, range: Range): Long = when (range) {
        Range.DAY -> c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
        Range.MONTH -> c.get(Calendar.YEAR) * 100L + c.get(Calendar.MONTH)
        Range.YEAR -> c.get(Calendar.YEAR).toLong()
    }

    private val shortMonths = DateFormatSymbols.getInstance().shortMonths

    private fun labelOf(c: Calendar, range: Range): String = when (range) {
        Range.DAY -> c.get(Calendar.DAY_OF_MONTH).toString()
        Range.MONTH -> shortMonths.getOrElse(c.get(Calendar.MONTH)) { "" }
        Range.YEAR -> c.get(Calendar.YEAR).toString()
    }
}
