package com.puretech.dialer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.puretech.dialer.databinding.ActivityLanguageSettingsBinding

class LanguageSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLanguageSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLanguageSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }

        val current = Prefs.language(this)
        val adapter = LangAdapter(LANGUAGES, current) { tag ->
            Prefs.setLanguage(this, tag)
            val locales = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                          else LocaleListCompat.forLanguageTags(tag)
            AppCompatDelegate.setApplicationLocales(locales)
            // AppCompat automatically recreates all open activities.
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
    }

    private data class LangOption(val tag: String, val english: String, val native: String)

    private class LangAdapter(
        private val items: List<LangOption>,
        private var selected: String,
        private val onPick: (String) -> Unit,
    ) : RecyclerView.Adapter<LangAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val english: TextView = v.findViewById(R.id.langEnglish)
            val native: TextView  = v.findViewById(R.id.langNative)
            val check: ImageView  = v.findViewById(R.id.langCheck)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_language, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            h.english.text = item.english
            h.native.text  = item.native
            h.native.visibility = if (item.native.isNotEmpty()) View.VISIBLE else View.GONE
            h.check.visibility  = if (item.tag == selected) View.VISIBLE else View.INVISIBLE
            h.itemView.setOnClickListener {
                val prev = items.indexOfFirst { it.tag == selected }
                selected = item.tag
                if (prev >= 0) notifyItemChanged(prev)
                notifyItemChanged(position)
                onPick(item.tag)
            }
        }
    }

    companion object {
        private val LANGUAGES = listOf(
            LangOption("",      "System default",         ""),
            LangOption("en",    "English",                "English"),
            LangOption("es",    "Spanish",                "Español"),
            LangOption("fr",    "French",                 "Français"),
            LangOption("de",    "German",                 "Deutsch"),
            LangOption("it",    "Italian",                "Italiano"),
            LangOption("nl",    "Dutch",                  "Nederlands"),
            LangOption("pl",    "Polish",                 "Polski"),
            LangOption("pt-BR", "Portuguese (Brazil)",    "Português (Brasil)"),
            LangOption("ru",    "Russian",                "Русский"),
            LangOption("tr",    "Turkish",                "Türkçe"),
            LangOption("ar",    "Arabic",                 "العربية"),
            LangOption("he",    "Hebrew",                 "עברית"),
            LangOption("hi",    "Hindi",                  "हिन्दी"),
            LangOption("zh-CN", "Chinese (Simplified)",   "中文（简体）"),
            LangOption("ja",    "Japanese",               "日本語"),
            LangOption("ko",    "Korean",                 "한국어"),
        )
    }
}
