package com.puretech.dialer.vvm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.telephony.VisualVoicemailSms
import android.telephony.VisualVoicemailSmsFilterSettings
import android.util.Log
import com.puretech.dialer.VoicemailNotifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives OMTP visual voicemail: turns the feature on (set SMS filter + send the
 * carrier an Activate message), handles the carrier's STATUS/SYNC replies, and
 * syncs the IMAP inbox into [VvmStore]. Network work must run off the main thread.
 */
object VvmSync {

    private const val TAG = "M5Vvm"

    // Standard OMTP (e.g. AT&T) protocol version used in the Activate message.
    // T-Mobile CVVM and Verizon VVM3 use entirely different Activate formats
    // (see sendActivate) -- this only applies to the generic OMTP branch.
    private const val PROTOCOL_VERSION_OMTP = "11"

    // Matches AOSP's own StatusSmsFetcher: it gives up waiting for a STATUS
    // reply after 60s. We used to wait forever (leaving "Setting up..."
    // stuck indefinitely if the carrier never replies) -- see [routeEvent]
    // and [awaitStatusOrFallback].
    private const val STATUS_TIMEOUT_SEC = 60L

    @Volatile
    private var statusLatch: CountDownLatch? = null

    /** Turn VVM on for the default subscription: register the SMS filter and ask
     *  the carrier to activate. Returns false if unsupported or not permitted. */
    fun enable(context: Context): Boolean {
        val cfg = VvmConfig.read(context)
        if (cfg == null) { Log.w(TAG, "enable: no carrier config"); return false }
        Log.d(
            TAG, "enable: type=${cfg.type} dest=${cfg.destinationNumber} port=${cfg.port} " +
                "prefix=${cfg.clientPrefix} ssl=${cfg.sslEnabled} " +
                "cellularRequired=${cfg.cellularDataRequired} supported=${cfg.isSupported}"
        )
        if (!cfg.isSupported) return false
        // Must bind to the specific SIM's PhoneAccountHandle, not the plain
        // default TelephonyManager -- this device is dual-SIM-capable
        // (dsds), and VvmService.onCellServiceConnected already did this
        // correctly while this user-triggered path didn't. Confirmed
        // 2026-08-03: a real Google Dialer capture on this same line bound
        // every VVM call (send SMS, filter, IMAP network request) to the
        // specific PhoneAccountHandle/subId; our unbound calls here likely
        // never got properly associated with an incoming reply by the
        // platform's VvmSmsFilter, which is why no reply was ever observed
        // even after the SMS number/prefix values were also corrected.
        val handle = defaultPhoneAccountHandle(context) ?: return false
        val tm = context.getSystemService(TelephonyManager::class.java)
            ?.createForPhoneAccountHandle(handle) ?: return false
        try {
            tm.setVisualVoicemailSmsFilterSettings(buildFilter(cfg))
            Log.d(TAG, "SMS filter set (port=${cfg.port}); sending activation")
            val latch = CountDownLatch(1)
            statusLatch = latch
            sendActivate(context, tm, cfg)
            VvmPrefs.setEnabled(context, true)
            VvmPrefs.setState(context, VvmState.SETTING_UP)
            Thread { awaitStatusOrFallback(context.applicationContext, cfg, latch) }.start()
            return true
        } catch (e: SecurityException) {
            Log.w(TAG, "enable denied (need to be default dialer): ${e.message}")
            VvmPrefs.setState(context, VvmState.FAILED)
            return false
        } catch (e: Exception) {
            Log.w(TAG, "enable failed: ${e.message}")
            VvmPrefs.setState(context, VvmState.FAILED)
            return false
        }
    }

    /**
     * Waits up to [STATUS_TIMEOUT_SEC] for a real STATUS reply (short-circuited
     * by [routeEvent] the moment one arrives, via [statusLatch]). If nothing
     * ever arrives -- confirmed 2026-08-03 on Verizon-family VVM3: no reply of
     * any kind comes back even after several minutes -- fall back to VVM3's
     * HTTPS self-provisioning gateway for that carrier family, matching what
     * a real STATUS reply of "st=U;rc=2" (self-provisioning available) would
     * have triggered per AOSP's Vvm3Protocol.
     */
    private fun awaitStatusOrFallback(context: Context, cfg: VvmConfig, latch: CountDownLatch) {
        val arrived = latch.await(STATUS_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (statusLatch === latch) statusLatch = null
        if (arrived) return // routeEvent/storeCredentials already updated state
        Log.w(TAG, "STATUS SMS timed out after ${STATUS_TIMEOUT_SEC}s, no reply from carrier")
        if (cfg.type == VVM_TYPE_VVM3 && cfg.vmgUrl.isNotBlank()) {
            Log.i(TAG, "attempting VVM3 HTTPS self-provisioning fallback (static vmg_url, no live reply)")
            val ok = CellularNetwork.withCellular(context, cfg.cellularDataRequired) { network ->
                Vvm3Provisioning.selfProvision(context, cfg, cfg.vmgUrl, network)
            }
            // On success leave state as SETTING_UP: the mailbox itself is
            // provisioned asynchronously by the carrier, and real IMAP
            // credentials arrive later via a normal STATUS/SYNC SMS, handled
            // the usual way whenever it shows up.
            if (!ok) VvmPrefs.setState(context, VvmState.FAILED)
        } else {
            VvmPrefs.setState(context, VvmState.FAILED)
        }
    }

    /** Turn VVM off: clear the SMS filter and forget stored credentials. */
    fun disable(context: Context) {
        val handle = defaultPhoneAccountHandle(context)
        val tm = context.getSystemService(TelephonyManager::class.java)
            ?.let { if (handle != null) it.createForPhoneAccountHandle(handle) else it }
        try {
            tm?.setVisualVoicemailSmsFilterSettings(null)
        } catch (_: Exception) {
        }
        VvmPrefs.setEnabled(context, false)
        VvmPrefs.clear(context)
    }

    /** The SIM-backed [PhoneAccountHandle] VVM operations must be bound to
     *  (see [enable]/[disable]) -- the first call-capable account, matching
     *  what [Vvm3Provisioning]'s own phone-number lookup already falls back
     *  to for the same reason. */
    private fun defaultPhoneAccountHandle(context: Context): PhoneAccountHandle? {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return null
        return try {
            telecom.callCapablePhoneAccounts?.firstOrNull()
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Build the platform SMS filter for this carrier. Crucially, when the carrier
     * delivers OMTP messages on a dedicated port (T-Mobile CVVM uses 1808), we must
     * tell the filter that port. The default `DESTINATION_PORT_ANY` only matches
     * plain text SMS, so a port-directed *data* SMS is rejected before the prefix is
     * ever checked — it then falls through to the normal SMS apps (Google Messages)
     * and never reaches us. Setting the port makes the platform route it here.
     */
    fun buildFilter(cfg: VvmConfig): VisualVoicemailSmsFilterSettings {
        // No setOriginatingNumbers() restriction: VVM3 replies DO use a real
        // text prefix ("//VZWVVM:STATUS:...", confirmed 2026-08-03 by reading
        // the actual reply after the platform's own VvmSmsFilter rejected it
        // for not matching a wrongly-guessed origin number and routed it to
        // the normal SMS app instead) -- clientPrefix alone is enough, same
        // as OMTP/CVVM already rely on. Restricting by origin number requires
        // guessing which of several distinct short codes a carrier's gateway
        // actually replies from (three different Verizon numbers turned up
        // across this investigation alone), which is fragile; the prefix is
        // the carrier-published, stable identifier.
        val b = VisualVoicemailSmsFilterSettings.Builder()
            .setClientPrefix(cfg.clientPrefix)
        if (cfg.port != 0) b.setDestinationPort(cfg.port)
        return b.build()
    }

    /**
     * Each OMTP family speaks a different Activate wire format -- these came from
     * cross-checking against the AOSP Dialer's protocol classes (Apache-2.0),
     * since guessing a single generic format here previously meant we were
     * sending T-Mobile a message it likely didn't recognize as a real Activate
     * at all (its CVVM protocol doesn't use pv/ct like standard OMTP does).
     */
    fun sendActivate(context: Context, tm: TelephonyManager, cfg: VvmConfig) {
        val text = when (cfg.type) {
            // Verizon VVM3 self-provisions off a STATUS request; there's no
            // separate Activate message.
            VVM_TYPE_VVM3 -> "STATUS"
            // T-Mobile CVVM: fixed "dt=6" (device type), not pv/ct.
            VVM_TYPE_CVVM -> "Activate:dt=6"
            // Standard OMTP (AT&T etc): pv=11 + a device-derived client type.
            else -> "Activate:pv=$PROTOCOL_VERSION_OMTP;ct=${clientType()}"
        }
        Log.d(TAG, "sendVisualVoicemailSms text='$text' to ${cfg.destinationNumber}:${cfg.port}")
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val sent = PendingIntent.getBroadcast(
            context, 0, Intent(context, VvmSmsSentReceiver::class.java), flags
        )
        tm.sendVisualVoicemailSms(cfg.destinationNumber, cfg.port, text, sent)
    }

    /** "<manufacturer>.<model>.<release>", sanitized and truncated to fit the
     *  ~28-char field OMTP carriers expect (matches AOSP's OmtpConstants). */
    private fun clientType(): String {
        fun sanitize(s: String) = s.replace('=', '_').replace(';', '_').replace('.', '_').replace(' ', '_')
        val manufacturer = sanitize(Build.MANUFACTURER).take(12)
        val version = sanitize(Build.VERSION.RELEASE).take(8)
        val modelMax = (28 - manufacturer.length - version.length).coerceAtLeast(0)
        val model = sanitize(Build.MODEL).take(modelMax)
        return "$manufacturer.$model.$version"
    }

    /** Handle a VVM SMS delivered to our VisualVoicemailService (platform path). */
    fun onSms(context: Context, sms: VisualVoicemailSms) {
        val event = sms.prefix ?: return
        val fields = sms.fields ?: Bundle.EMPTY
        Log.d(TAG, "VVM SMS (platform) event=$event fields=$fields")
        routeEvent(context, event, fields)
    }

    /**
     * Handle an OMTP message we parsed ourselves from a raw data SMS, for the case
     * where the platform's VisualVoicemailSmsFilter drops it (observed on this
     * Duoqin/MTK ROM with T-Mobile's port-1808 7-bit STATUS). [body] is the decoded
     * text, e.g. "//VVM:STATUS:st=R;srv=...;u=...;pw=...". No-op if it isn't ours.
     */
    fun onRawMessage(context: Context, body: String) {
        val cfg = VvmConfig.read(context) ?: return
        val prefix = cfg.clientPrefix
        if (!body.startsWith(prefix)) return
        // "//VVM:STATUS:st=R;..." -> event "STATUS", fields {st=R, ...}
        val rest = body.substring(prefix.length).trimStart(':')
        val sep = rest.indexOf(':')
        val event = (if (sep >= 0) rest.substring(0, sep) else rest).trim()
        val fieldStr = if (sep >= 0) rest.substring(sep + 1) else ""
        val fields = Bundle()
        for (pair in fieldStr.split(';')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            fields.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim())
        }
        Log.d(TAG, "VVM SMS (self-parsed) event=$event fields=$fields")
        routeEvent(context, event, fields)
    }

    /** Dispatch a parsed OMTP event regardless of which receive path found it. */
    private fun routeEvent(context: Context, event: String, fields: Bundle) {
        when (event.uppercase()) {
            "STATUS" -> {
                // Wake up a pending enable() wait immediately rather than
                // making it sit out the full timeout.
                statusLatch?.countDown()
                handleStatusMessage(context, fields)
            }
            "SYNC" -> sync(context)
        }
    }

    /**
     * "st=U;rc=2" (self-provisioning available) is VVM3-specific and doesn't
     * carry IMAP credentials at all -- confirmed 2026-08-03 with a real reply
     * on this account: `//VZWVVM:STATUS:rc=2;st=U;vmg_url=https://vmg.vzw.com/...`.
     * Route it straight to [Vvm3Provisioning] using the vmg_url the reply
     * itself carries (which can differ from any static per-carrier fallback
     * -- it did here), matching what AOSP's Vvm3Protocol.startProvisioning
     * does for this exact status/return-code combination. Anything else
     * falls through to the normal credentials path.
     */
    private fun handleStatusMessage(context: Context, f: Bundle) {
        val provisioning = unquote(f.getString("st").orEmpty())
        val returnCode = unquote(f.getString("rc").orEmpty())
        if (provisioning == "U" && returnCode == "2") {
            val vmgUrl = f.getString("vmg_url").orEmpty()
            val cfg = VvmConfig.read(context)
            Log.i(TAG, "STATUS: self-provisioning available (rc=2), vmg_url=$vmgUrl")
            if (vmgUrl.isBlank() || cfg == null) {
                VvmPrefs.setState(context, VvmState.FAILED)
                return
            }
            Thread {
                val ok = CellularNetwork.withCellular(context, cfg.cellularDataRequired) { network ->
                    Vvm3Provisioning.selfProvision(context, cfg, vmgUrl, network)
                }
                if (!ok) VvmPrefs.setState(context, VvmState.FAILED)
            }.start()
            return
        }
        if (storeCredentials(context, f)) sync(context)
    }

    /** Pull IMAP host/user/password out of an OMTP STATUS message. */
    private fun storeCredentials(context: Context, f: Bundle): Boolean {
        // "N"/"U"/"B" => not ready; "R" (ready) or creds present => good. Some
        // carriers quote the value ("R" as literally `"R"`); AOSP unquotes it.
        val provisioning = unquote(f.getString("st").orEmpty())
        // OMTP's "srv" field is "<1|2>:<host>" (the leading digit is a
        // connection-type marker, not part of the hostname) -- previously we
        // parsed that digit as the host itself on carriers using this form.
        // The port always comes from "ipt" (or the carrier's advertised SSL
        // port), never from splitting "srv" on ':'.
        val server = unquote(f.getString("srv").orEmpty()).replaceFirst(Regex("^[12]:"), "").trim()
        val cfg = VvmConfig.read(context)
        val sslPort = cfg?.sslPort ?: 0
        val useSsl = sslPort != 0
        val imapPort = if (useSsl) sslPort else (f.getString("ipt")?.toIntOrNull() ?: 143)
        val user = f.getString("u").orEmpty()
        val pass = f.getString("pw").orEmpty()
        val cr = VvmCredentials(server, imapPort, user, pass, useSsl || (cfg?.sslEnabled ?: false))
        if (!cr.isComplete) {
            Log.w(TAG, "STATUS not provisioned (st=$provisioning, host=$server)")
            VvmPrefs.setState(context, VvmState.FAILED)
            return false
        }
        VvmPrefs.saveCredentials(context, cr)
        VvmPrefs.setState(context, VvmState.CONNECTED)
        return true
    }

    private fun unquote(s: String) =
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s

    /** Reconcile the carrier IMAP inbox into the local voicemail store. */
    fun sync(context: Context): Boolean {
        val cr = VvmPrefs.credentials(context) ?: return false
        val requireCellular = VvmConfig.read(context)?.cellularDataRequired ?: false
        return CellularNetwork.withCellular(context, requireCellular) { network ->
            // Construction moved inside the try: an exception thrown by
            // ImapClient's own init (e.g. a bad regex literal -- exactly
            // what crashed this process 2026-08-03) previously bypassed this
            // catch entirely and took down the whole default-dialer app.
            var client: ImapClient? = null
            try {
                client = ImapClient(cr, network)
                client.connect(); client.login(); client.selectInbox()
                val server = client.listUids()
                val local = VvmStore.existingUids(context)
                var added = 0
                for (uid in server) {
                    if (uid in local) continue
                    val msg = client.fetchMessage(uid) ?: continue
                    if (VvmStore.insert(context, msg)) added++
                }
                Log.d(TAG, "sync complete: ${server.size} on server, $added new")
                if (added > 0) VoicemailNotifier.show(context)
                true
            } catch (e: Throwable) {
                Log.w(TAG, "sync failed: ${e.message}")
                false
            } finally {
                runCatching { client?.close() }
            }
        }
    }

    /** Delete a voicemail locally and from the carrier server. */
    fun deleteVoicemail(context: Context, id: Long) {
        val uid = VvmStore.sourceData(context, id)
        VvmStore.delete(context, id)
        if (uid != null) {
            val cr = VvmPrefs.credentials(context) ?: return
            val requireCellular = VvmConfig.read(context)?.cellularDataRequired ?: false
            CellularNetwork.withCellular(context, requireCellular) { network ->
                var client: ImapClient? = null
                try {
                    client = ImapClient(cr, network)
                    client.connect(); client.login(); client.selectInbox()
                    client.delete(uid)
                } catch (_: Throwable) {
                } finally {
                    runCatching { client?.close() }
                }
            }
        }
    }

    /**
     * Whether [changePinAuto] has anything to try at all -- true unless we
     * have neither stored credentials nor a way to derive a candidate,
     * which in practice is always true once VVM is connected (see
     * [changePinAuto]: the new PIN itself is always a valid candidate).
     */
    fun canAutoChangePin(context: Context): Boolean = VvmPrefs.credentials(context) != null

    /**
     * Changes the live mailbox PIN over IMAP without requiring the caller to
     * already know the current one. Tries, in order, until one isn't
     * rejected as a mismatch:
     *  1. Whatever we last set the PIN to ourselves ([VvmPrefs.knownPin]).
     *  2. **The new PIN itself as OLD_PWD too.** Confirmed 2026-08-04 by
     *     tracing the real client's `VoicemailChangePinFragmentPeer`: its
     *     3-step wizard (enter new PIN -> confirm new PIN) validates the two
     *     entries match, then commits with `PWD=<new>` and
     *     `OLD_PWD=<the confirm-retype>` -- i.e. old and new end up equal.
     *     The real client never separately collects or verifies an actual
     *     "current" PIN for this flow.
     *  3. VVM3's documented default-PIN formula ([defaultVvmPin]), as a
     *     last-resort fallback in case a given account's server actually
     *     does enforce the true old PIN and candidate 2 gets rejected.
     * Stops early on any non-mismatch result (e.g. TOO_WEAK) so a genuine
     * new-PIN validation error isn't masked by retrying with different old
     * PINs. Must be called off the main thread.
     */
    fun changePinAuto(context: Context, newPin: String): ChangePinResult? {
        val candidates = LinkedHashSet<String>()
        VvmPrefs.knownPin(context)?.let(candidates::add)
        candidates.add(newPin)
        VvmPrefs.credentials(context)?.username?.let(::defaultVvmPin)?.let(candidates::add)
        Log.d(TAG, "changePinAuto: trying ${candidates.size} candidate(s)")
        var last: ChangePinResult? = null
        for (candidate in candidates) {
            val result = changePin(context, candidate, newPin)
            if (result != ChangePinResult.OLD_MISMATCH) return result
            last = result
        }
        return last
    }

    /** Changes the live mailbox PIN over IMAP given an explicit current PIN.
     *  Must be called off the main thread. Returns null if there are no
     *  stored credentials to connect with (VVM not connected) or the
     *  connection itself failed. On success, remembers [newPin] via
     *  [VvmPrefs.setKnownPin] so the next change doesn't need asking either. */
    fun changePin(context: Context, oldPin: String, newPin: String): ChangePinResult? {
        val cr = VvmPrefs.credentials(context)
        if (cr == null) {
            Log.w(TAG, "changePin: no stored credentials")
            return null
        }
        val requireCellular = VvmConfig.read(context)?.cellularDataRequired ?: false
        Log.d(TAG, "changePin: connecting to ${cr.host}:${cr.port} cellularRequired=$requireCellular")
        val result = CellularNetwork.withCellular(context, requireCellular) { network ->
            var client: ImapClient? = null
            try {
                client = ImapClient(cr, network)
                client.connect()
                Log.d(TAG, "changePin: connected, logging in")
                client.login()
                Log.d(TAG, "changePin: logged in, sending CHANGE_TUI_PWD")
                val r = client.changePin(oldPin, newPin)
                Log.d(TAG, "changePin: result=$r")
                r
            } catch (e: Throwable) {
                Log.w(TAG, "changePin failed: ${e}")
                null
            } finally {
                runCatching { client?.close() }
            }
        }
        if (result == ChangePinResult.OK) VvmPrefs.setKnownPin(context, newPin)
        return result
    }

    /** Uploads a custom greeting recording over IMAP. Must be called off the
     *  main thread. See [ImapClient.appendGreeting] -- real protocol
     *  confirmed from a live decompile of Google's own Play Store client. */
    fun uploadGreeting(context: Context, audio: ByteArray, mimeType: String, durationSec: Long): Boolean {
        val cr = VvmPrefs.credentials(context) ?: return false
        val requireCellular = VvmConfig.read(context)?.cellularDataRequired ?: false
        Log.d(TAG, "uploadGreeting: ${audio.size} bytes, mimeType=$mimeType, durationSec=$durationSec")
        return CellularNetwork.withCellular(context, requireCellular) { network ->
            var client: ImapClient? = null
            try {
                client = ImapClient(cr, network)
                client.connect()
                Log.d(TAG, "uploadGreeting: connected, logging in")
                client.login()
                Log.d(TAG, "uploadGreeting: logged in, sending APPEND")
                val ok = client.appendGreeting(audio, mimeType, durationSec)
                Log.d(TAG, "uploadGreeting: result=$ok")
                ok
            } catch (e: Throwable) {
                Log.w(TAG, "uploadGreeting failed: $e")
                false
            } finally {
                runCatching { client?.close() }
            }
        }
    }
}
