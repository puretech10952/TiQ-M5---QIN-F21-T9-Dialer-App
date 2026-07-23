package com.puretech.dialer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.puretech.dialer.databinding.ActivityToggleSettingBinding

/**
 * Generic Material 3 detail page for a single on/off setting. The switch and the
 * explanation live here (inside the page) rather than on the main settings list,
 * which only shows the setting's name. Driven by [EXTRA_KEY].
 */
class ToggleSettingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityToggleSettingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityToggleSettingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val (titleRes, descRes) = when (key) {
            KEY_DIALPAD_TONE -> R.string.setting_dialpad_tone to R.string.setting_dialpad_tone_summary
            KEY_SWIPE_ANSWER -> R.string.setting_swipe_answer to R.string.setting_swipe_answer_summary
            KEY_BLOCK_UNKNOWN -> R.string.setting_block_unknown to R.string.setting_block_unknown_summary
            KEY_ALWAYS_FULL_SCREEN_CALLS ->
                R.string.setting_always_full_screen_calls to R.string.setting_always_full_screen_calls_summary
            else -> { finish(); return }
        }

        binding.title.setText(titleRes)
        binding.toggleLabel.setText(titleRes)
        binding.desc.setText(descRes)
        binding.back.setOnClickListener { finish() }

        binding.toggleSwitch.isChecked = read(key)
        binding.toggleSwitch.setOnCheckedChangeListener { _, checked -> write(key, checked) }
        binding.toggleRow.setOnClickListener {
            binding.toggleSwitch.isChecked = !binding.toggleSwitch.isChecked
        }
    }

    private fun read(key: String): Boolean = when (key) {
        KEY_DIALPAD_TONE -> Prefs.dialpadTone(this)
        KEY_SWIPE_ANSWER -> Prefs.swipeToAnswer(this)
        KEY_BLOCK_UNKNOWN -> Prefs.blockUnknownCallers(this)
        KEY_ALWAYS_FULL_SCREEN_CALLS -> Prefs.alwaysFullScreenCalls(this)
        else -> false
    }

    private fun write(key: String, on: Boolean) = when (key) {
        KEY_DIALPAD_TONE -> Prefs.setDialpadTone(this, on)
        KEY_SWIPE_ANSWER -> Prefs.setSwipeToAnswer(this, on)
        KEY_BLOCK_UNKNOWN -> Prefs.setBlockUnknownCallers(this, on)
        KEY_ALWAYS_FULL_SCREEN_CALLS -> Prefs.setAlwaysFullScreenCalls(this, on)
        else -> {}
    }

    companion object {
        const val EXTRA_KEY = "toggle_key"
        const val KEY_DIALPAD_TONE = "dialpad_tone"
        const val KEY_SWIPE_ANSWER = "swipe_answer"
        const val KEY_BLOCK_UNKNOWN = "block_unknown"
        const val KEY_ALWAYS_FULL_SCREEN_CALLS = "always_full_screen_calls"
    }
}
