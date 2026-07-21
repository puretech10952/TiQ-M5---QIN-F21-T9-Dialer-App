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
 * Distinct from the M5/F21 hardware hold-to-speed-dial feature — this is
 * software-only, keyed by what's currently typed on the on-screen/keypad.
 */
object QuickDial {

    data class Entry(val name: String, val number: String)

    const val MIN_CODE = 1
    const val MAX_CODE = 99

    private const val FILE = "quick_dial"
    private const val KEY_MAP = "map"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun readMap(c: Context): JSONObject =
        JSONObject(sp(c).getString(KEY_MAP, null) ?: "{}")

    private fun writeMap(c: Context, obj: JSONObject) {
        sp(c).edit().putString(KEY_MAP, obj.toString()).apply()
    }

    /** All assignments, sorted by code. */
    fun all(c: Context): List<Pair<Int, Entry>> {
        val obj = readMap(c)
        val out = ArrayList<Pair<Int, Entry>>()
        obj.keys().forEach { k ->
            val code = k.toIntOrNull() ?: return@forEach
            val e = obj.optJSONObject(k) ?: return@forEach
            out.add(code to Entry(e.optString("name"), e.optString("number")))
        }
        return out.sortedBy { it.first }
    }

    /** The entry assigned to the exact typed [digits] (e.g. "2", "24"), or null. */
    fun get(c: Context, digits: String): Entry? {
        val code = digits.toIntOrNull() ?: return null
        if (code !in MIN_CODE..MAX_CODE) return null
        val e = readMap(c).optJSONObject(code.toString()) ?: return null
        return Entry(e.optString("name"), e.optString("number"))
    }

    /** Assigns [code] to (name, number), overwriting any existing assignment. */
    fun set(c: Context, code: Int, name: String, number: String) {
        val obj = readMap(c)
        obj.put(code.toString(), JSONObject().put("name", name).put("number", number))
        writeMap(c, obj)
    }

    fun remove(c: Context, code: Int) {
        val obj = readMap(c)
        obj.remove(code.toString())
        writeMap(c, obj)
    }
}
