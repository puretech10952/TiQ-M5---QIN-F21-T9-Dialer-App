package com.puretech.dialer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Schedules/cancels the exact-time alarm behind a "call back" reminder. Fires
 *  [CallBackReminderReceiver]. Alarms don't survive reboot, so [BootReceiver]
 *  re-arms every pending reminder found in [StarredStore] after a restart. */
object CallBackReminderScheduler {

    const val EXTRA_NUMBER = "com.puretech.dialer.extra.REMINDER_NUMBER"

    fun schedule(context: Context, number: String, atMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, number)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (_: SecurityException) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi) } catch (_: Exception) {}
        } catch (_: Exception) {
        }
    }

    fun cancel(context: Context, number: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try { am.cancel(pendingIntent(context, number)) } catch (_: Exception) {}
    }

    /** Re-arms every pending reminder — called after a reboot. */
    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        Thread {
            val now = System.currentTimeMillis()
            StarredStore.loadAll(app).forEach { entry ->
                val at = entry.reminderAt ?: return@forEach
                if (at > now) schedule(app, entry.number, at)
                else StarredStore.setReminderAt(app, entry.number, null)
            }
        }.start()
    }

    private fun pendingIntent(context: Context, number: String): PendingIntent {
        val intent = Intent(context, CallBackReminderReceiver::class.java)
            .putExtra(EXTRA_NUMBER, number)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode(number), intent, flags)
    }

    private fun requestCode(number: String): Int {
        val digits = number.filter { it.isDigit() }.takeLast(7)
        return (digits.ifEmpty { number }).hashCode() and 0xFFFF
    }
}
