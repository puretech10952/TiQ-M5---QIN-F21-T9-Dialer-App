package com.puretech.dialer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.puretech.dialer.databinding.ActivityNameFormatSettingsBinding

/** Display > Name format: how contact/caller names are shown everywhere in the
 *  app — "First Last" (default) or "Last, First". See [NameFormat]. */
class NameFormatSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNameFormatSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNameFormatSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }

        if (Prefs.lastNameFirst(this)) binding.formatLastFirst.isChecked = true
        else binding.formatFirstFirst.isChecked = true

        binding.nameFormatGroup.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setLastNameFirst(this, checkedId == R.id.formatLastFirst)
        }
    }
}
