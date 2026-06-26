package com.puretech.dialer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView

/**
 * Shows ranked contact suggestions. Tap = call immediately; long-press = the
 * caller-provided options popup (edit / message / copy).
 */
class SuggestionAdapter(
    private val onCall: (Contact) -> Unit,
    private val onOptions: (Contact, View) -> Unit
) : RecyclerView.Adapter<SuggestionAdapter.VH>() {

    private val items = ArrayList<Contact>()

    fun submit(list: List<Contact>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatarInitial: TextView = view.findViewById(R.id.avatarInitial)
        val photo: ShapeableImageView = view.findViewById(R.id.photo)
        val name: TextView = view.findViewById(R.id.name)
        val number: TextView = view.findViewById(R.id.number)

        init {
            view.setOnClickListener {
                items.getOrNull(bindingAdapterPosition)?.let(onCall)
            }
            view.setOnLongClickListener {
                items.getOrNull(bindingAdapterPosition)?.let { onOptions(it, view) }
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_suggestion, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.name.text = c.name.ifBlank { c.number }
        holder.number.text = c.number

        // Grouped card: only the list's outer corners are rounded; rows are split
        // by a small gap (the item's top margin).
        val first = position == 0
        val last = position == items.size - 1
        holder.itemView.setBackgroundResource(
            when {
                first && last -> R.drawable.bg_group_row
                first -> R.drawable.bg_rowgroup_top
                last -> R.drawable.bg_rowgroup_bottom
                else -> R.drawable.bg_rowgroup_middle
            }
        )

        // Same avatar pipeline as the call log (cached, recycle-safe, no size jump).
        Avatars.bind(holder.avatarInitial, holder.photo, c.name.ifBlank { null }, c.photoUri)
    }

    override fun getItemCount(): Int = items.size
}
