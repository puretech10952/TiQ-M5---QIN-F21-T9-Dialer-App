package com.puretech.dialer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Shows a "new voicemail" notification. Tapping it broadcasts a request to dial
 * the voicemail box (handled by [NotificationActionReceiver]).
 */
object VoicemailNotifier {

    /** Public so Settings can deep-link to this channel's system notification page. */
    const val CHANNEL = "voicemail_v1"
    private const val NOTIF_ID = 6000

    fun show(context: Context) {
        ensureChannel(context)
        val tapPi = PendingIntent.getBroadcast(
            context, NOTIF_ID,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_CALL_VOICEMAIL),
            piFlags()
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_voicemail)
            .setContentTitle(context.getString(R.string.voicemail_title))
            .setContentText(context.getString(R.string.voicemail_text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(tapPi)
        manager(context).notify(NOTIF_ID, builder.build())
    }

    fun cancel(context: Context) = manager(context).cancel(NOTIF_ID)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = manager(context)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.notif_channel_voicemail),
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
