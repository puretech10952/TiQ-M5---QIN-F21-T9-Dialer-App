package com.puretech.dialer

import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile

/**
 * Bridges [CallService] and [InCallActivity], and owns multi-call state:
 * a foreground call, an optional held call, and an optional second incoming
 * (call-waiting) call. Exposes the telecom operations the UI needs.
 */
object CallManager {

    var service: InCallService? = null

    /** True while the full-screen in-call UI is visible — suppresses the notification. */
    var uiVisible = false

    /** True when WE put the call on hold (as opposed to the remote party holding us). */
    private var isLocalHold = false

    /** True while the remote party has us on hold. MTK/IMS calls stay STATE_ACTIVE the
     *  whole time — remote hold is only signalled via EVENT_CALL_REMOTELY_HELD/_UNHELD. */
    private var remoteHeld = false

    /** Epoch millis when the remote party placed this call on hold; 0 if not remote-held. */
    var remoteHoldStartMs = 0L
        private set

    private val _calls = mutableListOf<Call>()
    val calls: List<Call> get() = _calls

    /** Backward-compatible "primary" call used by the notification/UI header. */
    val call: Call? get() = primaryCall()

    private val listeners = mutableListOf<Listener>()

    interface Listener {
        fun onCallChanged()
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_ACTIVE) isLocalHold = false
            notifyChanged()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) = notifyChanged()
        override fun onConnectionEvent(call: Call, event: String?, extras: android.os.Bundle?) {
            // The telephony framework reports a failed operation (e.g. recording
            // request rejected) with this event — reflect it so our UI recovers.
            if (event == EVENT_OPERATION_FAILED) recording = false
            // Remote hold: MTK/IMS calls stay STATE_ACTIVE the whole time, so this
            // connection event is the only signal that the other party held us.
            when (event) {
                EVENT_CALL_REMOTELY_HELD -> {
                    remoteHeld = true
                    if (remoteHoldStartMs == 0L) remoteHoldStartMs = System.currentTimeMillis()
                }
                EVENT_CALL_REMOTELY_UNHELD -> {
                    remoteHeld = false
                    remoteHoldStartMs = 0L
                }
            }
            notifyChanged()
        }
    }

    fun addCall(c: Call) {
        if (!_calls.contains(c)) {
            _calls.add(c)
            c.registerCallback(callback)
        }
        notifyChanged()
    }

    fun removeCall(c: Call) {
        c.unregisterCallback(callback)
        _calls.remove(c)
        if (_calls.isEmpty()) {
            recording = false
            isLocalHold = false
            remoteHeld = false
            remoteHoldStartMs = 0L
            service?.let { CallRecordings.scheduleOrganize(it) }
        }
        notifyChanged()
    }

    // --- Call selection --------------------------------------------------------

    @Suppress("DEPRECATION")
    fun ringingCall(): Call? = _calls.firstOrNull { it.state == Call.STATE_RINGING }

    @Suppress("DEPRECATION")
    fun activeCall(): Call? = _calls.firstOrNull {
        it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING ||
            it.state == Call.STATE_CONNECTING || it.state == Call.STATE_PULLING_CALL
    }

    @Suppress("DEPRECATION")
    fun heldCall(): Call? = _calls.firstOrNull { it.state == Call.STATE_HOLDING }

    /** The call shown in the header: a ringing call wins, else the active one. */
    fun primaryCall(): Call? = ringingCall() ?: activeCall() ?: _calls.firstOrNull()

    /** A second incoming call that arrived while another call is in progress. */
    fun waitingCall(): Call? {
        val ringing = ringingCall() ?: return null
        return if (_calls.size > 1) ringing else null
    }

    fun hasHeldCall(): Boolean = heldCall() != null
    fun callCount(): Int = _calls.size

    // --- Operations ------------------------------------------------------------

    fun answer(c: Call? = ringingCall()) = c?.answer(VideoProfile.STATE_AUDIO_ONLY) ?: Unit
    fun reject(c: Call? = ringingCall()) = c?.reject(false, null) ?: Unit

    /** Reject the ringing call and send a text back (system respond-via-message). */
    fun rejectWithMessage(text: String, c: Call? = ringingCall()) =
        c?.reject(true, text) ?: Unit

    fun hangup(c: Call? = primaryCall()) = c?.disconnect() ?: Unit

    fun hold(c: Call? = activeCall()) {
        isLocalHold = true
        c?.hold() ?: Unit
    }

    fun unhold(c: Call? = heldCall()) {
        isLocalHold = false
        remoteHoldStartMs = 0L
        c?.unhold() ?: Unit
    }

    /** Swap foreground/background: unholding the held call auto-holds the active. */
    fun swap() {
        isLocalHold = false
        remoteHoldStartMs = 0L
        heldCall()?.unhold()
    }

    /** True when the remote party (not the user) has placed this call on hold. */
    fun isRemoteHold(): Boolean = remoteHeld

    /** Merge the active and held calls into a conference. */
    fun merge() {
        val a = activeCall() ?: return
        val h = heldCall() ?: return
        a.conference(h)
    }

    // --- Conference ------------------------------------------------------------

    /** The conference (multi-party) call, if one exists — created locally by merge
     *  or by the remote party. Detected by the conference property or by children. */
    @Suppress("DEPRECATION")
    fun conferenceCall(): Call? =
        _calls.firstOrNull { it.details.hasProperty(Call.Details.PROPERTY_CONFERENCE) }
            ?: _calls.firstOrNull { it.children.isNotEmpty() }

    fun isConference(): Boolean = conferenceCall() != null

    /** Participants of the conference (each can be hung up individually). */
    @Suppress("DEPRECATION")
    fun conferenceChildren(): List<Call> = conferenceCall()?.children ?: emptyList()

    /** Hang up one specific participant in the conference. */
    fun disconnectParticipant(c: Call) = c.disconnect()

    /** Answer a waiting call, putting the current one on hold (call waiting). */
    fun answerAndHold() {
        isLocalHold = true
        activeCall()?.hold()
        ringingCall()?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    /** Answer a waiting call, ending the current one. */
    fun answerAndEnd() {
        activeCall()?.disconnect()
        ringingCall()?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    // --- Audio (whole-call, via the InCallService) -----------------------------

    fun setMuted(muted: Boolean) {
        service?.setMuted(muted)
    }

    fun setSpeaker(on: Boolean) {
        service?.setAudioRoute(
            if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
        )
    }

    fun isMuted(): Boolean = service?.callAudioState?.isMuted == true
    fun isSpeakerOn(): Boolean =
        service?.callAudioState?.route == CallAudioState.ROUTE_SPEAKER

    fun supportedRouteMask(): Int = service?.callAudioState?.supportedRouteMask ?: 0
    fun currentRoute(): Int =
        service?.callAudioState?.route ?: CallAudioState.ROUTE_EARPIECE
    fun setRoute(route: Int) {
        service?.setAudioRoute(route)
    }
    fun isBluetoothAvailable(): Boolean =
        (supportedRouteMask() and CallAudioState.ROUTE_BLUETOOTH) != 0
    fun isOnEarpiece(): Boolean = currentRoute() == CallAudioState.ROUTE_EARPIECE

    /** The connected Bluetooth headset's name (e.g. "AirPods"), or null.
     *  activeBluetoothDevice is often null even when routed to BT, so we also
     *  fall back to the supported-devices list, and to the user alias. */
    @Suppress("DEPRECATION")
    fun bluetoothDeviceName(): String? {
        val state = service?.callAudioState ?: return null
        val device = try {
            state.activeBluetoothDevice ?: state.supportedBluetoothDevices.firstOrNull()
        } catch (e: Throwable) {
            null
        } ?: return null
        return try {
            // alias is the user-set name; name is the factory name. getName/getAlias
            // need BLUETOOTH_CONNECT (auto-granted to the dialer role).
            val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null
            (alias ?: device.name)?.ifBlank { null }
        } catch (e: Throwable) {
            null
        }
    }

    private val dtmfHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** In-call keypad tone. Holds the DTMF tone briefly so it sounds like a real
     *  tone instead of a clipped blip; always plays (independent of any setting). */
    fun playDtmf(c: Char) {
        val target = activeCall() ?: primaryCall() ?: return
        target.playDtmfTone(c)
        dtmfHandler.postDelayed({
            try { target.stopDtmfTone() } catch (_: Exception) {}
        }, 160)
    }

    // --- Call recording (via the telephony framework) --------------------------
    // We don't capture audio ourselves (that only yields white noise for a
    // non-privileged app). Instead we ask the MTK telephony framework to record,
    // the same way the stock MTK dialer does — which records BOTH sides and saves
    // the file. Needs only RECORD_AUDIO. Two MTK variants exist:
    //   • Newer (Qin F21): InCallService.doMtkAction(Bundle{key_action:"…"}).
    //   • Older (TiQ M5):  Call.sendCallEvent("mediatek.telecom.event.REQUEST_…").
    // We try doMtkAction first (verified against the F21 stock dialer) and fall
    // back to the call event, so both phones record from one build.

    var recording = false
        private set

    /** @return true if a start request was accepted by either MTK mechanism. */
    fun startRecording(): Boolean {
        val call = activeCall() ?: primaryCall() ?: return false
        val ok = mtkAction(ACTION_START_RECORDING) || run {
            try { call.sendCallEvent(EVENT_START_RECORDING, android.os.Bundle()); true }
            catch (_: Exception) { false }
        }
        if (ok) {
            recording = true
            CallRecordings.markStarted()
        }
        return ok
    }

    fun stopRecording() {
        val call = activeCall() ?: primaryCall()
        if (!mtkAction(ACTION_STOP_RECORDING)) {
            try { call?.sendCallEvent(EVENT_STOP_RECORDING, android.os.Bundle()) } catch (_: Exception) {}
        }
        recording = false
    }

    /** @return the new recording state (true = now recording). */
    fun toggleRecording(): Boolean {
        return if (recording) { stopRecording(); false } else startRecording()
    }

    /**
     * MediaTek's hidden InCallService.doMtkAction(Bundle). The F21 stock dialer
     * builds the Bundle as {"key_action": "startVoiceRecording"/"stopVoiceRecording"}
     * (reverse-engineered from mediatek-telecom-common.jar). Reachable via
     * reflection thanks to [HiddenApi.unseal]. @return true if the call landed.
     */
    private fun mtkAction(action: String): Boolean {
        val svc = service ?: return false
        return try {
            val bundle = android.os.Bundle().apply { putString(KEY_ACTION, action) }
            val m = svc.javaClass.getMethod("doMtkAction", android.os.Bundle::class.java)
            m.invoke(svc, bundle)
            true
        } catch (e: Throwable) {
            false
        }
    }

    private const val KEY_ACTION = "key_action"
    private const val ACTION_START_RECORDING = "startVoiceRecording"
    private const val ACTION_STOP_RECORDING = "stopVoiceRecording"
    private const val EVENT_START_RECORDING =
        "mediatek.telecom.event.REQUEST_START_VOICE_RECORDING"
    private const val EVENT_STOP_RECORDING =
        "mediatek.telecom.event.REQUEST_STOP_VOICE_RECORDING"
    private const val EVENT_OPERATION_FAILED =
        "mediatek.telecom.event.OPERATION_FAILED"
    private const val EVENT_CALL_REMOTELY_HELD = "android.telecom.event.CALL_REMOTELY_HELD"
    private const val EVENT_CALL_REMOTELY_UNHELD = "android.telecom.event.CALL_REMOTELY_UNHELD"

    // --- Details ---------------------------------------------------------------

    fun number(c: Call? = primaryCall()): String =
        c?.details?.handle?.schemeSpecificPart ?: ""

    fun isHighDef(c: Call? = primaryCall()): Boolean =
        c?.details?.hasProperty(Call.Details.PROPERTY_HIGH_DEF_AUDIO) == true

    /** Epoch millis when the active call connected, or 0 if not connected/unknown. */
    fun connectTimeMillis(): Long = activeCall()?.details?.connectTimeMillis ?: 0L

    // --- Listeners -------------------------------------------------------------

    fun registerListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
        l.onCallChanged()
    }

    fun unregisterListener(l: Listener) {
        listeners.remove(l)
    }

    fun notifyChanged() {
        listeners.toList().forEach { it.onCallChanged() }
    }
}
