package com.puretech.dialer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.puretech.dialer.databinding.ActivityContactsBinding

/** A simple alphabetical contacts list; tap to call. */
class ContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactsBinding
    private val telecomManager by lazy { getSystemService(TelecomManager::class.java) }
    private lateinit var adapter: SuggestionAdapter
    private var pendingNumber: String? = null

    private val callPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted && pendingNumber != null) placeCall() else pendingNumber = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        adapter = SuggestionAdapter(
            onCall = { callNumber(it.number) },
            onOptions = { c, v -> showOptions(c.number, v) },
            onMessage = { messageNumber(it.number) }
        )
        binding.contactList.layoutManager = LinearLayoutManager(this)
        binding.contactList.adapter = adapter

        Thread {
            val list = ContactsRepository.load(applicationContext).sortedBy { it.name.lowercase() }
            runOnUiThread { adapter.submit(list) }
        }.start()
    }

    private fun callNumber(raw: String) {
        if (raw.isBlank()) return
        pendingNumber = raw
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) placeCall() else callPermLauncher.launch(Manifest.permission.CALL_PHONE)
    }

    private fun placeCall() {
        val n = pendingNumber ?: return
        pendingNumber = null
        // Route through Dialer so the default SIM + AI-number block apply here too.
        Dialer.place(this, Dialer.normalize(this, n))
    }

    private fun messageNumber(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")))
        } catch (_: Exception) {
        }
    }

    private fun showOptions(number: String, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.log_message)
            menu.add(0, 2, 1, R.string.log_copy)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> messageNumber(number)
                    2 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("number", number))
                        Toast.makeText(this@ContactsActivity, R.string.copied, Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            show()
        }
    }
}
