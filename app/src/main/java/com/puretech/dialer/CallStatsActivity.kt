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

    /** All call timestamps, loaded once and re-bucketed when the range changes. */
    private var callDates: LongArray = LongArray(0)

    private enum class Range { DAY, MONTH, YEAR }

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

        binding.rangeToggle.check(R.id.btnRangeMonth)
        binding.rangeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) renderChart(rangeFor(checkedId))
        }

        Thread {
            val s = CallLogRepository.stats(applicationContext)
            val dates = CallLogRepository.callDates(applicationContext)
            runOnUiThread {
                bind(s)
                callDates = dates
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

    private fun renderChart(range: Range) {
        val bars = buckets(callDates, range)
        val hasData = bars.any { it.value > 0 }
        binding.chart.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.chartEmpty.visibility = if (hasData) View.GONE else View.VISIBLE
        binding.chart.setData(bars)
    }

    // --- Bucketing -------------------------------------------------------------

    /** Tally call dates into the trailing window for [range], oldest → newest. */
    private fun buckets(dates: LongArray, range: Range): List<BarChartView.Bar> {
        val counts = HashMap<Long, Int>()
        val cal = Calendar.getInstance()
        for (d in dates) {
            cal.timeInMillis = d
            val key = keyOf(cal, range)
            counts[key] = (counts[key] ?: 0) + 1
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
            out.add(BarChartView.Bar(labelOf(c, range), counts[keyOf(c, range)] ?: 0))
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
