package com.puretech.dialer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Posts a "Missed call" notification with Call back / View actions. Detected by
 * [CallService] when an incoming call is removed with a MISSED disconnect cause.
 */
object MissedCallNotifier {

    private const val CHANNEL = "missed_call_v1"
    private const val BASE_ID = 5000

    /** IDs of missed-call notifications we've posted, so we can clear them all. */
    private val activeIds = mutableSetOf<Int>()

    fun show(context: Context, number: String) {
        ensureChannel(context)
        val name = ContactsRepository.displayName(context, number)
            ?: number.ifBlank { context.getString(R.string.unknown_caller) }
        val id = BASE_ID + (number.ifBlank { "unknown" }.hashCode() and 0xFFFF)
        activeIds.add(id)

        // Tap or "View" → open recents.
        val viewPi = PendingIntent.getActivity(
            context, id,
            Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            piFlags()
        )
        // "Call back" → broadcast that places the call and dismisses this notification.
        val callBackPi = PendingIntent.getBroadcast(
            context, id,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_CALL_BACK)
                .putExtra(NotificationActionReceiver.EXTRA_NUMBER, number)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, id),
            piFlags()
        )

        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_call_missed)
            .setContentTitle(context.getString(R.string.missed_call_title))
            .setContentText(name)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(viewPi)

        if (number.isNotBlank()) {
            builder.addAction(
                R.drawable.ic_call, context.getString(R.string.missed_call_back), callBackPi
            )
        }
        builder.addAction(
            R.drawable.ic_history, context.getString(R.string.missed_call_view), viewPi
        )

        manager(context).notify(id, builder.build())
    }

    fun cancel(context: Context, id: Int) {
        activeIds.remove(id)
        manager(context).cancel(id)
    }

    /** Clear every missed-call notification we've posted (Telecom sent count 0,
     *  or the user opened recents). Also resets the framework's missed count. */
    fun cancelAll(context: Context) {
        val nm = manager(context)
        activeIds.forEach { nm.cancel(it) }
        activeIds.clear()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = manager(context)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.notif_channel_missed),
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

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
