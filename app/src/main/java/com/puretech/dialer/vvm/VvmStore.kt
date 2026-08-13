package com.puretech.dialer.vvm

import android.content.ContentValues
import android.content.Context
import android.provider.VoicemailContract.Voicemails

/**
 * Reads/writes voicemails in Android's [Voicemails] provider. As the default
 * dialer we own the voicemail store, so we can insert downloaded voicemails
 * (with their audio) and reconcile against what the carrier server still holds.
 */
object VvmStore {

    /** Source UIDs already stored, so a re-sync doesn't duplicate downloads. */
    fun existingUids(context: Context): Set<String> {
        val uids = HashSet<String>()
        val uri = Voicemails.buildSourceUri(context.packageName)
        context.contentResolver.query(
            uri, arrayOf(Voicemails.SOURCE_DATA), null, null, null
        )?.use { c ->
            val i = c.getColumnIndex(Voicemails.SOURCE_DATA)
            while (c.moveToNext()) c.getString(i)?.let { uids.add(it) }
        }
        return uids
    }

    /** Insert a downloaded voicemail and write its audio. Returns true on success. */
    fun insert(context: Context, msg: VvmMessage): Boolean {
        val uri = Voicemails.buildSourceUri(context.packageName)
        val values = ContentValues().apply {
            put(Voicemails.SOURCE_PACKAGE, context.packageName)
            put(Voicemails.SOURCE_DATA, msg.uid)
            put(Voicemails.NUMBER, msg.sender)
            put(Voicemails.DATE, msg.dateMillis)
            put(Voicemails.DURATION, msg.durationSec)
            put(Voicemails.IS_READ, 0)
            put(Voicemails.MIME_TYPE, msg.mimeType)
            put(Voicemails.HAS_CONTENT, if (msg.audio.isNotEmpty()) 1 else 0)
        }
        val row = context.contentResolver.insert(uri, values) ?: return false
        if (msg.audio.isNotEmpty()) {
            try {
                context.contentResolver.openOutputStream(row)?.use { it.write(msg.audio) }
            } catch (_: Exception) {
                return false
            }
        }
        return true
    }

    /** Mark a stored voicemail read (local); the IMAP flag is set separately. */
    fun markRead(context: Context, id: Long) {
        val values = ContentValues().apply { put(Voicemails.IS_READ, 1) }
        context.contentResolver.update(
            android.content.ContentUris.withAppendedId(Voicemails.CONTENT_URI, id),
            values, null, null
        )
    }

    /** Delete a stored voicemail row (after the server copy is expunged). */
    fun delete(context: Context, id: Long) {
        context.contentResolver.delete(
            android.content.ContentUris.withAppendedId(Voicemails.CONTENT_URI, id),
            null, null
        )
    }

    /** Source UID for a stored voicemail id, to delete the carrier copy too. */
    fun sourceData(context: Context, id: Long): String? {
        context.contentResolver.query(
            android.content.ContentUris.withAppendedId(Voicemails.CONTENT_URI, id),
            arrayOf(Voicemails.SOURCE_DATA), null, null, null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }
}
