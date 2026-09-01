package com.puretech.dialer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import com.puretech.dialer.databinding.ActivityLegalBinding

/** Shows the Terms of Service, Privacy Policy, or Change Log from a bundled HTML document. */
class LegalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLegalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLegalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }

        val (titleRes, rawRes) = when (intent.getStringExtra(EXTRA_DOC)) {
            DOC_PRIVACY -> R.string.legal_privacy to R.raw.privacy_policy
            DOC_CHANGELOG -> R.string.changelog_title to R.raw.changelog
            else -> R.string.legal_terms to R.raw.terms_of_service
        }
        binding.title.setText(titleRes)
        binding.body.text = HtmlCompat.fromHtml(readRaw(rawRes), HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    private fun readRaw(resId: Int): String =
        resources.openRawResource(resId).bufferedReader().use { it.readText() }

    companion object {
        const val EXTRA_DOC = "doc"
        const val DOC_TERMS = "terms"
        const val DOC_PRIVACY = "privacy"
        const val DOC_CHANGELOG = "changelog"
    }
}
