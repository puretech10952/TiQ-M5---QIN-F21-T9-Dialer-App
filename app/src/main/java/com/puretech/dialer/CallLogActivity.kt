package com.puretech.dialer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.text.format.DateUtils
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.puretech.dialer.databinding.ActivityCallLogBinding
import java.util.Calendar

/** Recents screen (Google-Dialer card style) with search, favorites, drawer. */
class CallLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallLogBinding
    private val telecomManager by lazy { getSystemService(TelecomManager::class.java) }
    private lateinit var logAdapter: CallLogAdapter
    private lateinit var favoritesAdapter: FavoritesAdapter
    private var allContacts: List<Contact> = emptyList()
    private var pendingNumber: String? = null

    private val callPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted && pendingNumber != null) placeCall() else pendingNumber = null }

    private val logPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) reload() else showEmpty(getString(R.string.log_perm_needed)) }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logAdapter = CallLogAdapter(
            onCall = { callNumber(it.number) },
            onMessage = { messageNumber(it) },
            onHistory = { openHistory(it) },
            onAddContact = { addContact(it) },
            onCopy = { copyNumber(it) },
            onOpenContact = { openContact(it) },
            onLongPress = { showEntryMenu(it) }
        )
        binding.recents.layoutManager = LinearLayoutManager(this)
        binding.recents.adapter = logAdapter

        favoritesAdapter = FavoritesAdapter { callNumber(it.number) }
        binding.favoritesStrip.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.favoritesStrip.adapter = favoritesAdapter

        binding.filterChips.setOnCheckedStateChangeListener { _, _ -> reload() }
        binding.btnMenu.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.drawerVersion.text = getString(R.string.drawer_version, appVersionName())
        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.nav_durations -> startActivity(Intent(this, CallStatsActivity::class.java))
                R.id.nav_updates -> startActivity(Intent(this, UpdateActivity::class.java))
                R.id.nav_about -> startActivity(Intent(this, AboutActivity::class.java))
            }
            binding.drawerLayout.closeDrawers()
            true
        }
        binding.searchInput.doAfterTextChanged { onSearchChanged(it?.toString().orEmpty()) }
        setupBackBehavior()

        binding.favoritesToggle.setOnClickListener { toggleFavorites() }
        binding.viewContacts.setOnClickListener { openContactsApp() }
        binding.navKeypad.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        binding.navRecents.setOnClickListener {
            if (!binding.searchInput.text.isNullOrBlank()) binding.searchInput.text?.clear()
            binding.appBar.setExpanded(true, true)
            binding.recents.smoothScrollToPosition(0)
        }

        loadContacts()
        ensureLogPermission()
    }

    override fun onResume() {
        super.onResume()
        // Block the whole app until the user accepts the Terms/Privacy and sets
        // us as the default phone app — no recents, settings, or keypad before.
        if (Gates.enforce(this)) return
        // Never resume into the search field with the keyboard up.
        clearSearchFocus()
        // Once phone permission is granted, the MWI watcher can register.
        VoicemailMonitor.start(this)
        // Viewing recents clears missed calls: dismiss our notifications and reset
        // the framework's missed-call count so it stays in sync with us.
        clearMissedCalls()
        if (binding.searchInput.text.isNullOrBlank() && hasLogPermission()) reload()
    }

    /** Reset both our missed-call notifications and Telecom's missed count. */
    private fun clearMissedCalls() {
        MissedCallNotifier.cancelAll(this)
        try {
            getSystemService(TelecomManager::class.java)?.cancelMissedCallsNotification()
        } catch (_: Exception) {
            // Needs to be the default dialer; ignore if we're not.
        }
    }

    /** Back: 1) hide keyboard, 2) leave the search field, 3) exit the screen. */
    private fun setupBackBehavior() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isKeyboardVisible() -> hideKeyboard()
                    binding.searchInput.hasFocus() || !binding.searchInput.text.isNullOrBlank() ->
                        clearSearchFocus()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun isKeyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(binding.root)
            ?.isVisible(WindowInsetsCompat.Type.ime()) ?: false

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun clearSearchFocus() {
        if (!binding.searchInput.text.isNullOrBlank()) binding.searchInput.text?.clear()
        binding.searchInput.clearFocus()
        binding.root.requestFocus()
        hideKeyboard()
    }

    // --- Search & favorites ----------------------------------------------------

    private fun loadContacts() {
        Thread {
            val list = ContactsRepository.load(applicationContext)
            val favs = ContactsRepository.loadFavorites(applicationContext)
            runOnUiThread {
                allContacts = list
                favoritesAdapter.submit(favs)
                binding.favoritesToggle.visibility = if (favs.isEmpty()) View.GONE else View.VISIBLE
                // Restore the saved expand/collapse state (persists across launches).
                binding.favoritesStrip.visibility =
                    if (favs.isNotEmpty() && Prefs.favoritesExpanded(this)) View.VISIBLE
                    else View.GONE
                updateFavoritesArrow()
            }
        }.start()
    }

    private fun toggleFavorites() {
        val show = binding.favoritesStrip.visibility != View.VISIBLE
        binding.favoritesStrip.visibility = if (show) View.VISIBLE else View.GONE
        Prefs.setFavoritesExpanded(this, show)
        updateFavoritesArrow()
    }

    /** Point the caret up when expanded, down when collapsed. */
    private fun updateFavoritesArrow() {
        val expanded = binding.favoritesStrip.visibility == View.VISIBLE
        val caret = androidx.core.content.ContextCompat.getDrawable(
            this, if (expanded) R.drawable.ic_arrow_drop_up else R.drawable.ic_arrow_drop_down
        )
        binding.favoritesToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null, null, caret, null
        )
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            binding.favoritesToggle,
            android.content.res.ColorStateList.valueOf(
                themeColor(com.google.android.material.R.attr.colorOnSurface)
            )
        )
    }

    private fun onSearchChanged(query: String) {
        if (query.isBlank()) {
            binding.collapseHeader.visibility = View.VISIBLE
            binding.appBar.setExpanded(true, false)
            reload()
        } else {
            binding.collapseHeader.visibility = View.GONE
            binding.favoritesStrip.visibility = View.GONE
            val rows = ContactsRepository.searchByText(query, allContacts).map { c ->
                CallLogRow.Item(
                    CallLogEntry(c.number, c.name, c.photoUri, 0, 0L, 1, false, asContact = true)
                )
            }
            logAdapter.submit(rows)
            binding.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            if (rows.isEmpty()) binding.emptyText.text = getString(R.string.no_recents)
        }
    }

    // --- Recents ---------------------------------------------------------------

    private fun ensureLogPermission() {
        if (hasLogPermission()) reload()
        else logPermLauncher.launch(Manifest.permission.READ_CALL_LOG)
    }

    private fun hasLogPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    private fun reload() {
        if (!hasLogPermission() || !binding.searchInput.text.isNullOrBlank()) return
        val missedOnly = binding.chipMissed.isChecked
        val receivedOnly = binding.chipReceived.isChecked
        val outgoingOnly = binding.chipOutgoing.isChecked
        val contactsOnly = binding.chipContacts.isChecked
        Thread {
            try {
                val all = CallLogRepository.load(applicationContext, missedOnly)
                val entries = when {
                    contactsOnly -> all.filter { it.name != null }
                    receivedOnly -> all.filter {
                        it.type == android.provider.CallLog.Calls.INCOMING_TYPE
                    }
                    outgoingOnly -> all.filter {
                        it.type == android.provider.CallLog.Calls.OUTGOING_TYPE
                    }
                    else -> all
                }
                val rows = buildRows(entries)
                runOnUiThread {
                    logAdapter.submit(rows)
                    binding.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    if (rows.isEmpty()) binding.emptyText.text = getString(R.string.no_recents)
                }
            } catch (e: Throwable) {
                android.util.Log.e("M5CallLog", "load failed", e)
            }
        }.start()
    }

    private fun showEmpty(text: String) {
        binding.emptyText.text = text
        binding.emptyText.visibility = View.VISIBLE
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

    /** Today / Yesterday / weekday name within the last week / else the date. */
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

    // --- Row actions -----------------------------------------------------------

    private fun openHistory(entry: CallLogEntry) {
        startActivity(
            Intent(this, CallHistoryActivity::class.java)
                .putExtra(CallHistoryActivity.EXTRA_NUMBER, entry.number)
                .putExtra(CallHistoryActivity.EXTRA_NAME, entry.name ?: entry.number)
        )
    }

    /** Open the contact card for a saved number (avatar tap). */
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
        } catch (e: Exception) {
            null
        }
        if (contactUri != null) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, contactUri))
            } catch (_: Exception) {
            }
        } else {
            addContact(entry.number)
        }
    }

    // --- Block / delete (long-press) ------------------------------------------

    private fun showEntryMenu(entry: CallLogEntry) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_entry_actions, null)
        view.findViewById<android.widget.TextView>(R.id.sheetTitle).text =
            entry.name ?: android.telephony.PhoneNumberUtils
                .formatNumber(entry.number, java.util.Locale.US.country) ?: entry.number
        view.findViewById<View>(R.id.sheetBlock).setOnClickListener {
            sheet.dismiss(); blockNumber(entry.number)
        }
        view.findViewById<View>(R.id.sheetDelete).setOnClickListener {
            sheet.dismiss(); deleteEntry(entry.number)
        }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun blockNumber(number: String) {
        Thread {
            BlockedNumbers.add(applicationContext, number)
            runOnUiThread {
                Toast.makeText(this, R.string.number_blocked, Toast.LENGTH_SHORT).show()
                reload()
            }
        }.start()
    }

    private fun deleteEntry(number: String) {
        Thread {
            try {
                contentResolver.delete(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    "${android.provider.CallLog.Calls.NUMBER} = ?", arrayOf(number)
                )
            } catch (e: Exception) {
                android.util.Log.w("M5CallLog", "delete failed: ${e.message}")
            }
            runOnUiThread {
                Toast.makeText(this, R.string.entry_deleted, Toast.LENGTH_SHORT).show()
                reload()
            }
        }.start()
    }

    private fun addContact(number: String) {
        try {
            startActivity(
                Intent(ContactsContract.Intents.Insert.ACTION).apply {
                    type = ContactsContract.RawContacts.CONTENT_TYPE
                    putExtra(ContactsContract.Intents.Insert.PHONE, number)
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun messageNumber(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")))
        } catch (_: Exception) {
        }
    }

    private fun copyNumber(number: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("number", number))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // --- Calling ---------------------------------------------------------------

    private fun callNumber(raw: String) {
        if (raw.isBlank()) return
        pendingNumber = normalizeForDial(raw)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) placeCall() else callPermLauncher.launch(Manifest.permission.CALL_PHONE)
    }

    private fun placeCall() {
        val n = pendingNumber ?: return
        pendingNumber = null
        Dialer.place(this, n)
    }

    /** Open the phone's default Contacts app. */
    private fun openContactsApp() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Fallback to viewing the contacts list directly.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))
            } catch (_: Exception) {
                startActivity(Intent(this, ContactsActivity::class.java))
            }
        }
    }

    private fun normalizeForDial(raw: String): String = Dialer.normalize(this, raw)

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }
}
