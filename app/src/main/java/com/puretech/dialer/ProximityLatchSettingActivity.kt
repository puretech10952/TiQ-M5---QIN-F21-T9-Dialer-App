package com.puretech.dialer

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.puretech.dialer.databinding.ActivityProximityLatchSettingBinding

/**
 * Explains the proximity-lock helper and links straight to the system
 * Accessibility toggle -- we can't enable an accessibility service ourselves,
 * Android requires the user to do it. Mirrors [RedirectSettingsActivity].
 */
class ProximityLatchSettingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProximityLatchSettingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProximityLatchSettingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        binding.openAccessibility.setOnClickListener { openAccessibilitySettings() }
    }

    override fun onResume() {
        super.onResume()
        val on = ProximityAccessibilityService.isEnabled(this)
        binding.status.text = getString(
            if (on) R.string.proximity_lock_status_on else R.string.proximity_lock_status_off
        )
    }

    /** Deep-link to this app's accessibility entry; fall back to the full list. */
    private fun openAccessibilitySettings() {
        val component = ComponentName(this, ProximityAccessibilityService::class.java).flattenToString()
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val args = Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, component) }
            intent.putExtra(EXTRA_FRAGMENT_ARG_KEY, component)
            intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGS, args)
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    companion object {
        private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    }
}
