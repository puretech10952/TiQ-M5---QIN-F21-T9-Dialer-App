package com.puretech.dialer

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.puretech.dialer.databinding.ActivityWelcomeBinding

/**
 * One-time onboarding shown on first launch: a quick tour of the dialer's
 * features and a required agreement to the Terms of Service and Privacy Policy.
 * It blocks entry to the app — the user must tick the box and continue, and
 * pressing Back leaves the app rather than slipping past.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.agree.setOnCheckedChangeListener { _, checked ->
            binding.getStarted.isEnabled = checked
        }
        binding.linkTerms.setOnClickListener { openLegal(LegalActivity.DOC_TERMS) }
        binding.linkPrivacy.setOnClickListener { openLegal(LegalActivity.DOC_PRIVACY) }

        binding.getStarted.setOnClickListener {
            if (!binding.agree.isChecked) return@setOnClickListener
            Prefs.setWelcomeShown(this)
            // A fresh install has nothing to compare "what's new" against —
            // baseline it here so WhatsNewSheet doesn't fire right after
            // onboarding; only an existing user updating into a new version
            // (whose baseline is already set from before) should ever see it.
            WhatsNewSheet.recordCurrentVersion(this)
            finish()
        }

        // Don't let Back slip past the agreement — leave the app instead.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    private fun openLegal(doc: String) {
        startActivity(
            Intent(this, LegalActivity::class.java).putExtra(LegalActivity.EXTRA_DOC, doc)
        )
    }
}
