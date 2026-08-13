package com.puretech.dialer

import android.content.ContentUris
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.VoicemailContract.Voicemails
import android.telecom.TelecomManager
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.puretech.dialer.databinding.FragmentVoicemailBinding
import com.puretech.dialer.vvm.VvmConfig
import com.puretech.dialer.vvm.VvmPrefs
import com.puretech.dialer.vvm.VvmStore
import com.puretech.dialer.vvm.VvmSync
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Voicemail tab: while not yet activated, a centered chip lets the user turn
 * visual voicemail on (or retry after a failed attempt), alongside a chip to
 * just call the voicemail box directly. Once activated it lists voicemails the
 * carrier pushed to us (downloaded over IMAP by [VvmSync]), styled and grouped
 * exactly like this app's own call log (day-header cards). Tapping a row opens
 * a shade in place -- a pill-shaped Play button (playback only ever starts by
 * tapping it, never automatically), a seekbar, secondary controls, then
 * Call/Message/Block rows -- only one row is ever expanded at a time.
 * Long-press enters multi-select mode (a bar at the top of the list: cancel /
 * count / select-all / delete -- this tab owns that bar locally since, unlike
 * Recents, it has no shared chrome to borrow). Requires being the default
 * dialer on a carrier that offers OMTP VVM. Hosted by [HomeActivity] alongside
 * Recents/Dialer, so it follows the same show/hide-not-recreate lifecycle --
 * playback stops on [onHiddenChanged] rather than onStop, since this
 * fragment's view stays alive while backgrounded.
 */
class VoicemailFragment : Fragment() {

    private var _binding: FragmentVoicemailBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VoicemailAdapter
    private var player: MediaPlayer? = null
    private var expandedId: Long = -1L
    private var speakerOn = true

    private var selectionMode = false
    private val selectedIds = LinkedHashSet<Long>()
    private var allKnownIds: List<Long> = emptyList()

    private val tickHandler = Handler(Looper.getMainLooper())
    private var ticking = false
    private val tickRunnable = object : Runnable {
        override fun run() {
            val mp = player
            if (mp == null || expandedId == -1L) {
                ticking = false
                return
            }
            pushState(mp.isPlaying)
            if (mp.isPlaying) tickHandler.postDelayed(this, 200) else ticking = false
        }
    }

    // True once an activation attempt has come back unsuccessful, so the chip
    // reads "Retry" instead of "Activate" until the next app restart.
    private var attemptFailed = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVoicemailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.chipActivate.setOnClickListener { onActivateClicked() }
        binding.chipCallVoicemail.setOnClickListener { callVoicemail() }

        binding.selectionClose.setOnClickListener { exitSelection() }
        binding.selectionSelectAll.setOnClickListener { selectAll() }
        binding.selectionDelete.setOnClickListener { confirmDeleteSelected() }

        adapter = VoicemailAdapter(
            onExpand = { onExpand(it) },
            onSeekStart = { stopTicking() },
            onSeek = { onSeek(it) },
            onPlayPause = { onPlayPauseClicked(it) },
            onSpeakerToggle = { onSpeakerToggleClicked(it) },
            onShare = { shareVoicemail(it) },
            onDownload = { downloadVoicemail(it) },
            onCall = { callNumber(it.number) },
            onMessage = { messageNumber(it.number) },
            onBlock = { confirmBlock(it) },
            onDelete = { confirmDelete(it) },
            onLongPress = { enterSelection(it) },
            onToggleSelect = { toggleSelect(it) }
        )
        binding.voicemailList.layoutManager = LinearLayoutManager(requireContext())
        binding.voicemailList.adapter = adapter

    }

    override fun onDestroyView() {
        super.onDestroyView()
        collapse()
        _binding = null
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            collapse()
            exitSelection()
        }
    }

    // --- Host-facing API ---------------------------------------------------

    fun scrollTarget(): RecyclerView? = _binding?.voicemailList

    fun onTabResumed() {
        if (_binding == null) return
        refreshUi()
        load()
        if (VvmPrefs.enabled(requireContext())) syncNow()
    }

    /** Whether the multi-select bar is showing -- lets [HomeActivity] route
     *  the system back button to [exitSelection] instead of leaving the tab. */
    fun isSelecting(): Boolean = selectionMode

    // --- Activation state ----------------------------------------------------

    private fun refreshUi() {
        val ctx = requireContext()
        val cfg = VvmConfig.read(ctx)
        val supported = cfg?.isSupported == true
        val enabled = VvmPrefs.enabled(ctx)

        binding.listState.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.activateState.visibility = if (enabled) View.GONE else View.VISIBLE
        if (enabled) return

        binding.status.text = when {
            !supported -> getString(R.string.vvm_unsupported)
            else -> getString(R.string.vvm_enable_sub)
        }
        binding.activateChips.visibility = if (supported) View.VISIBLE else View.GONE
        binding.chipActivate.text = getString(if (attemptFailed) R.string.vvm_retry else R.string.vvm_activate)
    }

    private fun onActivateClicked() {
        val ctx = requireContext().applicationContext
        binding.chipActivate.isEnabled = false
        Thread {
            val ok = VvmSync.enable(ctx)
            ui {
                binding.chipActivate.isEnabled = true
                attemptFailed = !ok
                if (!ok) Toast.makeText(requireContext(), R.string.vvm_enable_failed, Toast.LENGTH_LONG).show()
                refreshUi()
                load()
            }
        }.start()
    }

    /** Syncs with the carrier in the background -- called whenever this tab
     *  is opened, since there's no manual refresh action anymore. */
    private fun syncNow() {
        val ctx = requireContext().applicationContext
        Thread {
            try {
                VvmSync.sync(ctx)
            } catch (_: Throwable) {
                // sync() already catches internally, but this is the UI's
                // own entry point onto that background thread -- don't let
                // any future exception here crash the whole dialer either.
            }
            ui { load() }
        }.start()
    }

    // --- List ------------------------------------------------------------------

    private fun load() {
        if (!VvmPrefs.enabled(requireContext())) return
        val ctx = requireContext().applicationContext
        Thread {
            val items = query(ctx)
            ui {
                adapter.submit(grouped(items))
                binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                allKnownIds = items.map { it.id }
                if (selectionMode) {
                    // Drop selections for voicemails that no longer exist
                    // (e.g. deleted from another device) rather than leaving
                    // the count/selection out of sync with reality.
                    val known = allKnownIds.toSet()
                    if (selectedIds.retainAll(known)) {
                        if (selectedIds.isEmpty()) exitSelection() else pushSelection()
                    }
                }
            }
        }.start()
    }

    /** Buckets voicemails under "Today" / "Yesterday" / a formatted date
     *  header, matching the real Google Dialer voicemail list's grouping. */
    private fun grouped(items: List<VoicemailItem>): List<VoicemailListEntry> {
        val out = ArrayList<VoicemailListEntry>()
        var lastLabel: String? = null
        for (item in items) {
            val label = dateGroupLabel(item.dateMillis)
            if (label != lastLabel) {
                out.add(VoicemailListEntry.Header(label))
                lastLabel = label
            }
            out.add(VoicemailListEntry.Item(item))
        }
        return out
    }

    private fun dateGroupLabel(dateMillis: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = dateMillis }
        return when {
            DateUtils.isToday(dateMillis) -> getString(R.string.date_group_today)
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1 ->
                getString(R.string.date_group_yesterday)
            else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(dateMillis)
        }
    }

    private fun query(ctx: android.content.Context): List<VoicemailItem> {
        val out = ArrayList<VoicemailItem>()
        val uri = Voicemails.buildSourceUri(ctx.packageName)
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    Voicemails._ID, Voicemails.NUMBER, Voicemails.DATE,
                    Voicemails.DURATION, Voicemails.IS_READ, Voicemails.HAS_CONTENT
                ),
                null, null, "${Voicemails.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(1).orEmpty()
                    out.add(
                        VoicemailItem(
                            id = c.getLong(0),
                            number = number,
                            displayName = ContactsRepository.displayName(ctx, number),
                            photoUri = ContactsRepository.photoUri(ctx, number),
                            dateMillis = c.getLong(2),
                            durationSec = c.getLong(3),
                            isRead = c.getInt(4) != 0,
                            hasContent = c.getInt(5) != 0
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    // --- Expand / playback ------------------------------------------------

    private fun onExpand(item: VoicemailItem) {
        if (item.id == expandedId) {
            collapse()
            return
        }
        collapse()
        expandedId = item.id
        speakerOn = true
        adapter.setExpanded(item.id)
        // Deliberately no auto-play: the shade just shows controls (seekbar
        // at 0:00, the pill reading "Play") until the user taps Play.
        if (!item.isRead) markRead(item.id)
    }

    private fun collapse() {
        stopTicking()
        stopPlayer()
        expandedId = -1L
        if (_binding != null) adapter.setExpanded(-1L)
    }

    private fun startPlayback(item: VoicemailItem) {
        if (!item.hasContent) {
            Toast.makeText(requireContext(), R.string.vvm_no_audio, Toast.LENGTH_SHORT).show()
            collapse()
            return
        }
        try {
            val uri = ContentUris.withAppendedId(Voicemails.CONTENT_URI, item.id)
            val mp = buildPlayer(uri)
            mp.start()
            player = mp
            applyRouting()
            pushState(playing = true)
            startTicking()
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.vvm_play_failed, Toast.LENGTH_SHORT).show()
            collapse()
        }
    }

    private fun buildPlayer(uri: Uri, seekToMs: Int = 0): MediaPlayer {
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (speakerOn) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mp.setDataSource(requireContext(), uri)
        mp.setOnCompletionListener { onPlaybackComplete() }
        mp.prepare()
        if (seekToMs > 0) mp.seekTo(seekToMs)
        return mp
    }

    private fun applyRouting() {
        requireContext().getSystemService(AudioManager::class.java)?.isSpeakerphoneOn = speakerOn
    }

    private fun onPlayPauseClicked(item: VoicemailItem) {
        val mp = player
        if (mp == null) {
            startPlayback(item)
            return
        }
        if (mp.isPlaying) {
            mp.pause()
            stopTicking()
            pushState(playing = false)
        } else {
            mp.start()
            startTicking()
            pushState(playing = true)
        }
    }

    private fun shareVoicemail(item: VoicemailItem) {
        val ctx = requireContext().applicationContext
        Thread {
            try {
                val uri = ContentUris.withAppendedId(Voicemails.CONTENT_URI, item.id)
                val dir = File(ctx.cacheDir, "voicemail_share").apply { mkdirs() }
                val out = File(dir, "voicemail_${item.id}.amr")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                } ?: return@Thread
                val shareUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/amr"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ui { startActivity(Intent.createChooser(intent, getString(R.string.vvm_share))) }
            } catch (_: Exception) {
                ui { Toast.makeText(requireContext(), R.string.vvm_play_failed, Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun downloadVoicemail(item: VoicemailItem) {
        val ctx = requireContext().applicationContext
        Thread {
            try {
                val uri = ContentUris.withAppendedId(Voicemails.CONTENT_URI, item.id)
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(item.dateMillis)
                val out = File(dir, "Voicemail_$stamp.amr")
                val ok = ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                    true
                } ?: false
                if (!ok) {
                    ui { Toast.makeText(requireContext(), R.string.vvm_download_failed, Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), null, null)
                ui { Toast.makeText(requireContext(), R.string.vvm_downloaded, Toast.LENGTH_SHORT).show() }
            } catch (_: Exception) {
                ui { Toast.makeText(requireContext(), R.string.vvm_download_failed, Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun onSpeakerToggleClicked(item: VoicemailItem) {
        val mp = player ?: return
        val wasPlaying = mp.isPlaying
        val posMs = mp.currentPosition
        speakerOn = !speakerOn
        stopTicking()
        runCatching { mp.stop() }
        runCatching { mp.release() }
        player = null
        try {
            val uri = ContentUris.withAppendedId(Voicemails.CONTENT_URI, item.id)
            val np = buildPlayer(uri, posMs)
            player = np
            applyRouting()
            if (wasPlaying) {
                np.start()
                startTicking()
            }
            pushState(playing = wasPlaying)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.vvm_play_failed, Toast.LENGTH_SHORT).show()
            pushState(playing = false)
        }
    }

    private fun onSeek(posMs: Int) {
        val mp = player ?: return
        runCatching { mp.seekTo(posMs) }
        pushState(playing = mp.isPlaying)
        if (mp.isPlaying) startTicking()
    }

    private fun onPlaybackComplete() {
        stopTicking()
        runCatching { player?.seekTo(0) }
        pushState(playing = false)
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        tickHandler.post(tickRunnable)
    }

    private fun stopTicking() {
        ticking = false
        tickHandler.removeCallbacks(tickRunnable)
    }

    private fun stopPlayer() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }

    private fun pushState(playing: Boolean) {
        val mp = player ?: return
        if (_binding == null || expandedId == -1L) return
        val pos = runCatching { mp.currentPosition }.getOrDefault(0)
        val dur = runCatching { mp.duration }.getOrDefault(0).coerceAtLeast(0)
        adapter.updateProgress(
            expandedId,
            VoicemailPlayerState(
                playing = playing, positionMs = pos, durationMs = dur, speakerOn = speakerOn
            )
        )
    }

    private fun markRead(id: Long) {
        val ctx = requireContext().applicationContext
        Thread { VvmStore.markRead(ctx, id) }.start()
    }

    private fun confirmDelete(item: VoicemailItem) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.vvm_delete_confirm)
            .setPositiveButton(R.string.vvm_delete) { _, _ ->
                if (expandedId == item.id) collapse()
                val ctx = requireContext().applicationContext
                Thread {
                    VvmSync.deleteVoicemail(ctx, item.id)
                    ui { load() }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun callNumber(number: String) {
        val ctx = requireContext()
        Dialer.place(ctx, Dialer.normalize(ctx, number))
    }

    private fun messageNumber(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")))
        } catch (_: Exception) {
        }
    }

    private fun confirmBlock(item: VoicemailItem) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.vvm_block_confirm)
            .setPositiveButton(R.string.block_number) { _, _ -> blockNumber(item.number) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun blockNumber(number: String) {
        val ctx = requireContext().applicationContext
        Thread {
            BlockedNumbers.add(ctx, number)
            ui { Toast.makeText(requireContext(), R.string.number_blocked, Toast.LENGTH_SHORT).show() }
        }.start()
    }

    // --- Multi-select --------------------------------------------------------

    private fun enterSelection(item: VoicemailItem) {
        collapse()
        selectionMode = true
        selectedIds.clear()
        selectedIds.add(item.id)
        showSelectionBar()
        pushSelection()
    }

    private fun toggleSelect(item: VoicemailItem) {
        if (!selectedIds.remove(item.id)) selectedIds.add(item.id)
        if (selectedIds.isEmpty()) exitSelection() else pushSelection()
    }

    private fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(allKnownIds)
        pushSelection()
    }

    fun exitSelection() {
        if (!selectionMode) return
        selectionMode = false
        selectedIds.clear()
        if (_binding == null) return
        binding.selectionBar.visibility = View.GONE
        adapter.setSelection(false, emptySet())
    }

    private fun showSelectionBar() {
        if (_binding == null) return
        binding.selectionBar.visibility = View.VISIBLE
    }

    private fun pushSelection() {
        if (_binding == null) return
        binding.selectionCount.text = getString(R.string.selection_count_fmt, selectedIds.size)
        adapter.setSelection(true, selectedIds.toSet())
    }

    private fun confirmDeleteSelected() {
        if (selectedIds.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.vvm_delete_selected_confirm)
            .setPositiveButton(R.string.vvm_delete) { _, _ -> deleteSelected() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteSelected() {
        val ids = selectedIds.toList()
        val ctx = requireContext().applicationContext
        Thread {
            for (id in ids) VvmSync.deleteVoicemail(ctx, id)
            ui {
                exitSelection()
                load()
            }
        }.start()
    }

    private fun callVoicemail() {
        try {
            requireContext().getSystemService(TelecomManager::class.java)
                ?.placeCall(Uri.fromParts("voicemail", "", null), Dialer.accountExtras(requireContext()))
        } catch (_: Exception) {
        }
    }

    /** Run [block] on the UI thread only if the view is still alive. */
    private fun ui(block: () -> Unit) {
        _binding?.root?.post { if (_binding != null) block() }
    }
}
