package com.puretech.dialer.vvm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.puretech.dialer.R

/**
 * The platform delegates legacy (non-visual) voicemail notifications to
 * whichever app holds the default-dialer role, via this broadcast -- action
 * and extras confirmed from the decompiled AOSP Dialer's own
 * `LegacyVoicemailNotificationReceiver` (Apache-2.0). AOSP's own client
 * explicitly skips showing it once visual voicemail is activated for the
 * account ("visual voicemail is activated, ignoring notification"); same
 * here -- once VVM is CONNECTED the user already sees new voicemails in the
 * Voicemail tab, so this "call in" prompt would just be redundant nagging.
 */
class LegacyVoicemailReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOW_VOICEMAIL_NOTIFICATION) return

        val count = intent.getIntExtra(EXTRA_NOTIFICATION_COUNT, -1).let { if (it == -1) 1 else it }
        if (count == 0) {
            cancel(context)
            return
        }
        if (VvmPrefs.state(context) == VvmState.CONNECTED) {
            cancel(context)
            return
        }

        val callIntent = getParcelableExtraCompat(intent, EXTRA_CALL_VOICEMAIL_INTENT)
        showLegacyNotification(context, count, callIntent)
    }

    private fun getParcelableExtraCompat(intent: Intent, key: String): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(key)
        }

    private fun showLegacyNotification(context: Context, count: Int, callIntent: PendingIntent?) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_voicemail)
            .setContentTitle(context.getString(R.string.voicemail_title))
            .setContentText(context.getString(R.string.voicemail_text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (callIntent != null) builder.setContentIntent(callIntent)
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, builder.build())
    }

    private fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
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

    companion object {
        const val ACTION_SHOW_VOICEMAIL_NOTIFICATION = "android.telephony.action.SHOW_VOICEMAIL_NOTIFICATION"
        private const val EXTRA_NOTIFICATION_COUNT = "android.telephony.extra.NOTIFICATION_COUNT"
        private const val EXTRA_CALL_VOICEMAIL_INTENT = "android.telephony.extra.CALL_VOICEMAIL_INTENT"

        // Shares a channel/id with VoicemailNotifier's "new synced voicemail"
        // notification -- both represent "you have a voicemail to check",
        // just from different sources (legacy MWI vs. VVM sync), and are
        // mutually exclusive by design (this receiver suppresses itself
        // entirely once VVM is connected).
        private const val CHANNEL = "voicemail_v1"
        private const val NOTIF_ID = 6000
    }
}
