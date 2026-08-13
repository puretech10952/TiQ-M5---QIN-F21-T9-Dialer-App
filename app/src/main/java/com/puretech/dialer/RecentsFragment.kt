package com.puretech.dialer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

    private val pickFavoriteContact = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        val lookupKey = resolvePickedFavoriteLookupKey(uri) ?: return@registerForActivityResult
        val ctx = requireContext().applicationContext
        Thread {
            // A contact that's actually starred in the real Contacts app always
            // belongs in the "starred" (front) section — if it was hidden from
            // this strip before, re-adding it just un-hides it rather than
            // filing it as a separate in-app extra at the back.
            val isRealStarred = ContactsRepository.loadFavorites(ctx).any { it.lookupKey == lookupKey }
            if (isRealStarred) Prefs.unhideFavorite(ctx, lookupKey)
            else Prefs.addExtraFavorite(ctx, lookupKey)
            ui { loadContacts() }
        }.start()
    }

    // Mirrors the shared search box's current text (that view now lives in
    // HomeActivity, not this fragment's own binding -- see onSearchChanged).
    private var currentQuery: String = ""

    // Live-refreshes the call log/favorites when a contact is renamed, deleted,
    // or a number gets newly linked to a contact -- without this, those changes
    // only showed up the next time this tab was revisited, since CallLogRepository
    // otherwise only re-resolves names on an explicit reload(). Contact sync can
    // fire several onChange notifications in a burst for one real edit (e.g. a
    // rename touches the raw contact row and its data rows separately), so this
    // debounces with a short delay rather than reloading on every single one.
    private val contactsChangeHandler = Handler(Looper.getMainLooper())
    private val contactsChangeRunnable = Runnable {
        if (_binding == null) return@Runnable
        loadContacts()
        if (hasLogPermission()) reload()
    }
    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            contactsChangeHandler.removeCallbacks(contactsChangeRunnable)
            contactsChangeHandler.postDelayed(contactsChangeRunnable, 600)
        }
    }
    private var contactsObserverRegistered = false

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

        favoritesAdapter = FavoritesAdapter(
            onClick = { contact, anchor -> onFavoriteClick(contact, anchor) },
            onLongPress = { contact, anchor -> onFavoriteLongPress(contact, anchor) },
            onAddClick = { openAddFavorite() }
        )
        binding.favoritesStrip.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.favoritesStrip.adapter = favoritesAdapter

        binding.filterChips.setOnCheckedStateChangeListener { _, _ -> reload() }
        binding.favoritesToggle.setOnClickListener { toggleFavorites() }
        binding.viewContacts.setOnClickListener { openContactsApp() }

        loadContacts()
        ensureLogPermission()
        ensureContactsObserver()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (contactsObserverRegistered) {
            requireContext().contentResolver.unregisterContentObserver(contactsObserver)
            contactsObserverRegistered = false
        }
        contactsChangeHandler.removeCallbacks(contactsChangeRunnable)
        _binding = null
    }

    /** Registering on the Contacts provider requires READ_CONTACTS to already
     *  be granted, not just requested -- calling this unconditionally crashed
     *  the whole app on launch with a SecurityException whenever this app
     *  isn't the default dialer yet (that permission isn't granted until
     *  then on this ROM). Called again from onTabResumed so the live-refresh
     *  starts working the moment permission/default-dialer status is granted,
     *  without needing an app restart. */
    private fun ensureContactsObserver() {
        if (contactsObserverRegistered) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        requireContext().contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI, true, contactsObserver
        )
        contactsObserverRegistered = true
    }

    // --- Host-facing API -------------------------------------------------------

    fun scrollTarget(): RecyclerView? = _binding?.recents

    /** Re-run the per-visit work whenever this tab becomes the visible one. */
    fun onTabResumed() {
        if (_binding == null) return
        (activity as? HomeActivity)?.let { it.clearSearchFocus(); it.applyPendingVoiceQuery() }
        VoicemailMonitor.start(requireContext())
        clearMissedCalls()
        ensureContactsObserver()
        if (currentQuery.isBlank() && hasLogPermission()) reload()
        if (currentQuery.isBlank()) loadContacts()
    }

    /** Home re-tap while already on Recents. */
    fun scrollToTopAndClearSearch() {
        if (_binding == null) return
        binding.appBar.setExpanded(true, true)
        binding.recents.smoothScrollToPosition(0)
    }

    /** Preselect a filter chip (from the call-stats screen). */
    fun applyFilter(filter: String?) {
        filter ?: return
        if (_binding == null) return
        (activity as? HomeActivity)?.clearSearchFocus()
        binding.appBar.setExpanded(true, false)
        when (filter) {
            HomeActivity.FILTER_INCOMING -> binding.chipReceived.isChecked = true
            HomeActivity.FILTER_OUTGOING -> binding.chipOutgoing.isChecked = true
            HomeActivity.FILTER_MISSED -> binding.chipMissed.isChecked = true
            else -> binding.chipAll.isChecked = true
        }
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

    // --- Search & favorites ----------------------------------------------------

    private fun loadContacts() {
        Thread {
            val list = ContactsRepository.load(requireContext().applicationContext)
            val favs = ContactsRepository.loadDialerFavorites(requireContext().applicationContext)
            ui {
                allContacts = list
                favoritesAdapter.submit(favs)
                // Always shown now — the strip's trailing "Add" cell is always the
                // entry point for adding a favorite, even with zero favorites yet.
                binding.favoritesToggle.visibility = View.VISIBLE
                binding.favoritesStrip.visibility =
                    if (Prefs.favoritesExpanded(requireContext())) View.VISIBLE else View.GONE
                updateFavoritesArrow()
            }
        }.start()
    }

    /** Opens the system contact picker to add a contact as a dialer-only favorite
     *  (does not touch the real Contacts app's starred flag). */
    private fun openAddFavorite() {
        try {
            pickFavoriteContact.launch(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            )
        } catch (_: Exception) {
        }
    }

    private fun resolvePickedFavoriteLookupKey(uri: Uri): String? =
        try {
            requireContext().contentResolver.query(
                uri, arrayOf(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }

    private fun toggleFavorites() {
        val strip = binding.favoritesStrip
        val show = strip.visibility != View.VISIBLE
        strip.animate().cancel()
        if (show) {
            strip.alpha = 1f; strip.scaleX = 1f; strip.scaleY = 1f
            strip.visibility = View.VISIBLE
            // Play the fade + scale-up "pop in" when the strip is opened.
            strip.scheduleLayoutAnimation()
        } else {
            // Mirror the open animation (fade + scale) in reverse before hiding.
            strip.pivotX = 0f
            strip.pivotY = strip.height / 2f
            strip.animate()
                .alpha(0f).scaleX(0.6f).scaleY(0.6f)
                .setDuration(200)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    strip.visibility = View.GONE
                    strip.alpha = 1f; strip.scaleX = 1f; strip.scaleY = 1f
                }
                .start()
        }
        Prefs.setFavoritesExpanded(requireContext(), show)
        // Pass the intended end state explicitly rather than letting
        // updateFavoritesArrow() re-read the strip's live visibility: the
        // collapse path above is a 200ms animation that only flips
        // visibility to GONE inside withEndAction, so reading it here
        // (synchronously, mid-animation) would still see VISIBLE and freeze
        // the arrow on the wrong (expanded) icon even after the strip has
        // actually finished closing.
        updateFavoritesArrow(show)
    }

    private fun updateFavoritesArrow(expanded: Boolean = binding.favoritesStrip.visibility == View.VISIBLE) {
        val caret = ContextCompat.getDrawable(
            requireContext(),
            if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
        )
        binding.favoritesToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, caret, null)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            binding.favoritesToggle,
            android.content.res.ColorStateList.valueOf(
                requireContext().themeColor(com.google.android.material.R.attr.colorOnSurface)
            )
        )
    }

    /** Called by HomeActivity's shared search box (now hosted there, not in
     *  this fragment's own binding) on every text change. */
    fun onSearchChanged(query: String) {
        currentQuery = query
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
        if (!hasLogPermission() || currentQuery.isNotBlank()) return
        val missedOnly = binding.chipMissed.isChecked
        val receivedOnly = binding.chipReceived.isChecked
        val outgoingOnly = binding.chipOutgoing.isChecked
        val contactsOnly = binding.chipContacts.isChecked
        val ctx = requireContext().applicationContext

        // Paint instantly from [CallLogCache] if a prefetch already finished (fired
        // right when the last call ended) so a just-finished call shows up the moment
        // this tab opens, instead of waiting on this device's slow call-log query
        // below. On a cold start (force-stop, or the process was killed while idle)
        // there's nothing in memory yet, so seed it from the on-disk snapshot first —
        // that's a few-KB local read, nothing like the multi-second provider query.
        // The full load below still runs right after to catch anything the cache
        // missed (a fresh delete, an edit, or a prefetch/snapshot that's stale).
        CallLogCache.ensureLoaded(ctx)
        CallLogCache.entries?.let { cached ->
            val filtered = when {
                missedOnly -> cached.filter {
                    it.type == android.provider.CallLog.Calls.MISSED_TYPE ||
                        it.type == android.provider.CallLog.Calls.REJECTED_TYPE
                }
                contactsOnly -> cached.filter { it.name != null }
                receivedOnly -> cached.filter { it.type == android.provider.CallLog.Calls.INCOMING_TYPE }
                outgoingOnly -> cached.filter { it.type == android.provider.CallLog.Calls.OUTGOING_TYPE }
                else -> cached
            }
            val rows = buildRows(ctx, filtered)
            logAdapter.submit(rows)
            binding.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            if (rows.isEmpty()) binding.emptyText.text = getString(R.string.no_recents)
        }

        Thread {
            try {
                val all = CallLogRepository.load(ctx, missedOnly)
                // Only the unfiltered load is the full superset the cache promises —
                // storing a missedOnly-narrowed query here would corrupt it for the
                // other chip filters on the next tab open.
                if (!missedOnly) CallLogCache.store(all, ctx)
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

    /** Long-press popup: add to Quick dial / block number / delete entry. */
    private fun showEntryMenu(entry: CallLogEntry, anchor: View) {
        val title = entry.name ?: android.telephony.PhoneNumberUtils
            .formatNumber(entry.number, java.util.Locale.US.country) ?: entry.number
        CardMenu(requireContext(), anchor)
            .title(title)
            .add(MENU_QUICK_DIAL, R.drawable.ic_bolt, getString(R.string.quick_dial_add_to))
            .add(MENU_BLOCK, R.drawable.ic_block, getString(R.string.block_number))
            .add(MENU_DELETE, R.drawable.ic_delete, getString(R.string.delete_entry))
            .onClick { id ->
                when (id) {
                    MENU_QUICK_DIAL -> addToQuickDial(entry)
                    MENU_BLOCK -> blockNumber(entry.number)
                    MENU_DELETE -> deleteEntry(entry.number)
                }
            }
            .show()
    }

    /** Opens Quick dial in "assign mode" for this entry — tapping any number
     *  there assigns it to this contact and returns, instead of the normal
     *  add/manage flows. */
    private fun addToQuickDial(entry: CallLogEntry) {
        val name = entry.name?.ifBlank { null } ?: entry.number
        startActivity(
            Intent(requireContext(), QuickDialActivity::class.java)
                .putExtra(QuickDialActivity.EXTRA_ASSIGN_NAME, name)
                .putExtra(QuickDialActivity.EXTRA_ASSIGN_NUMBER, entry.number)
                .putExtra(QuickDialActivity.EXTRA_ASSIGN_PHOTO_URI, entry.photoUri?.toString())
        )
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
        showNumberPicker(contact, anchor, key, dialIfSingle = true)
    }

    /** Long-press: choose a different number to call, or remove this contact from
     *  the dialer's Favorites strip (never touches the real Contacts app's star). */
    private fun onFavoriteLongPress(contact: Contact, anchor: View) {
        val key = contact.lookupKey ?: return
        CardMenu(requireContext(), anchor)
            .title(contact.name)
            .add(MENU_FAV_NUMBER, R.drawable.ic_call, getString(R.string.favorite_choose_number))
            .add(MENU_FAV_REMOVE, R.drawable.ic_delete, getString(R.string.favorite_remove))
            .onClick { id ->
                when (id) {
                    MENU_FAV_NUMBER -> showNumberPicker(contact, anchor, key, dialIfSingle = false)
                    MENU_FAV_REMOVE -> removeFavorite(key)
                }
            }
            .show()
    }

    /** Removes a favorite from just this app's strip — an in-app "extra" favorite
     *  is dropped outright, a real starred contact is hidden from the strip only,
     *  leaving its Contacts app star untouched (see [Prefs] favorites overlay). */
    private fun removeFavorite(lookupKey: String) {
        val ctx = requireContext()
        if (Prefs.extraFavoriteKeys(ctx).contains(lookupKey)) {
            Prefs.removeExtraFavorite(ctx, lookupKey)
        } else {
            Prefs.hideFavorite(ctx, lookupKey)
        }
        loadContacts()
    }

    private fun showNumberPicker(contact: Contact, anchor: View, key: String, dialIfSingle: Boolean) {
        Thread {
            val numbers = ContactsRepository.numbersFor(requireContext().applicationContext, key)
            ui {
                if (numbers.size <= 1) {
                    if (dialIfSingle) callNumber(numbers.firstOrNull()?.number ?: contact.number)
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
        const val MENU_QUICK_DIAL = 1
        const val MENU_BLOCK = 2
        const val MENU_DELETE = 3
        const val MENU_FAV_NUMBER = 4
        const val MENU_FAV_REMOVE = 5
    }
}
