package com.puretech.dialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.puretech.dialer.databinding.ActivityStarredBinding

/** The Starred page: pinned call-log entries/contacts, sortable, with notes. */
class StarredActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStarredBinding
    private lateinit var adapter: StarredAdapter
    private var allEntries: List<StarredStore.StarredEntry> = emptyList()
    private var pendingCallNumber: String? = null

    private val callPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) placeCall() else pendingCallNumber = null }

    private val pickContact = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        val picked = resolvePickedContact(uri) ?: return@registerForActivityResult
        starAndReload(picked.number, picked.name, picked.photoUri)
    }

    private val pickRecent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        val number = data.getStringExtra(QuickDialRecentsActivity.EXTRA_NUMBER)
            ?: return@registerForActivityResult
        val name = data.getStringExtra(QuickDialRecentsActivity.EXTRA_NAME)
        val photo = data.getStringExtra(QuickDialRecentsActivity.EXTRA_PHOTO_URI)
        starAndReload(number, name, photo)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStarredBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        binding.sortBtn.setOnClickListener { showSortMenu(it) }
        binding.clearBtn.setOnClickListener { confirmClearAll() }
        binding.addBtn.setOnClickListener { showAddMenu() }

        adapter = StarredAdapter(
            onNotes = { editNotes(it) },
            onMessage = { messageEntry(it) },
            onHistory = { openHistory(it) },
            onCall = { callEntry(it) },
            onOpenContact = { openContact(it) },
            onLongPress = { entry, anchor -> showEntryMenu(entry, anchor) }
        )
        binding.starredList.layoutManager = LinearLayoutManager(this)
        binding.starredList.adapter = adapter

        reload()
    }

    override fun onResume() {
        super.onResume()
        // Star state can change from CallHistoryActivity/RecentsFragment while
        // this screen is backgrounded — pick it up on return.
        reload()
    }

    private fun reload() {
        Thread {
            val entries = StarredStore.loadAll(applicationContext)
            runOnUiThread {
                allEntries = entries
                applySort()
            }
        }.start()
    }

    private fun applySort() {
        val sorted = when (Prefs.starredSortMode(this)) {
            1 -> allEntries.sortedBy { it.starredAt }
            2 -> allEntries.sortedBy {
                (NameFormat.apply(this, it.name) ?: it.name ?: it.number).lowercase()
            }
            else -> allEntries.sortedByDescending { it.starredAt }
        }
        adapter.submit(sorted)
        binding.emptyState.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openHistory(entry: StarredStore.StarredEntry) {
        val name = NameFormat.apply(this, entry.name) ?: entry.name ?: entry.number
        startActivity(
            Intent(this, CallHistoryActivity::class.java)
                .putExtra(CallHistoryActivity.EXTRA_NUMBER, entry.number)
                .putExtra(CallHistoryActivity.EXTRA_NAME, name)
        )
    }

    private fun messageEntry(entry: StarredStore.StarredEntry) {
        try {
            startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(entry.number)}"))
            )
        } catch (_: Exception) {
        }
    }

    private fun copyNumber(entry: StarredStore.StarredEntry) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("number", entry.number))
        android.widget.Toast.makeText(this, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun callEntry(entry: StarredStore.StarredEntry) {
        if (entry.number.isBlank()) return
        pendingCallNumber = entry.number
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) placeCall() else callPermLauncher.launch(Manifest.permission.CALL_PHONE)
    }

    private fun placeCall() {
        val n = pendingCallNumber ?: return
        pendingCallNumber = null
        Dialer.place(this, Dialer.normalize(this, n))
    }

    /** Tapping the avatar opens the matching contact, or offers to add one if
     *  this number isn't saved yet — same behaviour as CallHistoryActivity's
     *  header tap. */
    private fun openContact(entry: StarredStore.StarredEntry) {
        val number = entry.number
        if (number.isBlank()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
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
        } catch (_: Exception) {
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

    private fun editNotes(entry: StarredStore.StarredEntry) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(entry.notes.orEmpty())
            hint = getString(R.string.notes_hint)
            minLines = 3
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        field.setPadding(pad, pad / 2, pad, pad / 2)
        AlertDialog.Builder(this)
            .setTitle(R.string.notes)
            .setView(field)
            .setPositiveButton(R.string.notes_save) { _, _ ->
                val text = field.text?.toString().orEmpty()
                val ctx = applicationContext
                val number = entry.number
                Thread {
                    StarredStore.updateNotes(ctx, number, text)
                    runOnUiThread { reload() }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEntryMenu(entry: StarredStore.StarredEntry, anchor: View) {
        val title = NameFormat.apply(this, entry.name) ?: entry.name ?: entry.number
        CardMenu(this, anchor)
            .title(title)
            .add(MENU_UNSTAR, R.drawable.ic_star_filled, getString(R.string.unstar_entry))
            .add(MENU_COPY, R.drawable.ic_content_copy, getString(R.string.log_copy))
            .onClick { id ->
                when (id) {
                    MENU_UNSTAR -> unstarAndReload(entry.number)
                    MENU_COPY -> copyNumber(entry)
                }
            }
            .show()
    }

    private fun unstarAndReload(number: String) {
        val ctx = applicationContext
        Thread {
            StarredStore.unstar(ctx, number)
            runOnUiThread { reload() }
        }.start()
    }

    private fun starAndReload(number: String, name: String?, photoUri: String?) {
        val ctx = applicationContext
        Thread {
            StarredStore.star(ctx, number, name, photoUri)
            runOnUiThread { reload() }
        }.start()
    }

    // --- Sort --------------------------------------------------------------------

    private fun showSortMenu(anchor: View) {
        val current = Prefs.starredSortMode(this)
        CardMenu(this, anchor)
            .add(0, R.drawable.ic_sort, getString(R.string.starred_sort_date_new), current == 0)
            .add(1, R.drawable.ic_sort, getString(R.string.starred_sort_date_old), current == 1)
            .add(2, R.drawable.ic_sort, getString(R.string.starred_sort_name), current == 2)
            .onClick { mode ->
                Prefs.setStarredSortMode(this, mode)
                applySort()
            }
            .show()
    }

    // --- Clear all -------------------------------------------------------------

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setMessage(R.string.starred_clear_all_confirm)
            .setPositiveButton(R.string.starred_clear_all) { _, _ -> clearAll() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearAll() {
        val ctx = applicationContext
        Thread {
            StarredStore.clearAll(ctx)
            runOnUiThread { reload() }
        }.start()
    }

    // --- Add ("+") -----------------------------------------------------------------

    private fun showAddMenu() {
        AlertDialog.Builder(this)
            .setItems(
                arrayOf(
                    getString(R.string.starred_add_from_contacts),
                    getString(R.string.starred_add_from_recents),
                    getString(R.string.starred_add_manually)
                )
            ) { _, which ->
                when (which) {
                    0 -> pickFromContacts()
                    1 -> pickFromRecents()
                    2 -> enterManually()
                }
            }
            .show()
    }

    private fun pickFromContacts() {
        try {
            pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        } catch (_: Exception) {
        }
    }

    private fun pickFromRecents() {
        pickRecent.launch(Intent(this, QuickDialRecentsActivity::class.java))
    }

    private fun enterManually() {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = getString(R.string.quick_dial_manual_hint)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        field.setPadding(pad, pad / 2, pad, pad / 2)
        AlertDialog.Builder(this)
            .setTitle(R.string.starred_add_manually)
            .setView(field)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val number = field.text?.toString()?.trim().orEmpty()
                if (number.isNotEmpty()) starAndReload(number, number, null)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private data class Picked(val name: String, val number: String, val photoUri: String?)

    private fun resolvePickedContact(uri: Uri): Picked? {
        return contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val contactId = c.getLong(0)
                val name = c.getString(1) ?: ""
                val number = c.getString(2) ?: ""
                val photo = c.getString(3) ?: lookupContactPhoto(contactId)
                if (number.isBlank()) null else Picked(name.ifBlank { number }, number, photo)
            } else null
        }
    }

    private fun lookupContactPhoto(contactId: Long): String? = try {
        contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.PHOTO_URI),
            "${ContactsContract.Contacts._ID} = ?", arrayOf(contactId.toString()), null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MENU_UNSTAR = 1
        const val MENU_COPY = 2
    }
}
