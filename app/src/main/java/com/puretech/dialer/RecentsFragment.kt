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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.puretech.dialer.databinding.FragmentRecentsBinding
import java.util.Calendar

/** Recents screen (Google-Dialer card style) with search + favorites. Hosted by
 *  [HomeActivity]; the drawer, bottom bar, and gating live in the host. */
class RecentsFragment : Fragment() {

    private var _binding: FragmentRecentsBinding? = null
    private val binding get() = _binding!!

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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        logAdapter = CallLogAdapter(
            onCall = { callNumber(it.number) },
            onMessage = { messageNumber(it) },
            onHistory = { openHistory(it) },
            onAddContact = { addContact(it) },
            onCopy = { copyNumber(it) },
            onOpenContact = { openContact(it) },
            onLongPress = { showEntryMenu(it) }
        )
        binding.recents.layoutManager = LinearLayoutManager(requireContext())
        binding.recents.adapter = logAdapter

        favoritesAdapter = FavoritesAdapter { callNumber(it.number) }
        binding.favoritesStrip.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.favoritesStrip.adapter = favoritesAdapter

        binding.filterChips.setOnCheckedStateChangeListener { _, _ -> reload() }
        binding.btnMenu.setOnClickListener { (requireActivity() as HomeActivity).openDrawer() }
        binding.searchInput.doAfterTextChanged { onSearchChanged(it?.toString().orEmpty()) }
        binding.favoritesToggle.setOnClickListener { toggleFavorites() }
        binding.viewContacts.setOnClickListener { openContactsApp() }

        loadContacts()
        ensureLogPermission()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // --- Host-facing API -------------------------------------------------------

    fun scrollTarget(): RecyclerView? = _binding?.recents

    fun isSearchFocused(): Boolean = _binding?.searchInput?.hasFocus() == true

    /** Re-run the per-visit work whenever this tab becomes the visible one. */
    fun onTabResumed() {
        if (_binding == null) return
        clearSearchFocus()
        VoicemailMonitor.start(requireContext())
        clearMissedCalls()
        if (binding.searchInput.text.isNullOrBlank() && hasLogPermission()) reload()
        if (binding.searchInput.text.isNullOrBlank()) loadContacts()
    }

    /** Home re-tap while already on Recents. */
    fun scrollToTopAndClearSearch() {
        if (_binding == null) return
        if (!binding.searchInput.text.isNullOrBlank()) binding.searchInput.text?.clear()
        binding.appBar.setExpanded(true, true)
        binding.recents.smoothScrollToPosition(0)
    }

    /** Preselect a filter chip (from the call-stats screen). */
    fun applyFilter(filter: String?) {
        filter ?: return
        if (_binding == null) return
        if (!binding.searchInput.text.isNullOrBlank()) binding.searchInput.text?.clear()
        binding.appBar.setExpanded(true, false)
        when (filter) {
            HomeActivity.FILTER_INCOMING -> binding.chipReceived.isChecked = true
            HomeActivity.FILTER_OUTGOING -> binding.chipOutgoing.isChecked = true
            HomeActivity.FILTER_MISSED -> binding.chipMissed.isChecked = true
            else -> binding.chipAll.isChecked = true
        }
    }

    /** Back handling for the Recents tab: hide keyboard, then leave the search field. */
    fun handleBack(): Boolean = when {
        isKeyboardVisible() -> { hideKeyboard(); true }
        binding.searchInput.hasFocus() || !binding.searchInput.text.isNullOrBlank() ->
            { clearSearchFocus(); true }
        else -> false
    }

    private fun clearMissedCalls() {
        MissedCallNotifier.cancelAll(requireContext())
        try {
            requireContext().getSystemService(TelecomManager::class.java)
                ?.cancelMissedCallsNotification()
        } catch (_: Exception) {
        }
    }

    private fun isKeyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(binding.root)
            ?.isVisible(WindowInsetsCompat.Type.ime()) ?: false

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
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
            val list = ContactsRepository.load(requireContext().applicationContext)
            val favs = ContactsRepository.loadFavorites(requireContext().applicationContext)
            ui {
                allContacts = list
                favoritesAdapter.submit(favs)
                binding.favoritesToggle.visibility = if (favs.isEmpty()) View.GONE else View.VISIBLE
                binding.favoritesStrip.visibility =
                    if (favs.isNotEmpty() && Prefs.favoritesExpanded(requireContext())) View.VISIBLE
                    else View.GONE
                updateFavoritesArrow()
            }
        }.start()
    }

    private fun toggleFavorites() {
        val show = binding.favoritesStrip.visibility != View.VISIBLE
        binding.favoritesStrip.visibility = if (show) View.VISIBLE else View.GONE
        Prefs.setFavoritesExpanded(requireContext(), show)
        updateFavoritesArrow()
    }

    private fun updateFavoritesArrow() {
        val expanded = binding.favoritesStrip.visibility == View.VISIBLE
        val caret = ContextCompat.getDrawable(
            requireContext(),
            if (expanded) R.drawable.ic_arrow_drop_up else R.drawable.ic_arrow_drop_down
        )
        binding.favoritesToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, caret, null)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            binding.favoritesToggle,
            android.content.res.ColorStateList.valueOf(
                requireContext().themeColor(com.google.android.material.R.attr.colorOnSurface)
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
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    private fun reload() {
        if (_binding == null) return
        if (!hasLogPermission() || !binding.searchInput.text.isNullOrBlank()) return
        val missedOnly = binding.chipMissed.isChecked
        val receivedOnly = binding.chipReceived.isChecked
        val outgoingOnly = binding.chipOutgoing.isChecked
        val contactsOnly = binding.chipContacts.isChecked
        val ctx = requireContext().applicationContext
        Thread {
            try {
                val all = CallLogRepository.load(ctx, missedOnly)
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
                ui {
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

    private fun dayLabel(date: Long): String {
        val diff = ((midnight(System.currentTimeMillis()) - midnight(date)) /
            DateUtils.DAY_IN_MILLIS).toInt()
        return when {
            diff <= 0 -> getString(R.string.recents_today)
            diff == 1 -> getString(R.string.recents_yesterday)
            diff in 2..6 -> DateUtils.formatDateTime(requireContext(), date, DateUtils.FORMAT_SHOW_WEEKDAY)
            else -> DateUtils.formatDateTime(
                requireContext(), date, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
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
            Intent(requireContext(), CallHistoryActivity::class.java)
                .putExtra(CallHistoryActivity.EXTRA_NUMBER, entry.number)
                .putExtra(CallHistoryActivity.EXTRA_NAME, entry.name ?: entry.number)
        )
    }

    private fun openContact(entry: CallLogEntry) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(entry.number)
        )
        val contactUri: Uri? = try {
            requireContext().contentResolver.query(
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

    /** Long-press popup: block number / delete entry. */
    private fun showEntryMenu(entry: CallLogEntry) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
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
        val ctx = requireContext().applicationContext
        Thread {
            BlockedNumbers.add(ctx, number)
            ui {
                Toast.makeText(requireContext(), R.string.number_blocked, Toast.LENGTH_SHORT).show()
                reload()
            }
        }.start()
    }

    private fun deleteEntry(number: String) {
        val ctx = requireContext().applicationContext
        Thread {
            try {
                ctx.contentResolver.delete(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    "${android.provider.CallLog.Calls.NUMBER} = ?", arrayOf(number)
                )
            } catch (e: Exception) {
                android.util.Log.w("M5CallLog", "delete failed: ${e.message}")
            }
            ui {
                Toast.makeText(requireContext(), R.string.entry_deleted, Toast.LENGTH_SHORT).show()
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
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("number", number))
        Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // --- Calling ---------------------------------------------------------------

    private fun callNumber(raw: String) {
        if (raw.isBlank()) return
        pendingNumber = Dialer.normalize(requireContext(), raw)
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) placeCall() else callPermLauncher.launch(Manifest.permission.CALL_PHONE)
    }

    private fun placeCall() {
        val n = pendingNumber ?: return
        pendingNumber = null
        Dialer.place(requireContext(), n)
    }

    private fun openContactsApp() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))
            } catch (_: Exception) {
                startActivity(Intent(requireContext(), ContactsActivity::class.java))
            }
        }
    }

    /** Run [block] on the UI thread only if the view is still alive. */
    private fun ui(block: () -> Unit) {
        _binding?.root?.post { if (_binding != null) block() }
    }
}
