package com.puretech.dialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager

/**
 * Handles notification action buttons: the ongoing-call controls (Hang up / Mute
 * / Speaker), "Call back" / "Message" from a missed-call notification, and
 * "dial voicemail".
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> CallManager.answer()
            ACTION_HANGUP -> CallManager.hangup()
            ACTION_MUTE -> CallManager.setMuted(!CallManager.isMuted())
            ACTION_SPEAKER -> CallManager.setSpeaker(!CallManager.isSpeakerOn())
            ACTION_CALL_BACK -> {
                val number = intent.getStringExtra(EXTRA_NUMBER)
                val id = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
                if (id != -1) MissedCallNotifier.cancel(context, id)
                // Acting on the missed call also clears it so it won't return on reboot.
                MissedCallNotifier.markCallsRead(context, number ?: "")
                if (!number.isNullOrBlank()) Dialer.place(context, Dialer.normalize(context, number))
                return
            }
            ACTION_MESSAGE -> {
                val number = intent.getStringExtra(EXTRA_NUMBER)
                val id = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
                if (id != -1) MissedCallNotifier.cancel(context, id)
                MissedCallNotifier.markCallsRead(context, number ?: "")
                if (!number.isNullOrBlank()) {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                }
                return
            }
            ACTION_MISSED_DISMISSED -> {
                val number = intent.getStringExtra(EXTRA_NUMBER)
                val id = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
                if (id != -1) MissedCallNotifier.cancel(context, id)
                // Swiped away → mark read so the framework won't re-post it on reboot.
                MissedCallNotifier.markCallsRead(context, number ?: "")
                return
            }
            ACTION_CALL_VOICEMAIL -> {
                VoicemailNotifier.cancel(context)
                dialVoicemail(context)
                return
            }
        }
        // For the live call-control actions, reflect the new state (or dismiss).
        if (CallManager.call != null) CallNotifier.update(context) else CallNotifier.cancel(context)
    }

    private fun dialVoicemail(context: Context) {
        try {
            context.getSystemService(TelecomManager::class.java)
                ?.placeCall(Uri.fromParts("voicemail", "", null), Dialer.accountExtras(context))
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.puretech.dialer.action.ANSWER"
        const val ACTION_HANGUP = "com.puretech.dialer.action.HANGUP"
        const val ACTION_MUTE = "com.puretech.dialer.action.MUTE"
        const val ACTION_SPEAKER = "com.puretech.dialer.action.SPEAKER"
        const val ACTION_CALL_BACK = "com.puretech.dialer.action.CALL_BACK"
        const val ACTION_MESSAGE = "com.puretech.dialer.action.MESSAGE"
        const val ACTION_MISSED_DISMISSED = "com.puretech.dialer.action.MISSED_DISMISSED"
        const val ACTION_CALL_VOICEMAIL = "com.puretech.dialer.action.CALL_VOICEMAIL"
        const val EXTRA_NUMBER = "com.puretech.dialer.extra.NUMBER"
        const val EXTRA_NOTIF_ID = "com.puretech.dialer.extra.NOTIF_ID"
    }
}
