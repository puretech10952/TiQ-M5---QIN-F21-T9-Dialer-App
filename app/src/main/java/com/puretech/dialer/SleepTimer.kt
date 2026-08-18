package com.puretech.dialer

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast

/**
 * Auto-hangup countdown for the current call ("sleep timer" -- e.g. listening
 * to a hotline in bed without draining the phone overnight). A process-lifetime
 * singleton like [CallManager], not owned by [InCallActivity]: the in-call
 * screen can be destroyed and recreated (backgrounded via the persistent call
 * notification) while the call itself keeps going, and the scheduled hangup
 * must survive that. [CallManager] cancels this whenever the call ends.
 */
object SleepTimer {

    private val handler = Handler(Looper.getMainLooper())
    private var deadlineRealtimeMs = 0L
    private var appContext: Context? = null

    private val warnRunnable = Runnable { warn() }
    private val fireRunnable = Runnable {
        CallManager.hangup()
        cancel()
    }

    fun start(context: Context, minutes: Int) {
        cancel()
        appContext = context.applicationContext
        deadlineRealtimeMs = SystemClock.elapsedRealtime() + minutes * 60_000L
        handler.postDelayed(fireRunnable, minutes * 60_000L)
        if (minutes > 1) handler.postDelayed(warnRunnable, (minutes - 1) * 60_000L)
    }

    fun cancel() {
        handler.removeCallbacks(fireRunnable)
        handler.removeCallbacks(warnRunnable)
        deadlineRealtimeMs = 0L
    }

    fun isRunning(): Boolean = deadlineRealtimeMs > 0L

    fun remainingMs(): Long =
        (deadlineRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    private fun warn() {
        val c = appContext ?: return
        Toast.makeText(c, c.getString(R.string.sleep_timer_warning), Toast.LENGTH_LONG).show()
        val vib = c.getSystemService(Vibrator::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(400)
            }
        } catch (_: Exception) {
        }
    }
}
