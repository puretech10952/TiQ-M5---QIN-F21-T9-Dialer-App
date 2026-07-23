package com.puretech.dialer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.puretech.dialer.databinding.ActivityQuickDialRecentsBinding

/**
 * A read-only view of recent calls for assigning a Quick dial number — tap
 * one to select it and return; no expand/message/history/delete actions like
 * the real Recents screen has, since picking is the only thing this does.
 */
class QuickDialRecentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickDialRecentsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickDialRecentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }

        val adapter = RecentsAdapter { entry ->
            val name = entry.name?.ifBlank { null } ?: entry.number
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_NUMBER, entry.number)
                    .putExtra(EXTRA_PHOTO_URI, entry.photoUri?.toString())
            )
            finish()
        }
        binding.recentsList.layoutManager = LinearLayoutManager(this)
        binding.recentsList.adapter = adapter

        Thread {
            val all = CallLogRepository.load(applicationContext)
            val seen = HashSet<String>()
            val recents = ArrayList<CallLogEntry>()
            for (e in all) {
                val key = e.number.filter { it.isDigit() }.takeLast(10)
                if (key.isBlank() || !seen.add(key)) continue
                recents.add(e)
                if (recents.size >= 50) break
            }
            runOnUiThread {
                binding.emptyText.visibility = if (recents.isEmpty()) View.VISIBLE else View.GONE
                adapter.submit(recents)
            }
        }.start()
    }

    private class RecentsAdapter(private val onPick: (CallLogEntry) -> Unit) :
        RecyclerView.Adapter<RecentsAdapter.VH>() {

        private val items = ArrayList<CallLogEntry>()

        fun submit(list: List<CallLogEntry>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_quick_dial_recent, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val avatarInitial: TextView = view.findViewById(R.id.avatarInitial)
            private val photo: ShapeableImageView = view.findViewById(R.id.photo)
            private val name: TextView = view.findViewById(R.id.name)
            private val number: TextView = view.findViewById(R.id.number)

            fun bind(e: CallLogEntry) {
                Avatars.bind(avatarInitial, photo, e.name, e.photoUri)
                name.text = e.name ?: e.number
                number.text = e.number
                itemView.setOnClickListener {
                    items.getOrNull(bindingAdapterPosition)?.let(onPick)
                }
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_PHOTO_URI = "photo_uri"
    }
}
