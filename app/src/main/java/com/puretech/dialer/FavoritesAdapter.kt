package com.puretech.dialer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView

/** Horizontal strip of starred contacts shown under "Favorites", plus a trailing
 *  "Add" cell to add another contact as a dialer-only favorite. Long-press a
 *  contact cell to change its remembered number or remove it from favorites. */
class FavoritesAdapter(
    private val onClick: (Contact, View) -> Unit,
    private val onLongPress: (Contact, View) -> Unit,
    private val onAddClick: (View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<Contact>()

    fun submit(list: List<Contact>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun getItemCount() = items.size + 1

    override fun getItemViewType(position: Int) =
        if (position == items.size) VIEW_TYPE_ADD else VIEW_TYPE_CONTACT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == VIEW_TYPE_ADD) {
            AddVH(LayoutInflater.from(parent.context).inflate(R.layout.item_favorite_add, parent, false))
        } else {
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_favorite, parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is VH -> holder.bind(items[position])
            is AddVH -> holder.itemView.setOnClickListener { onAddClick(holder.itemView) }
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val initial: TextView = view.findViewById(R.id.avatarInitial)
        private val photo: ShapeableImageView = view.findViewById(R.id.avatarPhoto)
        private val name: TextView = view.findViewById(R.id.favName)

        fun bind(c: Contact) {
            Avatars.bind(initial, photo, c.name, c.photoUri)
            name.text = c.name
            itemView.setOnClickListener { onClick(c, itemView) }
            itemView.setOnLongClickListener { onLongPress(c, itemView); true }
        }
    }

    inner class AddVH(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val VIEW_TYPE_CONTACT = 0
        const val VIEW_TYPE_ADD = 1
    }
}
