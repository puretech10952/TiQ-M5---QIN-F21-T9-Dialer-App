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

    // Voice result, applied in onTabResumed (host onResume clears the field first).
    private var pendingVoiceQuery: String? = null

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                pendingVoiceQuery = spoken
                if (_binding != null) binding.searchInput.post { applyPendingVoiceQuery() }
            }
        }
    }

    private fun applyPendingVoiceQuery() {
        val q = pendingVoiceQuery ?: return
        pendingVoiceQuery = null
        if (_binding == null) return
        binding.searchInput.setText(q)
        binding.searchInput.setSelection(q.length)
    }

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
            onLongPress = { entry, anchor -> showEntryMenu(entry, anchor) }
        )
        binding.recents.layoutManager = LinearLayoutManager(requireContext())
        binding.recents.adapter = logAdapter

        favoritesAdapter = FavoritesAdapter { contact, anchor -> onFavoriteClick(contact, anchor) }
        binding.favoritesStrip.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.favoritesStrip.adapter = favoritesAdapter

        binding.filterChips.setOnCheckedStateChangeListener { _, _ -> reload() }
        binding.btnMenu.setOnClickListener { (requireActivity() as HomeActivity).openDrawer() }
        binding.searchInput.doAfterTextChanged { onSearchChanged(it?.toString().orEmpty()) }
        binding.favoritesToggle.setOnClickListener { toggleFavorites() }
        binding.viewContacts.setOnClickListener { openContactsApp() }
        binding.btnVoiceSearch.setOnClickListener { startVoiceSearch() }

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
        // Apply a voice-search result captured while we were away (after the reset).
        applyPendingVoiceQuery()
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
        // Clear the NEW flag ourselves: on the M5/F21, cancelMissedCallsNotification()
        // alone leaves rows NEW=1 and Telecom re-posts them all after a reboot.
        MissedCallNotifier.markAllMissedRead(requireContext())
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
        // Play the fade + scale-up "pop in" when the strip is opened.
        if (show) binding.favoritesStrip.scheduleLayoutAnimation()
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

    fun reload() {
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
                val rows = buildRows(ctx, entries)
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

    private fun buildRows(ctx: Context, entries: List<CallLogEntry>): List<CallLogRow> {
        val rows = ArrayList<CallLogRow>()
        var lastLabel: String? = null
        for (e in entries) {
            val label = dayLabel(ctx, e.date)
            if (label != lastLabel) {
                rows.add(CallLogRow.Header(label)); lastLabel = label
            }
            rows.add(CallLogRow.Item(e))
        }
        return rows
    }

    private fun dayLabel(ctx: Context, date: Long): String {
        val diff = ((midnight(System.currentTimeMillis()) - midnight(date)) /
            DateUtils.DAY_IN_MILLIS).toInt()
        return when {
            diff <= 0 -> ctx.getString(R.string.recents_today)
            diff == 1 -> ctx.getString(R.string.recents_yesterday)
            diff in 2..6 -> DateUtils.formatDateTime(ctx, date, DateUtils.FORMAT_SHOW_WEEKDAY)
            else -> DateUtils.formatDateTime(
                ctx, date, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
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
    private fun showEntryMenu(entry: CallLogEntry, anchor: View) {
        val title = entry.name ?: android.telephony.PhoneNumberUtils
            .formatNumber(entry.number, java.util.Locale.US.country) ?: entry.number
        CardMenu(requireContext(), anchor)
            .title(title)
            .add(MENU_BLOCK, R.drawable.ic_block, getString(R.string.block_number))
            .add(MENU_DELETE, R.drawable.ic_delete, getString(R.string.delete_entry))
            .onClick { id ->
                when (id) {
                    MENU_BLOCK -> blockNumber(entry.number)
                    MENU_DELETE -> deleteEntry(entry.number)
                }
            }
            .show()
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
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setMessage(R.string.entry_delete_confirm)
            .setPositiveButton(R.string.log_delete) { _, _ -> doDeleteEntry(number) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doDeleteEntry(number: String) {
        val ctx = requireContext().applicationContext
        Thread {
            CallLogRepository.delete(ctx, number)
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

    /** Tapping a favorite either dials straight away (single-number contact, or a
     *  remembered default), or shows an anchored popup to pick which number when
     *  there's more than one — with an option to remember the choice. */
    private fun onFavoriteClick(contact: Contact, anchor: View) {
        val key = contact.lookupKey
        if (key == null) { callNumber(contact.number); return }
        val remembered = Prefs.defaultNumberForContact(requireContext(), key)
        if (remembered != null) { callNumber(remembered); return }
        Thread {
            val numbers = ContactsRepository.numbersFor(requireContext().applicationContext, key)
            ui {
                if (numbers.size <= 1) {
                    callNumber(numbers.firstOrNull()?.number ?: contact.number)
                } else {
                    NumberPickerPopup(requireContext(), anchor)
                        .title(contact.name)
                        .numbers(numbers)
                        .onPick { number, remember ->
                            if (remember) Prefs.setDefaultNumberForContact(requireContext(), key, number)
                            callNumber(number)
                        }
                        .show()
                }
            }
        }.start()
    }

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

    /** Launch the system (Google) speech recognizer; the result fills the search box. */
    private fun startVoiceSearch() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_search))
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.voice_search_unavailable, Toast.LENGTH_SHORT).show()
        }
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

    private companion object {
        const val MENU_BLOCK = 1
        const val MENU_DELETE = 2
    }
}
