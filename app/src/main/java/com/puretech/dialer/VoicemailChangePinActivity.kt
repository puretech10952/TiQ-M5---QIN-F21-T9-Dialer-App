package com.puretech.dialer

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.puretech.dialer.databinding.ActivityVoicemailChangePinBinding
import com.puretech.dialer.vvm.ChangePinResult
import com.puretech.dialer.vvm.VvmSync

/**
 * Changes the live voicemail box PIN over IMAP (`CHANGE_TUI_PWD`, confirmed
 * from AOSP's ImapHelper -- see [com.puretech.dialer.vvm.ImapClient.changePin]).
 * This is a real, mutating action against the carrier account.
 *
 * Doesn't ask for the current PIN up front: [VvmSync.changePinAuto] tries
 * VVM3's well-known default-PIN formula first (matches how AOSP/Google's own
 * client never has to ask either -- it auto-sets and remembers a PIN during
 * activation; this app doesn't do that mutating step, so it falls back to
 * the documented default instead). Only if that guess turns out wrong does
 * this screen reveal a "current PIN" field and ask.
 */
class VoicemailChangePinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoicemailChangePinBinding
    private var needsOldPin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoicemailChangePinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        binding.save.setOnClickListener { save() }
    }

    private fun save() {
        val oldPin = binding.inputOldPin.text?.toString().orEmpty()
        val newPin = binding.inputNewPin.text?.toString().orEmpty()
        val confirmPin = binding.inputConfirmPin.text?.toString().orEmpty()

        if (newPin.isBlank() || (needsOldPin && oldPin.isBlank())) {
            showError(getString(R.string.pin_empty))
            return
        }
        if (newPin != confirmPin) {
            showError(getString(R.string.pin_mismatch))
            return
        }
        if (!needsOldPin && !VvmSync.canAutoChangePin(applicationContext)) {
            // Nothing to even try silently (e.g. IMAP username too short to
            // derive VVM3's default-PIN formula from) -- skip straight to
            // asking, rather than a network round-trip we know will fail.
            revealOldPinField(getString(R.string.pin_error_no_default))
            return
        }

        setBusy(true)
        val ctx = applicationContext
        Thread {
            val result = try {
                if (needsOldPin) VvmSync.changePin(ctx, oldPin, newPin) else VvmSync.changePinAuto(ctx, newPin)
            } catch (_: Throwable) {
                null
            }
            ui {
                setBusy(false)
                if (result == null) {
                    showError(getString(R.string.pin_error_not_connected))
                    return@ui
                }
                when (result) {
                    ChangePinResult.OK -> {
                        Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    ChangePinResult.TOO_SHORT -> showError(getString(R.string.pin_error_too_short))
                    ChangePinResult.TOO_LONG -> showError(getString(R.string.pin_error_too_long))
                    ChangePinResult.TOO_WEAK -> showError(getString(R.string.pin_error_too_weak))
                    ChangePinResult.OLD_MISMATCH -> {
                        if (needsOldPin) {
                            showError(getString(R.string.pin_error_old_mismatch))
                        } else {
                            // Our silent guess at the current PIN was wrong --
                            // now (and only now) ask for it explicitly.
                            revealOldPinField(getString(R.string.pin_error_old_mismatch_retry))
                        }
                    }
                    ChangePinResult.INVALID_CHARS -> showError(getString(R.string.pin_error_invalid_chars))
                    ChangePinResult.UNKNOWN -> showError(getString(R.string.pin_error_unknown))
                }
            }
        }.start()
    }

    private fun revealOldPinField(message: String) {
        needsOldPin = true
        binding.oldPinLayout.visibility = View.VISIBLE
        showError(message)
    }

    private fun setBusy(busy: Boolean) {
        binding.save.isEnabled = !busy
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        binding.pinError.text = message
        binding.pinError.visibility = View.VISIBLE
    }

    private fun ui(block: () -> Unit) = binding.root.post(block)
}
