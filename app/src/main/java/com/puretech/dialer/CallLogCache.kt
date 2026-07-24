package com.puretech.dialer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Caches the last full (unfiltered) Recents list so reopening the app after a
 * call doesn't have to sit through this device's slow CallLog.Calls provider
 * query (profiled at multiple seconds on this ROM) before showing the call
 * that just ended. [prefetch] is fired the instant a call ends (from
 * [InCallServiceImpl]) — well before the user has actually navigated back to
 * Recents — so that slow query runs hidden behind call teardown / the user
 * looking at their screen, instead of after they've already opened the tab.
 * [RecentsFragment.reload] paints this immediately when available, then still
 * runs its own fresh load right after to catch anything the cache missed.
 *
 * [entries] alone only survives while the process is alive, so it's empty on
 * a cold start (force-stop, or the process getting killed after being idle
 * in the background) — exactly the case that otherwise still sits through
 * the slow provider query. [ensureLoaded] backs it with a small on-disk
 * snapshot (a handful of rows, plain JSON in the app's cache dir) so a cold
 * start can paint instantly from the last known state too, the same way
 * Google Dialer reads its own local call-log database before syncing rather
 * than blocking the UI on the system provider.
 */
object CallLogCache {
    @Volatile
    var entries: List<CallLogEntry>? = null
        private set

    @Volatile
    private var loading = false

    @Volatile
    private var triedDisk = false

    private const val DISK_LIMIT = 60
    private const val FILE_NAME = "call_log_cache.json"

    fun prefetch(context: Context) {
        if (loading) return
        loading = true
        val ctx = context.applicationContext
        Thread {
            try {
                entries = CallLogRepository.load(ctx)
                entries?.let { writeToDisk(ctx, it) }
            } finally {
                loading = false
            }
        }.start()
    }

    /** Keeps the cache in sync with whatever [RecentsFragment.reload] actually
     *  finds, so a delete/edit or a prefetch that missed something doesn't keep
     *  getting served stale on the next tab open. Callers already run this off
     *  the main thread, so the disk write happens inline here too. */
    fun store(list: List<CallLogEntry>, context: Context? = null) {
        entries = list
        if (context != null) writeToDisk(context.applicationContext, list)
    }

    /** Seeds [entries] from the on-disk snapshot on the first call this
     *  process (a no-op once [entries] is set, or after the first attempt).
     *  Reading a few KB off local storage is milliseconds — nothing like the
     *  multi-second system CallLog provider query — so this is safe to call
     *  synchronously from the main thread right before painting. */
    fun ensureLoaded(context: Context) {
        if (entries != null || triedDisk) return
        triedDisk = true
        try {
            val text = file(context).takeIf { it.exists() }?.readText() ?: return
            val arr = JSONArray(text)
            val list = ArrayList<CallLogEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    CallLogEntry(
                        number = o.getString("number"),
                        name = o.optStringOrNull("name"),
                        photoUri = o.optStringOrNull("photoUri")?.let { Uri.parse(it) },
                        type = o.getInt("type"),
                        date = o.getLong("date"),
                        count = o.getInt("count"),
                        isHd = o.getBoolean("isHd"),
                        isWifi = o.optBoolean("isWifi", false),
                        numberType = o.optInt("numberType", 0),
                        numberLabel = o.optStringOrNull("numberLabel"),
                        geocoded = o.optStringOrNull("geocoded"),
                        simLabel = o.optStringOrNull("simLabel")
                    )
                )
            }
            entries = list
        } catch (_: Throwable) {
            // Missing/corrupt snapshot — just fall through to the real query.
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun file(context: Context) = File(context.applicationContext.cacheDir, FILE_NAME)

    private fun writeToDisk(context: Context, list: List<CallLogEntry>) {
        try {
            val arr = JSONArray()
            for (e in list.take(DISK_LIMIT)) {
                val o = JSONObject()
                o.put("number", e.number)
                o.put("name", e.name)
                o.put("photoUri", e.photoUri?.toString())
                o.put("type", e.type)
                o.put("date", e.date)
                o.put("count", e.count)
                o.put("isHd", e.isHd)
                o.put("isWifi", e.isWifi)
                o.put("numberType", e.numberType)
                o.put("numberLabel", e.numberLabel)
                o.put("geocoded", e.geocoded)
                o.put("simLabel", e.simLabel)
                arr.put(o)
            }
            file(context).writeText(arr.toString())
        } catch (_: Throwable) {
            // Best-effort snapshot only — never let a disk hiccup break the reload path.
        }
    }
}
