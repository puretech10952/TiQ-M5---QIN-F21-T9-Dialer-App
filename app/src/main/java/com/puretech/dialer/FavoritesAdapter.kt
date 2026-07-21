package com.puretech.dialer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView

/** Horizontal strip of starred contacts shown under "Favorites". */
class FavoritesAdapter(private val onClick: (Contact, View) -> Unit) :
    RecyclerView.Adapter<FavoritesAdapter.VH>() {

    private val items = ArrayList<Contact>()

    fun submit(list: List<Contact>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_favorite, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val initial: TextView = view.findViewById(R.id.avatarInitial)
        private val photo: ShapeableImageView = view.findViewById(R.id.avatarPhoto)
        private val name: TextView = view.findViewById(R.id.favName)

        fun bind(c: Contact) {
            Avatars.bind(initial, photo, c.name, c.photoUri)
            name.text = c.name
            itemView.setOnClickListener { onClick(c, itemView) }
        }
    }
}
