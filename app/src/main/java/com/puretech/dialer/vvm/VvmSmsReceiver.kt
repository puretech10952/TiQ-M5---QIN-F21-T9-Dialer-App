package com.puretech.dialer.vvm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Catches the carrier's OMTP data SMS on the VVM port (T-Mobile CVVM = 1808) and
 * parses it ourselves. We need this because on this Duoqin/MTK ROM the platform's
 * VisualVoicemailSmsFilter does not unpack/route the port-directed 7-bit STATUS to
 * our VisualVoicemailService — it falls through to the normal SMS apps instead. We
 * decode the body here and hand it to [VvmSync.onRawMessage].
 */
class VvmSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (msgs == null || msgs.isEmpty()) return

            // Prefer the platform-decoded text; fall back to unpacking GSM 7-bit
            // from the raw user data (T-Mobile sends the STATUS as packed septets).
            val sbText = StringBuilder()
            val raw = ArrayList<Byte>()
            var sender: String? = null
            for (m in msgs) {
                sender = sender ?: m.originatingAddress
                m.messageBody?.let { sbText.append(it) }
                m.userData?.let { for (b in it) raw.add(b) }
            }
            val rawBytes = raw.toByteArray()
            var body = sbText.toString()
            if (!body.startsWith("//") && rawBytes.isNotEmpty()) {
                body = unpackGsm7(rawBytes)
            }

            // Don't log the body — a real STATUS reply contains IMAP credentials.
            Log.d("M5Vvm", "VVM data-sms from=$sender len=${rawBytes.size}")
            if (body.isNotBlank()) VvmSync.onRawMessage(context.applicationContext, body)
        } catch (e: Exception) {
            Log.w("M5Vvm", "VvmSmsReceiver error: ${e.message}")
        }
    }

    /** Unpack GSM 7-bit packed septets (LSB-first) into a string. */
    private fun unpackGsm7(data: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = buffer or ((b.toInt() and 0xFF) shl bits)
            bits += 8
            while (bits >= 7) {
                out.append((buffer and 0x7F).toChar())
                buffer = buffer ushr 7
                bits -= 7
            }
        }
        return out.toString()
    }
}
