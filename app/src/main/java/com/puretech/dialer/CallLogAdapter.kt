package com.puretech.dialer

import android.content.Context
import android.content.res.ColorStateList
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import java.util.Locale

sealed class CallLogRow {
    data class Header(val label: String) : CallLogRow()
    data class Item(val entry: CallLogEntry) : CallLogRow()
}

/**
 * Google-Dialer-style recents. Each row is a card; tapping expands a shade
 * (Add contact for unknown numbers / Message / History / Copy). The phone icon
 * places the call. Also renders contact search results (asContact = true).
 */
class CallLogAdapter(
    private val onCall: (CallLogEntry) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onHistory: (CallLogEntry) -> Unit,
    private val onAddContact: (String) -> Unit,
    private val onCopy: (String) -> Unit,
    private val onOpenContact: (CallLogEntry) -> Unit,
    private val onLongPress: (CallLogEntry, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = ArrayList<CallLogRow>()
    private var expandedPosition = RecyclerView.NO_POSITION

    fun submit(list: List<CallLogRow>) {
        rows.clear()
        rows.addAll(list)
        expandedPosition = RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) =
        if (rows[position] is CallLogRow.Header) TYPE_HEADER else TYPE_ITEM

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_call_log_header, parent, false))
        } else {
            ItemVH(inflater.inflate(R.layout.item_call_log, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is CallLogRow.Header -> (holder as HeaderVH).label.text = row.label
            is CallLogRow.Item -> {
                // Group consecutive items between day headers into one rounded card.
                val first = position == 0 || rows[position - 1] is CallLogRow.Header
                val last = position == rows.size - 1 || rows[position + 1] is CallLogRow.Header
                (holder as ItemVH).bind(row.entry, position == expandedPosition, first, last)
            }
        }
    }

    private fun toggle(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        val prev = expandedPosition
        expandedPosition = if (position == expandedPosition) RecyclerView.NO_POSITION else position
        if (prev != RecyclerView.NO_POSITION) notifyItemChanged(prev)
        if (expandedPosition != RecyclerView.NO_POSITION) notifyItemChanged(expandedPosition)
    }

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view as TextView
    }

    inner class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        private val rowDivider: View = view.findViewById(R.id.rowDivider)
        private val row: View = view.findViewById(R.id.row)
        private val avatar: View = view.findViewById(R.id.avatar)
        private val avatarInitial: TextView = view.findViewById(R.id.avatarInitial)
        private val avatarPhoto: ShapeableImageView = view.findViewById(R.id.avatarPhoto)
        private val name: TextView = view.findViewById(R.id.name)
        private val count: TextView = view.findViewById(R.id.count)
        private val hd: ImageView = view.findViewById(R.id.hd)
        private val wifi: ImageView = view.findViewById(R.id.wifi)
        private val typeIcon: ImageView = view.findViewById(R.id.typeIcon)
        private val time: TextView = view.findViewById(R.id.time)
        private val callBtn: ImageView = view.findViewById(R.id.callBtn)
        private val actions: View = view.findViewById(R.id.actions)
        private val actionAddContact: TextView = view.findViewById(R.id.actionAddContact)
        private val actionMessage: TextView = view.findViewById(R.id.actionMessage)
        private val actionHistory: TextView = view.findViewById(R.id.actionHistory)
        private val actionCopy: TextView = view.findViewById(R.id.actionCopy)

        fun bind(e: CallLogEntry, expanded: Boolean, firstInGroup: Boolean, lastInGroup: Boolean) {
            val ctx = name.context
            val onSurface = ctx.themeColor(com.google.android.material.R.attr.colorOnSurface)
            val variant = ctx.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            val red = ContextCompat.getColor(ctx, R.color.missed_red)
            val number = formatNumber(e.number)

            // One rounded card per day: only the group's outer corners are rounded;
            // a small gap (the item's top margin) separates rows of the same day.
            // An expanded entry becomes its own fully-rounded standalone card.
            itemView.setBackgroundResource(
                when {
                    expanded -> R.drawable.bg_log_group_single
                    firstInGroup && lastInGroup -> R.drawable.bg_log_group_single
                    firstInGroup -> R.drawable.bg_log_group_top
                    lastInGroup -> R.drawable.bg_log_group_bottom
                    else -> R.drawable.bg_log_group_middle
                }
            )
            rowDivider.visibility = View.GONE

            Avatars.bind(avatarInitial, avatarPhoto, e.name, e.photoUri)
            name.text = e.name ?: number.ifBlank { ctx.getString(R.string.unknown_caller) }

            if (e.asContact) {
                name.setTextColor(onSurface)
                typeIcon.visibility = View.GONE
                count.visibility = View.GONE
                hd.visibility = View.GONE
                wifi.visibility = View.GONE
                time.text = number
                time.setTextColor(variant)
            } else {
                val missed = e.type == CallLog.Calls.MISSED_TYPE || e.type == CallLog.Calls.REJECTED_TYPE
                // The primary line (name / phone number) always stays the normal
                // colour; only the second line (arrow, label, time) turns red for missed.
                name.setTextColor(onSurface)
                count.text = if (e.count > 1) "(${e.count})" else ""
                count.visibility = if (e.count > 1) View.VISIBLE else View.GONE
                hd.visibility = if (e.isHd) View.VISIBLE else View.GONE
                wifi.visibility = if (e.isWifi) View.VISIBLE else View.GONE
                typeIcon.visibility = View.VISIBLE
                typeIcon.setImageResource(
                    when (e.type) {
                        CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_call_made
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> R.drawable.ic_call_missed
                        else -> R.drawable.ic_call_received
                    }
                )
                // Colour the direction arrow only (not the text): outgoing green,
                // missed red, incoming blue.
                val arrowColor = when (e.type) {
                    CallLog.Calls.OUTGOING_TYPE -> ContextCompat.getColor(ctx, R.color.call_arrow_outgoing)
                    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> red
                    else -> ContextCompat.getColor(ctx, R.color.call_arrow_incoming)
                }
                typeIcon.imageTintList = ColorStateList.valueOf(arrowColor)
                // "Mobile • 5 min ago"  (known)  /  "New City, NY • 8 min ago" (unknown)
                // plus " • SIM 1" on dual-SIM devices.
                val parts = ArrayList<String>(3)
                subtitleLabel(ctx, e)?.let { parts.add(it) }
                relativeTime(ctx, e.date).takeIf { it.isNotBlank() }?.let { parts.add(it) }
                e.simLabel?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                time.text = parts.joinToString(" • ")
                time.setTextColor(if (missed) red else variant)
            }

            actions.visibility = if (expanded) View.VISIBLE else View.GONE
            // Add-contact only for unknown numbers in the real call log.
            actionAddContact.visibility =
                if (!e.asContact && e.name == null) View.VISIBLE else View.GONE

            // Grouped-card shade (top/middle/bottom) like settings & the call log.
            val addShown = actionAddContact.visibility == View.VISIBLE
            actionAddContact.setBackgroundResource(R.drawable.bg_shade_top)
            actionMessage.setBackgroundResource(
                if (addShown) R.drawable.bg_shade_middle else R.drawable.bg_shade_top
            )
            actionHistory.setBackgroundResource(R.drawable.bg_shade_middle)
            actionCopy.setBackgroundResource(R.drawable.bg_shade_bottom)

            callBtn.setOnClickListener { onCall(e) }
            row.setOnClickListener { toggle(bindingAdapterPosition) }
            // Tap the avatar to open the contact (only when it's a saved contact).
            avatar.setOnClickListener {
                if (e.name != null) onOpenContact(e) else toggle(bindingAdapterPosition)
            }
            // Long-press a real call-log row for block / delete.
            if (e.asContact) {
                row.setOnLongClickListener(null)
                row.isLongClickable = false
            } else {
                row.setOnLongClickListener { onLongPress(e, row); true }
            }
            actionAddContact.setOnClickListener { onAddContact(e.number) }
            actionMessage.setOnClickListener { onMessage(e.number) }
            actionHistory.setOnClickListener { onHistory(e) }
            actionCopy.setOnClickListener { onCopy(e.number) }
        }
    }

    private fun formatNumber(number: String): String =
        PhoneNumberUtils.formatNumber(number, Locale.US.country) ?: number

    /** Number-type label ("Mobile"/"Home"…) for contacts, geocoded city for unknowns. */
    private fun subtitleLabel(ctx: Context, e: CallLogEntry): String? = when {
        e.name != null && e.numberType > 0 ->
            ContactsContract.CommonDataKinds.Phone
                .getTypeLabel(ctx.resources, e.numberType, e.numberLabel)
                ?.toString()?.ifBlank { null }
        e.name == null -> e.geocoded?.ifBlank { null }
        else -> null
    }

    private fun relativeTime(ctx: Context, date: Long): String {
        if (date <= 0L) return ""
        val diff = System.currentTimeMillis() - date
        return when {
            diff < 60_000L -> ctx.getString(R.string.just_now)
            diff < 3_600_000L -> ctx.getString(R.string.min_ago_fmt, (diff / 60_000L).toInt())
            else -> DateUtils.formatDateTime(ctx, date, DateUtils.FORMAT_SHOW_TIME)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
