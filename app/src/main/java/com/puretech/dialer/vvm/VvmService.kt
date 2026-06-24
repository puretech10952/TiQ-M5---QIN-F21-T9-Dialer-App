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
            tm?.setVisualVoicemailSmsFilterSettings(VvmSync.buildFilter(cfg))
            // Re-arm activation; the carrier answers with a STATUS SMS.
            tm?.sendVisualVoicemailSms(
                cfg.destinationNumber, cfg.port,
                "Activate:pv=12;ct=Google", null
            )
        } catch (_: Exception) {
        }
        task.finish()
    }

    override fun onSmsReceived(task: VisualVoicemailTask, sms: VisualVoicemailSms) {
        Thread {
            try {
                VvmSync.onSms(applicationContext, sms)
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
