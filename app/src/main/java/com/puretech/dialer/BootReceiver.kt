package com.puretech.dialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the keep-alive service after a reboot (only if the user enabled it). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                KeepAliveService.start(context)
                // Inexact repeating alarms are cleared on reboot — re-arm the
                // background update check if the user enabled it.
                UpdateScheduler.reschedule(context)
                // Exact alarms are also cleared on reboot — re-arm any pending
                // "call back" reminders from the Call back list.
                CallBackReminderScheduler.rescheduleAll(context)
            }
        }
    }
}
