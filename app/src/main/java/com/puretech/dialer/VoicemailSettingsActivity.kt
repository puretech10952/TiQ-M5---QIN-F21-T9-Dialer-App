package com.puretech.dialer

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.puretech.dialer.databinding.ActivityVoicemailSettingsBinding
import com.puretech.dialer.vvm.VvmConfig
import com.puretech.dialer.vvm.VvmPrefs
import com.puretech.dialer.vvm.VvmState
import com.puretech.dialer.vvm.VvmSync

/**
 * Dedicated Voicemail settings screen, mirroring how Google's own Phone app
 * structures it: the visual-voicemail on/off switch (reflecting live carrier
 * support, and which also automatically shows/hides the bottom-nav Voicemail
 * tab), the voicemail number, and a link to the system's per-channel
 * notification settings.
 */
class VoicemailSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoicemailSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoicemailSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }

        binding.switchVisualVoicemail.setOnCheckedChangeListener { _, checked ->
            if (rowVisualVoicemailArmed) setVisualVoicemail(checked)
        }
        binding.rowVisualVoicemail.setOnClickListener {
            if (binding.switchVisualVoicemail.isEnabled) {
                binding.switchVisualVoicemail.isChecked = !binding.switchVisualVoicemail.isChecked
            }
        }

        binding.rowVoicemailNumber.setOnClickListener { callVoicemail() }
        binding.rowNotifications.setOnClickListener { openChannelSettings() }
        binding.rowChangePin.setOnClickListener {
            if (binding.rowChangePin.isEnabled) {
                startActivity(Intent(this, VoicemailChangePinActivity::class.java))
            }
        }
        binding.rowChangeGreeting.setOnClickListener {
            if (binding.rowChangeGreeting.isEnabled) {
                startActivity(Intent(this, VoicemailGreetingActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    // Guards the switch's checked-change listener so programmatic refresh()
    // doesn't re-trigger VvmSync.enable()/disable() on every resume.
    private var rowVisualVoicemailArmed = false

    // The carrier's STATUS reply (and thus the SETTING_UP -> CONNECTED/FAILED
    // transition) arrives asynchronously, seconds after enable() returns. While
    // this screen is open and mid-activation, poll so the status text updates
    // live instead of only on the next manual resume. Self-stops once the
    // state leaves SETTING_UP.
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = Runnable { refresh() }

    private fun refresh() {
        val cfg = VvmConfig.read(this)
        val supported = cfg?.isSupported == true
        val enabled = VvmPrefs.enabled(this)
        val state = VvmPrefs.state(this)

        // Connected/Setting up/Failed are single-word, color-coded status --
        // everything else keeps the longer explanatory text since there's no
        // color convention for it.
        binding.visualStatusDetail.visibility = View.GONE
        when (state) {
            VvmState.CONNECTED -> {
                binding.visualStatus.text = getString(R.string.vvm_status_connected)
                binding.visualStatus.setTextColor(ContextCompat.getColor(this, R.color.call_arrow_outgoing))
            }
            VvmState.SETTING_UP -> {
                binding.visualStatus.text = getString(R.string.vvm_status_setting_up)
                binding.visualStatus.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
            }
            VvmState.FAILED -> {
                binding.visualStatus.text = getString(R.string.vvm_status_failed)
                binding.visualStatus.setTextColor(ContextCompat.getColor(this, R.color.missed_red))
                binding.visualStatusDetail.text = getString(R.string.vvm_status_failed_detail)
                binding.visualStatusDetail.visibility = View.VISIBLE
            }
            else -> {
                binding.visualStatus.text = when {
                    enabled -> getString(R.string.vvm_enabled_sub)
                    !supported -> getString(R.string.vvm_unsupported)
                    else -> getString(R.string.vvm_enable_sub)
                }
                binding.visualStatus.setTextColor(
                    themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
            }
        }
        rowVisualVoicemailArmed = false
        binding.switchVisualVoicemail.isChecked = enabled
        binding.switchVisualVoicemail.isEnabled = supported
        binding.rowVisualVoicemail.isEnabled = supported
        binding.rowVisualVoicemail.alpha = if (supported) 1f else 0.6f
        rowVisualVoicemailArmed = true

        binding.voicemailNumber.text = voicemailNumber()
            ?: getString(R.string.setting_voicemail_number_not_set)

        // PIN/greeting changes are real IMAP actions against the live
        // mailbox -- only meaningful once actually connected.
        val connected = state == VvmState.CONNECTED
        binding.rowChangePin.isEnabled = connected
        binding.rowChangePin.alpha = if (connected) 1f else 0.6f
        binding.rowChangeGreeting.isEnabled = connected
        binding.rowChangeGreeting.alpha = if (connected) 1f else 0.6f

        pollHandler.removeCallbacks(pollRunnable)
        if (state == VvmState.SETTING_UP) pollHandler.postDelayed(pollRunnable, 1000)
    }

    private fun setVisualVoicemail(on: Boolean) {
        binding.switchVisualVoicemail.isEnabled = false
        val ctx = applicationContext
        Thread {
            if (on) VvmSync.enable(ctx) else VvmSync.disable(ctx)
            ui {
                binding.switchVisualVoicemail.isEnabled = true
                refresh()
            }
        }.start()
    }

    private fun voicemailNumber(): String? = try {
        getSystemService(TelephonyManager::class.java)?.voiceMailNumber?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    }

    private fun callVoicemail() {
        try {
            getSystemService(TelecomManager::class.java)
                ?.placeCall(Uri.fromParts("voicemail", "", null), Dialer.accountExtras(this))
        } catch (_: Exception) {
        }
    }

    private fun openChannelSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, VoicemailNotifier.CHANNEL)
            )
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun ui(block: () -> Unit) = binding.root.post(block)
}
