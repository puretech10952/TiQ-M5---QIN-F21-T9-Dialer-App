package com.puretech.dialer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Auto-hangup countdown for the current call ("sleep timer" -- e.g. listening
 * to a hotline in bed without draining the phone overnight).
 *
 * Fires via [AlarmManager.setExactAndAllowWhileIdle] + [SleepTimerReceiver]
 * rather than an in-process Handler: this app freezes in the background on
 * some ROMs (see Prefs.keepAlive's doc on DuraSpeed) whenever "Keep alive"
 * isn't turned on, which silently starved a plain Handler.postDelayed of
 * ever running — the reported "sleep timer doesn't actually hang up" bug.
 * An exact-and-allow-while-idle alarm wakes the app (or Telecom directly, via
 * [android.telecom.TelecomManager.endCall] in the receiver) regardless.
 *
 * The deadline is persisted (survives process death) so [isRunning] and
 * [remainingMs] stay correct even if this process was restarted mid-call.
 */
object SleepTimer {

    private const val REQUEST_CODE_FIRE = 9001
    private const val REQUEST_CODE_WARN = 9002

    fun start(context: Context, minutes: Int) {
        val ctx = context.applicationContext
        cancel(ctx)
        val deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        Prefs.setSleepTimerDeadlineElapsed(ctx, deadline)
        schedule(ctx, firePendingIntent(ctx), deadline)
        if (minutes > 1) schedule(ctx, warnPendingIntent(ctx), deadline - 60_000L)
    }

    fun cancel(context: Context) {
        val ctx = context.applicationContext
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            am?.cancel(firePendingIntent(ctx))
            am?.cancel(warnPendingIntent(ctx))
        } catch (_: Exception) {
        }
        Prefs.setSleepTimerDeadlineElapsed(ctx, 0L)
    }

    fun isRunning(context: Context): Boolean = remainingMs(context) > 0L

    fun remainingMs(context: Context): Long {
        val deadline = Prefs.sleepTimerDeadlineElapsed(context)
        if (deadline <= 0L) return 0L
        return (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    }

    private fun schedule(context: Context, pi: PendingIntent, atElapsedRealtime: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedRealtime, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedRealtime, pi)
            }
        } catch (_: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedRealtime, pi)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private fun firePendingIntent(context: Context): PendingIntent = pendingIntent(
        context, REQUEST_CODE_FIRE, SleepTimerReceiver.ACTION_FIRE
    )

    private fun warnPendingIntent(context: Context): PendingIntent = pendingIntent(
        context, REQUEST_CODE_WARN, SleepTimerReceiver.ACTION_WARN
    )

    private fun pendingIntent(context: Context, requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, SleepTimerReceiver::class.java).setAction(action)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}
