package com.puretech.dialer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.telephony.PhoneNumberUtils
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

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

        // Tap or "View" → open dedicated Missed Calls page.
        val viewPi = PendingIntent.getActivity(
            context, id,
            Intent(context, MissedCallsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
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
        // Swipe-dismiss → mark this caller's missed calls as read so they are NOT
        // re-posted by the Telecom framework after a reboot.
        val dismissPi = PendingIntent.getBroadcast(
            context, id,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_MISSED_DISMISSED)
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
            .setDeleteIntent(dismissPi)

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

    /**
     * Marks a caller's missed/rejected calls as read (NEW=0, IS_READ=1) in the call
     * log so the Telecom framework does not re-post them as missed-call
     * notifications after a reboot. Called when the user dismisses or acts on the
     * notification. No-op without WRITE_CALL_LOG.
     */
    fun markCallsRead(context: Context, number: String) {
        if (!canWriteCallLog(context)) return
        try {
            // The call log may store the number in a different format than the
            // notification carried (e.g. +countrycode), so an exact NUMBER=? match
            // can miss rows. Compare in code instead and update the matches by id.
            val ids = mutableListOf<String>()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER),
                "${CallLog.Calls.NEW}=1 AND ${CallLog.Calls.TYPE} IN (?, ?)",
                NEW_MISSED_TYPES, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val rowNumber = c.getString(1).orEmpty()
                    val matches =
                        if (number.isBlank()) rowNumber.isBlank()
                        else PhoneNumberUtils.compare(number, rowNumber)
                    if (matches) ids.add(c.getString(0))
                }
            }
            if (ids.isNotEmpty()) {
                context.contentResolver.update(
                    CallLog.Calls.CONTENT_URI, readValues(),
                    "${CallLog.Calls._ID} IN (${ids.joinToString(",")})", null
                )
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Marks ALL new missed/rejected calls as read. Called whenever the user opens
     * the call log. TelecomManager.cancelMissedCallsNotification() is supposed to
     * do this, but on the MTK builds of the M5/F21 it does not clear the NEW flag,
     * so the framework re-posted every old missed call after a reboot.
     */
    fun markAllMissedRead(context: Context) {
        if (!canWriteCallLog(context)) return
        val app = context.applicationContext
        Thread {
            try {
                app.contentResolver.update(
                    CallLog.Calls.CONTENT_URI, readValues(),
                    "${CallLog.Calls.NEW}=1 AND ${CallLog.Calls.TYPE} IN (?, ?)",
                    NEW_MISSED_TYPES
                )
            } catch (_: Exception) {
            }
        }.start()
    }

    private val NEW_MISSED_TYPES = arrayOf(
        CallLog.Calls.MISSED_TYPE.toString(),
        CallLog.Calls.REJECTED_TYPE.toString()
    )

    private fun readValues() = ContentValues().apply {
        put(CallLog.Calls.NEW, 0)
        put(CallLog.Calls.IS_READ, 1)
    }

    private fun canWriteCallLog(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

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
