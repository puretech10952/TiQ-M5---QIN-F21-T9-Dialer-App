package com.puretech.dialer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.puretech.dialer.databinding.ActivityCallLogSearchBinding
import java.util.Calendar

/** Full-text search across all call log entries (system log + LocalCallStore). */
class CallLogSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallLogSearchBinding
    private lateinit var adapter: CallLogAdapter

    /** All entries loaded once on create; filtering is done in-memory. */
    private var allEntries: List<CallLogEntry> = emptyList()
    private var loaded = false

    private val debounce = Handler(Looper.getMainLooper())
    private var pendingNumber: String? = null

    private val callPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted && pendingNumber != null) placeCall() else pendingNumber = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallLogSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAdapter()
        setupSearch()
        setupBottomBar()
        loadAllEntries()

        binding.back.setOnClickListener { finish() }
    }

    // --- Data ------------------------------------------------------------------

    private fun loadAllEntries() {
        val ctx = applicationContext
        Thread {
            val entries = CallLogRepository.load(ctx)
            runOnUiThread {
                allEntries = entries
                loaded = true
                // Re-run any query the user typed while loading.
                val q = binding.searchInput.text?.toString().orEmpty()
                if (q.isNotBlank()) applyQuery(q)
            }
        }.start()
    }

    // --- Adapter ---------------------------------------------------------------

    private fun setupAdapter() {
        adapter = CallLogAdapter(
            onCall        = { e -> callNumber(e.number) },
            onMessage     = { n -> messageNumber(n) },
            onHistory     = { e -> openHistory(e) },
            onAddContact  = { n -> addContact(n) },
            onCopy        = { n -> copyNumber(n) },
            onOpenContact = { e -> openContact(e) },
            onLongPress   = { _, _ -> /* no block/delete in search results */ }
        )
        binding.results.layoutManager = LinearLayoutManager(this)
        binding.results.adapter = adapter
    }

    // --- Search ----------------------------------------------------------------

    private fun setupSearch() {
        binding.searchInput.doAfterTextChanged { text ->
            val q = text?.toString().orEmpty()
            binding.clearBtn.visibility = if (q.isNotBlank()) View.VISIBLE else View.GONE
            debounce.removeCallbacksAndMessages(null)
            debounce.postDelayed({ applyQuery(q) }, 200)
        }
        binding.clearBtn.setOnClickListener {
            binding.searchInput.text?.clear()
        }
        binding.searchInput.requestFocus()
        binding.searchInput.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun applyQuery(query: String) {
        if (query.isBlank()) {
            adapter.submit(emptyList())
            showEmpty(getString(R.string.search_calls_prompt))
            updateCount(0, visible = false)
            return
        }
        if (!loaded) return // still loading; will re-run in loadAllEntries callback

        val q = query.trim().lowercase()
        val matched = allEntries.filter { e ->
            e.number.contains(q) || e.name?.lowercase()?.contains(q) == true
        }

        if (matched.isEmpty()) {
            adapter.submit(emptyList())
            showEmpty(getString(R.string.search_calls_no_results, query))
            updateCount(0, visible = false)
        } else {
            binding.emptyText.visibility = View.GONE
            adapter.submit(buildRows(matched))
            val n = matched.size
            updateCount(n, visible = true)
        }
    }

    private fun buildRows(entries: List<CallLogEntry>): List<CallLogRow> {
        val rows = ArrayList<CallLogRow>()
        var lastLabel: String? = null
        for (e in entries) {
            val label = dayLabel(e.date)
            if (label != lastLabel) {
                rows.add(CallLogRow.Header(label)); lastLabel = label
            }
            rows.add(CallLogRow.Item(e))
        }
        return rows
    }

    private fun dayLabel(date: Long): String {
        val diff = ((midnight(System.currentTimeMillis()) - midnight(date)) / DAY_MS).toInt()
        return when {
            diff <= 0 -> getString(R.string.recents_today)
            diff == 1 -> getString(R.string.recents_yesterday)
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

    // --- Bottom bar ------------------------------------------------------------

    private fun setupBottomBar() {
        binding.results.addOnScrollListener(BottomBarHider(binding.bottomBar))
    }

    private fun showEmpty(msg: String) {
        binding.emptyText.text = msg
        binding.emptyText.visibility = View.VISIBLE
    }

    private fun updateCount(n: Int, visible: Boolean) {
        binding.bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            binding.resultCount.text = resources.getQuantityString(
                R.plurals.search_result_count, n, n
            )
        }
    }

    // --- Row actions -----------------------------------------------------------

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

    private fun messageNumber(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")))
        } catch (_: Exception) {}
    }

    private fun copyNumber(number: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("number", number))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // --- Calling ---------------------------------------------------------------

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

    companion object {
        private const val DAY_MS = 86_400_000L
    }
}
