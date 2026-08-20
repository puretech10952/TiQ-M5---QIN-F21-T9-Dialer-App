package com.puretech.dialer

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import java.util.Calendar

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
    val asContact: Boolean = false,
    /** True when this number is in the Starred list ([StarredStore]). */
    val isStarred: Boolean = false
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

/** A single "record" call: who, how long, when. Used for the longest-call stat. */
data class CallRecord(val name: String?, val number: String, val duration: Long, val date: Long)

/** One calendar day's totals, for the busiest-day-ever stat. */
data class DayRecord(val date: Long, val count: Int, val duration: Long)

/** Talk time in one weekday (0 = Sunday .. 6 = Saturday) x daypart (0 = 12a-4a
 *  .. 5 = 8p-12a) cell of the busy-times heatmap grid. */
data class HeatmapCell(val weekday: Int, val daypart: Int, val count: Int, val duration: Long)

/** One contact's total talk time across the whole call history. */
data class ContactTalkTime(val name: String, val duration: Long, val count: Int)

/** Deep aggregate analytics across the whole call log: averages, records,
 *  weekday/daypart distribution, top contacts, and week-over-week / run-rate
 *  trends. Powers the Insights screen -- see [CallLogRepository.deepStats]. */
data class DeepCallStats(
    val avgDurationOverall: Long,
    val avgDurationIncoming: Long,
    val avgDurationOutgoing: Long,
    val avgCallsPerActiveDay: Double,
    val answerRatePercent: Int,
    val longestCall: CallRecord?,
    val busiestDayEver: DayRecord?,
    val heatmapCells: List<HeatmapCell>,
    val topContacts: List<ContactTalkTime>,
    val thisWeekDuration: Long,
    val lastWeekDuration: Long,
    val projectedThisMonth: Long
)

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
        val resolved = resolveNames(context, group(combined))
        val starredKeys = StarredStore.loadKeys(context)
        // Display-only reformat ("Name format" setting) — applied last, after
        // grouping/dedup/sort all keyed off the original names, so it never
        // affects matching, only what's shown.
        return resolved.map {
            it.copy(
                name = NameFormat.apply(context, it.name),
                isStarred = starredMatchKey(it.number) in starredKeys
            )
        }
    }

    private fun starredMatchKey(number: String): String {
        val digits = number.filter { it.isDigit() }.takeLast(7)
        return digits.ifEmpty { number }
    }

    private data class ContactInfo(val name: String, val photo: Uri?, val type: Int, val label: String?)

    /**
     * The system's CACHED_NAME can be empty when a call arrived as "+1 845…" but
     * the contact is saved as a bare 10-digit number, or simply stale after the
     * contact was renamed or a number was newly linked to a contact — Android
     * writes CACHED_NAME once when the call happens and doesn't retroactively
     * fix up old rows when a contact changes later. Rather than only filling in
     * rows the system left blank, this always prefers a live Contacts match
     * over whatever's cached, so a rename/re-link shows up the next time the
     * list reloads (already happens on every visit to this tab) instead of
     * whenever the system's own cache eventually catches up.
     *
     * This used to run one PhoneLookup ContentResolver query per row missing a
     * name/photo — for call logs with many unknown/uncached numbers that's
     * dozens of sequential IPC round-trips, the single biggest cost in loading
     * the list. One bulk query over every phone number on the device, matched
     * in memory by trailing digits (the same matching convention used
     * everywhere else in this file), replaces all of them.
     */
    private fun resolveNames(context: Context, entries: List<CallLogEntry>): List<CallLogEntry> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return entries
        if (entries.none { it.number.isNotBlank() }) return entries

        val byDigits = HashMap<String, ContactInfo>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                ),
                null, null, null
            )?.use { c ->
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val photoIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val typeIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
                while (c.moveToNext()) {
                    val num = if (numIdx >= 0) c.getString(numIdx) else null
                    val digits = num?.filter { it.isDigit() }?.takeLast(10) ?: continue
                    if (digits.length < 7) continue
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                    if (name.isNullOrBlank()) continue
                    // First match wins per number; don't overwrite once set.
                    if (!byDigits.containsKey(digits)) {
                        byDigits[digits] = ContactInfo(
                            name,
                            (if (photoIdx >= 0) c.getString(photoIdx) else null)?.let { Uri.parse(it) },
                            if (typeIdx >= 0) c.getInt(typeIdx) else 0,
                            if (labelIdx >= 0) c.getString(labelIdx) else null
                        )
                    }
                }
            }
        } catch (e: Exception) {
            return entries
        }

        return entries.map { e ->
            if (e.number.isBlank()) return@map e
            val info = byDigits[e.number.filter { it.isDigit() }.takeLast(10)]
            when {
                // Live contact match: always wins over CACHED_NAME/PHOTO, even
                // if the system already cached something — that cached value
                // could be exactly what's now stale (an old name, or a number
                // that's only just been linked to a contact).
                info != null -> e.copy(
                    name = info.name,
                    photoUri = info.photo,
                    numberType = info.type,
                    numberLabel = info.label
                )
                // No live contact for this number (never saved, or since
                // deleted) — fall back to whatever the system cached.
                else -> e
            }
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

    /** Every call's (timestamp, duration) pair -- for the call-activity graph's
     *  Calls/Minutes toggle on the "Call durations" screen. */
    fun callLog(context: Context): List<Pair<Long, Long>> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val out = ArrayList<Pair<Long, Long>>()
        var sysOldest = Long.MAX_VALUE
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                while (c.moveToNext()) {
                    val d = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    val dur = if (durIdx >= 0) c.getLong(durIdx) else 0L
                    out.add(d to dur)
                    if (d < sysOldest) sysOldest = d
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("M5CallLog", "callLog failed: ${e.message}")
        }
        LocalCallStore.loadBefore(context, sysOldest).mapTo(out) { it.date to it.duration }
        out.sortByDescending { it.first }
        return out
    }

    /** Deep aggregate analytics (system + local store) -- see [DeepCallStats]. */
    fun deepStats(context: Context): DeepCallStats {
        val empty = DeepCallStats(0, 0, 0, 0.0, 0, null, null, emptyList(), emptyList(), 0, 0, 0)
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return empty

        data class Row(val number: String, val name: String?, val type: Int, val date: Long, val duration: Long)
        val rows = ArrayList<Row>()
        var sysOldest = Long.MAX_VALUE
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE
                ),
                null, null, null
            )?.use { c ->
                val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                while (c.moveToNext()) {
                    val number = if (numIdx >= 0) c.getString(numIdx).orEmpty() else ""
                    val name = if (nameIdx >= 0) c.getString(nameIdx)?.ifBlank { null } else null
                    val type = if (typeIdx >= 0) c.getInt(typeIdx) else 0
                    val dur = if (durIdx >= 0) c.getLong(durIdx) else 0L
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else Long.MAX_VALUE
                    if (date < sysOldest) sysOldest = date
                    rows.add(Row(number, name, type, date, dur))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("M5CallLog", "deepStats failed: ${e.message}")
        }
        LocalCallStore.loadBefore(context, sysOldest).forEach {
            rows.add(Row(it.number, it.name, it.type, it.date, it.duration))
        }
        if (rows.isEmpty()) return empty

        var inCount = 0; var inDur = 0L
        var outCount = 0; var outDur = 0L
        var missed = 0
        var longest: Row? = null
        val activeDays = HashSet<Long>()
        val gridCount = Array(7) { IntArray(6) }
        val gridDur = Array(7) { LongArray(6) }
        val dayCount = HashMap<Long, Int>(); val dayDur = HashMap<Long, Long>()
        val contactDur = HashMap<String, Long>()
        val contactCount = HashMap<String, Int>()
        val contactName = HashMap<String, String>()

        val now = System.currentTimeMillis()
        val weekMs = 7L * 24 * 3600 * 1000
        val thisWeekStart = now - weekMs
        val lastWeekStart = now - 2 * weekMs
        var thisWeekDur = 0L; var lastWeekDur = 0L

        val monthCal = Calendar.getInstance()
        val dayOfMonth = monthCal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        monthCal.set(Calendar.DAY_OF_MONTH, 1)
        monthCal.set(Calendar.HOUR_OF_DAY, 0); monthCal.set(Calendar.MINUTE, 0)
        monthCal.set(Calendar.SECOND, 0); monthCal.set(Calendar.MILLISECOND, 0)
        val monthStart = monthCal.timeInMillis
        var monthSoFarDur = 0L

        val cal = Calendar.getInstance()
        for (r in rows) {
            when (r.type) {
                CallLog.Calls.OUTGOING_TYPE -> { outCount++; outDur += r.duration }
                CallLog.Calls.INCOMING_TYPE,
                CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> { inCount++; inDur += r.duration }
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> missed++
            }
            if (r.duration <= 0) continue

            val cur = longest
            if (cur == null || r.duration > cur.duration) longest = r

            cal.timeInMillis = r.date
            val weekday = cal.get(Calendar.DAY_OF_WEEK) - 1
            val daypart = (cal.get(Calendar.HOUR_OF_DAY) / 4).coerceIn(0, 5)
            gridCount[weekday][daypart]++; gridDur[weekday][daypart] += r.duration

            val df = dayFloor(r.date)
            activeDays.add(df)
            dayCount[df] = (dayCount[df] ?: 0) + 1
            dayDur[df] = (dayDur[df] ?: 0L) + r.duration

            if (r.date >= thisWeekStart) thisWeekDur += r.duration
            else if (r.date >= lastWeekStart) lastWeekDur += r.duration
            if (r.date >= monthStart) monthSoFarDur += r.duration

            val digits = r.number.filter { it.isDigit() }
            val key = if (digits.length >= 7) digits.takeLast(7) else digits
            if (key.isNotEmpty()) {
                contactDur[key] = (contactDur[key] ?: 0L) + r.duration
                contactCount[key] = (contactCount[key] ?: 0) + 1
                if (r.name != null && contactName[key] == null) contactName[key] = r.name
            }
        }

        val answered = inCount + outCount
        val totalDur = inDur + outDur
        val busiestDay = dayDur.maxByOrNull { it.value }
        val topContacts = contactDur.entries.sortedByDescending { it.value }.take(5).map { (key, dur) ->
            ContactTalkTime(contactName[key] ?: key, dur, contactCount[key] ?: 0)
        }
        val projected = if (dayOfMonth > 0) monthSoFarDur * daysInMonth / dayOfMonth else 0L
        val heatmapCells = (0..6).flatMap { wd ->
            (0..5).map { dp -> HeatmapCell(wd, dp, gridCount[wd][dp], gridDur[wd][dp]) }
        }

        return DeepCallStats(
            avgDurationOverall = if (answered > 0) totalDur / answered else 0L,
            avgDurationIncoming = if (inCount > 0) inDur / inCount else 0L,
            avgDurationOutgoing = if (outCount > 0) outDur / outCount else 0L,
            avgCallsPerActiveDay = if (activeDays.isNotEmpty()) answered.toDouble() / activeDays.size else 0.0,
            answerRatePercent = if (inCount + missed > 0) (inCount * 100) / (inCount + missed) else 0,
            longestCall = longest?.let { CallRecord(it.name, it.number, it.duration, it.date) },
            busiestDayEver = busiestDay?.let { DayRecord(it.key, dayCount[it.key] ?: 0, it.value) },
            heatmapCells = heatmapCells,
            topContacts = topContacts,
            thisWeekDuration = thisWeekDur,
            lastWeekDuration = lastWeekDur,
            projectedThisMonth = projected
        )
    }

    private fun dayFloor(date: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = date
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
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
        // Only entries older than everything still in the system log can possibly
        // be missing from it — anything the system already has doesn't need a
        // local fallback. Bounding the local query by date (like stats()/
        // callDates() already do) lets SQLite do the filtering instead of
        // pulling the entire local history into memory on every load, which
        // got slower and slower as that table grew past the system's ~500-row
        // cap. The dedup check stays as a cheap safety net, but by construction
        // a local row with date < sysOldest can never collide with a system row
        // (they're all >= sysOldest), so it should rarely find anything to drop.
        val sysOldest = sysRaw.minOfOrNull { it.date } ?: Long.MAX_VALUE
        val sysKeys = sysRaw.map { dedupKey(it.number, it.type, it.date) }.toHashSet()
        val extra = LocalCallStore.loadBefore(context, sysOldest)
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
