package com.puretech.dialer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Fired by [CallBackReminderScheduler]'s alarm at the time the user chose
 *  when setting a "call back later" reminder. Posts a notification with a
 *  direct Call action; tapping the body opens that number's call history
 *  (where the note they wrote is one tap away, via the Notes dropdown item). */
class CallBackReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra(CallBackReminderScheduler.EXTRA_NUMBER) ?: return
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                val entry = StarredStore.find(app, number)
                // The reminder already fired — clear it so it doesn't linger as "pending".
                StarredStore.setReminderAt(app, number, null)
                val name = NameFormat.apply(app, entry?.name)
                    ?: number.ifBlank { app.getString(R.string.unknown_caller) }
                notify(app, number, name)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun notify(context: Context, number: String, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)
        val id = BASE_ID + (number.ifBlank { "unknown" }.hashCode() and 0xFFFF)

        val viewPi = PendingIntent.getActivity(
            context, id,
            Intent(context, CallHistoryActivity::class.java)
                .putExtra(CallHistoryActivity.EXTRA_NUMBER, number)
                .putExtra(CallHistoryActivity.EXTRA_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            piFlags()
        )
        val callPi = PendingIntent.getBroadcast(
            context, id,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_CALL_BACK)
                .putExtra(NotificationActionReceiver.EXTRA_NUMBER, number)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, id),
            piFlags()
        )

        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_callback)
            .setContentTitle(context.getString(R.string.reminder_notif_title))
            .setContentText(name)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(viewPi)
            .addAction(R.drawable.ic_call, context.getString(R.string.missed_call_back), callPi)
            .build()

        try {
            context.getSystemService(NotificationManager::class.java).notify(id, notif)
        } catch (_: SecurityException) {
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.reminder_notif_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    private fun piFlags(): Int {
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f = f or PendingIntent.FLAG_IMMUTABLE
        return f
    }

    private companion object {
        const val CHANNEL = "callback_reminder_v1"
        const val BASE_ID = 6000
    }
}
