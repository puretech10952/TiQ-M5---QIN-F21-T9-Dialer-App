package com.puretech.dialer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.puretech.dialer.databinding.ActivityMissedCallsBinding
import java.util.Calendar

/** Dedicated full-screen page for missed/rejected calls — launched from the missed-call notification. */
class MissedCallsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMissedCallsBinding
    private lateinit var adapter: CallLogAdapter
    private var pendingNumber: String? = null

    private val callPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted && pendingNumber != null) placeCall() else pendingNumber = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMissedCallsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }

        adapter = CallLogAdapter(
            onCall        = { e -> callNumber(e.number) },
            onMessage     = { n -> messageNumber(n) },
            onHistory     = { e -> openHistory(e) },
            onAddContact  = { n -> addContact(n) },
            onCopy        = { n -> copyNumber(n) },
            onOpenContact = { e -> openContact(e) },
            onLongPress   = {}
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        loadMissed()
    }

    private fun loadMissed() {
        val ctx = applicationContext
        Thread {
            val entries = CallLogRepository.load(ctx, missedOnly = true)
            val rows = buildRows(entries)
            runOnUiThread {
                if (rows.isEmpty()) {
                    binding.emptyText.text = getString(R.string.no_recents)
                    binding.emptyText.visibility = View.VISIBLE
                } else {
                    binding.emptyText.visibility = View.GONE
                    adapter.submit(rows)
                }
            }
        }.start()
    }

    private fun buildRows(entries: List<CallLogEntry>): List<CallLogRow> {
        val rows = ArrayList<CallLogRow>()
        var lastLabel: String? = null
        for (e in entries) {
            val label = dayLabel(e.date)
            if (label != lastLabel) { rows.add(CallLogRow.Header(label)); lastLabel = label }
            rows.add(CallLogRow.Item(e))
        }
        return rows
    }

    private fun dayLabel(date: Long): String {
        val diff = ((midnight(System.currentTimeMillis()) - midnight(date)) / DAY_MS).toInt()
        return when {
            diff <= 0  -> getString(R.string.recents_today)
            diff == 1  -> getString(R.string.recents_yesterday)
            diff in 2..6 -> android.text.format.DateUtils.formatDateTime(
                this, date, android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY
            )
            else -> android.text.format.DateUtils.formatDateTime(
                this, date,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or
                    android.text.format.DateUtils.FORMAT_ABBREV_MONTH
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

    private fun callNumber(raw: String) {
        if (raw.isBlank()) return
        pendingNumber = Dialer.normalize(this, raw)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) placeCall() else callPermLauncher.launch(Manifest.permission.CALL_PHONE)
    }

    private fun placeCall() {
        val n = pendingNumber ?: return
        pendingNumber = null
        Dialer.place(this, n)
    }

    private fun messageNumber(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")))
        } catch (_: Exception) {}
    }

    private fun openHistory(entry: CallLogEntry) {
        startActivity(
            Intent(this, CallHistoryActivity::class.java)
                .putExtra(CallHistoryActivity.EXTRA_NUMBER, entry.number)
                .putExtra(CallHistoryActivity.EXTRA_NAME, entry.name ?: entry.number)
        )
    }

    private fun openContact(entry: CallLogEntry) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(entry.number)
        )
        val contactUri: Uri? = try {
            contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst())
                    ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
                else null
            }
        } catch (_: Exception) { null }
        if (contactUri != null) {
            try { startActivity(Intent(Intent.ACTION_VIEW, contactUri)) } catch (_: Exception) {}
        } else {
            addContact(entry.number)
        }
    }

    private fun addContact(number: String) {
        try {
            startActivity(
                Intent(ContactsContract.Intents.Insert.ACTION).apply {
                    type = ContactsContract.RawContacts.CONTENT_TYPE
                    putExtra(ContactsContract.Intents.Insert.PHONE, number)
                }
            )
        } catch (_: Exception) {}
    }

    private fun copyNumber(number: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("number", number))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DAY_MS = 86_400_000L
    }
}
