package com.puretech.dialer

import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.puretech.dialer.databinding.ActivityScreenProfileBinding

/**
 * Screen-profile chooser: Small (keypad phone) vs Large (smartphone). Shown
 * once during onboarding (EXTRA_ONBOARDING=true, blocks with a Continue
 * button, Back leaves the app) and reachable anytime from Settings
 * (EXTRA_ONBOARDING=false/absent, has a back arrow, applies instantly).
 */
class ScreenProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScreenProfileBinding
    private val onboarding by lazy { intent.getBooleanExtra(EXTRA_ONBOARDING, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.visibility = if (onboarding) View.GONE else View.VISIBLE
        binding.back.setOnClickListener { finish() }
        binding.continueButton.visibility = if (onboarding) View.VISIBLE else View.GONE

        when (Prefs.screenProfile(this)) {
            Prefs.PROFILE_LARGE -> binding.profileLarge.isChecked = true
            else -> binding.profileSmall.isChecked = true
        }
        binding.rowSmall.setOnClickListener { binding.profileSmall.isChecked = true }
        binding.rowLarge.setOnClickListener { binding.profileLarge.isChecked = true }

        if (!onboarding) {
            binding.profileGroup.setOnCheckedChangeListener { _, checkedId ->
                val p = if (checkedId == R.id.profileLarge) Prefs.PROFILE_LARGE else Prefs.PROFILE_SMALL
                if (p != Prefs.screenProfile(this)) Prefs.setScreenProfile(this, p)
            }
        } else {
            onBackPressedDispatcher.addCallback(this) {
                moveTaskToBack(true)
            }
        }

        binding.continueButton.setOnClickListener {
            val p = if (binding.profileLarge.isChecked) Prefs.PROFILE_LARGE else Prefs.PROFILE_SMALL
            Prefs.setScreenProfile(this, p)
            Prefs.setScreenProfileChosen(this)
            finish()
        }
    }

    companion object {
        const val EXTRA_ONBOARDING = "onboarding"
    }
}
