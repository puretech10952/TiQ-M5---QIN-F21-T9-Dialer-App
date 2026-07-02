package com.puretech.dialer

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

/**
 * Permanent local call history in a private SQLite database.
 *
 * Android's system call log auto-deletes the oldest entries once it exceeds
 * ~500 rows. Every call is mirrored here by [CallService] at the moment it
 * ends so nothing is ever lost. [CallLogRepository] merges this store with
 * the system log, preferring the system log for recent calls and falling back
 * to this store for older ones that the system has already trimmed.
 */
object LocalCallStore {

    private const val DB_NAME = "local_calls.db"
    private const val DB_VERSION = 1
    private const val TABLE = "calls"

    private const val C_ID        = "_id"
    private const val C_NUMBER    = "number"
    private const val C_TYPE      = "type"
    private const val C_DATE      = "date"
    private const val C_DURATION  = "duration"
    private const val C_HD        = "hd"
    private const val C_WIFI      = "wifi"
    private const val C_NAME      = "name"
    private const val C_PHOTO     = "photo_uri"
    private const val C_NUM_TYPE  = "number_type"
    private const val C_NUM_LABEL = "number_label"
    private const val C_GEO       = "geocoded"
    private const val C_SIM       = "sim_label"

    data class StoredCall(
        val number: String,
        val type: Int,
        val date: Long,
        val duration: Long,
        val isHd: Boolean,
        val isWifi: Boolean,
        val name: String?,
        val photoUri: Uri?,
        val numberType: Int,
        val numberLabel: String?,
        val geocoded: String?,
        val simLabel: String?
    )

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE $TABLE (
                    $C_ID        INTEGER PRIMARY KEY AUTOINCREMENT,
                    $C_NUMBER    TEXT    NOT NULL,
                    $C_TYPE      INTEGER NOT NULL,
                    $C_DATE      INTEGER NOT NULL,
                    $C_DURATION  INTEGER NOT NULL DEFAULT 0,
                    $C_HD        INTEGER NOT NULL DEFAULT 0,
                    $C_WIFI      INTEGER NOT NULL DEFAULT 0,
                    $C_NAME      TEXT,
                    $C_PHOTO     TEXT,
                    $C_NUM_TYPE  INTEGER NOT NULL DEFAULT 0,
                    $C_NUM_LABEL TEXT,
                    $C_GEO       TEXT,
                    $C_SIM       TEXT
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_calls_date   ON $TABLE($C_DATE DESC)")
            db.execSQL("CREATE INDEX idx_calls_number ON $TABLE($C_NUMBER)")
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    }

    @Volatile private var helper: Helper? = null

    private fun db(ctx: Context): SQLiteDatabase =
        (helper ?: synchronized(this) {
            helper ?: Helper(ctx.applicationContext).also { helper = it }
        }).writableDatabase

    /** Called by [CallService] at the end of every call. Never duplicate-checks —
     *  that is handled in [CallLogRepository] when displaying the merged log. */
    fun record(
        ctx: Context,
        number: String, type: Int, date: Long, duration: Long,
        isHd: Boolean, isWifi: Boolean,
        name: String?, photoUri: String?,
        numberType: Int, numberLabel: String?,
        geocoded: String?, simLabel: String?
    ) {
        try {
            val cv = ContentValues().apply {
                put(C_NUMBER, number)
                put(C_TYPE, type)
                put(C_DATE, date)
                put(C_DURATION, duration)
                put(C_HD, if (isHd) 1 else 0)
                put(C_WIFI, if (isWifi) 1 else 0)
                put(C_NAME, name)
                put(C_PHOTO, photoUri)
                put(C_NUM_TYPE, numberType)
                put(C_NUM_LABEL, numberLabel)
                put(C_GEO, geocoded)
                put(C_SIM, simLabel)
            }
            db(ctx).insert(TABLE, null, cv)
        } catch (_: Exception) {}
    }

    /** All stored calls, newest first. */
    fun loadAll(ctx: Context): List<StoredCall> {
        val out = mutableListOf<StoredCall>()
        try {
            db(ctx).rawQuery("SELECT * FROM $TABLE ORDER BY $C_DATE DESC", null)
                .use { c -> while (c.moveToNext()) out.add(row(c)) }
        } catch (_: Exception) {}
        return out
    }

    /** Calls whose number ends with [last7], newest first. */
    fun loadForNumber(ctx: Context, last7: String): List<StoredCall> {
        val out = mutableListOf<StoredCall>()
        try {
            db(ctx).rawQuery(
                "SELECT * FROM $TABLE WHERE $C_NUMBER LIKE ? ORDER BY $C_DATE DESC",
                arrayOf("%$last7")
            ).use { c -> while (c.moveToNext()) out.add(row(c)) }
        } catch (_: Exception) {}
        return out
    }

    /** Calls older than [beforeDate] (epoch ms), for stats / graph augmentation. */
    fun loadBefore(ctx: Context, beforeDate: Long): List<StoredCall> {
        val out = mutableListOf<StoredCall>()
        try {
            db(ctx).rawQuery(
                "SELECT * FROM $TABLE WHERE $C_DATE < ? ORDER BY $C_DATE DESC",
                arrayOf(beforeDate.toString())
            ).use { c -> while (c.moveToNext()) out.add(row(c)) }
        } catch (_: Exception) {}
        return out
    }

    private fun row(c: Cursor) = StoredCall(
        number      = c.getString(c.getColumnIndexOrThrow(C_NUMBER)) ?: "",
        type        = c.getInt(c.getColumnIndexOrThrow(C_TYPE)),
        date        = c.getLong(c.getColumnIndexOrThrow(C_DATE)),
        duration    = c.getLong(c.getColumnIndexOrThrow(C_DURATION)),
        isHd        = c.getInt(c.getColumnIndexOrThrow(C_HD)) != 0,
        isWifi      = c.getInt(c.getColumnIndexOrThrow(C_WIFI)) != 0,
        name        = c.getString(c.getColumnIndexOrThrow(C_NAME)),
        photoUri    = c.getString(c.getColumnIndexOrThrow(C_PHOTO))
                        ?.ifBlank { null }?.let { Uri.parse(it) },
        numberType  = c.getInt(c.getColumnIndexOrThrow(C_NUM_TYPE)),
        numberLabel = c.getString(c.getColumnIndexOrThrow(C_NUM_LABEL)),
        geocoded    = c.getString(c.getColumnIndexOrThrow(C_GEO)),
        simLabel    = c.getString(c.getColumnIndexOrThrow(C_SIM))
    )
}
