package com.puretech.dialer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.puretech.dialer.databinding.ActivityAboutBinding

/** About screen: app name, version/build, copyright, and links to the legal docs. */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }

        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
        val name = info?.versionName ?: ""
        val code = info?.longVersionCode ?: 0L
        binding.aboutVersion.text = getString(R.string.about_version, name, code)

        binding.rowTerms.setOnClickListener { openLegal(LegalActivity.DOC_TERMS) }
        binding.rowPrivacy.setOnClickListener { openLegal(LegalActivity.DOC_PRIVACY) }
    }

    private fun openLegal(doc: String) {
        startActivity(
            Intent(this, LegalActivity::class.java).putExtra(LegalActivity.EXTRA_DOC, doc)
        )
    }
}
