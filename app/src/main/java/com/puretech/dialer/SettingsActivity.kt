package com.puretech.dialer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.puretech.dialer.databinding.ActivitySettingsBinding

/** Settings hub. Each row shows only the setting name; details (and any switch)
 *  live on the setting's own page. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        binding.rowTheme.setOnClickListener { showThemeDialog() }
        binding.rowBlocked.setOnClickListener {
            startActivity(Intent(this, BlockedNumbersActivity::class.java))
        }
        binding.rowQuick.setOnClickListener {
            startActivity(Intent(this, QuickResponsesActivity::class.java))
        }
        binding.rowAssisted.setOnClickListener {
            startActivity(Intent(this, AssistedDialingActivity::class.java))
        }
        binding.rowAccounts.setOnClickListener {
            startActivity(Intent(this, CallingAccountsActivity::class.java))
        }
        binding.rowFloating.setOnClickListener {
            startActivity(Intent(this, KeypadSettingsActivity::class.java))
        }
        binding.rowRedirect.setOnClickListener {
            startActivity(Intent(this, RedirectSettingsActivity::class.java))
        }
        binding.rowKeepAlive.setOnClickListener {
            startActivity(Intent(this, KeepAliveSettingsActivity::class.java))
        }
        binding.rowRecording.setOnClickListener {
            startActivity(Intent(this, RecordingSettingsActivity::class.java))
        }

        // On/off settings now open their own detail page (switch lives inside).
        binding.rowDialpadTone.setOnClickListener { openToggle(ToggleSettingActivity.KEY_DIALPAD_TONE) }
        binding.rowSwipeAnswer.setOnClickListener { openToggle(ToggleSettingActivity.KEY_SWIPE_ANSWER) }
        binding.rowBlockAi.setOnClickListener { openToggle(ToggleSettingActivity.KEY_BLOCK_AI) }
        binding.rowBlockUnknown.setOnClickListener { openToggle(ToggleSettingActivity.KEY_BLOCK_UNKNOWN) }
    }

    private fun openToggle(key: String) {
        startActivity(
            Intent(this, ToggleSettingActivity::class.java)
                .putExtra(ToggleSettingActivity.EXTRA_KEY, key)
        )
    }

    private fun showThemeDialog() {
        val labels = arrayOf(
            getString(R.string.setting_theme_light),
            getString(R.string.setting_theme_dark),
            getString(R.string.setting_theme_system)
        )
        val modes = intArrayOf(Prefs.THEME_LIGHT, Prefs.THEME_DARK, Prefs.THEME_SYSTEM)
        val current = modes.indexOf(Prefs.themeMode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_theme)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Prefs.setThemeMode(this, modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
