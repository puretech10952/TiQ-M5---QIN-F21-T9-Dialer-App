package com.puretech.dialer

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView

/** One stored voicemail (from VoicemailContract) shown in the voicemail list. */
data class VoicemailItem(
    val id: Long,
    val number: String,
    val displayName: String?,
    val photoUri: Uri?,
    val dateMillis: Long,
    val durationSec: Long,
    val isRead: Boolean,
    val hasContent: Boolean
)

/** Live playback state for whichever voicemail is currently expanded — there is
 *  only ever one, since expanding a row shows (but doesn't auto-start) its
 *  player, and only one row is ever expanded at a time. */
data class VoicemailPlayerState(
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val speakerOn: Boolean = true
)

/** One row in the list: either a date-group label ("Today", "Yesterday", ...)
 *  or a voicemail, matching how the real Google Dialer groups its list. */
sealed class VoicemailListEntry {
    data class Header(val label: String) : VoicemailListEntry()
    data class Item(val item: VoicemailItem) : VoicemailListEntry()
}

/**
 * Voicemail list, styled exactly like this app's own call log (grouped cards
 * per day, [R.drawable.bg_log_group_single]/top/middle/bottom). Tapping a row
 * expands a shade in place -- a pill-shaped Play button (tapping it is the
 * only thing that starts playback), a seekbar, secondary icon controls
 * (speaker/share/delete), then Call/Message/Block rows styled like the call
 * log's own message/history shade buttons. Only one row is ever expanded at a
 * time. Long-press enters multi-select mode (checkmark overlay on the
 * avatar); the fragment owns the actual selection set and pushes it back in
 * via [setSelection].
 */
class VoicemailAdapter(
    private val onExpand: (VoicemailItem) -> Unit,
    private val onSeekStart: () -> Unit,
    private val onSeek: (positionMs: Int) -> Unit,
    private val onPlayPause: (VoicemailItem) -> Unit,
    private val onSpeakerToggle: (VoicemailItem) -> Unit,
    private val onShare: (VoicemailItem) -> Unit,
    private val onDownload: (VoicemailItem) -> Unit,
    private val onCall: (VoicemailItem) -> Unit,
    private val onMessage: (VoicemailItem) -> Unit,
    private val onBlock: (VoicemailItem) -> Unit,
    private val onDelete: (VoicemailItem) -> Unit,
    private val onLongPress: (VoicemailItem) -> Unit,
    private val onToggleSelect: (VoicemailItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val entries = ArrayList<VoicemailListEntry>()
    private var expandedId: Long = -1L
    private var playerState = VoicemailPlayerState()
    private var boundHolder: ItemVH? = null
    private var selectedIds: Set<Long> = emptySet()
    private var selectionMode = false

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemViewType(position: Int) =
        if (entries[position] is VoicemailListEntry.Header) TYPE_HEADER else TYPE_ITEM

    override fun getItemId(position: Int) = when (val e = entries[position]) {
        is VoicemailListEntry.Header -> -(e.label.hashCode().toLong() and 0x7FFFFFFFL) - 1
        is VoicemailListEntry.Item -> e.item.id
    }

    fun submit(list: List<VoicemailListEntry>) {
        entries.clear(); entries.addAll(list); notifyDataSetChanged()
    }

    /** Expand [id] (or -1 to collapse everything). Resets the shown player state
     *  to defaults; the fragment pushes real state in via [updateProgress]. */
    fun setExpanded(id: Long) {
        expandedId = id
        playerState = VoicemailPlayerState()
        notifyDataSetChanged()
    }

    fun updateProgress(id: Long, state: VoicemailPlayerState) {
        if (id != expandedId) return
        playerState = state
        boundHolder?.let { if (it.boundId == id) it.applyPlayerState(state) }
    }

    /** Pushes the current selection set/mode from the fragment; redraws. */
    fun setSelection(mode: Boolean, ids: Set<Long>) {
        selectionMode = mode
        selectedIds = ids
        notifyDataSetChanged()
    }

    override fun getItemCount() = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_voicemail_header, parent, false))
        } else {
            ItemVH(inflater.inflate(R.layout.item_voicemail, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val e = entries[position]) {
            is VoicemailListEntry.Header -> (holder as HeaderVH).bind(e.label)
            is VoicemailListEntry.Item -> {
                val first = position == 0 || entries[position - 1] is VoicemailListEntry.Header
                val last = position == entries.size - 1 || entries[position + 1] is VoicemailListEntry.Header
                (holder as ItemVH).bind(e.item, first, last)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (boundHolder === holder) boundHolder = null
    }

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val label = view as TextView
        fun bind(text: String) { label.text = text }
    }

    inner class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        var boundId: Long = -1L
            private set

        private val rowDivider: View = view.findViewById(R.id.rowDivider)
        private val row: View = view.findViewById(R.id.vmRow)
        private val avatarInitial: TextView = view.findViewById(R.id.avatarInitial)
        private val avatarPhoto: ShapeableImageView = view.findViewById(R.id.avatarPhoto)
        private val selectedCheck: ImageView = view.findViewById(R.id.selectedCheck)
        private val title: TextView = view.findViewById(R.id.vmTitle)
        private val subtitle: TextView = view.findViewById(R.id.vmSubtitle)
        private val unread: View = view.findViewById(R.id.vmUnread)
        private val expanded: View = view.findViewById(R.id.vmExpanded)
        private val playPill: MaterialButton = view.findViewById(R.id.vmPlayPill)
        private val seek: SeekBar = view.findViewById(R.id.vmSeek)
        private val elapsed: TextView = view.findViewById(R.id.vmElapsed)
        private val total: TextView = view.findViewById(R.id.vmTotal)
        private val speaker: ImageView = view.findViewById(R.id.vmSpeaker)
        private val share: ImageView = view.findViewById(R.id.vmShare)
        private val download: ImageView = view.findViewById(R.id.vmDownload)
        private val delete: ImageView = view.findViewById(R.id.vmDelete)
        private val actionCall: TextView = view.findViewById(R.id.vmActionCall)
        private val actionMessage: TextView = view.findViewById(R.id.vmActionMessage)
        private val actionBlock: TextView = view.findViewById(R.id.vmActionBlock)

        private var seeking = false

        init {
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar) {
                    seeking = true
                    onSeekStart()
                }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    seeking = false
                    onSeek(sb.progress)
                }
            })
        }

        fun bind(item: VoicemailItem, firstInGroup: Boolean, lastInGroup: Boolean) {
            boundId = item.id
            val ctx = title.context
            title.text = item.displayName
                ?: item.number.ifBlank { ctx.getString(R.string.unknown_caller) }

            val isExpanded = item.id == expandedId
            itemView.setBackgroundResource(
                when {
                    isExpanded -> R.drawable.bg_log_group_single
                    firstInGroup && lastInGroup -> R.drawable.bg_log_group_single
                    firstInGroup -> R.drawable.bg_log_group_top
                    lastInGroup -> R.drawable.bg_log_group_bottom
                    else -> R.drawable.bg_log_group_middle
                }
            )
            rowDivider.visibility = View.GONE

            val dur = formatDuration(item.durationSec)
            val time = android.text.format.DateUtils.formatDateTime(
                ctx, item.dateMillis, android.text.format.DateUtils.FORMAT_SHOW_TIME
            )
            subtitle.text = if (dur.isNotBlank()) "$dur • $time" else time
            unread.visibility = if (item.isRead) View.GONE else View.VISIBLE

            val isSelected = selectedIds.contains(item.id)
            if (selectionMode) {
                avatarInitial.visibility = if (isSelected) View.GONE else avatarInitial.visibility
                avatarPhoto.visibility = if (isSelected) View.GONE else avatarPhoto.visibility
                selectedCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                if (!isSelected) Avatars.bind(avatarInitial, avatarPhoto, item.displayName ?: item.number, item.photoUri)
            } else {
                selectedCheck.visibility = View.GONE
                Avatars.bind(avatarInitial, avatarPhoto, item.displayName ?: item.number, item.photoUri)
            }
            itemView.alpha = if (selectionMode && !isSelected) 0.85f else 1f

            row.setOnClickListener {
                if (selectionMode) onToggleSelect(item) else onExpand(item)
            }
            row.setOnLongClickListener { onLongPress(item); true }

            expanded.visibility = if (isExpanded && !selectionMode) View.VISIBLE else View.GONE
            if (isExpanded && !selectionMode) {
                boundHolder = this
                applyPlayerState(playerState)
                playPill.setOnClickListener { onPlayPause(item) }
                speaker.setOnClickListener { onSpeakerToggle(item) }
                share.setOnClickListener { onShare(item) }
                download.setOnClickListener { onDownload(item) }
                delete.setOnClickListener { onDelete(item) }
                actionCall.setOnClickListener { onCall(item) }
                actionMessage.setOnClickListener { onMessage(item) }
                actionBlock.setOnClickListener { onBlock(item) }
            } else if (boundHolder === this) {
                boundHolder = null
            }
        }

        fun applyPlayerState(state: VoicemailPlayerState) {
            playPill.setIconResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
            playPill.text = ctx().getString(if (state.playing) R.string.greeting_pause else R.string.vvm_play)
            seek.max = state.durationMs.coerceAtLeast(1)
            if (!seeking) seek.progress = state.positionMs.coerceIn(0, seek.max)
            elapsed.text = formatMs(state.positionMs)
            total.text = formatMs(state.durationMs)
            speaker.setImageResource(if (state.speakerOn) R.drawable.ic_speaker else R.drawable.ic_call_outline)
            speaker.contentDescription = ctx().getString(
                if (state.speakerOn) R.string.vvm_speaker else R.string.vvm_earpiece
            )
        }

        private fun ctx() = title.context

        private fun formatMs(ms: Int): String {
            val sec = (ms / 1000).coerceAtLeast(0)
            return String.format("%d:%02d", sec / 60, sec % 60)
        }

        private fun formatDuration(sec: Long): String {
            if (sec <= 0) return ""
            return String.format("%d:%02d", sec / 60, sec % 60)
        }
    }
}
