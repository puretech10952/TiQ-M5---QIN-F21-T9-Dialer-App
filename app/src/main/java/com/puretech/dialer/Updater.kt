package com.puretech.dialer

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update over the GitHub Releases API — no browser, no login. Fetches the
 * latest release of the public repo, compares it to the installed version, and
 * (on request) downloads the release's APK asset straight to the app cache so it
 * can be handed to the system package installer.
 *
 * Note: anonymous GitHub API access is rate-limited (~60 req/hr per IP), which is
 * far more than a manual "check for updates" tap needs.
 */
object Updater {

    // Public repo that hosts the release APKs. NOTE: builds <= 0.9.1 shipped with
    // the old slug "M5-F21-dialer"; GitHub redirects that to this repo, so their
    // in-app updater keeps working. Keep an "M5-F21-dialer" rename-redirect alive
    // (i.e. don't create a new repo under that old name).
    private const val OWNER = "puretech10952"
    private const val REPO = "TiQ-M5---QIN-F21-T9-Dialer-App"
    private const val LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    data class Release(
        val tag: String,
        /** Tag without a leading "v", for numeric comparison (e.g. "0.9.0"). */
        val versionName: String,
        val notes: String,
        val apkUrl: String?,
        val apkName: String?,
        val sizeBytes: Long
    )

    /** Fetch the latest release metadata. Blocking — call off the main thread. */
    fun fetchLatest(): Release {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PureTech-Phone")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("GitHub API HTTP ${conn.responseCode}")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val tag = json.optString("tag_name")
            val notes = json.optString("body").ifBlank { "" }
            var apkUrl: String? = null
            var apkName: String? = null
            var size = 0L
            json.optJSONArray("assets")?.let { assets ->
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url")
                        apkName = name
                        size = a.optLong("size")
                        break
                    }
                }
            }
            return Release(tag, tag.trimStart('v', 'V'), notes, apkUrl, apkName, size)
        } finally {
            conn.disconnect()
        }
    }

    /** Download the release APK into the app cache, reporting progress 0..100. */
    fun downloadApk(context: Context, release: Release, onProgress: (Int) -> Unit): File {
        val url = release.apkUrl ?: throw IllegalStateException("Release has no APK asset")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PureTech-Phone")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            val total = if (release.sizeBytes > 0) release.sizeBytes else conn.contentLengthLong
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Clear stale downloads so the cache doesn't grow without bound.
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, release.apkName ?: "update.apk")
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            onProgress(100)
            return out
        } finally {
            conn.disconnect()
        }
    }

    /** Numeric dotted-version compare; true when [latest] is strictly newer than [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
