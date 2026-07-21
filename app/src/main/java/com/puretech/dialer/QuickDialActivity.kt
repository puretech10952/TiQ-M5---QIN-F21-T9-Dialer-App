package com.puretech.dialer

import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.puretech.dialer.databinding.ActivityQuickDialBinding

/** Assign a 1-99 number to a contact so it's pinned to the top of the dial
 *  screen's suggestions while that exact number is typed. See [QuickDial]. */
class QuickDialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickDialBinding

    /** Set right before launching the picker from an existing row's tap, so the
     *  result reassigns that code directly instead of prompting for a new one. */
    private var reassignCode: Int? = null

    private val pickContact = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = reassignCode
        reassignCode = null
        val uri = result.data?.data ?: return@registerForActivityResult
        val picked = resolvePickedNumber(uri) ?: return@registerForActivityResult
        if (code != null) saveAndRender(code, picked.first, picked.second)
        else promptForCode(picked.first, picked.second)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickDialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        binding.addButton.setOnClickListener {
            pickContact.launch(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            )
        }
        render()
    }

    /** Resolves the name + number for the phone-data row the picker returned. */
    private fun resolvePickedNumber(uri: android.net.Uri): Pair<String, String>? {
        return contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: ""
                val number = c.getString(1) ?: ""
                if (number.isBlank()) null else (name.ifBlank { number }) to number
            } else null
        }
    }

    private fun promptForCode(name: String, number: String) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.quick_dial_code_hint)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        field.setPadding(pad, pad / 2, pad, pad / 2)
        AlertDialog.Builder(this)
            .setTitle(R.string.quick_dial_code_title)
            .setView(field)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = field.text?.toString()?.trim()?.toIntOrNull()
                if (code == null || code !in QuickDial.MIN_CODE..QuickDial.MAX_CODE) {
                    android.widget.Toast.makeText(this, R.string.quick_dial_code_invalid, android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val existing = QuickDial.all(this).firstOrNull { it.first == code }
                if (existing != null) {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.quick_dial_code_taken, code, existing.second.name))
                        .setPositiveButton(android.R.string.ok) { _, _ -> saveAndRender(code, name, number) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    saveAndRender(code, name, number)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveAndRender(code: Int, name: String, number: String) {
        QuickDial.set(this, code, name, number)
        render()
    }

    private fun render() {
        val entries = QuickDial.all(this)
        binding.quickDialList.removeAllViews()
        binding.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        for ((code, entry) in entries) {
            val row = layoutInflater.inflate(R.layout.item_quick_dial, binding.quickDialList, false)
            row.findViewById<TextView>(R.id.codeBadge).text = code.toString()
            row.findViewById<TextView>(R.id.qdName).text = entry.name
            row.findViewById<TextView>(R.id.qdNumber).text = entry.number
            // Tapping the row lets you point this same code at a different contact.
            row.setOnClickListener { pickContactForReassign(code) }
            row.findViewById<MaterialButton>(R.id.removeButton).setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage(R.string.quick_dial_remove_confirm)
                    .setPositiveButton(R.string.quick_dial_remove) { _, _ ->
                        QuickDial.remove(this, code)
                        render()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            binding.quickDialList.addView(row)
        }
    }

    private fun pickContactForReassign(code: Int) {
        reassignCode = code
        pickContact.launch(
            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        )
    }
}
