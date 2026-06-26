package com.puretech.dialer

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
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
        CallManager.addCall(call)
        // Register the listener FIRST so the call notification is posted before we
        // attempt to open the screen. For a ringing call that notification carries
        // the full-screen intent, which is the reliable way to surface the incoming
        // UI over another foreground app — a direct background startActivity from
        // this service is often blocked by OEM background-launch limits.
        CallManager.registerListener(notifListener)
        startActivity(
            Intent(this, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
        // The missed-call notification is handled the official way via
        // MissedCallReceiver (Telecom delegates it to us as the default dialer),
        // so we deliberately do NOT post one here — that would duplicate it.
        CallManager.removeCall(call)
        if (CallManager.calls.isEmpty()) {
            CallManager.unregisterListener(notifListener)
            CallNotifier.cancel(this)
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        CallManager.notifyChanged()
    }

    companion object {
        private const val TAG = "M5CallService"
    }
}
