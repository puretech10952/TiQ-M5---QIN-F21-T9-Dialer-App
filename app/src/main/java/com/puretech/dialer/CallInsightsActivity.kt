package com.puretech.dialer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.puretech.dialer.databinding.ActivityCallInsightsBinding
import com.puretech.dialer.databinding.ItemTopContactBinding
import java.text.DateFormatSymbols
import java.util.Date
import kotlin.math.abs

/** Deep call analytics: averages, records, busy-times heatmap, top contacts,
 *  and week-over-week trends -- reached from the "Call durations" screen. */
class CallInsightsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallInsightsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallInsightsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }

        Thread {
            val s = CallLogRepository.deepStats(applicationContext)
            runOnUiThread { bind(s) }
        }.start()
    }

    private fun bind(s: DeepCallStats) {
        bindAverages(s)
        bindTrends(s)
        bindHeatmap(s)
        bindTopContacts(s)
    }

    // --- Averages & records -----------------------------------------------------

    private fun bindAverages(s: DeepCallStats) {
        binding.avgOverall.text = s.avgDurationOverall.asTalkTime()
        binding.avgIncoming.text = s.avgDurationIncoming.asTalkTime()
        binding.avgOutgoing.text = s.avgDurationOutgoing.asTalkTime()
        binding.answerRate.text = getString(R.string.percent_format, s.answerRatePercent)
        binding.callsPerDay.text = String.format("%.1f", s.avgCallsPerActiveDay)

        val longest = s.longestCall
        if (longest != null) {
            binding.longestCallValue.text = longest.duration.asTalkTime()
            val who = longest.name ?: longest.number
            binding.longestCallSub.text = getString(R.string.insights_longest_call_sub, who, shortDate(longest.date))
        } else {
            binding.longestCallValue.text = "--"
            binding.longestCallSub.text = getString(R.string.insights_no_data)
        }

        val busiest = s.busiestDayEver
        if (busiest != null) {
            binding.busiestDayValue.text = busiest.duration.asTalkTime()
            binding.busiestDaySub.text = getString(R.string.insights_busiest_day_sub, shortDate(busiest.date)) + " · " +
                resources.getQuantityString(R.plurals.insights_call_count, busiest.count, busiest.count)
        } else {
            binding.busiestDayValue.text = "--"
            binding.busiestDaySub.text = getString(R.string.insights_no_data)
        }
    }

    // --- Trends & run-rate --------------------------------------------------------

    private fun bindTrends(s: DeepCallStats) {
        binding.thisWeekValue.text = s.thisWeekDuration.asTalkTime()
        binding.lastWeekValue.text = s.lastWeekDuration.asTalkTime()

        val delta = when {
            s.lastWeekDuration > 0 -> {
                val pct = ((s.thisWeekDuration - s.lastWeekDuration) * 100 / s.lastWeekDuration)
                val arrow = if (pct >= 0) "▲" else "▼"
                "$arrow ${abs(pct)}% ${getString(R.string.insights_vs_last_week)}"
            }
            s.thisWeekDuration > 0 -> getString(R.string.insights_first_week)
            else -> ""
        }
        binding.trendDelta.text = delta
        binding.trendDelta.visibility = if (delta.isEmpty()) View.GONE else View.VISIBLE

        binding.projectedValue.text = getString(R.string.insights_about, s.projectedThisMonth.asTalkTime())
    }

    // --- Busy times heatmap --------------------------------------------------------

    private val daypartLabels get() = resources.getStringArray(R.array.daypart_labels).toList()
    private val shortWeekdays = DateFormatSymbols.getInstance().shortWeekdays
    private val fullWeekdays = DateFormatSymbols.getInstance().weekdays

    private fun bindHeatmap(s: DeepCallStats) {
        val cells = s.heatmapCells.map {
            BusyTimesHeatmapView.Cell(it.weekday, it.daypart, it.duration)
        }
        // shortWeekdays/weekdays are 1-based (index 0 unused, 1 = Sunday .. 7 = Saturday);
        // our weekday index is 0-based (0 = Sunday .. 6 = Saturday).
        val weekdayCols = (0..6).map { shortWeekdays.getOrElse(it + 1) { "" }.take(2) }
        binding.heatmap.setData(cells, weekdayCols, daypartLabels)

        val busiest = s.heatmapCells.maxByOrNull { it.duration }
        if (busiest != null && busiest.duration > 0) {
            val weekdayName = fullWeekdays.getOrElse(busiest.weekday + 1) { "" }
            val daypartName = daypartLabels.getOrElse(busiest.daypart) { "" }
            binding.busiestSentence.text =
                getString(R.string.insights_busiest_sentence, weekdayName, daypartName)
            binding.busiestSentence.visibility = View.VISIBLE
        } else {
            binding.busiestSentence.visibility = View.GONE
        }
    }

    // --- Top contacts --------------------------------------------------------------

    private fun bindTopContacts(s: DeepCallStats) {
        binding.topContactsContainer.removeAllViews()
        if (s.topContacts.isEmpty()) {
            binding.topContactsEmpty.visibility = View.VISIBLE
            return
        }
        binding.topContactsEmpty.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        s.topContacts.forEachIndexed { index, contact ->
            val row = ItemTopContactBinding.inflate(inflater, binding.topContactsContainer, true)
            row.root.background = getDrawable(
                when {
                    s.topContacts.size == 1 -> R.drawable.bg_rowgroup_solo
                    index == 0 -> R.drawable.bg_rowgroup_top
                    index == s.topContacts.size - 1 -> R.drawable.bg_rowgroup_bottom
                    else -> R.drawable.bg_rowgroup_middle
                }
            )
            Avatars.bind(row.contactInitial, row.contactPhoto, contact.name, null)
            row.contactName.text = contact.name
            row.contactSub.text =
                resources.getQuantityString(R.plurals.insights_call_count, contact.count, contact.count)
            row.contactDuration.text = contact.duration.asTalkTime()
        }
    }

    private fun shortDate(date: Long): String =
        android.text.format.DateFormat.format("MMM d", Date(date)).toString()
}
