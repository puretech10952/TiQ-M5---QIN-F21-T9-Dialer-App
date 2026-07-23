package com.puretech.dialer

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.PhoneNumberFormattingTextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.puretech.dialer.databinding.FragmentDialerBinding
import java.util.Locale

/** The dial screen content (number field + suggestions + dialing). Hosted by
 *  [HomeActivity]; the persistent bottom bar and the on-screen keypad live in the
 *  host and call into this fragment. */
class DialerFragment : Fragment() {

    private var _binding: FragmentDialerBinding? = null
    private val binding get() = _binding!!

    private val roleManager by lazy { requireContext().getSystemService(RoleManager::class.java) }

    private var pendingNumber: String? = null
    private var zeroAsPlus = false
    var callPlaced = false
        private set

    private var allContacts: List<Contact> = emptyList()
    private var frequentContacts: List<Contact> = emptyList()
    private lateinit var suggestionAdapter: SuggestionAdapter
    private var pendingPrefill: String? = null

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateBanner() }

    private val callPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted && pendingNumber != null) placeCall() else pendingNumber = null }

    private val contactsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) loadContacts() }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDialerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.numberInput.apply {
            showSoftInputOnFocus = false
            movementMethod = ArrowKeyMovementMethod.getInstance()
            isCursorVisible = true
            addTextChangedListener(PhoneNumberFormattingTextWatcher(Locale.US.country))
            doAfterTextChanged { onNumberChanged() }
            requestFocus()
        }

        binding.btnBackspace.setOnClickListener { backspace() }
        binding.btnBackspace.setOnLongClickListener { clearAll(); true }
        binding.btnDialMenu.setOnClickListener { showDialMenu(it) }
        binding.btnAddContactDial.setOnClickListener { addDialedToContacts() }
        binding.btnMessageDial.setOnClickListener { messageDialed() }

        suggestionAdapter = SuggestionAdapter(
            onCall = { callContact(it.number) },
            onOptions = { c, v -> showOptions(c, v) },
            onMessage = { messageNumber(it.number) }
        )
        binding.suggestions.layoutManager = LinearLayoutManager(requireContext())
        binding.suggestions.adapter = suggestionAdapter

        binding.setDefaultButton.setOnClickListener {
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            }
        }

        ensureContacts()
        maybeRequestNotifications()
        pendingPrefill?.let { setNumber(it); pendingPrefill = null }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // --- Host-facing API -------------------------------------------------------

    fun scrollTarget(): RecyclerView? = _binding?.suggestions

    fun hasText(): Boolean = _binding?.numberInput?.text?.isNotEmpty() == true

    fun hasPendingCall(): Boolean = pendingNumber != null || callPlaced

    fun setSuggestionsBottomPadding(px: Int) {
        val s = _binding?.suggestions ?: return
        s.setPadding(s.paddingLeft, s.paddingTop, s.paddingRight, px)
    }

    fun onTabResumed() {
        if (_binding == null) return
        updateBanner()
        // Put the D-pad cursor back in the number field so digits/arrows edit it.
        binding.numberInput.requestFocus()
    }

    /** Going Home with nothing dialed/placed discards a stale number. */
    fun onLeaveHint() {
        if (_binding != null && !hasPendingCall()) clearAll()
    }

    /** After a call is placed and the in-call screen covers us, drop the number. */
    fun onHostStopped() {
        if (callPlaced) {
            callPlaced = false
            _binding?.numberInput?.text?.clear()
        }
    }

    /** Pre-fill the field from a DIAL/tel: intent (deferred if the view isn't ready). */
    fun prefillFromIntent(intent: Intent?) {
        val number = numberFromIntent(intent) ?: return
        if (_binding == null) pendingPrefill = number else setNumber(number)
    }

    /** A digit pressed from the Recents tab continues the current number. */
    fun insertInitial(ch: Char) {
        if (_binding == null) { pendingPrefill = (pendingPrefill ?: "") + ch; return }
        insert(ch)
    }

    private fun setNumber(number: String) {
        binding.numberInput.setText(number)
        binding.numberInput.setSelection(binding.numberInput.text?.length ?: 0)
    }

    // --- Keypad input (called by host OSK keys + hardware keys) -----------------

    fun insert(c: Char) {
        val et = _binding?.numberInput ?: return
        val text = et.text ?: return
        val start = et.selectionStart.coerceAtLeast(0)
        val end = et.selectionEnd.coerceAtLeast(start)
        text.replace(start, end, c.toString())
        if (Prefs.dialpadTone(requireContext())) DialpadTones.play(c)
    }

    fun backspace() {
        val et = _binding?.numberInput ?: return
        val text = et.text ?: return
        val start = et.selectionStart
        val end = et.selectionEnd
        if (start != end) {
            text.delete(start.coerceAtMost(end), start.coerceAtLeast(end))
        } else if (start > 0) {
            text.delete(start - 1, start)
        }
    }

    fun clearAll() {
        _binding?.numberInput?.text?.clear()
    }

    /** Hardware-key handling for the Dialer tab. Returns true if consumed. Back is
     *  intentionally NOT handled here — the host's back dispatcher owns it. */
    fun handleKey(event: KeyEvent): Boolean {
        val kc = event.keyCode
        val firstDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        when (kc) {
            KeyEvent.KEYCODE_CALL -> {
                if (event.action == KeyEvent.ACTION_UP) startCall()
                return true
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN ->
                        if (event.repeatCount == 0) zeroAsPlus = false
                        else if (!zeroAsPlus) { insert('+'); zeroAsPlus = true }
                    KeyEvent.ACTION_UP -> if (!zeroAsPlus) insert('0')
                }
                return true
            }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                if (firstDown) insert('0' + (kc - KeyEvent.KEYCODE_0)); return true
            }
            in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> {
                if (firstDown) insert('0' + (kc - KeyEvent.KEYCODE_NUMPAD_0)); return true
            }
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> {
                if (firstDown) insert('*'); return true
            }
            KeyEvent.KEYCODE_POUND -> { if (firstDown) insert('#'); return true }
            KeyEvent.KEYCODE_PLUS -> { if (firstDown) insert('+'); return true }
            KeyEvent.KEYCODE_DEL -> { if (firstDown) backspace(); return true }
        }
        return false
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun onNumberChanged() {
        updateSuggestions()
        val hasText = !binding.numberInput.text.isNullOrEmpty()
        binding.btnBackspace.visibility = if (hasText) View.VISIBLE else View.GONE
        binding.btnDialMenu.visibility = if (hasText) View.VISIBLE else View.GONE
        binding.numberActions.visibility = if (hasText) View.VISIBLE else View.GONE
        autoSizeNumberField()
    }

    private fun autoSizeNumberField() {
        // A re-posted pass can fire after the view is torn down (back / theme
        // recreate); bail instead of dereferencing a null binding.
        val et = (_binding ?: return).numberInput
        val avail = et.width - et.paddingStart - et.paddingEnd
        if (avail <= 0) { et.post { autoSizeNumberField() }; return }
        val sd = resources.displayMetrics.scaledDensity
        val maxPx = 32f * sd
        val minPx = 15f * sd
        val text = et.text?.toString().orEmpty()
        if (text.isEmpty()) {
            et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, maxPx); return
        }
        val paint = android.graphics.Paint().apply { typeface = et.typeface }
        var size = maxPx
        while (size > minPx) {
            paint.textSize = size
            if (paint.measureText(text) <= avail) break
            size -= 1f
        }
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
    }

    private fun dialedDigits(): String =
        (binding.numberInput.text?.toString() ?: "")
            .filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    private fun addDialedToContacts() {
        val n = dialedDigits()
        if (n.isEmpty()) return
        try {
            startActivity(
                Intent(ContactsContract.Intents.Insert.ACTION).apply {
                    type = ContactsContract.RawContacts.CONTENT_TYPE
                    putExtra(ContactsContract.Intents.Insert.PHONE, n)
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun messageDialed() {
        val n = dialedDigits()
        if (n.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(n)}")))
        } catch (_: Exception) {
        }
    }

    // --- Contacts + suggestions ------------------------------------------------

    private fun ensureContacts() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) loadContacts()
        else contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS)
    }

    private fun loadContacts() {
        val ctx = requireContext().applicationContext
        Thread {
            val list = ContactsRepository.load(ctx)
            val activity = ContactsRepository.recentActivity(ctx)
            ui {
                allContacts = list
                frequentContacts = buildFrequentContacts(list, activity)
                updateSuggestions()
            }
        }.start()
    }

    /** Pre-dial suggestions: whoever's been called twice or more in the last 2
     *  hours first (most likely who you're actively trying to reach), then the
     *  rest of the last 7 days' most-called, ranked by that week's count —
     *  never lifetime totals. Each Contact row is a specific number, so a
     *  contact's other numbers the user never actually dialed can't qualify.
     *  Capped at 5; shows fewer if fewer qualify. */
    private fun buildFrequentContacts(
        list: List<Contact>,
        activity: ContactsRepository.RecentActivity
    ): List<Contact> {
        fun key(c: Contact) = c.digits.takeLast(10)
        val urgent = list.asSequence()
            .filter { key(it) in activity.urgentNumbers }
            .distinctBy { key(it) }
        val weekly = list.asSequence()
            .filter { (activity.weekCounts[key(it)] ?: 0) > 0 }
            .sortedByDescending { activity.weekCounts[key(it)] ?: 0 }
            .distinctBy { key(it) }
        return (urgent + weekly).distinctBy { key(it) }.take(5).toList()
    }

    private fun updateSuggestions() {
        val q = binding.numberInput.text?.filter { it.isDigit() }?.toString() ?: ""
        val showFrequent = q.isEmpty()
        val base = if (showFrequent) frequentContacts else ContactsRepository.search(q, allContacts)
        suggestionAdapter.submit(if (showFrequent) base else applyQuickDial(q, base))
        binding.frequentLabel.visibility =
            if (showFrequent && frequentContacts.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** Pins the Quick Dial contact assigned to the exact typed digits (if any) at
     *  the top of the suggestions, marked as such, ahead of the regular ranked
     *  matches below it — with that same number filtered out of the rest so it
     *  doesn't also show up a second time. */
    private fun applyQuickDial(q: String, base: List<Contact>): List<Contact> {
        val entry = QuickDial.get(requireContext(), q) ?: return base
        val pinnedDigits = entry.number.filter { it.isDigit() }
        val rest = base.filter { !sameTailDigits(it.digits, pinnedDigits) }
        val pinned = Contact(
            name = entry.name, number = entry.number, digits = pinnedDigits,
            nameT9 = "", wordT9 = emptyList(), photoUri = null,
            timesContacted = 0, lastTimeContacted = 0, isQuickDial = true
        )
        return listOf(pinned) + rest
    }

    private fun sameTailDigits(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val n = minOf(a.length, b.length, 7)
        return a.takeLast(n) == b.takeLast(n)
    }

    private fun numberFromIntent(intent: Intent?): String? {
        intent ?: return null
        val data = intent.data
        val dialActions = setOf(Intent.ACTION_DIAL, Intent.ACTION_VIEW, Intent.ACTION_CALL)
        if (data?.scheme == "tel" && intent.action in dialActions) {
            return Uri.decode(data.schemeSpecificPart)?.trim()
        }
        if (intent.action == Intent.ACTION_DIAL) {
            val kv = intent.extras?.get("key_value")?.toString()?.trim()
            if (!kv.isNullOrEmpty() && kv.all { it.isDigit() || it in "+*#" }) return kv
        }
        return null
    }

    // --- Placing calls ---------------------------------------------------------

    fun startCall() {
        val mmi = (_binding?.numberInput?.text?.toString() ?: "")
            .filter { it.isDigit() || it in "+*#,;" }
        if (mmi.isEmpty()) return
        dial(Dialer.normalize(requireContext(), mmi))
    }

    private fun callContact(raw: String) = dial(Dialer.normalize(requireContext(), raw))

    private fun dial(number: String) {
        if (number.isEmpty()) return
        if (SpecialCodes.handle(requireActivity(), number)) {
            clearAll()
            return
        }
        pendingNumber = number
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) placeCall()
        else callPermLauncher.launch(Manifest.permission.CALL_PHONE)
    }

    private fun placeCall() {
        val n = pendingNumber ?: return
        pendingNumber = null
        Dialer.place(requireContext(), n)
        callPlaced = true
    }

    // --- Suggestion long-press options ----------------------------------------

    private fun showOptions(c: Contact, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.opt_edit)
            menu.add(0, 2, 1, R.string.opt_message)
            menu.add(0, 3, 2, R.string.opt_copy)
            menu.add(0, 4, 3, R.string.opt_contact_details)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { editBeforeDial(c.number); true }
                    2 -> { messageNumber(c.number); true }
                    3 -> { copyNumber(c.number); true }
                    4 -> { openContactDetails(c.number); true }
                    else -> false
                }
            }
            show()
        }
    }

    /** Opens the matching contact's details, or offers to add one if this
     *  number isn't saved yet — same pattern used from Recents/History. */
    private fun openContactDetails(number: String) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
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
        try {
            if (contactUri != null) {
                startActivity(Intent(Intent.ACTION_VIEW, contactUri))
            } else {
                startActivity(
                    Intent(ContactsContract.Intents.Insert.ACTION).apply {
                        type = ContactsContract.RawContacts.CONTENT_TYPE
                        putExtra(ContactsContract.Intents.Insert.PHONE, number)
                    }
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun showDialMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.dial_add_pause)
            menu.add(0, 2, 1, R.string.dial_add_wait)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { insert(','); true }
                    2 -> { insert(';'); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun editBeforeDial(number: String) = setNumber(number)

    private fun messageNumber(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")))
        } catch (e: Exception) {
            Log.w(TAG, "sms failed: ${e.message}")
        }
    }

    private fun copyNumber(number: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("number", number))
        Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun updateBanner() {
        val held = roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
        binding.defaultBanner.visibility = if (held) View.GONE else View.VISIBLE
    }

    private fun ui(block: () -> Unit) {
        _binding?.root?.post { if (_binding != null) block() }
    }

    companion object {
        private const val TAG = "M5Dialer"
    }
}
