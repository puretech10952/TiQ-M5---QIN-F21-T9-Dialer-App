package com.puretech.dialer

import android.os.Bundle
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
        binding.openAccessibility.setOnClickListener { ProximityAccessibilityService.openSettings(this) }

        binding.switchLatch.isChecked = Prefs.proximityLatchEnabled(this)
        binding.switchLatch.setOnCheckedChangeListener { _, checked ->
            Prefs.setProximityLatchEnabled(this, checked)
        }
        binding.rowLatch.setOnClickListener {
            binding.switchLatch.isChecked = !binding.switchLatch.isChecked
        }
    }

    override fun onResume() {
        super.onResume()
        val on = ProximityAccessibilityService.isEnabled(this)
        binding.status.text = getString(
            if (on) R.string.proximity_lock_status_on else R.string.proximity_lock_status_off
        )
    }
}
