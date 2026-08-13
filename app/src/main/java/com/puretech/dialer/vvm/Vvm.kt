package com.puretech.dialer.vvm

import android.content.Context
import android.telephony.CarrierConfigManager
import android.telephony.TelephonyManager

/**
 * Visual Voicemail (OMTP family) plumbing. The flow mirrors the AOSP Dialer:
 *
 *   1. We register a [VvmService] (an `android.telephony.VisualVoicemailService`).
 *   2. The platform calls us when cellular service is up; we set an SMS filter and
 *      send the carrier an OMTP "Activate" message.
 *   3. The carrier replies with a silent STATUS SMS carrying the IMAP host / port /
 *      username / password. We store those.
 *   4. We connect to the carrier IMAP server, download voicemails (audio + headers)
 *      into Android's [android.provider.VoicemailContract], and sync read/delete back.
 *
 * Works only when we are the default dialer and the carrier provisions OMTP VVM
 * (T-Mobile = CVVM, AT&T = OMTP, Verizon = VVM3). Reads the carrier's VVM config
 * from [CarrierConfigManager]; if it is blank, VVM is unsupported on that SIM.
 */

const val VVM_TYPE_OMTP = "vvm_type_omtp"
const val VVM_TYPE_CVVM = "vvm_type_cvvm"
const val VVM_TYPE_VVM3 = "vvm_type_vvm3"

/** Carrier VVM settings pulled from [CarrierConfigManager]. */
data class VvmConfig(
    val type: String,
    val destinationNumber: String,
    val port: Int,
    val clientPrefix: String,
    val sslEnabled: Boolean,
    val prefetch: Boolean,
    /** Dedicated IMAP-over-TLS port some carriers advertise (e.g. 993); 0 if
     *  none, in which case the STATUS message's own "ipt" port is used as-is.
     *  Not exposed as a named CarrierConfigManager constant, but present in
     *  the raw carrier config bundle under this key on carriers that set it. */
    val sslPort: Int,
    /** Carrier's VVM IMAP gateway is only reachable over its own cellular data,
     *  not Wi-Fi (true for every Verizon-family VVM3 entry in Google's own
     *  vvm_config.xml). When set, IMAP connections must bind to the cellular
     *  transport explicitly rather than following the phone's default route. */
    val cellularDataRequired: Boolean = false,
    /** Verizon VVM3's HTTPS self-provisioning gateway URL ("default_vmg_url"
     *  in Google's own vvm_config.xml). Only meaningful for [VVM_TYPE_VVM3];
     *  used when the carrier never sends an SMS STATUS reply at all -- see
     *  [Vvm3Provisioning]. */
    val vmgUrl: String = ""
) {
    /** True when the carrier actually advertises an OMTP-family VVM service. */
    val isSupported: Boolean
        get() = type.isNotBlank() && destinationNumber.isNotBlank()

    companion object {
        fun read(context: Context): VvmConfig? {
            val ccm = context.getSystemService(CarrierConfigManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            val b = ccm.config ?: return null
            val type = b.getString(CarrierConfigManager.KEY_VVM_TYPE_STRING, "").orEmpty()
            val dest = b.getString(CarrierConfigManager.KEY_VVM_DESTINATION_NUMBER_STRING, "").orEmpty()
            val port = b.getInt(CarrierConfigManager.KEY_VVM_PORT_NUMBER_INT, 0)
            val prefix = b.getString(CarrierConfigManager.KEY_VVM_CLIENT_PREFIX_STRING, "//VVM")
                ?.ifBlank { "//VVM" } ?: "//VVM"
            val ssl = b.getBoolean(CarrierConfigManager.KEY_VVM_SSL_ENABLED_BOOL, false)
            val prefetch = b.getBoolean(CarrierConfigManager.KEY_VVM_PREFETCH_BOOL, true)
            val sslPort = b.getInt("vvm_ssl_port_number_int", 0)
            // Same situation as sslPort above: present in the raw bundle on
            // carriers that set it, no named CarrierConfigManager constant.
            val cellularRequired = b.getBoolean("vvm_cellular_data_required_bool", false)
            val vmgUrl = b.getString("default_vmg_url", "").orEmpty().trim()
            if (type.isNotBlank() && dest.isNotBlank()) {
                return VvmConfig(type, dest, port, prefix, ssl, prefetch, sslPort, cellularRequired, vmgUrl)
            }
            // The platform's own carrier config is blank on some MVNOs riding a
            // network that genuinely does provision VVM -- confirmed for this
            // exact SIM by watching a real activation succeed (Google's Phone
            // app) while CarrierConfigManager reported nothing. Fall back to a
            // small known-good table keyed by the registered network's MCC+MNC.
            return knownCarrierFallback(context)
        }

        private fun knownCarrierFallback(context: Context): VvmConfig? {
            val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            val operator = tm.networkOperator?.ifBlank { null } ?: tm.simOperator.orEmpty()
            return when (operator) {
                // Verizon (and MVNOs riding its network, e.g. US Mobile).
                // destinationNumber/clientPrefix corrected 2026-08-03 to match
                // Google's own real vvm_config.xml for this exact MCC/MNC
                // (900080006205/"//VVM" was tried first, based on an earlier
                // "confirmed working" observation that turned out to be a
                // misattributed signal -- see [[william-dialer-vvm]] memory
                // for the full story. Two live tests with those values got
                // zero SMS reply, while Google's real Play Store dialer using
                // 900080006200/"//VZWVVM" got a real status=R reply within 5s
                // on this same line the same day). IMAP host confirmed via
                // that same live Google Dialer capture: cs1lv.imsvm.com:143
                // (STARTTLS, not direct SSL).
                "311480" -> VvmConfig(
                    type = VVM_TYPE_VVM3,
                    destinationNumber = "900080006200",
                    port = 0,
                    clientPrefix = "//VZWVVM",
                    sslEnabled = false,
                    prefetch = true,
                    sslPort = 0,
                    // Google's own vvm_config.xml flags every Verizon-family
                    // VVM3 entry (gid1-scoped MVNOs included) as requiring
                    // cellular data for the IMAP gateway -- matches even
                    // though the destination number/prefix values here don't.
                    cellularDataRequired = true,
                    // From Google's own vvm_config.xml (311480 Verizon-family
                    // entries) -- Verizon's real VVM3 self-provisioning
                    // gateway. Confirmed 2026-08-03: this SIM's carrier never
                    // answers a plain STATUS SMS at all (checked both the
                    // filtered platform path and the raw SMS inbox, nothing
                    // arrives within several minutes), so activation needs
                    // this HTTPS fallback rather than the SMS round trip.
                    vmgUrl = "https://mobile.vzw.com/VMGIMS/VMServices"
                )
                else -> null
            }
        }
    }
}

/** IMAP credentials the carrier hands us in the OMTP STATUS message. */
data class VvmCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val sslEnabled: Boolean
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && port > 0 && username.isNotBlank() && password.isNotBlank()
}

/** One voicemail as fetched from IMAP, before it goes into VoicemailContract. */
data class VvmMessage(
    val uid: String,
    val sender: String,
    val dateMillis: Long,
    val durationSec: Long,
    val mimeType: String,
    val audio: ByteArray
)

/** Where activation currently stands, for the Settings screen to show progress
 *  ("Setting up…" / "Connected") rather than just a static on/off switch. */
enum class VvmState { NOT_SET_UP, SETTING_UP, CONNECTED, FAILED }

/** Persisted OMTP provisioning state (IMAP creds) for the line. Device-local. */
object VvmPrefs {
    private const val FILE = "m5_vvm_prefs"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun state(c: Context): VvmState =
        runCatching { VvmState.valueOf(sp(c).getString("state", null) ?: "") }
            .getOrDefault(VvmState.NOT_SET_UP)

    fun setState(c: Context, state: VvmState) =
        sp(c).edit().putString("state", state.name).apply()

    fun saveCredentials(c: Context, cr: VvmCredentials) {
        sp(c).edit()
            .putString("host", cr.host)
            .putInt("port", cr.port)
            .putString("user", cr.username)
            .putString("pass", cr.password)
            .putBoolean("ssl", cr.sslEnabled)
            .apply()
    }

    fun credentials(c: Context): VvmCredentials? {
        val s = sp(c)
        val host = s.getString("host", null) ?: return null
        return VvmCredentials(
            host = host,
            port = s.getInt("port", 0),
            username = s.getString("user", "").orEmpty(),
            password = s.getString("pass", "").orEmpty(),
            sslEnabled = s.getBoolean("ssl", false)
        ).takeIf { it.isComplete }
    }

    fun clear(c: Context) = sp(c).edit().clear().apply()

    /** Whether the user has turned the VVM feature on (off by default — opt-in). */
    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("enabled", on).apply()

    /**
     * The PIN we last successfully set on the mailbox ourselves, so a future
     * "change PIN" doesn't need to ask the user for their current one -- same
     * idea as AOSP's `PinChangerImpl.setScrambledPin()`/`getScrambledPin()`
     * ("default_old_pin" preference key, confirmed 2026-08-03 from the real
     * decompiled source), just under our own key name.
     */
    fun knownPin(c: Context): String? = sp(c).getString("known_pin", null)
    fun setKnownPin(c: Context, pin: String?) = sp(c).edit().putString("known_pin", pin).apply()
}

/**
 * VVM3's well-known carrier default PIN for a mailbox that's never had a
 * custom PIN set: `"1" + last 4 digits of the IMAP username`. Confirmed from
 * AOSP's `Vvm3Protocol.getDefaultPin()` (Apache-2.0) -- real production code
 * used during new-subscriber auto-provisioning, not a guess. Only meaningful
 * as a *first* PIN-change attempt on a freshly self-provisioned account
 * (exactly this app's situation, since it doesn't auto-set a PIN during
 * activation the way AOSP/Google's client does); once any PIN change
 * actually succeeds, [VvmPrefs.knownPin] takes over instead.
 */
fun defaultVvmPin(imapUsername: String): String? {
    val local = imapUsername.substringBefore('@')
    if (local.length < 4) return null
    return "1" + local.takeLast(4)
}
