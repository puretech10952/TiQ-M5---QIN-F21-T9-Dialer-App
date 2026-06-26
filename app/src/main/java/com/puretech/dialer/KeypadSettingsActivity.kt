package com.puretech.dialer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.puretech.dialer.databinding.ActivityKeypadSettingsBinding

/** Toggles the optional floating on-screen keypad button on the dial screen. */
class KeypadSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeypadSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeypadSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }

        binding.switchDefaultOpen.isChecked = Prefs.keypadDefaultOpen(this)
        binding.switchDefaultOpen.setOnCheckedChangeListener { _, checked ->
            Prefs.setKeypadDefaultOpen(this, checked)
        }
        binding.rowDefaultOpen.setOnClickListener {
            binding.switchDefaultOpen.isChecked = !binding.switchDefaultOpen.isChecked
        }

        binding.switchBigKeypad.isChecked = Prefs.bigKeypad(this)
        binding.switchBigKeypad.setOnCheckedChangeListener { _, checked ->
            Prefs.setBigKeypad(this, checked)
        }
        binding.rowBigKeypad.setOnClickListener {
            binding.switchBigKeypad.isChecked = !binding.switchBigKeypad.isChecked
        }
    }
}
