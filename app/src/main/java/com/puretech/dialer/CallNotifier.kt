package com.puretech.dialer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import androidx.core.app.NotificationCompat
import androidx.core.app.Person

/**
 * Ongoing/incoming call notification. Uses CallStyle so the system pins it to
 * the top of the shade and keeps it visible above other notifications. While
 * ringing it shows Answer/Decline; while active it shows Hang up + Mute +
 * Speaker. Tapping it returns to the in-call screen.
 */
object CallNotifier {

    // New ids so the importance levels take effect (channels are immutable once created).
    private const val CHANNEL_INCOMING = "incoming_call_v2"  // HIGH: heads-up + full-screen
    // DEFAULT (silent) so the status-bar icon shows on minimal ROMs that hide LOW
    // notification icons (e.g. the Qin F21). New id so the importance bump applies.
    private const val CHANNEL_ONGOING = "ongoing_call_v3"
    private const val NOTIF_ID = 42

    fun update(context: Context) {
        // A call that just hung up briefly stays in CallManager.calls (Telecom
        // removes it a moment later) — CallManager.call/primaryCall() still
        // returns it during that window, which InCallActivity relies on to show
        // "Call ended" for 2s. But that same window let this update() re-post a
        // fresh sticky notification for the already-ended call moments before
        // CallService.onCallRemoved()'s cancel() ran, leaving a ghost ongoing-call
        // notification with nothing left to clear it. Treat a disconnecting call
        // as "no call" here specifically, so the notification never comes back.
        @Suppress("DEPRECATION")
        val call = CallManager.call?.takeIf {
            it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING
        } ?: run { cancel(context); return }
        // While our full-screen call UI is visible, show NO notification — the
        // screen is the UI. If we get backgrounded (uiVisible=false, e.g. an OEM
        // like DuraSpeed pushes us back), onStop re-posts the full-screen-intent
        // notification, which re-launches the screen.
        if (CallManager.uiVisible) { cancel(context); return }
        ensureChannels(context)

        @Suppress("DEPRECATION")
        val ringing = call.state == Call.STATE_RINGING

        val number = CallManager.number()
        val title = NameFormat.apply(context, ContactsRepository.displayName(context, number))
            ?: number.ifBlank { context.getString(R.string.app_name) }
        val person = Person.Builder().setName(title).build()

        val contentPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            piFlags()
        )

        val channel = if (ringing) CHANNEL_INCOMING else CHANNEL_ONGOING

        val builder = NotificationCompat.Builder(context, channel)
            // Animated phone-with-waves icon so the status bar pulses during a call.
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(title)
            // Tapping the notification returns to the in-call screen.
            .setContentIntent(contentPi)
            // Required by the platform for CallStyle notifications (and what pops
            // the full-screen UI for a ringing call). For the LOW-importance
            // ongoing notification this satisfies the requirement without actually
            // launching full-screen.
            .setFullScreenIntent(contentPi, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)

        if (ringing) {
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person,
                    action(context, NotificationActionReceiver.ACTION_HANGUP), // decline
                    action(context, NotificationActionReceiver.ACTION_ANSWER)  // answer
                )
            )
        } else {
            // Ongoing call: CallStyle pins the notification to the top of the shade
            // and shows a live call timer; tapping it (contentIntent) returns to the
            // in-call screen. Mute + Speaker ride along as extra actions.
            builder.setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    person,
                    action(context, NotificationActionReceiver.ACTION_HANGUP)
                )
            )
            // Live elapsed-time timer in the notification / status bar.
            val connect = CallManager.connectTimeMillis()
            if (connect > 0) {
                builder.setWhen(connect).setShowWhen(true).setUsesChronometer(true)
            } else {
                builder.setShowWhen(false)
            }

            val muted = CallManager.isMuted()
            val speakerOn = CallManager.isSpeakerOn()
            builder.addAction(
                if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                context.getString(if (muted) R.string.ctl_unmute else R.string.ctl_mute),
                action(context, NotificationActionReceiver.ACTION_MUTE)
            )
            builder.addAction(
                R.drawable.ic_speaker,
                context.getString(if (speakerOn) R.string.notif_speaker_on else R.string.ctl_speaker),
                action(context, NotificationActionReceiver.ACTION_SPEAKER)
            )
        }

        manager(context).notify(NOTIF_ID, builder.build())
    }

    fun cancel(context: Context) = manager(context).cancel(NOTIF_ID)

    private fun action(context: Context, a: String): PendingIntent {
        val i = Intent(context, NotificationActionReceiver::class.java).setAction(a)
        return PendingIntent.getBroadcast(context, a.hashCode(), i, piFlags())
    }

    private fun piFlags(): Int {
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f = f or PendingIntent.FLAG_IMMUTABLE
        return f
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = manager(context)
        if (nm.getNotificationChannel(CHANNEL_INCOMING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_INCOMING,
                    context.getString(R.string.notif_channel_incoming),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ONGOING) == null) {
            val ongoing = NotificationChannel(
                CHANNEL_ONGOING,
                context.getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                // Show the status-bar icon but stay silent during the call.
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ongoing)
            nm.deleteNotificationChannel("ongoing_call_v2")  // retire the old LOW channel
        }
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
