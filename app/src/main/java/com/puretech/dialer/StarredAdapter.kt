package com.puretech.dialer

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import java.text.DateFormat
import java.util.Calendar

/**
 * Row list for the Starred page. Card style matches the call log / voicemail
 * lists: tap a row to expand its action shade (Notes / Message / History),
 * same pattern as [CallLogAdapter]. Long-press for Unstar / Copy number.
 */
class StarredAdapter(
    private val onNotes: (StarredStore.StarredEntry) -> Unit,
    private val onMessage: (StarredStore.StarredEntry) -> Unit,
    private val onHistory: (StarredStore.StarredEntry) -> Unit,
    private val onCall: (StarredStore.StarredEntry) -> Unit,
    private val onOpenContact: (StarredStore.StarredEntry) -> Unit,
    private val onLongPress: (StarredStore.StarredEntry, View) -> Unit
) : RecyclerView.Adapter<StarredAdapter.VH>() {

    private val items = ArrayList<StarredStore.StarredEntry>()
    private var expandedPosition = RecyclerView.NO_POSITION

    fun submit(list: List<StarredStore.StarredEntry>) {
        items.clear()
        items.addAll(list)
        expandedPosition = RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_starred, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position], position == expandedPosition)

    private fun toggle(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        val prev = expandedPosition
        expandedPosition = if (position == expandedPosition) RecyclerView.NO_POSITION else position
        if (prev != RecyclerView.NO_POSITION) notifyItemChanged(prev)
        if (expandedPosition != RecyclerView.NO_POSITION) notifyItemChanged(expandedPosition)
    }

    /** Same day/time added: just the time. Yesterday: "Yesterday {time}". Up to
     *  a week back: weekday name + time. Older: date + time. */
    private fun addedLabel(ctx: Context, date: Long): String {
        val diff = ((midnight(System.currentTimeMillis()) - midnight(date)) / DateUtils.DAY_IN_MILLIS).toInt()
        val time = DateUtils.formatDateTime(ctx, date, DateUtils.FORMAT_SHOW_TIME)
        return when {
            diff <= 0 -> time
            diff == 1 -> ctx.getString(R.string.recents_yesterday) + " " + time
            diff in 2..6 -> DateUtils.formatDateTime(
                ctx, date, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_TIME
            )
            else -> DateUtils.formatDateTime(
                ctx, date,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME
            )
        }
    }

    private fun midnight(t: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val row: View = view.findViewById(R.id.row)
        private val avatar: View = view.findViewById(R.id.avatar)
        private val avatarInitial: TextView = view.findViewById(R.id.avatarInitial)
        private val avatarPhoto: ShapeableImageView = view.findViewById(R.id.avatarPhoto)
        private val name: TextView = view.findViewById(R.id.name)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val notePreview: TextView = view.findViewById(R.id.notePreview)
        private val reminderPreview: TextView = view.findViewById(R.id.reminderPreview)
        private val callBtn: View = view.findViewById(R.id.callBtn)
        private val actions: View = view.findViewById(R.id.actions)
        private val actionNotes: TextView = view.findViewById(R.id.actionNotes)
        private val actionMessage: TextView = view.findViewById(R.id.actionMessage)
        private val actionHistory: TextView = view.findViewById(R.id.actionHistory)

        fun bind(e: StarredStore.StarredEntry, expanded: Boolean) {
            val ctx = itemView.context
            val displayName = NameFormat.apply(ctx, e.name) ?: e.name
            Avatars.bind(avatarInitial, avatarPhoto, displayName, e.photoUri)
            name.text = displayName?.ifBlank { null } ?: e.number

            subtitle.text = ctx.getString(R.string.starred_subtitle, addedLabel(ctx, e.starredAt))

            val note = e.notes?.trim().orEmpty()
            if (note.isNotEmpty()) {
                notePreview.visibility = View.VISIBLE
                notePreview.text = ctx.getString(R.string.starred_note_preview, note)
            } else {
                notePreview.visibility = View.GONE
            }

            val reminderAt = e.reminderAt
            if (reminderAt != null) {
                reminderPreview.visibility = View.VISIBLE
                reminderPreview.text = ctx.getString(
                    R.string.starred_reminder_subtitle,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(reminderAt)
                )
            } else {
                reminderPreview.visibility = View.GONE
            }

            actions.visibility = if (expanded) View.VISIBLE else View.GONE

            row.setOnClickListener { toggle(bindingAdapterPosition) }
            row.setOnLongClickListener { onLongPress(e, row); true }
            avatar.setOnClickListener { onOpenContact(e) }
            callBtn.setOnClickListener { onCall(e) }
            actionNotes.setOnClickListener { onNotes(e) }
            actionMessage.setOnClickListener { onMessage(e) }
            actionHistory.setOnClickListener { onHistory(e) }
        }
    }
}
