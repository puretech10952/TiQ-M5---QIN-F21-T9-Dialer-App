package com.puretech.dialer

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.puretech.dialer.databinding.ActivityQuickDialBinding

/**
 * Grid of numbers 1-99 (3 per row, scrollable) — round avatars with a small
 * number badge and the name/number below, no visible card/grid lines. An
 * empty cell shows a "+" and is tapped to assign a contact/number; an
 * assigned cell shows the contact's photo (or initial) and is tapped for
 * Call / Change / Remove. See [QuickDial].
 *
 * Can also be launched in "assign mode" (from a call log entry's long-press
 * menu) via [EXTRA_ASSIGN_NAME]/[EXTRA_ASSIGN_NUMBER]/[EXTRA_ASSIGN_PHOTO_URI] —
 * every cell then assigns that pending contact instead of the normal flows,
 * and the screen closes itself once a slot is picked.
 */
class QuickDialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickDialBinding
    private lateinit var adapter: GridAdapter

    /** The code the contact-picker result should apply to. */
    private var pendingCode: Int = 0

    /** Set when launched from a call log entry's "Add to Quick dial" — every
     *  cell tap assigns this instead of opening the normal add/manage flows. */
    private var pendingAssign: Picked? = null

    private val pickContact = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = pendingCode
        val uri = result.data?.data ?: return@registerForActivityResult
        val picked = resolvePickedContact(uri) ?: return@registerForActivityResult
        QuickDial.set(this, code, picked.name, picked.number, picked.photoUri)
        adapter.refresh()
    }

    private val pickRecent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = pendingCode
        val data = result.data ?: return@registerForActivityResult
        val name = data.getStringExtra(QuickDialRecentsActivity.EXTRA_NAME) ?: return@registerForActivityResult
        val number = data.getStringExtra(QuickDialRecentsActivity.EXTRA_NUMBER) ?: return@registerForActivityResult
        val photo = data.getStringExtra(QuickDialRecentsActivity.EXTRA_PHOTO_URI)
        QuickDial.set(this, code, name, number, photo)
        adapter.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickDialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pendingAssign = intent.getStringExtra(EXTRA_ASSIGN_NUMBER)?.let { number ->
            Picked(
                name = intent.getStringExtra(EXTRA_ASSIGN_NAME) ?: number,
                number = number,
                photoUri = intent.getStringExtra(EXTRA_ASSIGN_PHOTO_URI)
            )
        }
        pendingAssign?.let {
            binding.quickDialDesc.text = getString(R.string.quick_dial_assign_banner, it.name)
            binding.quickDialDesc.visibility = View.VISIBLE
        }

        binding.back.setOnClickListener { finish() }

        adapter = GridAdapter()
        binding.quickDialGrid.layoutManager = GridLayoutManager(this, 3)
        binding.quickDialGrid.adapter = adapter

        if (pendingAssign == null) showHelpIfNeeded()
    }

    // --- First-run help -----------------------------------------------------------

    private fun showHelpIfNeeded() {
        if (Prefs.quickDialHelpDismissed(this)) return
        val pad = (24 * resources.displayMetrics.density).toInt()
        val padSmall = (12 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, padSmall, pad, 0)
        }
        val message = TextView(this).apply {
            text = getString(R.string.quick_dial_desc)
            textSize = 15f
        }
        val checkbox = CheckBox(this).apply {
            text = getString(R.string.quick_dial_help_dont_show)
            setPadding(0, pad, 0, 0)
        }
        container.addView(message)
        container.addView(checkbox)

        AlertDialog.Builder(this)
            .setTitle(R.string.quick_dial_help_title)
            .setView(container)
            .setCancelable(true)
            .setPositiveButton(R.string.quick_dial_help_dismiss) { _, _ ->
                if (checkbox.isChecked) Prefs.setQuickDialHelpDismissed(this)
            }
            .show()
    }

    // --- Assigning a cell --------------------------------------------------------

    private fun assignCell(code: Int) {
        val pending = pendingAssign
        if (pending != null) {
            applyAssignment(code, pending)
            return
        }
        AlertDialog.Builder(this)
            .setItems(
                arrayOf(
                    getString(R.string.quick_dial_from_contacts),
                    getString(R.string.quick_dial_from_recents),
                    getString(R.string.quick_dial_enter_manually)
                )
            ) { _, which ->
                when (which) {
                    0 -> pickFromContacts(code)
                    1 -> pickFromRecents(code)
                    2 -> enterManually(code)
                }
            }
            .show()
    }

    /** Used by assign-mode: confirms before overwriting an existing slot, then
     *  saves and closes the screen (the whole point of this mode was to place
     *  this one contact somewhere). */
    private fun applyAssignment(code: Int, pending: Picked) {
        val existing = entriesSnapshot()[code]
        if (existing != null) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.quick_dial_overwrite_confirm, code, existing.name, pending.name))
                .setPositiveButton(android.R.string.ok) { _, _ -> finishAssignment(code, pending) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            finishAssignment(code, pending)
        }
    }

    private fun finishAssignment(code: Int, pending: Picked) {
        QuickDial.set(this, code, pending.name, pending.number, pending.photoUri)
        Toast.makeText(this, R.string.quick_dial_added_toast, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun entriesSnapshot(): Map<Int, QuickDial.Entry> = QuickDial.all(this).toMap()

    private fun pickFromContacts(code: Int) {
        pendingCode = code
        try {
            pickContact.launch(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            )
        } catch (_: Exception) {
        }
    }

    private data class Picked(val name: String, val number: String, val photoUri: String?)

    /** Resolves name + number + photo for the phone-data row the picker returned.
     *  Phone.PHOTO_URI on the data row itself is often null even when the
     *  contact does have a photo, so fall back to a direct lookup on the
     *  aggregated contact via CONTACT_ID, which reflects it reliably. */
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
    } catch (e: Exception) {
        null
    }

    private fun pickFromRecents(code: Int) {
        pendingCode = code
        pickRecent.launch(Intent(this, QuickDialRecentsActivity::class.java))
    }

    private fun enterManually(code: Int) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = getString(R.string.quick_dial_manual_hint)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        field.setPadding(pad, pad / 2, pad, pad / 2)
        AlertDialog.Builder(this)
            .setTitle(R.string.quick_dial_enter_manually)
            .setView(field)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val number = field.text?.toString()?.trim().orEmpty()
                if (number.isNotEmpty()) {
                    QuickDial.set(this, code, number, number)
                    adapter.refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Managing an assigned cell -----------------------------------------------

    private fun showCellOptions(code: Int, entry: QuickDial.Entry) {
        if (pendingAssign != null) {
            applyAssignment(code, pendingAssign!!)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("$code — ${entry.name}")
            .setItems(
                arrayOf(
                    getString(R.string.log_call),
                    getString(R.string.quick_dial_change),
                    getString(R.string.quick_dial_remove)
                )
            ) { _, which ->
                when (which) {
                    0 -> callEntry(entry)
                    1 -> assignCell(code)
                    2 -> confirmRemove(code)
                }
            }
            .show()
    }

    private fun callEntry(entry: QuickDial.Entry) {
        try {
            val uri = if (entry.isVoicemail) Uri.parse("voicemail:")
                      else Uri.fromParts("tel", entry.number, null)
            startActivity(Intent(Intent.ACTION_CALL, uri))
        } catch (_: Exception) {
            Toast.makeText(this, entry.number, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmRemove(code: Int) {
        AlertDialog.Builder(this)
            .setMessage(R.string.quick_dial_remove_confirm)
            .setPositiveButton(R.string.quick_dial_remove) { _, _ ->
                QuickDial.remove(this, code)
                adapter.refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Grid ---------------------------------------------------------------------

    private inner class GridAdapter : RecyclerView.Adapter<GridAdapter.VH>() {
        private var entries: Map<Int, QuickDial.Entry> = emptyMap()

        init {
            refresh()
        }

        fun refresh() {
            entries = QuickDial.all(this@QuickDialActivity).toMap()
            notifyDataSetChanged()
        }

        override fun getItemCount() = QuickDial.MAX_CODE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_quick_dial_cell, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val code = position + 1
            val entry = entries[code]
            holder.number.text = code.toString()

            when {
                entry == null -> {
                    holder.initial.visibility = View.GONE
                    holder.photo.visibility = View.GONE
                    holder.icon.visibility = View.VISIBLE
                    holder.icon.setBackgroundResource(R.drawable.bg_circle_empty)
                    holder.icon.setImageResource(R.drawable.ic_add)
                    holder.icon.imageTintList = null
                    // INVISIBLE, not GONE: reserves the same line height as an
                    // assigned cell's name, so empty and assigned cells in the
                    // same grid row stay the same height instead of the empty
                    // ones sitting shorter.
                    holder.name.visibility = View.INVISIBLE
                    holder.avatarFrame.setOnClickListener { assignCell(code) }
                }
                entry.isVoicemail -> {
                    holder.initial.visibility = View.GONE
                    holder.photo.visibility = View.GONE
                    holder.icon.visibility = View.VISIBLE
                    holder.icon.setBackgroundResource(R.drawable.bg_circle_solid)
                    holder.icon.backgroundTintList = ColorStateList.valueOf(
                        this@QuickDialActivity.themeColor(com.google.android.material.R.attr.colorSecondaryContainer)
                    )
                    holder.icon.setImageResource(R.drawable.ic_voicemail)
                    holder.icon.imageTintList = ColorStateList.valueOf(
                        this@QuickDialActivity.themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
                    )
                    holder.name.visibility = View.VISIBLE
                    holder.name.text = entry.name
                    holder.avatarFrame.setOnClickListener { showCellOptions(code, entry) }
                }
                else -> {
                    holder.icon.visibility = View.GONE
                    val displayName = NameFormat.apply(this@QuickDialActivity, entry.name) ?: entry.name
                    Avatars.bind(holder.initial, holder.photo, displayName, entry.photoUri?.let { Uri.parse(it) })
                    holder.name.visibility = View.VISIBLE
                    // Number only stands in for a missing name — never shown
                    // alongside a real one.
                    holder.name.text = displayName.ifBlank { entry.number }
                    holder.avatarFrame.setOnClickListener { showCellOptions(code, entry) }
                }
            }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val avatarFrame: View = view.findViewById(R.id.avatarFrame)
            val number: TextView = view.findViewById(R.id.cellNumber)
            val initial: TextView = view.findViewById(R.id.cellInitial)
            val photo: ShapeableImageView = view.findViewById(R.id.cellPhoto)
            val icon: ImageView = view.findViewById(R.id.cellIcon)
            val name: TextView = view.findViewById(R.id.cellName)
        }
    }

    companion object {
        const val EXTRA_ASSIGN_NAME = "assign_name"
        const val EXTRA_ASSIGN_NUMBER = "assign_number"
        const val EXTRA_ASSIGN_PHOTO_URI = "assign_photo_uri"
    }
}
