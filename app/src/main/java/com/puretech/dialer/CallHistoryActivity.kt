package com.puretech.dialer

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.puretech.dialer.databinding.ActivityCallHistoryBinding
import java.util.Calendar
import java.util.Locale

/** All calls (grouped, with duration) for a single number — Google card style. */
class CallHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallHistoryBinding
    private val telecomManager by lazy { getSystemService(TelecomManager::class.java) }
    private var number = ""

    private val callPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) placeCall() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }

        binding.title.text = name ?: PhoneNumberUtils.formatNumber(number, Locale.US.country) ?: number
        binding.subtitle.text = PhoneNumberUtils.formatNumber(number, Locale.US.country) ?: number
        Avatars.bind(binding.avatarInitial, binding.avatarPhoto, name, lookupPhoto(number))

        binding.back.setOnClickListener { finish() }
        binding.callFab.setOnClickListener { callNumber() }
        binding.msgBtn.setOnClickListener {
            try {
                startActivity(android.content.Intent(
                    android.content.Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")
                ))
            } catch (_: Exception) {
            }
        }

        val adapter = HistoryAdapter()
        binding.history.layoutManager = LinearLayoutManager(this)
        binding.history.adapter = adapter

        Thread {
            val details = CallLogRepository.loadForNumber(applicationContext, number)
            val totalSecs = details.sumOf { it.duration }
            val answered = details.count { it.duration > 0 }
            val rows = ArrayList<Row>()
            // Total talk time leads the list so it scrolls away with the calls.
            if (totalSecs > 0) rows.add(Row.Total(totalSecs, answered))
            rows.addAll(buildRows(details))
            runOnUiThread { adapter.submit(rows) }
        }.start()
    }

    private fun lookupPhoto(num: String): Uri? {
        if (num.isBlank() ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num)
        )
        return try {
            contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.PHOTO_URI), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { Uri.parse(it) } else null }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun callNumber() {
        if (number.isBlank()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) placeCall() else callPermLauncher.launch(Manifest.permission.CALL_PHONE)
    }

    private fun placeCall() {
        Dialer.place(this, Dialer.normalize(this, number))
    }

    // --- Rows / grouping -------------------------------------------------------

    private sealed class Row {
        data class Total(val totalSecs: Long, val count: Int) : Row()
        data class Header(val label: String) : Row()
        data class Item(val detail: CallDetail) : Row()
    }

    private fun buildRows(details: List<CallDetail>): List<Row> {
        val rows = ArrayList<Row>()
        var lastLabel: String? = null
        for (d in details) {
            val label = dayLabel(d.date)
            if (label != lastLabel) { rows.add(Row.Header(label)); lastLabel = label }
            rows.add(Row.Item(d))
        }
        return rows
    }

    private fun dayLabel(date: Long): String {
        val diff = ((midnight(System.currentTimeMillis()) - midnight(date)) /
            DateUtils.DAY_IN_MILLIS).toInt()
        return when {
            diff <= 0 -> getString(R.string.recents_today)
            diff == 1 -> getString(R.string.recents_yesterday)
            diff in 2..6 -> DateUtils.formatDateTime(this, date, DateUtils.FORMAT_SHOW_WEEKDAY)
            else -> DateUtils.formatDateTime(
                this, date, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
            )
        }
    }

    private fun midnight(t: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = ArrayList<Row>()

        fun submit(list: List<Row>) { rows.clear(); rows.addAll(list); notifyDataSetChanged() }

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Total -> 2
            is Row.Header -> 0
            is Row.Item -> 1
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                2 -> TotalVH(inflater.inflate(R.layout.item_call_history_total, parent, false))
                0 -> HeaderVH(inflater.inflate(R.layout.item_call_log_header, parent, false))
                else -> ItemVH(inflater.inflate(R.layout.item_call_history, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Total -> (holder as TotalVH).bind(row.totalSecs, row.count)
                is Row.Header -> (holder as HeaderVH).label.text = row.label
                is Row.Item -> (holder as ItemVH).bind(row.detail)
            }
        }

        inner class TotalVH(view: View) : RecyclerView.ViewHolder(view) {
            private val duration: TextView = view.findViewById(R.id.totalDuration)
            private val count: TextView = view.findViewById(R.id.totalCount)
            fun bind(totalSecs: Long, calls: Int) {
                duration.text = totalSecs.asTalkTime()
                count.text = itemView.resources.getQuantityString(
                    R.plurals.stats_calls, calls, calls
                )
            }
        }

        inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view as TextView
        }

        inner class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
            private val typeIcon: ImageView = view.findViewById(R.id.typeIcon)
            private val title: TextView = view.findViewById(R.id.title)
            private val time: TextView = view.findViewById(R.id.time)
            private val duration: TextView = view.findViewById(R.id.duration)

            fun bind(d: CallDetail) {
                val ctx = title.context
                val missed = d.type == CallLog.Calls.MISSED_TYPE || d.type == CallLog.Calls.REJECTED_TYPE
                val red = ContextCompat.getColor(ctx, R.color.missed_red)
                val onSurface = ctx.themeColor(com.google.android.material.R.attr.colorOnSurface)

                title.text = getString(
                    when (d.type) {
                        CallLog.Calls.OUTGOING_TYPE -> R.string.hist_outgoing
                        CallLog.Calls.REJECTED_TYPE -> R.string.hist_declined
                        CallLog.Calls.MISSED_TYPE -> R.string.hist_missed
                        else -> R.string.hist_incoming
                    }
                )
                title.setTextColor(if (missed) red else onSurface)
                typeIcon.setImageResource(
                    when (d.type) {
                        CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_call_made
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> R.drawable.ic_call_missed
                        else -> R.drawable.ic_call_received
                    }
                )
                // Match the recents list: outgoing green, missed red, incoming blue.
                val arrowColor = when (d.type) {
                    CallLog.Calls.OUTGOING_TYPE -> ContextCompat.getColor(ctx, R.color.call_arrow_outgoing)
                    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> red
                    else -> ContextCompat.getColor(ctx, R.color.call_arrow_incoming)
                }
                typeIcon.imageTintList = ColorStateList.valueOf(arrowColor)
                time.text = DateUtils.formatDateTime(ctx, d.date, DateUtils.FORMAT_SHOW_TIME)
                val s = d.duration
                duration.text = when {
                    missed -> ""
                    s < 60 -> "${s}s"
                    else -> "${s / 60} min ${s % 60} s"
                }
            }
        }
    }

    companion object {
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"
    }
}
