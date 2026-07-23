package com.puretech.dialer

import android.content.Context
import org.json.JSONObject

/**
 * Quick dial: assign a 1-2 digit code (1-99) to a contact. While the digits
 * typed on the dial pad exactly equal an assigned code, that contact is
 * pinned at the top of the suggestions, marked as a Quick dial match — ahead
 * of (and separate from) the regular T9/number ranking below it. Typing
 * further digits past an exact code match (e.g. "2" -> "24") drops back to
 * plain suggestions unless "24" itself is also assigned.
 *
 * Code 1 is auto-assigned to the carrier's Voicemail the first time this
 * store is ever touched (classic phone convention) — like any other entry,
 * the user can reassign or remove it afterward, and it won't come back once
 * they do (it's a one-time seed, not a permanent default).
 *
 * Distinct from the M5/F21 hardware hold-to-speed-dial feature — this is
 * software-only, keyed by what's currently typed on the on-screen/keypad.
 */
object QuickDial {

    data class Entry(
        val name: String,
        val number: String,
        val photoUri: String? = null,
        val isVoicemail: Boolean = false
    )

    const val MIN_CODE = 1
    const val MAX_CODE = 99
    const val VOICEMAIL_CODE = 1

    private const val FILE = "quick_dial"
    private const val KEY_MAP = "map"
    private const val KEY_SEEDED = "defaults_seeded"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun readMap(c: Context): JSONObject =
        JSONObject(sp(c).getString(KEY_MAP, null) ?: "{}")

    private fun writeMap(c: Context, obj: JSONObject) {
        sp(c).edit().putString(KEY_MAP, obj.toString()).apply()
    }

    private fun ensureDefaults(c: Context) {
        if (sp(c).getBoolean(KEY_SEEDED, false)) return
        sp(c).edit().putBoolean(KEY_SEEDED, true).apply()
        val obj = readMap(c)
        if (!obj.has(VOICEMAIL_CODE.toString())) {
            obj.put(
                VOICEMAIL_CODE.toString(),
                JSONObject()
                    .put("name", c.getString(R.string.quick_dial_voicemail))
                    .put("number", "")
                    .put("isVoicemail", true)
            )
            writeMap(c, obj)
        }
    }

    private fun entryFrom(o: JSONObject) = Entry(
        name = o.optString("name"),
        number = o.optString("number"),
        photoUri = o.optString("photoUri").ifBlank { null },
        isVoicemail = o.optBoolean("isVoicemail", false)
    )

    /** All assignments, sorted by code. */
    fun all(c: Context): List<Pair<Int, Entry>> {
        ensureDefaults(c)
        val obj = readMap(c)
        val out = ArrayList<Pair<Int, Entry>>()
        obj.keys().forEach { k ->
            val code = k.toIntOrNull() ?: return@forEach
            val e = obj.optJSONObject(k) ?: return@forEach
            out.add(code to entryFrom(e))
        }
        return out.sortedBy { it.first }
    }

    /** The entry assigned to the exact typed [digits] (e.g. "2", "24"), or null. */
    fun get(c: Context, digits: String): Entry? {
        ensureDefaults(c)
        val code = digits.toIntOrNull() ?: return null
        if (code !in MIN_CODE..MAX_CODE) return null
        val e = readMap(c).optJSONObject(code.toString()) ?: return null
        return entryFrom(e)
    }

    /** Assigns [code] to a contact/number, overwriting any existing assignment. */
    fun set(c: Context, code: Int, name: String, number: String, photoUri: String? = null) {
        val obj = readMap(c)
        obj.put(
            code.toString(),
            JSONObject().apply {
                put("name", name)
                put("number", number)
                photoUri?.let { put("photoUri", it) }
            }
        )
        writeMap(c, obj)
    }

    fun remove(c: Context, code: Int) {
        val obj = readMap(c)
        obj.remove(code.toString())
        writeMap(c, obj)
    }
}
