package com.puretech.dialer.vvm

import android.content.Context
import android.net.Network
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.regex.Pattern

/**
 * Verizon VVM3's HTTPS self-provisioning flow. Real AOSP only reaches this
 * (via Vvm3Protocol/Vvm3Subscriber, Apache-2.0) when a STATUS SMS reply
 * arrives but can't be parsed as OMTP fields -- it gets reinterpreted as
 * "self provisioning available" (st=U;rc=2) and this flow runs instead.
 *
 * On this carrier/account, no STATUS reply of any kind arrives at all
 * (confirmed 2026-08-03: checked both the platform SMS filter and the raw
 * SMS inbox after several minutes -- nothing). [VvmSync] falls back to this
 * after its own STATUS-SMS wait times out, rather than waiting for the
 * unparseable-reply trigger AOSP expects.
 *
 * This performs a real action against the live account (POSTs the
 * subscriber's own phone number to Verizon's gateway and "clicks" the
 * subscribe link it returns, enrolling the line in VVM3) -- same category
 * of live-account mutation as the auto-PIN-set flow that was deliberately
 * never wired in, so only call this with the user's knowledge.
 */
object Vvm3Provisioning {
    private const val TAG = "M5Vvm"
    private const val TIMEOUT_MS = 30_000

    // Matches AOSP's two known link-text patterns for the subscribe link.
    private val subscribeLinkPattern = Pattern.compile(
        "(?is)<a[^>]+href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>\\s*Subscribe to Basic Visual Voice ?Mail\\s*</a>"
    )

    /** Attempts self-provisioning; true if the subscribe step completed
     *  (the mailbox itself is still provisioned asynchronously by the
     *  carrier afterward -- real credentials arrive later via a normal
     *  STATUS/SYNC SMS, handled the usual way by [VvmSync.onSms]). */
    fun selfProvision(context: Context, cfg: VvmConfig, vmgUrl: String, network: Network?): Boolean {
        if (vmgUrl.isBlank()) {
            Log.w(TAG, "vvm3 self-provision: no vmg_url available")
            return false
        }
        val mdn = subscriberNumber(context)
        if (mdn == null) {
            Log.w(TAG, "vvm3 self-provision: no subscriber number available")
            return false
        }
        return try {
            val spgUrl = retrieveSpgUrl(vmgUrl, mdn, network)
            if (spgUrl.isNullOrBlank()) {
                Log.w(TAG, "vvm3 self-provision: no spgurl in VMG response")
                return false
            }
            val cookies = HashMap<String, String>()
            val page = postForm(
                spgUrl, network, cookies,
                linkedMapOf(
                    "VZW_MDN" to mdn,
                    "VZW_SERVICE" to "BVVM",
                    "DEVICE_MODEL" to "DROID_4G",
                    "APP_TOKEN" to "q8e3t5u2o1",
                    "SPG_LANGUAGE_PARAM" to "ENGLISH"
                )
            )
            val m = subscribeLinkPattern.matcher(page)
            if (!m.find()) {
                Log.w(TAG, "vvm3 self-provision: subscribe link not found in SPG response")
                logChunked("vvm3 self-provision: SPG response body", page)
                return false
            }
            val subscribeUrl = m.group(1)!!.replace("&amp;", "&")
            postForm(subscribeUrl, network, cookies, emptyMap())
            Log.i(TAG, "vvm3 self-provision: subscribe request sent, waiting for carrier to provision the mailbox")
            true
        } catch (e: Exception) {
            Log.w(TAG, "vvm3 self-provision failed: ${e.message}")
            false
        }
    }

    /** Android's logcat truncates single lines around ~4000 chars -- split
     *  long response bodies into chunks so a full page is actually visible. */
    private fun logChunked(label: String, text: String) {
        val chunkSize = 3000
        var i = 0
        var part = 0
        while (i < text.length) {
            val end = (i + chunkSize).coerceAtMost(text.length)
            Log.d(TAG, "$label [$part]: ${text.substring(i, end)}")
            i = end
            part++
        }
    }

    /**
     * `TelephonyManager.line1Number` is frequently blank on MVNOs that don't
     * provision the MSISDN on the SIM itself (confirmed on this exact line:
     * see the 2026-08-03 "no subscriber number available" failure this
     * fallback chain was added for). `SubscriptionManager.getPhoneNumber()`
     * (API 33+) aggregates UICC, carrier-config, and IMS sources instead --
     * try that first, fall back to line1Number for older devices.
     */
    private fun subscriberNumber(context: Context): String? {
        val fromSubscription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val sm = context.getSystemService(SubscriptionManager::class.java)
                val subId = SubscriptionManager.getDefaultSubscriptionId()
                sm?.getPhoneNumber(subId)?.ifBlank { null }
            } catch (e: Exception) {
                Log.w(TAG, "vvm3 self-provision: SubscriptionManager.getPhoneNumber failed: $e")
                null
            }
        } else {
            null
        }
        val tm = context.getSystemService(TelephonyManager::class.java)
        val fromLine1 = try { tm?.line1Number } catch (e: SecurityException) {
            Log.w(TAG, "vvm3 self-provision: line1Number denied: $e")
            null
        }
        // Last resort: Telecom's own record of this line's address, which
        // several carriers populate even when the telephony-layer sources
        // above are blank (confirmed blank on this exact MVNO SIM
        // 2026-08-03 for both of the above).
        val fromTelecom = try {
            val telecom = context.getSystemService(android.telecom.TelecomManager::class.java)
            telecom?.callCapablePhoneAccounts?.firstNotNullOfOrNull { handle ->
                telecom.getPhoneAccount(handle)?.address?.schemeSpecificPart
            }
        } catch (e: Exception) {
            Log.w(TAG, "vvm3 self-provision: TelecomManager phone account lookup failed: $e")
            null
        }
        Log.d(
            TAG, "vvm3 self-provision: subscriberNumber sources -- " +
                "subscription=${fromSubscription.isNullOrBlank().not()} " +
                "line1=${fromLine1.isNullOrBlank().not()} telecom=${fromTelecom.isNullOrBlank().not()}"
        )
        val raw = (fromSubscription ?: fromLine1 ?: fromTelecom)?.ifBlank { null } ?: return null
        return if (raw.startsWith("+1")) raw.substring(2) else raw
    }

    private fun retrieveSpgUrl(vmgUrl: String, mdn: String, network: Network?): String? {
        val transactionId = SecureRandom().nextLong().let { if (it < 0) -it else it }.toString()
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><VMGVVMRequest>" +
            "<MessageHeader><transactionid>$transactionId</transactionid></MessageHeader>" +
            "<MessageBody><mdn>$mdn</mdn><operation>retrieveSPGURL</operation>" +
            "<source>Device</source><devicemodel>${Build.MODEL}</devicemodel></MessageBody>" +
            "</VMGVVMRequest>"
        val response = postBody(
            vmgUrl, network, HashMap(), xml.toByteArray(Charsets.UTF_8), "text/xml; charset=utf-8"
        )
        val idMatch = Regex("<transactionid>(.*?)</transactionid>").find(response)
        if (idMatch == null || idMatch.groupValues[1] != transactionId) {
            Log.w(TAG, "vvm3 self-provision: VMG transaction id mismatch")
            return null
        }
        return Regex("<spgurl>(.*?)</spgurl>").find(response)?.groupValues?.get(1)
    }

    private fun postForm(
        url: String, network: Network?, cookies: MutableMap<String, String>, params: Map<String, String>
    ): String {
        val body = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        return postBody(
            url, network, cookies, body.toByteArray(Charsets.UTF_8), "application/x-www-form-urlencoded"
        )
    }

    private fun postBody(
        url: String, network: Network?, cookies: MutableMap<String, String>,
        body: ByteArray, contentType: String
    ): String {
        val conn = (
            if (network != null) network.openConnection(URL(url)) else URL(url).openConnection()
            ) as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Content-Type", contentType)
            if (cookies.isNotEmpty()) {
                conn.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            conn.outputStream.use { it.write(body) }
            conn.headerFields["Set-Cookie"]?.forEach { header ->
                val eq = header.substringBefore(';').indexOf('=')
                if (eq > 0) {
                    val nameValue = header.substringBefore(';')
                    cookies[nameValue.substring(0, eq).trim()] = nameValue.substring(eq + 1).trim()
                }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }
}
