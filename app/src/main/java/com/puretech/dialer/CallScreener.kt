package com.puretech.dialer

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Doesn't actually screen anything — [CallService] already handles blocked/unknown
 * callers once a call reaches it. This exists purely so Telecom has a legitimate,
 * declared reason to start our process as part of its incoming-call filtering
 * pipeline, before the call ever reaches [CallService]. Confirmed via logcat on
 * the Qin F21: Google Dialer's own CallScreeningService gets started by
 * ActivityManager for exactly this reason — and that succeeds even from a
 * force-stopped/fully-killed process — while our app, having no such service,
 * got skipped ("no call screening service defined") and never got that early
 * wake, leaving CallService's InCallService bind as the only (and less
 * reliable) way our process could ever start for a call.
 */
class CallScreener : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        respondToCall(callDetails, CallResponse.Builder().build())
    }
}
