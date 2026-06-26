package com.puretech.dialer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Optional keep-alive foreground service.
 *
 * On aggressive OEM ROMs — notably MediaTek **DuraSpeed** on the Qin F21 — a
 * user-installed dialer is frozen/cached after a few minutes idle. When a call
 * then arrives, Telecom's attempt to bind our [CallService] is deferred until
 * the app is reopened by hand, so the incoming-call screen never appears
 * ("no banner until I open the dialer, then it shows up").
 *
 * The vendor exempts Google's package from DuraSpeed by name; a third-party app
 * can't get on that list. Keeping a foreground service running is the reliable
 * no-OEM-cooperation workaround: it pins the process at a high oom priority so
 * the OS never freezes it, and Telecom can always bind instantly.
 *
 * Gated behind a Settings toggle (default off) because it shows a permanent
 * minimum-importance notification.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        // A foreground service is required to post a notification, but we don't
        // want it cluttering the shade. Deleting the channel right after posting
        // removes the notification from the shade/status bar while the service
        // stays in the foreground (keeps the process resident). Works on
        // Android 8–12; on newer builds it degrades to the minimized notification.
        hideNotification()
        // Ask the system to recreate us if it ever kills the process.
        return START_STICKY
    }

    private fun hideNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            getSystemService(NotificationManager::class.java).deleteNotificationChannel(CHANNEL)
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel(this)
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(getString(R.string.keepalive_notif_title))
            .setContentText(getString(R.string.keepalive_notif_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL = "keepalive"
        private const val NOTIF_ID = 7

        /** Start the service if the user has enabled keep-alive. */
        fun start(context: Context) {
            if (!Prefs.keepAlive(context)) return
            val i = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(i)
                else context.startService(i)
            } catch (_: Exception) {
                // Background-start window may be closed; ignored — it'll start
                // again next time the app is opened.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        /** Reconcile the running state with the current preference. */
        fun apply(context: Context) {
            if (Prefs.keepAlive(context)) start(context) else stop(context)
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.keepalive_channel),
                    NotificationManager.IMPORTANCE_MIN
                )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }
}
