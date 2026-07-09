package com.puretech.dialer

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract

/** One recents row (consecutive calls with the same number are grouped). */
data class CallLogEntry(
    val number: String,
    val name: String?,
    val photoUri: Uri?,
    val type: Int,
    val date: Long,
    val count: Int,
    val isHd: Boolean,
    val isWifi: Boolean = false,
    /** Phone number type (Mobile/Home/Work…) cached on the call, when known. */
    val numberType: Int = 0,
    val numberLabel: String? = null,
    /** Geocoded location (e.g. "New City, NY") for unknown callers. */
    val geocoded: String? = null,
    /** SIM label (e.g. "SIM 1") — only set on dual-SIM devices. */
    val simLabel: String? = null,
    /** True when this row is a contact search result rather than a real call. */
    val asContact: Boolean = false
)

/** A single call (for the per-number History screen). */
data class CallDetail(val type: Int, val date: Long, val duration: Long)

/** Aggregate call totals across the whole call log (since ever). */
data class CallStats(
    val incomingCount: Int,
    val incomingDuration: Long,
    val outgoingCount: Int,
    val outgoingDuration: Long,
    val missedCount: Int
) {
    val totalDuration: Long get() = incomingDuration + outgoingDuration
    val answeredCount: Int get() = incomingCount + outgoingCount
}

object CallLogRepository {

    private const val FEATURE_HD_VOICE = 0x04  // CallLog.Calls.FEATURES_HD_VOICE
    private const val FEATURE_WIFI = 0x08      // CallLog.Calls.FEATURES_WIFI

    fun load(context: Context, missedOnly: Boolean = false): List<CallLogEntry> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.CACHED_PHOTO_URI,
            CallLog.Calls.CACHED_NUMBER_TYPE,
            CallLog.Calls.CACHED_NUMBER_LABEL,
            CallLog.Calls.GEOCODED_LOCATION,
            CallLog.Calls.PHONE_ACCOUNT_ID,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.FEATURES
        )
        // "Missed" includes declined/rejected calls.
        val selection = if (missedOnly) "${CallLog.Calls.TYPE} IN (?, ?)" else null
        val args = if (missedOnly) arrayOf(
            CallLog.Calls.MISSED_TYPE.toString(), CallLog.Calls.REJECTED_TYPE.toString()
        ) else null

        // Resolve SIM labels once per load (only meaningful on dual-SIM devices).
        val simLabels: Map<String, String> =
            if (CallingAccounts.isMultiSim(context))
                CallingAccounts.list(context).associate { it.id to CallingAccounts.label(context, it) }
            else emptyMap()

        val raw = ArrayList<CallLogEntry>()
        try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection, selection, args,
            "${CallLog.Calls.DATE} DESC"
        )?.use { c ->
            val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
            val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val photoIdx = c.getColumnIndex(CallLog.Calls.CACHED_PHOTO_URI)
            val nTypeIdx = c.getColumnIndex(CallLog.Calls.CACHED_NUMBER_TYPE)
            val nLabelIdx = c.getColumnIndex(CallLog.Calls.CACHED_NUMBER_LABEL)
            val geoIdx = c.getColumnIndex(CallLog.Calls.GEOCODED_LOCATION)
            val acctIdx = c.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
            val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
            val featIdx = c.getColumnIndex(CallLog.Calls.FEATURES)
            while (c.moveToNext()) {
                val number = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                // CACHED_NAME/PHOTO come back as "" (not null) when the system never
                // matched the number to a contact — treat blank as "no name" so the
                // resolver below fills it in via PhoneLookup.
                val name = if (nameIdx >= 0) c.getString(nameIdx)?.ifBlank { null } else null
                val photo = if (photoIdx >= 0)
                    c.getString(photoIdx)?.ifBlank { null }?.let { Uri.parse(it) } else null
                val nType = if (nTypeIdx >= 0) c.getInt(nTypeIdx) else 0
                val nLabel = if (nLabelIdx >= 0) c.getString(nLabelIdx) else null
                val geo = if (geoIdx >= 0) c.getString(geoIdx) else null
                val acctId = if (acctIdx >= 0) c.getString(acctIdx) else null
                val type = if (typeIdx >= 0) c.getInt(typeIdx) else CallLog.Calls.INCOMING_TYPE
                val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                val feat = if (featIdx >= 0) c.getInt(featIdx) else 0
                raw.add(
                    CallLogEntry(
                        number, name, photo, type, date, 1, (feat and FEATURE_HD_VOICE) != 0,
                        isWifi = (feat and FEATURE_WIFI) != 0,
                        numberType = nType, numberLabel = nLabel, geocoded = geo,
                        simLabel = simLabels[acctId]
                    )
                )
            }
        }
        } catch (e: Exception) {
            android.util.Log.w("M5CallLog", "load failed: ${e.message}")
        }

        // Merge in local-store entries that the system log has already trimmed away.
        val combined = mergeLocal(context, raw, missedOnly)
        return resolveNames(context, group(combined))
    }

    private data class ContactInfo(val name: String, val photo: Uri?, val type: Int, val label: String?)

    /**
     * The system's CACHED_NAME can be empty when a call arrived as "+1 845…" but
     * the contact is saved as a bare 10-digit number (or the cache is stale).
     * Fill in the contact name/photo ourselves via PhoneLookup, which normalizes
     * the country code, so those rows show the contact instead of a bare number.
     */
    private fun resolveNames(context: Context, entries: List<CallLogEntry>): List<CallLogEntry> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return entries
        val cache = HashMap<String, ContactInfo?>()
        return entries.map { e ->
            // The system call log caches the name but often NOT the photo URI, so
            // look the contact up whenever we're missing the name OR the photo.
            if ((e.name != null && e.photoUri != null) || e.number.isBlank()) e
            else {
                val info = cache.getOrPut(e.number) { lookupContact(context, e.number) }
                if (info != null) e.copy(
                    name = e.name ?: info.name,
                    photoUri = e.photoUri ?: info.photo,
                    numberType = if (e.numberType > 0) e.numberType else info.type,
                    numberLabel = e.numberLabel ?: info.label
                ) else e
            }
        }
    }

    private fun lookupContact(context: Context, number: String): ContactInfo? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI,
                    ContactsContract.PhoneLookup.TYPE,
                    ContactsContract.PhoneLookup.LABEL
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) ContactInfo(
                        name,
                        c.getString(1)?.let { Uri.parse(it) },
                        c.getInt(2),
                        c.getString(3)
                    ) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Merge consecutive entries that share the same number into one row + count. */
    private fun group(entries: List<CallLogEntry>): List<CallLogEntry> {
        val out = ArrayList<CallLogEntry>()
        for (e in entries) {
            val last = out.lastOrNull()
            if (last != null && sameNumber(last.number, e.number)) {
                out[out.size - 1] = last.copy(
                    count = last.count + 1,
                    isHd = last.isHd || e.isHd,
                    isWifi = last.isWifi || e.isWifi
                )
            } else {
                out.add(e)
            }
        }
        return out
    }

    /**
     * Searches all call log entries (system log + local store) for entries whose
     * number or cached name contains [query]. Reuses [load] so the result is
     * already grouped, name-resolved, and sorted newest-first.
     */
    fun search(context: Context, query: String): List<CallLogEntry> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        return load(context).filter { e ->
            e.number.contains(q) || e.name?.lowercase()?.contains(q) == true
        }
    }

    /**
     * Deletes every call matching [number] from BOTH the system call log and
     * [LocalCallStore]. Deleting only the system provider left entries that had
     * already been mirrored to (or exist only in) the local store — the local
     * store exists specifically because some ROMs trim/represent the system log
     * differently, so a row that "deletes" from one can still resurface from the
     * other on the next [load]. Matches by trailing-digits, like [loadForNumber],
     * since the exact string stored in CallLog.Calls.NUMBER isn't guaranteed to
     * match what's held in memory across ROMs.
     */
    fun delete(context: Context, number: String) {
        val digits = number.filter { it.isDigit() }
        val last7 = if (digits.length >= 7) digits.takeLast(7) else digits
        try {
            if (last7.isNotEmpty()) {
                context.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI, "${CallLog.Calls.NUMBER} LIKE ?", arrayOf("%$last7")
                )
            } else {
                context.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI, "${CallLog.Calls.NUMBER} = ?", arrayOf(number)
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("M5CallLog", "delete failed: ${e.message}")
        }
        LocalCallStore.delete(context, last7, number)
    }

    /** Wipes every call log entry — system log + local store. */
    fun deleteAll(context: Context) {
        try {
            context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
        } catch (e: Exception) {
            android.util.Log.w("M5CallLog", "deleteAll failed: ${e.message}")
        }
        LocalCallStore.deleteAll(context)
    }

    /** Every individual call (with duration) for one number — for the History screen. */
    fun loadForNumber(context: Context, number: String): List<CallDetail> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val digits = number.filter { it.isDigit() }
        val last = if (digits.length >= 7) digits.takeLast(7) else digits
        val out = ArrayList<CallDetail>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.NUMBER} LIKE ?", arrayOf("%$last"),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                while (c.moveToNext()) {
                    val n = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                    if (!sameNumber(n, number)) continue
                    out.add(
                        CallDetail(
                            if (typeIdx >= 0) c.getInt(typeIdx) else 0,
                            if (dateIdx >= 0) c.getLong(dateIdx) else 0L,
                            if (durIdx >= 0) c.getLong(durIdx) else 0L
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("M5CallLog", "history failed: ${e.message}")
        }

        // Append local-store entries for this number not already in the system log.
        val sysKeys = out.map { dedupKey(number, it.type, it.date) }.toHashSet()
        LocalCallStore.loadForNumber(context, last)
            .filter { sameNumber(it.number, number) }
            .filter { dedupKey(it.number, it.type, it.date) !in sysKeys }
            .mapTo(out) { CallDetail(it.type, it.date, it.duration) }
        out.sortByDescending { it.date }
        return out
    }

    /** Aggregate totals over the entire call log (system + local store). */
    fun stats(context: Context): CallStats {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return CallStats(0, 0L, 0, 0L, 0)
        var inC = 0; var inD = 0L; var outC = 0; var outD = 0L; var missed = 0
        var sysOldest = Long.MAX_VALUE
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE),
                null, null, null
            )?.use { c ->
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val durIdx  = c.getColumnIndex(CallLog.Calls.DURATION)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                while (c.moveToNext()) {
                    val type = if (typeIdx >= 0) c.getInt(typeIdx) else 0
                    val dur  = if (durIdx  >= 0) c.getLong(durIdx)  else 0L
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else Long.MAX_VALUE
                    if (date < sysOldest) sysOldest = date
                    when (type) {
                        CallLog.Calls.OUTGOING_TYPE -> { outC++; outD += dur }
                        CallLog.Calls.INCOMING_TYPE,
                        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> { inC++; inD += dur }
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> missed++
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("M5CallLog", "stats failed: ${e.message}")
        }
        // Add local-store entries that predate everything in the system log.
        LocalCallStore.loadBefore(context, sysOldest).forEach {
            when (it.type) {
                CallLog.Calls.OUTGOING_TYPE -> { outC++; outD += it.duration }
                CallLog.Calls.INCOMING_TYPE -> { inC++; inD += it.duration }
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> missed++
            }
        }
        return CallStats(inC, inD, outC, outD, missed)
    }

    /** Every call's timestamp (millis) — for the call-activity graph. */
    fun callDates(context: Context): LongArray {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return LongArray(0)
        val out = ArrayList<Long>()
        var sysOldest = Long.MAX_VALUE
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                while (c.moveToNext()) {
                    val d = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    out.add(d)
                    if (d < sysOldest) sysOldest = d
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("M5CallLog", "callDates failed: ${e.message}")
        }
        LocalCallStore.loadBefore(context, sysOldest).mapTo(out) { it.date }
        out.sortDescending()
        return out.toLongArray()
    }

    /**
     * Two numbers are the same contact when their national digits match. A
     * 10-digit number and the same number with a "+1" country code (11 digits)
     * compare equal by looking at the last 10 digits.
     */
    /**
     * Appends local-store entries that are not already present in [sysRaw],
     * then returns the combined list sorted newest-first.
     *
     * Deduplication uses a "bucket key": last-7 digits + call type + date
     * rounded to 10 s. This is loose enough to absorb minor timestamp
     * differences between the system log and our local record, but tight
     * enough that two different calls from the same number never collide.
     */
    private fun mergeLocal(
        context: Context,
        sysRaw: List<CallLogEntry>,
        missedOnly: Boolean
    ): List<CallLogEntry> {
        val sysKeys = sysRaw.map { dedupKey(it.number, it.type, it.date) }.toHashSet()
        val extra = LocalCallStore.loadAll(context)
            .filter { !missedOnly || it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE }
            .filter { dedupKey(it.number, it.type, it.date) !in sysKeys }
            .map { localToEntry(it) }
        if (extra.isEmpty()) return sysRaw
        return (sysRaw + extra).sortedByDescending { it.date }
    }

    private fun dedupKey(number: String, type: Int, date: Long): String {
        val digits = number.filter { it.isDigit() }.takeLast(7)
        return "$digits:$type:${date / 10_000}"
    }

    private fun localToEntry(c: LocalCallStore.StoredCall) = CallLogEntry(
        number      = c.number,
        name        = c.name,
        photoUri    = c.photoUri,
        type        = c.type,
        date        = c.date,
        count       = 1,
        isHd        = c.isHd,
        isWifi      = c.isWifi,
        numberType  = c.numberType,
        numberLabel = c.numberLabel,
        geocoded    = c.geocoded,
        simLabel    = c.simLabel
    )

    private fun sameNumber(a: String, b: String): Boolean {
        val da = a.filter { it.isDigit() }
        val db = b.filter { it.isDigit() }
        if (da.isEmpty() || db.isEmpty()) return da == db
        val n = minOf(da.length, db.length)
        return when {
            n >= 10 -> da.takeLast(10) == db.takeLast(10)
            n >= 7 -> da.takeLast(n) == db.takeLast(n)
            else -> da == db
        }
    }
}
