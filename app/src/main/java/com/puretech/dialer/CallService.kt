package com.puretech.dialer

import android.content.Intent
import android.provider.CallLog
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.util.Log

/**
 * The system binds to this once we hold ROLE_DIALER and routes every call
 * (incoming and outgoing) here. We hand the call to [CallManager] and bring
 * up [InCallActivity], which provides the in-call UI.
 */
class CallService : InCallService() {

    // Keeps the ongoing-call notification in sync with call/audio state.
    private val notifListener = object : CallManager.Listener {
        override fun onCallChanged() {
            if (CallManager.call != null) CallNotifier.update(this@CallService)
            else CallNotifier.cancel(this@CallService)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        // NOTE: use Call.getState() (API 23), NOT Call.Details.getState() which
        // only exists on API 31+ and crashes with NoSuchMethodError on Android 11.
        Log.d(TAG, "onCallAdded state=${call.state}")

        // Safety net: reject incoming calls from blocked numbers without any UI.
        if (call.state == Call.STATE_RINGING) {
            val number = call.details.handle?.schemeSpecificPart
            if (number != null && BlockedNumbers.isBlocked(this, number)) {
                Log.d(TAG, "rejecting blocked number")
                call.reject(false, null)
                return
            }
            // Optional: reject callers that aren't saved contacts (and withheld numbers).
            if (Prefs.blockUnknownCallers(this) && isUnknownCaller(number)) {
                Log.d(TAG, "rejecting unknown caller")
                call.reject(false, null)
                return
            }
        }

        CallManager.service = this
        // Own the proximity screen-off for the whole call (works on any screen,
        // not just the in-call UI). Idempotent across multiple calls.
        ProximityController.attach(this)
        CallManager.addCall(call)
        // Register the listener FIRST so the call notification is posted before we
        // attempt to open the screen. For a ringing call that notification carries
        // the full-screen intent, which is the reliable way to surface the incoming
        // UI over another foreground app — a direct background startActivity from
        // this service is often blocked by OEM background-launch limits.
        CallManager.registerListener(notifListener)
        // Outgoing calls were just dialed from inside this app, so always go
        // full screen. Incoming (ringing) calls only take over the screen when
        // the device is actually locked — unlocked always just shows the
        // CallStyle notification instead (matches the real Google Dialer's
        // behavior, verified live: it does not distinguish home screen from
        // any other foreground app, only lock state). See CallUiGate.
        if (call.state != Call.STATE_RINGING || CallUiGate.shouldShowFullScreen(this)) {
            startActivity(
                Intent(this, InCallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** True when the caller is withheld/private or not a saved contact. Returns
     *  false if we can't read contacts, so we never reject everyone by mistake. */
    private fun isUnknownCaller(number: String?): Boolean {
        if (number.isNullOrBlank()) return true   // private / withheld
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        return ContactsRepository.displayName(this, number) == null
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved")
        // Persist to our permanent local store before the system call log can trim it.
        archiveCall(call)
        // The missed-call notification is handled the official way via
        // MissedCallReceiver (Telecom delegates it to us as the default dialer),
        // so we deliberately do NOT post one here — that would duplicate it.
        CallManager.removeCall(call)
        if (CallManager.calls.isEmpty()) {
            CallManager.unregisterListener(notifListener)
            ProximityController.detach()
            CallNotifier.cancel(this)
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        CallManager.notifyChanged()
    }

    /**
     * Mirrors the finished call into [LocalCallStore] so it survives Android's
     * automatic system call-log trimming (~500 rows). Runs on a background thread
     * to avoid blocking [onCallRemoved]. All data is captured synchronously from
     * the Call object first, then only the DB write happens off-thread.
     */
    private fun archiveCall(call: Call) {
        val details = call.details ?: return
        val number  = details.handle?.schemeSpecificPart ?: ""

        // Date = when the call was created in Telecom (matches what the system log stores).
        val date = details.creationTimeMillis.takeIf { it > 0L } ?: System.currentTimeMillis()

        // Duration = seconds from answer to hang-up (0 for unanswered calls).
        val connectTime = details.connectTimeMillis
        val duration = if (connectTime > 0L) (System.currentTimeMillis() - connectTime) / 1000L else 0L

        val isOutgoing = details.callDirection == Call.Details.DIRECTION_OUTGOING
        val dCode = details.disconnectCause?.code ?: DisconnectCause.UNKNOWN
        val type = when {
            isOutgoing                         -> CallLog.Calls.OUTGOING_TYPE
            dCode == DisconnectCause.MISSED    -> CallLog.Calls.MISSED_TYPE
            dCode == DisconnectCause.REJECTED  -> CallLog.Calls.REJECTED_TYPE
            else                               -> CallLog.Calls.INCOMING_TYPE
        }

        val isHd   = details.hasProperty(Call.Details.PROPERTY_HIGH_DEF_AUDIO)
        val isWifi = details.hasProperty(Call.Details.PROPERTY_WIFI)
        val acctId = details.accountHandle?.id

        val ctx = applicationContext
        Thread {
            val name = if (number.isNotBlank()) ContactsRepository.displayName(ctx, number) else null
            val simLabel = acctId?.let { id ->
                if (CallingAccounts.isMultiSim(ctx))
                    CallingAccounts.list(ctx).find { it.id == id }
                        ?.let { CallingAccounts.label(ctx, it) }
                else null
            }
            LocalCallStore.record(
                ctx, number, type, date, duration, isHd, isWifi,
                name, null, 0, null, null, simLabel
            )
        }.start()
    }

    companion object {
        private const val TAG = "M5CallService"
    }
}
