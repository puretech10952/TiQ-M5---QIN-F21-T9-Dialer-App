package com.puretech.dialer

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone

/** A single callable phone entry, with precomputed data for T9 matching/ranking. */
data class Contact(
    val name: String,
    val number: String,            // original, for display
    val digits: String,            // digits only, for number matching
    val nameT9: String,            // T9 of all letters in the name (anywhere match)
    val wordT9: List<String>,      // T9 of each name word (first/last-name prefix match)
    val photoUri: Uri?,
    val timesContacted: Int,
    val lastTimeContacted: Long,
    val lookupKey: String? = null, // stable per-contact id; set for favorites, used to
                                    // remember a chosen number when a contact has several
    val isQuickDial: Boolean = false // true for the synthetic row QuickDial pins to the
                                      // top of the dialer suggestions
)

/** One of a contact's phone numbers, with its type label (Mobile/Home/Work/...). */
data class ContactNumber(val number: String, val label: String)

/** Maps letters to their T9 keypad digit. */
object T9 {
    fun digitFor(c: Char): Char? = when (c.lowercaseChar()) {
        in 'a'..'c' -> '2'
        in 'd'..'f' -> '3'
        in 'g'..'i' -> '4'
        in 'j'..'l' -> '5'
        in 'm'..'o' -> '6'
        in 'p'..'s' -> '7'
        in 't'..'v' -> '8'
        in 'w'..'z' -> '9'
        else -> null
    }

    /** All letters of [s] mapped to T9 digits (non-letters dropped). */
    fun encode(s: String): String = buildString {
        for (c in s) digitFor(c)?.let { append(it) }
    }
}

object ContactsRepository {

    /** Load every phone number with its contact name + usage stats. */
    fun load(context: Context): List<Contact> {
        val out = ArrayList<Contact>()
        // Before we're the default dialer the contacts permission isn't granted;
        // querying anyway throws SecurityException on this background thread and
        // crashes the app. Bail out quietly until we have access.
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return out
        val projection = arrayOf(
            Phone.DISPLAY_NAME,
            Phone.NUMBER,
            Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.Contacts.TIMES_CONTACTED,
            ContactsContract.Contacts.LAST_TIME_CONTACTED
        )
        try {
        context.contentResolver.query(
            Phone.CONTENT_URI, projection, null, null, null
        )?.use { c ->
            val nameIdx = c.getColumnIndex(Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(Phone.NUMBER)
            val photoIdx = c.getColumnIndex(Phone.PHOTO_THUMBNAIL_URI)
            val timesIdx = c.getColumnIndex(ContactsContract.Contacts.TIMES_CONTACTED)
            val lastIdx = c.getColumnIndex(ContactsContract.Contacts.LAST_TIME_CONTACTED)
            while (c.moveToNext()) {
                val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                val number = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                if (number.isBlank()) continue
                val photo = if (photoIdx >= 0) c.getString(photoIdx)?.let { Uri.parse(it) } else null
                val times = if (timesIdx >= 0) c.getInt(timesIdx) else 0
                val last = if (lastIdx >= 0) c.getLong(lastIdx) else 0L
                // T9 fields are computed from the RAW name, before any display
                // reformatting below, so search matching never depends on the
                // "Name format" setting.
                val words = name.split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }
                out.add(
                    Contact(
                        name = NameFormat.apply(context, name) ?: name,
                        number = number,
                        digits = number.filter { it.isDigit() },
                        nameT9 = T9.encode(name),
                        wordT9 = words.map { T9.encode(it) },
                        photoUri = photo,
                        timesContacted = times,
                        lastTimeContacted = last
                    )
                )
            }
        }
        } catch (e: SecurityException) {
            // Permission revoked between the check and the query — ignore.
        }
        // The contacts provider's TIMES_CONTACTED is deprecated and reads 0 on
        // Android 11+, so fold in real call frequency/recency from the call log.
        mergeCallFrequency(context, out)
        return out
    }

    /**
     * Replace each contact's usage stats with how often / how recently we actually
     * called that number, counted from the call log. Used to rank suggestions so
     * frequently-called people surface even when another contact matches better.
     */
    private fun mergeCallFrequency(context: Context, contacts: MutableList<Contact>) {
        if (contacts.isEmpty()) return
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val counts = HashMap<String, Int>()
        val lasts = HashMap<String, Long>()
        try {
            context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE),
                null, null, null
            )?.use { c ->
                val numIdx = c.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                val dateIdx = c.getColumnIndex(android.provider.CallLog.Calls.DATE)
                while (c.moveToNext()) {
                    val key = freqKey(c.getString(numIdx) ?: continue)
                    if (key.isEmpty()) continue
                    counts[key] = (counts[key] ?: 0) + 1
                    val d = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    if (d > (lasts[key] ?: 0L)) lasts[key] = d
                }
            }
        } catch (e: Exception) {
            return
        }
        for (i in contacts.indices) {
            val c = contacts[i]
            val cnt = counts[freqKey(c.digits)] ?: 0
            if (cnt > 0) contacts[i] = c.copy(
                timesContacted = cnt,
                lastTimeContacted = maxOf(c.lastTimeContacted, lasts[freqKey(c.digits)] ?: 0L)
            )
        }
    }

    /** Per-number call counts in the trailing 7 days, and the set of numbers
     *  called 2+ times in the trailing 2 hours — used to rank the dial screen's
     *  pre-dial suggestions by recent activity (not lifetime totals). */
    data class RecentActivity(val weekCounts: Map<String, Int>, val urgentNumbers: Set<String>)

    fun recentActivity(context: Context): RecentActivity {
        val empty = RecentActivity(emptyMap(), emptySet())
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return empty
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24 * 60 * 60 * 1000
        val twoHoursAgo = now - 2L * 60 * 60 * 1000
        val weekCounts = HashMap<String, Int>()
        val last2hCounts = HashMap<String, Int>()
        try {
            context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE),
                "${android.provider.CallLog.Calls.DATE} >= ?", arrayOf(weekAgo.toString()), null
            )?.use { c ->
                val numIdx = c.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                val dateIdx = c.getColumnIndex(android.provider.CallLog.Calls.DATE)
                while (c.moveToNext()) {
                    val key = freqKey(c.getString(numIdx) ?: continue)
                    if (key.isEmpty()) continue
                    weekCounts[key] = (weekCounts[key] ?: 0) + 1
                    val d = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    if (d >= twoHoursAgo) last2hCounts[key] = (last2hCounts[key] ?: 0) + 1
                }
            }
        } catch (e: Exception) {
            return empty
        }
        return RecentActivity(weekCounts, last2hCounts.filterValues { it >= 2 }.keys)
    }

    /** Normalised key for matching call-log numbers to contact numbers (last 10 digits). */
    private fun freqKey(number: String): String {
        val d = number.filter { it.isDigit() }
        return if (d.length >= 10) d.takeLast(10) else d
    }

    /**
     * Rank contacts against the dialed digit string [query] (digits only).
     * Matches by phone number (prefix/substring) and by T9 of the name
     * (per-word prefix, or anywhere), then ranks by match quality, then by
     * how often / how recently the contact was called.
     */
    fun search(query: String, contacts: List<Contact>, limit: Int = 40): List<Contact> {
        if (query.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Contact, Int>>()
        for (c in contacts) {
            val s = score(c, query)
            // Add a frequency bonus to the match score (not just a tiebreak) so a
            // frequently-called contact can rank above a better text/number match.
            if (s > 0) scored.add(c to (s + freqBonus(c)))
        }
        scored.sortWith(
            compareByDescending<Pair<Contact, Int>> { it.second }
                .thenByDescending { it.first.timesContacted }
                .thenByDescending { it.first.lastTimeContacted }
                .thenBy { it.first.name.lowercase() }
        )
        return scored.take(limit).map { it.first }
    }

    /**
     * Score bump from call frequency. Sub-linear and capped: a handful of calls
     * gives a clear lift, and it saturates so a very frequent contact can climb
     * about one match tier (~30 pts) but never swamp the ranking entirely.
     */
    private fun freqBonus(c: Contact): Int {
        val t = c.timesContacted
        if (t <= 0) return 0
        return (10.0 * Math.log10((t + 1).toDouble())).toInt().coerceAtMost(30)
    }

    /** Quick contact-name lookup for a single number (used by the call notification). */
    fun displayName(context: Context, number: String): String? {
        if (number.isBlank()) return null
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        return try {
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: SecurityException) {
            null
        }
    }

    /** Starred contacts, for the Favorites strip. One row per contact (first number
     *  wins for display) — use [numbersFor] to get the rest when there's more than one. */
    fun loadFavorites(context: Context, limit: Int = 30): List<Contact> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val projection = arrayOf(
            Phone.DISPLAY_NAME, Phone.NUMBER, Phone.PHOTO_THUMBNAIL_URI, Phone.LOOKUP_KEY
        )
        val out = ArrayList<Contact>()
        val seen = HashSet<String>()
        context.contentResolver.query(
            Phone.CONTENT_URI, projection, "${Phone.STARRED} = 1", null, "${Phone.DISPLAY_NAME} ASC"
        )?.use { c ->
            val nameIdx = c.getColumnIndex(Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(Phone.NUMBER)
            val photoIdx = c.getColumnIndex(Phone.PHOTO_THUMBNAIL_URI)
            val keyIdx = c.getColumnIndex(Phone.LOOKUP_KEY)
            while (c.moveToNext() && out.size < limit) {
                val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                val lookupKey = if (keyIdx >= 0) c.getString(keyIdx) else null
                // Dedupe by contact, not name — a starred contact with several
                // numbers must collapse to one favorite row, not one per number.
                val dedupeKey = lookupKey ?: name
                if (name.isBlank() || dedupeKey.isBlank() || !seen.add(dedupeKey)) continue
                val number = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                val photo = if (photoIdx >= 0) c.getString(photoIdx)?.let { Uri.parse(it) } else null
                out.add(
                    Contact(
                        NameFormat.apply(context, name) ?: name,
                        number, number.filter { it.isDigit() }, "", emptyList(), photo, 0, 0L,
                        lookupKey = lookupKey
                    )
                )
            }
        }
        return out
    }

    /** All phone numbers for the contact identified by [lookupKey], with type labels
     *  (Mobile/Home/Work/...) — used to let the user pick which one to call when a
     *  favorite has more than one. */
    fun numbersFor(context: Context, lookupKey: String): List<ContactNumber> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val out = ArrayList<ContactNumber>()
        val seen = HashSet<String>()
        val projection = arrayOf(Phone.NUMBER, Phone.TYPE, Phone.LABEL)
        context.contentResolver.query(
            Phone.CONTENT_URI, projection, "${Phone.LOOKUP_KEY} = ?", arrayOf(lookupKey), null
        )?.use { c ->
            val numIdx = c.getColumnIndex(Phone.NUMBER)
            val typeIdx = c.getColumnIndex(Phone.TYPE)
            val labelIdx = c.getColumnIndex(Phone.LABEL)
            while (c.moveToNext()) {
                val number = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                if (number.isBlank() || !seen.add(number.filter { it.isDigit() })) continue
                val type = if (typeIdx >= 0) c.getInt(typeIdx) else Phone.TYPE_OTHER
                val customLabel = if (labelIdx >= 0) c.getString(labelIdx) else null
                val label = Phone.getTypeLabel(context.resources, type, customLabel).toString()
                out.add(ContactNumber(number, label))
            }
        }
        return out
    }

    /** Plain text search by name (or digits) — for the call-log search bar. */
    fun searchByText(query: String, contacts: List<Contact>, limit: Int = 50): List<Contact> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        val digits = query.filter { it.isDigit() }
        return contacts.asSequence()
            .filter { c ->
                c.name.lowercase().contains(q) || (digits.isNotEmpty() && c.digits.contains(digits))
            }
            .sortedBy { it.name.lowercase() }
            .take(limit)
            .toList()
    }

    private fun score(c: Contact, q: String): Int {
        return when {
            c.digits.startsWith(q) -> 100
            c.digits.contains(q) -> 80
            c.wordT9.any { it.startsWith(q) } -> 70
            c.nameT9.startsWith(q) -> 65
            c.nameT9.contains(q) -> 40
            else -> 0
        }
    }
}
