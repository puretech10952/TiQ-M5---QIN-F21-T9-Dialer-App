package com.puretech.dialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.widget.Toast

/**
 * Fired by [SleepTimer]'s alarm(s). [ACTION_FIRE] ends the call two ways:
 * [CallManager.hangup] for an immediate local UI update when the app is
 * already alive, and [TelecomManager.endCall] as the reliable fallback that
 * works even if this process was killed and [CallManager]'s in-memory call
 * list is empty — Telecom itself still knows about the call regardless.
 */
class SleepTimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> {
                SleepTimer.cancel(context)
                CallManager.hangup()
                try {
                    context.getSystemService(TelecomManager::class.java)?.endCall()
                } catch (_: SecurityException) {
                } catch (_: Exception) {
                }
            }
            ACTION_WARN -> warn(context)
        }
    }

    private fun warn(context: Context) {
        Toast.makeText(context, context.getString(R.string.sleep_timer_warning), Toast.LENGTH_LONG).show()
        val vib = context.getSystemService(Vibrator::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(400)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_FIRE = "com.puretech.dialer.action.SLEEP_TIMER_FIRE"
        const val ACTION_WARN = "com.puretech.dialer.action.SLEEP_TIMER_WARN"
    }
}
