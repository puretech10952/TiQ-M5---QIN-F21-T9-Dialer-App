package com.puretech.dialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telephony.PhoneNumberUtils
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.puretech.dialer.databinding.ActivityIncallBinding

/**
 * The in-call screen. Single + multi-call (hold/swap/merge/add) and call
 * waiting. Active controls collapse to Mute / Route / Keypad / More, with the
 * rest (record, add, hold, merge) in the More overflow. A proximity wake lock
 * blanks the screen at the ear during earpiece calls.
 */
class InCallActivity : AppCompatActivity(), CallManager.Listener {

    private lateinit var binding: ActivityIncallBinding
    private val audioManager by lazy { getSystemService(android.media.AudioManager::class.java) }

    private val preview by lazy { intent.getBooleanExtra(EXTRA_PREVIEW, false) }
    private val previewIncoming by lazy { intent.getBooleanExtra(EXTRA_PREVIEW_INCOMING, false) }
    private val isPreview get() = preview || previewIncoming

    private val handler = Handler(Looper.getMainLooper())
    private var timing = false
    // Fallback call-start clock for devices whose telecom returns connectTimeMillis == 0.
    private var activeSinceRealtime = 0L
    private val dtmfBuffer = StringBuilder()
    // True once any call reached ACTIVE, so we can tell a real hang-up from a
    // call that never connected (e.g. no service).
    private var everConnected = false
    // While showing "Server unreachable", hold the screen open for 2s before it closes.
    private var errorFinishPending = false

    private val recordPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startRecording() else toast(getString(R.string.rec_denied)) }

    private val ticker = object : Runnable {
        override fun run() {
            updateDuration()
            handler.postDelayed(this, 1000)
        }
    }

    private var holdTiming = false
    private val holdTicker = object : Runnable {
        override fun run() {
            updateRemoteHoldBanner()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIncallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        binding.btnEnd.setOnClickListener { CallManager.hangup() }
        binding.btnAnswer.setOnClickListener { CallManager.answer() }
        binding.btnDecline.setOnClickListener { CallManager.reject() }
        binding.swipeIncoming.onAnswer = { CallManager.answer() }
        binding.swipeIncoming.onDecline = { CallManager.reject() }
        binding.btnMute.setOnClickListener { toggleMute() }
        binding.btnRoute.setOnClickListener { onRouteClick() }
        // The first control is the in-call keypad (DTMF for hotline menus).
        binding.btnKeypad.setOnClickListener { toggleDtmf() }
        binding.btnMore.setOnClickListener { showMoreMenu(it) }
        binding.btnIncomingMessage.setOnClickListener { showReplyOptions(CallManager.ringingCall()) }

        binding.secondaryStrip.setOnClickListener { CallManager.swap() }
        binding.btnWaitDecline.setOnClickListener { CallManager.reject(CallManager.waitingCall()) }
        binding.btnWaitHold.setOnClickListener { CallManager.answerAndHold() }
        binding.btnWaitEnd.setOnClickListener { CallManager.answerAndEnd() }
        binding.btnWaitMessage.setOnClickListener { showReplyOptions(CallManager.waitingCall()) }

        setupDtmfPad()
        binding.btnDtmfCollapse.setOnClickListener { toggleDtmf() }
        // Vibrant green echo waves for incoming calls (matches the Answer button).
        binding.pulseRing.setRingColor(0xFF00C853.toInt())

        if (isPreview) renderPreview()
    }

    override fun onStart() {
        super.onStart()
        if (!isPreview) CallManager.registerListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (!isPreview) {
            CallManager.uiVisible = true
            // Full-screen call UI is up → remove any notification entirely. It is
            // re-posted from onStop only if we get backgrounded.
            CallNotifier.cancel(this)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isPreview) {
            CallManager.unregisterListener(this)
            // Left the call screen → bring the (ongoing) notification back.
            CallManager.uiVisible = false
            if (CallManager.call != null) CallNotifier.update(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        stopHoldTimer()
        // NOTE: do not stop recording here — the framework keeps recording with the
        // call and stops on its own when the call ends.
    }

    // --- Hardware keys ---------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_CALL) {
            CallManager.ringingCall()?.let { CallManager.answer(it) }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.dtmfOverlay.visibility == View.VISIBLE) {
            toggleDtmf()
            return true
        }
        // On these keypad phones (no volume rocker) the D-pad up/down adjusts the
        // in-call volume. Only active here — i.e. only on the call screen — and it
        // keeps working with the screen blanked by the proximity sensor, since the
        // proximity wake lock keeps input flowing to this activity. We pick the
        // stream that matches the current audio route so it also works on speaker
        // (same voice-call stream) and on Bluetooth (separate SCO volume).
        if (!isPreview && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            val dir = if (keyCode == KeyEvent.KEYCODE_DPAD_UP)
                android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
            // Earpiece/speaker share the voice-call stream; Bluetooth uses the SCO
            // stream (value 6, hidden in the SDK) so the headset volume changes too.
            val stream = if (CallManager.currentRoute() == CallAudioState.ROUTE_BLUETOOTH) 6
            else android.media.AudioManager.STREAM_VOICE_CALL
            try {
                audioManager?.adjustStreamVolume(stream, dir, android.media.AudioManager.FLAG_SHOW_UI)
            } catch (_: Exception) {
            }
            return true
        }
        val dtmf = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0))
            KeyEvent.KEYCODE_STAR -> '*'
            KeyEvent.KEYCODE_POUND -> '#'
            else -> null
        }
        if (dtmf != null) {
            onDtmf(dtmf)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // --- State machine ---------------------------------------------------------

    override fun onCallChanged() {
        val ringing = CallManager.ringingCall()
        val active = CallManager.activeCall()
        val held = CallManager.heldCall()
        val waiting = CallManager.waitingCall()

        // A 2nd (waiting) call takes over the screen like a normal incoming call, so
        // it drives the big caller display; the ongoing call is shown small below.
        val headerCall = if (waiting != null) waiting else CallManager.primaryCall()
        if (headerCall == null && !isPreview) {
            // If we're showing "Server unreachable", let the 2s timer close us.
            if (!errorFinishPending) finishAndStopRecording()
            return
        }
        headerCall?.let { bindIdentity(it) }
        headerCall?.let { updateStatusFor(it) }
        binding.hdIcon.visibility =
            if (headerCall != null && CallManager.isHighDef(headerCall)) View.VISIBLE else View.GONE
        @Suppress("DEPRECATION")
        binding.wifiIcon.visibility =
            if (headerCall?.details?.hasProperty(Call.Details.PROPERTY_WIFI) == true)
                View.VISIBLE else View.GONE

        when {
            waiting != null -> {
                // Full-screen incoming look: the big header already shows the waiting
                // caller, so hide the redundant line, pulse the avatar, and show the
                // ongoing call small in the secondary strip (not tappable here).
                show(binding.waitingPanel)
                hide(binding.bottomPanel, binding.incomingControls, binding.swipeIncomingPanel,
                    binding.btnIncomingMessage)
                binding.waitingText.visibility = View.GONE
                val ongoing = active ?: held
                if (ongoing != null) {
                    binding.secondaryStrip.visibility = View.VISIBLE
                    binding.secondaryStrip.isClickable = false
                    val status = if (ongoing == held) getString(R.string.on_hold)
                    else getString(R.string.on_call_ongoing)
                    binding.secondaryText.text = "${nameFor(ongoing)} • $status"
                } else {
                    binding.secondaryStrip.visibility = View.GONE
                }
                binding.pulseRing.start()
            }
            ringing != null -> {
                showIncomingControls()
                show(binding.btnIncomingMessage)
                hide(binding.bottomPanel,
                    binding.waitingPanel, binding.secondaryStrip)
                // Echo waves radiating from the avatar while the phone rings.
                binding.pulseRing.start()
            }
            else -> {
                // Keep the panel hidden while the in-call keypad is open.
                binding.bottomPanel.visibility =
                    if (binding.dtmfOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                hide(binding.incomingControls, binding.swipeIncomingPanel,
                    binding.btnIncomingMessage, binding.waitingPanel)
                binding.pulseRing.stop()
                bindControlStates()
                bindSecondaryStrip(held)
            }
        }
        // Keep the REC indicator in sync (e.g. if the framework rejects recording).
        updateRecordIndicator()

        updateRemoteHoldBanner()
    }

    /** Remote hold chip: visible only when the other party has held this call.
     *  Shows "[Name] has placed you on hold · MM:SS" with its own running timer —
     *  the regular statusText keeps counting the overall call duration untouched. */
    private fun updateRemoteHoldBanner() {
        val remoteHold = CallManager.isRemoteHold()
        binding.remoteHoldBanner.visibility = if (remoteHold) View.VISIBLE else View.GONE
        if (remoteHold) {
            // Remote hold keeps the call at STATE_ACTIVE (no STATE_HOLDING transition),
            // so heldCall() won't find it — fall back to the active call for the name.
            val other = CallManager.heldCall() ?: CallManager.activeCall()
            val name = other?.let { nameFor(it) } ?: ""
            val base = if (name.isNotBlank())
                getString(R.string.remote_hold_named, name)
            else
                getString(R.string.remote_hold_unknown)
            val elapsed = formatHoldDuration()
            binding.remoteHoldText.text = if (elapsed.isNotEmpty()) "$base · $elapsed" else base
            startHoldTimer()
        } else {
            stopHoldTimer()
        }
    }

    /** Show either the slide-to-answer control or the round Answer/Decline
     *  buttons for an incoming call, depending on the user's setting. */
    private fun showIncomingControls() {
        if (Prefs.swipeToAnswer(this)) {
            binding.swipeIncoming.reset()
            show(binding.swipeIncomingPanel)
            hide(binding.incomingControls)
        } else {
            show(binding.incomingControls)
            hide(binding.swipeIncomingPanel)
        }
    }

    private fun bindSecondaryStrip(held: Call?) {
        if (held != null) {
            binding.secondaryStrip.visibility = View.VISIBLE
            binding.secondaryStrip.isClickable = true   // tap to swap (re-enabled after call-waiting)
            binding.secondaryText.text = "${nameFor(held)} • ${getString(R.string.on_hold)}"
        } else {
            binding.secondaryStrip.visibility = View.GONE
        }
    }

    // --- More / route popups ---------------------------------------------------

    private fun showMoreMenu(anchor: View) {
        val menu = CardMenu(this, anchor)
        menu.add(
            MENU_RECORD,
            if (CallManager.recording) R.drawable.ic_mic_off else R.drawable.ic_record,
            getString(if (CallManager.recording) R.string.ctl_stop_record else R.string.ctl_record)
        )
        menu.add(MENU_ADD, R.drawable.ic_add_call, getString(R.string.ctl_add))
        // Two calls → Swap; a single locally-held call → Resume; active → Hold.
        // When the remote party is holding us, we can't act on hold state — omit the item.
        if (!CallManager.isRemoteHold()) {
            val (holdIcon, holdLabel) = when {
                CallManager.activeCall() != null && CallManager.heldCall() != null ->
                    R.drawable.ic_swap to R.string.ctl_swap
                CallManager.heldCall() != null -> R.drawable.ic_play to R.string.ctl_resume
                else -> R.drawable.ic_hold to R.string.ctl_hold
            }
            menu.add(MENU_HOLD, holdIcon, getString(holdLabel))
        }
        if (CallManager.activeCall() != null && CallManager.heldCall() != null) {
            menu.add(MENU_MERGE, R.drawable.ic_merge, getString(R.string.ctl_merge))
        }
        // In a conference, let the user manage / drop individual participants.
        if (CallManager.conferenceChildren().size > 1) {
            menu.add(MENU_MANAGE_CONF, R.drawable.ic_merge, getString(R.string.ctl_manage_conference))
        }
        menu.onClick { id ->
            when (id) {
                MENU_RECORD -> toggleRecord()
                MENU_ADD -> addCall()
                MENU_HOLD -> toggleHoldOrSwap()
                MENU_MERGE -> CallManager.merge()
                MENU_MANAGE_CONF -> showManageConference()
            }
        }
        // Highlight the 3-dot button (black icon on the light pill) while open.
        setActive(binding.btnMore, true)
        menu.onDismiss = { setActive(binding.btnMore, false) }
        menu.show()
    }

    /** List the conference participants; tap the end button to drop one. */
    private fun showManageConference() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
        }
        val title = android.widget.TextView(this).apply {
            text = getString(R.string.ctl_manage_conference)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
        }
        container.addView(title)

        fun rebuild() {
            // Drop everything after the title and re-list current participants.
            while (container.childCount > 1) container.removeViewAt(1)
            val kids = CallManager.conferenceChildren()
            if (kids.size <= 1) { sheet.dismiss(); return }
            for (child in kids) {
                val row = layoutInflater.inflate(R.layout.item_conference_party, container, false)
                row.findViewById<android.widget.TextView>(R.id.partyName).text = nameFor(child)
                row.findViewById<View>(R.id.partyEnd).setOnClickListener {
                    CallManager.disconnectParticipant(child)
                    row.postDelayed({ rebuild() }, 300)
                }
                container.addView(row)
            }
        }
        rebuild()
        sheet.setContentView(container)
        sheet.show()
    }

    private fun onRouteClick() {
        if (CallManager.isBluetoothAvailable()) {
            showRouteMenu(binding.btnRoute)
        } else {
            CallManager.setSpeaker(!CallManager.isSpeakerOn())
            bindControlStates()
        }
    }

    private fun showRouteMenu(anchor: View) {
        val mask = CallManager.supportedRouteMask()
        val current = CallManager.currentRoute()
        val menu = CardMenu(this, anchor)
        if (mask and CallAudioState.ROUTE_EARPIECE != 0)
            menu.add(
                CallAudioState.ROUTE_EARPIECE, R.drawable.ic_call_outline,
                getString(R.string.route_earpiece), current == CallAudioState.ROUTE_EARPIECE
            )
        if (mask and CallAudioState.ROUTE_SPEAKER != 0)
            menu.add(
                CallAudioState.ROUTE_SPEAKER, R.drawable.ic_speaker,
                getString(R.string.route_speaker), current == CallAudioState.ROUTE_SPEAKER
            )
        if (mask and CallAudioState.ROUTE_BLUETOOTH != 0)
            menu.add(
                CallAudioState.ROUTE_BLUETOOTH, R.drawable.ic_bluetooth,
                CallManager.bluetoothDeviceName() ?: getString(R.string.route_bluetooth),
                current == CallAudioState.ROUTE_BLUETOOTH
            )
        menu.onClick { id ->
            CallManager.setRoute(id)
            bindControlStates()
        }
        menu.show()
    }

    private fun toggleHoldOrSwap() {
        if (CallManager.hasHeldCall()) CallManager.swap() else CallManager.hold()
    }

    private fun addCall() {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .putExtra(HomeActivity.EXTRA_START_TAB, HomeActivity.TAB_DIALER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // --- Reply with message ----------------------------------------------------

    private fun showReplyOptions(target: Call?) {
        val call = target ?: return
        val responses = Prefs.quickResponses(this).toTypedArray()
        val items = (responses.toList() + getString(R.string.reply_custom)).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.reply_title)
            .setItems(items) { _, which ->
                if (which < responses.size) CallManager.rejectWithMessage(responses[which], call)
                else showCustomReply(call)
            }
            .show()
    }

    private fun showCustomReply(call: Call) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setHint(R.string.reply_title)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reply_custom)
            .setView(input)
            .setPositiveButton(R.string.reply_send) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) CallManager.rejectWithMessage(text, call)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Identity --------------------------------------------------------------

    private fun bindIdentity(call: Call) {
        // A conference shows a single "Conference call" identity with the head count.
        if (CallManager.isConference()) {
            val n = CallManager.conferenceChildren().size
            binding.nameText.text = getString(R.string.conference_call)
            binding.numberText.text =
                if (n > 0) resources.getQuantityString(R.plurals.conference_people, n, n) else ""
            binding.numberText.visibility = if (n > 0) View.VISIBLE else View.GONE
            Avatars.bind(binding.avatarInitial, binding.contactPhoto, null, null)
            return
        }
        val number = CallManager.number(call)
        val pretty = prettyNumber(number)
        val (name, photo) = lookupContact(number)
        val sim = call.details.accountHandle
            ?.takeIf { CallingAccounts.isMultiSim(this) }
            ?.let { CallingAccounts.label(this, it) }
        if (name != null) {
            binding.nameText.text = name
            binding.numberText.text = if (sim != null) "$pretty • $sim" else pretty
            binding.numberText.visibility = View.VISIBLE
        } else {
            binding.nameText.text = pretty.ifBlank { getString(R.string.app_name) }
            binding.numberText.text = sim ?: ""
            binding.numberText.visibility = if (sim != null) View.VISIBLE else View.GONE
        }
        // Big colored-initial circle (or photo / grey person), like the call log.
        Avatars.bind(binding.avatarInitial, binding.contactPhoto, name, photo)
    }

    private fun nameFor(call: Call): String {
        val number = CallManager.number(call)
        return lookupContact(number).first ?: number.ifBlank { getString(R.string.app_name) }
    }

    /** Show the number clearly with dashes, e.g. 845-351-1200 or +1 845-351-1200. */
    private fun prettyNumber(number: String): String {
        if (number.isBlank()) return number
        // Leave MMI/USSD codes untouched.
        if (number.any { it == '*' || it == '#' }) return number
        val digits = number.filter { it.isDigit() }
        return when {
            digits.length == 10 ->
                "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6)}"
            digits.length == 11 && digits.startsWith("1") ->
                "+1 ${digits.substring(1, 4)}-${digits.substring(4, 7)}-${digits.substring(7)}"
            else -> PhoneNumberUtils.formatNumber(number, java.util.Locale.US.country) ?: number
        }
    }

    private fun lookupContact(number: String): Pair<String?, Uri?> {
        if (number.isBlank()) return null to null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null to null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        return try {
            contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0) to (c.getString(1)?.let { Uri.parse(it) })
                } else null to null
            } ?: (null to null)
        } catch (e: SecurityException) {
            null to null
        }
    }

    // --- Audio / record controls ----------------------------------------------

    private fun toggleMute() {
        CallManager.setMuted(!CallManager.isMuted())
        bindControlStates()
    }

    private fun toggleDtmf() {
        val show = binding.dtmfOverlay.visibility != View.VISIBLE
        binding.dtmfOverlay.visibility = if (show) View.VISIBLE else View.GONE
        binding.avatar.visibility = if (show) View.GONE else View.VISIBLE
        // Hide the whole bottom panel (controls + End) so the keypad can run to the
        // bottom edge. Close it with the collapse chevron to get the End button back.
        binding.bottomPanel.visibility = if (show) View.GONE else View.VISIBLE
        setActive(binding.btnKeypad, show)
    }

    private fun bindControlStates() {
        val muted = CallManager.isMuted()
        binding.btnMute.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        setActive(binding.btnMute, muted)
        binding.lblMute.text = getString(if (muted) R.string.ctl_unmute else R.string.ctl_mute)
        // Keypad button reflects whether the DTMF pad is open.
        setActive(binding.btnKeypad, binding.dtmfOverlay.visibility == View.VISIBLE)
        bindRouteButton()
    }

    private fun bindRouteButton() {
        val route = CallManager.currentRoute()
        if (CallManager.isBluetoothAvailable()) {
            binding.btnRoute.setImageResource(R.drawable.ic_bluetooth)
            // Show the headset's actual name when routed to it, else the generic label.
            binding.lblRoute.text =
                if (route == CallAudioState.ROUTE_BLUETOOTH)
                    CallManager.bluetoothDeviceName() ?: getString(R.string.route_bluetooth)
                else getString(R.string.route_bluetooth)
            setActive(binding.btnRoute, route == CallAudioState.ROUTE_BLUETOOTH)
        } else {
            binding.btnRoute.setImageResource(R.drawable.ic_speaker)
            binding.lblRoute.setText(R.string.route_speaker)
            setActive(binding.btnRoute, route == CallAudioState.ROUTE_SPEAKER)
        }
    }

    private fun setActive(view: ImageView, active: Boolean) {
        view.background = ContextCompat.getDrawable(
            this, if (active) R.drawable.bg_circle_control_active else R.drawable.bg_circle_control
        )
        view.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (active) R.color.incall_control_active_icon else R.color.incall_control_icon
            )
        )
    }

    /** Start/stop call recording from the More menu, updating the red indicator.
     *  Recording is performed by the telephony framework (both sides). */
    private fun toggleRecord() {
        if (CallManager.recording) {
            CallManager.stopRecording()
            toast(getString(R.string.rec_saved))
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            recordPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        updateRecordIndicator()
    }

    private fun startRecording() {
        val ok = CallManager.startRecording()
        toast(getString(if (ok) R.string.rec_started else R.string.rec_failed))
        updateRecordIndicator()
    }

    /** Show a small blinking red dot at the top-left while recording. */
    private fun updateRecordIndicator() {
        val on = CallManager.recording
        binding.recordIndicator.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            if (binding.recordIndicator.animation == null) {
                binding.recordIndicator.startAnimation(
                    android.view.animation.AlphaAnimation(1f, 0.2f).apply {
                        duration = 700
                        repeatMode = android.view.animation.Animation.REVERSE
                        repeatCount = android.view.animation.Animation.INFINITE
                    }
                )
            }
        } else {
            binding.recordIndicator.clearAnimation()
        }
    }

    // --- DTMF ------------------------------------------------------------------

    private fun setupDtmfPad() {
        // Keys are now LinearLayout cells (digit + letters) like the dialer pad, so
        // walk the rows and wire each cell by the digit in its first TextView.
        wireDtmfKeys(binding.dtmfPad)
    }

    private fun wireDtmfKeys(group: android.view.ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) as? android.view.ViewGroup ?: continue
            val ch = (child.getChildAt(0) as? android.widget.TextView)
                ?.text?.toString()?.firstOrNull()
            if (ch != null && (ch.isDigit() || ch == '*' || ch == '#')) {
                child.setOnClickListener { onDtmf(ch) }
            } else {
                wireDtmfKeys(child)   // recurse into the row
            }
        }
    }

    private fun onDtmf(c: Char) {
        CallManager.playDtmf(c)
        dtmfBuffer.append(c)
        binding.dtmfDigits.text = dtmfBuffer.toString()
        binding.dtmfDigits.visibility = View.VISIBLE
    }

    // Proximity screen-off is owned by ProximityController for the whole call
    // (works on any screen, not just here), driven by CallService/CallManager.

    // --- Status / duration -----------------------------------------------------

    @Suppress("DEPRECATION")
    private fun updateStatusFor(call: Call) {
        // Note: remote hold does NOT interrupt this — the call stays STATE_ACTIVE the
        // whole time on this telephony stack, so the duration timer below keeps running
        // uninterrupted. The remote-hold chip (with its own timer) is driven separately
        // by updateRemoteHoldBanner()/holdTicker.
        when (call.state) {
            Call.STATE_ACTIVE -> {
                everConnected = true
                // Start the fallback clock the first moment the call is active.
                if (activeSinceRealtime == 0L) activeSinceRealtime = android.os.SystemClock.elapsedRealtime()
                binding.statusText.text = formatDuration(elapsedMs()); startTimer()
            }
            Call.STATE_DIALING, Call.STATE_PULLING_CALL -> { stopTimer(); binding.statusText.setText(R.string.state_dialing) }
            Call.STATE_RINGING -> { stopTimer(); binding.statusText.setText(R.string.state_ringing) }
            Call.STATE_HOLDING -> { stopTimer(); binding.statusText.setText(R.string.state_holding) }
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                stopTimer()
                if (isConnectFailure(call)) {
                    // Couldn't reach the network: show the reason and hold 2s, then close.
                    binding.statusText.setText(R.string.state_unreachable)
                    if (!errorFinishPending) {
                        errorFinishPending = true
                        handler.postDelayed({ finishAndStopRecording() }, 2000)
                    }
                } else {
                    binding.statusText.setText(R.string.state_disconnected)
                }
            }
            else -> { stopTimer(); binding.statusText.setText(R.string.state_connecting) }
        }
    }

    /** A call that ended with an error before it ever connected — e.g. no service. */
    @Suppress("DEPRECATION")
    private fun isConnectFailure(call: Call): Boolean {
        if (everConnected) return false
        val code = call.details?.disconnectCause?.code ?: return false
        return code == android.telecom.DisconnectCause.ERROR ||
            code == android.telecom.DisconnectCause.BUSY
    }

    private fun startTimer() {
        if (!timing) { timing = true; handler.post(ticker) }
    }

    private fun stopTimer() {
        timing = false
        handler.removeCallbacks(ticker)
    }

    private fun startHoldTimer() {
        if (!holdTiming) { holdTiming = true; handler.post(holdTicker) }
    }

    private fun stopHoldTimer() {
        holdTiming = false
        handler.removeCallbacks(holdTicker)
    }

    private fun formatHoldDuration(): String {
        val startMs = CallManager.remoteHoldStartMs
        if (startMs == 0L) return ""
        val total = ((System.currentTimeMillis() - startMs) / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d", total / 60, total % 60)
    }

    private fun updateDuration() {
        binding.statusText.text = formatDuration(elapsedMs())
    }

    private fun elapsedMs(): Long {
        val connect = CallManager.activeCall()?.details?.connectTimeMillis ?: 0L
        if (connect > 0) return System.currentTimeMillis() - connect
        // Telecom gave us no connect time — use our own clock from when it went active.
        return if (activeSinceRealtime > 0) android.os.SystemClock.elapsedRealtime() - activeSinceRealtime else 0L
    }

    private fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d", total / 60, total % 60)
    }

    private fun finishAndStopRecording() {
        // The framework auto-stops recording when the call ends; nothing to do here.
        stopTimer()
        if (!isFinishing) finish()
    }

    // --- Helpers ---------------------------------------------------------------

    private fun show(vararg views: View) = views.forEach { it.visibility = View.VISIBLE }
    private fun hide(vararg views: View) = views.forEach { it.visibility = View.GONE }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun renderPreview() {
        binding.nameText.text = "Kol Mevaser"
        binding.numberText.text = "845-351-1200"
        binding.numberText.visibility = View.VISIBLE
        Avatars.bind(binding.avatarInitial, binding.contactPhoto, "Kol Mevaser", null)
        if (previewIncoming) {
            binding.statusText.setText(R.string.state_ringing)
            showIncomingControls()
            show(binding.btnIncomingMessage)
            hide(binding.bottomPanel, binding.waitingPanel)
            binding.pulseRing.start()
        } else {
            binding.statusText.text = "00:42"
            binding.hdIcon.visibility = View.VISIBLE
            hide(binding.incomingControls, binding.swipeIncomingPanel,
                binding.btnIncomingMessage, binding.waitingPanel)
        }
    }

    companion object {
        const val EXTRA_PREVIEW = "preview"
        const val EXTRA_PREVIEW_INCOMING = "preview_incoming"
        private const val MENU_ADD = 102
        private const val MENU_HOLD = 103
        private const val MENU_MERGE = 104
        private const val MENU_RECORD = 105
        private const val MENU_MANAGE_CONF = 106
    }
}
