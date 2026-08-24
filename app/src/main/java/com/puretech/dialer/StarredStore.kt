package com.puretech.dialer

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

/**
 * Persistent store for the "Starred" feature: call-log entries/contacts the
 * user has pinned, each with an optional free-text note. Identity is a
 * number's trailing 7 digits — the same match key [CallLogRepository] already
 * uses to dedupe/merge calls — so starring works for any number, contact or
 * not.
 */
object StarredStore {

    private const val DB_NAME = "starred.db"
    private const val DB_VERSION = 2
    private const val TABLE = "starred"

    private const val C_ID       = "_id"
    private const val C_NUMBER   = "number"
    private const val C_MATCH    = "match_key"
    private const val C_NAME     = "name"
    private const val C_PHOTO    = "photo_uri"
    private const val C_STARRED  = "starred_at"
    private const val C_NOTES    = "notes"
    private const val C_REMINDER = "reminder_at"

    data class StarredEntry(
        val id: Long,
        val number: String,
        val matchKey: String,
        val name: String?,
        val photoUri: Uri?,
        val starredAt: Long,
        val notes: String?,
        /** Epoch millis of a pending "call back" reminder, or null if none is set. */
        val reminderAt: Long?
    )

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE $TABLE (
                    $C_ID       INTEGER PRIMARY KEY AUTOINCREMENT,
                    $C_NUMBER   TEXT    NOT NULL,
                    $C_MATCH    TEXT    NOT NULL UNIQUE,
                    $C_NAME     TEXT,
                    $C_PHOTO    TEXT,
                    $C_STARRED  INTEGER NOT NULL,
                    $C_NOTES    TEXT,
                    $C_REMINDER INTEGER
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_starred_match ON $TABLE($C_MATCH)")
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            if (old < 2) db.execSQL("ALTER TABLE $TABLE ADD COLUMN $C_REMINDER INTEGER")
        }
    }

    @Volatile private var helper: Helper? = null

    private fun db(ctx: Context): SQLiteDatabase =
        (helper ?: synchronized(this) {
            helper ?: Helper(ctx.applicationContext).also { helper = it }
        }).writableDatabase

    private fun matchKey(number: String): String {
        val digits = number.filter { it.isDigit() }.takeLast(7)
        return digits.ifEmpty { number }
    }

    /** Stars [number], no-op if already starred. */
    fun star(ctx: Context, number: String, name: String?, photoUri: String?) {
        try {
            val cv = ContentValues().apply {
                put(C_NUMBER, number)
                put(C_MATCH, matchKey(number))
                put(C_NAME, name)
                put(C_PHOTO, photoUri)
                put(C_STARRED, System.currentTimeMillis())
            }
            db(ctx).insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        } catch (_: Exception) {}
    }

    fun unstar(ctx: Context, number: String) {
        CallBackReminderScheduler.cancel(ctx, number)
        try {
            db(ctx).delete(TABLE, "$C_MATCH=?", arrayOf(matchKey(number)))
        } catch (_: Exception) {}
    }

    fun isStarred(ctx: Context, number: String): Boolean {
        return try {
            db(ctx).rawQuery(
                "SELECT $C_ID FROM $TABLE WHERE $C_MATCH=? LIMIT 1",
                arrayOf(matchKey(number))
            ).use { it.moveToFirst() }
        } catch (_: Exception) {
            false
        }
    }

    /** Fast lookup set of all starred match keys, for tagging call-log rows. */
    fun loadKeys(ctx: Context): Set<String> {
        val out = mutableSetOf<String>()
        try {
            db(ctx).rawQuery("SELECT $C_MATCH FROM $TABLE", null)
                .use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        } catch (_: Exception) {}
        return out
    }

    fun loadAll(ctx: Context): List<StarredEntry> {
        val out = mutableListOf<StarredEntry>()
        try {
            db(ctx).rawQuery("SELECT * FROM $TABLE ORDER BY $C_STARRED DESC", null)
                .use { c -> while (c.moveToNext()) out.add(row(c)) }
        } catch (_: Exception) {}
        return out
    }

    fun updateNotes(ctx: Context, number: String, notes: String) {
        try {
            val cv = ContentValues().apply { put(C_NOTES, notes) }
            db(ctx).update(TABLE, cv, "$C_MATCH=?", arrayOf(matchKey(number)))
        } catch (_: Exception) {}
    }

    fun notesFor(ctx: Context, number: String): String {
        return try {
            db(ctx).rawQuery(
                "SELECT $C_NOTES FROM $TABLE WHERE $C_MATCH=? LIMIT 1",
                arrayOf(matchKey(number))
            ).use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }
        } catch (_: Exception) {
            ""
        }
    }

    fun clearAll(ctx: Context) {
        loadAll(ctx).forEach { if (it.reminderAt != null) CallBackReminderScheduler.cancel(ctx, it.number) }
        try { db(ctx).delete(TABLE, null, null) } catch (_: Exception) {}
    }

    /** Sets (or clears, when [atMillis] is null) the "call back" reminder time. */
    fun setReminderAt(ctx: Context, number: String, atMillis: Long?) {
        try {
            val cv = ContentValues().apply {
                if (atMillis == null) putNull(C_REMINDER) else put(C_REMINDER, atMillis)
            }
            db(ctx).update(TABLE, cv, "$C_MATCH=?", arrayOf(matchKey(number)))
        } catch (_: Exception) {}
    }

    fun find(ctx: Context, number: String): StarredEntry? {
        return try {
            db(ctx).rawQuery(
                "SELECT * FROM $TABLE WHERE $C_MATCH=? LIMIT 1",
                arrayOf(matchKey(number))
            ).use { c -> if (c.moveToFirst()) row(c) else null }
        } catch (_: Exception) {
            null
        }
    }

    private fun row(c: Cursor) = StarredEntry(
        id         = c.getLong(c.getColumnIndexOrThrow(C_ID)),
        number     = c.getString(c.getColumnIndexOrThrow(C_NUMBER)) ?: "",
        matchKey   = c.getString(c.getColumnIndexOrThrow(C_MATCH)) ?: "",
        name       = c.getString(c.getColumnIndexOrThrow(C_NAME)),
        photoUri   = c.getString(c.getColumnIndexOrThrow(C_PHOTO))
                        ?.ifBlank { null }?.let { Uri.parse(it) },
        starredAt  = c.getLong(c.getColumnIndexOrThrow(C_STARRED)),
        notes      = c.getString(c.getColumnIndexOrThrow(C_NOTES)),
        reminderAt = c.getColumnIndex(C_REMINDER).let { idx ->
            if (idx < 0 || c.isNull(idx)) null else c.getLong(idx)
        }
    )
}
