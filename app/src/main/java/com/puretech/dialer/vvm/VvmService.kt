package com.puretech.dialer.vvm

import android.telecom.PhoneAccountHandle
import android.telephony.TelephonyManager
import android.telephony.VisualVoicemailService
import android.telephony.VisualVoicemailSms
import android.util.Log

/**
 * The platform's VVM entry point. The OS binds this (we declare it with
 * BIND_VISUAL_VOICEMAIL_SERVICE) while we are the default dialer. It calls
 * [onCellServiceConnected] to (re)activate and [onSmsReceived] for each OMTP
 * STATUS/SYNC message from the carrier. All real work is delegated to [VvmSync].
 */
class VvmService : VisualVoicemailService() {

    override fun onCellServiceConnected(task: VisualVoicemailTask, handle: PhoneAccountHandle) {
        Log.d("M5Vvm", "onCellServiceConnected (enabled=${VvmPrefs.enabled(this)})")
        if (!VvmPrefs.enabled(this)) { task.finish(); return }
        val cfg = VvmConfig.read(this)
        if (cfg == null || !cfg.isSupported) { task.finish(); return }
        try {
            val tm = getSystemService(TelephonyManager::class.java)
                ?.createForPhoneAccountHandle(handle)
            if (tm != null) {
                tm.setVisualVoicemailSmsFilterSettings(VvmSync.buildFilter(cfg))
                // Re-arm activation with the real per-carrier message (was a
                // stale hardcoded generic-OMTP string that predated the
                // per-carrier logic in VvmSync.sendActivate -- wrong for
                // both CVVM and VVM3).
                VvmSync.sendActivate(this, tm, cfg)
            }
        } catch (_: Exception) {
        }
        task.finish()
    }

    override fun onSmsReceived(task: VisualVoicemailTask, sms: VisualVoicemailSms) {
        Thread {
            try {
                VvmSync.onSms(applicationContext, sms)
            } catch (e: Throwable) {
                // A `finally` alone still lets the exception crash this
                // (default-dialer) process after cleanup runs -- confirmed
                // 2026-08-03, an uncaught regex bug three levels down from
                // here took down the phone app on every incoming VVM SMS.
                // Carrier-triggered code must never be able to do that.
                Log.w("M5Vvm", "onSmsReceived: unhandled error: ${e.message}")
            } finally {
                task.finish()
            }
        }.start()
    }

    override fun onSimRemoved(task: VisualVoicemailTask, handle: PhoneAccountHandle) {
        task.finish()
    }

    override fun onStopped(task: VisualVoicemailTask) {
        task.finish()
    }
}
