package com.puretech.dialer.vvm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.telephony.VisualVoicemailSms
import android.telephony.VisualVoicemailSmsFilterSettings
import android.util.Log

/**
 * Drives OMTP visual voicemail: turns the feature on (set SMS filter + send the
 * carrier an Activate message), handles the carrier's STATUS/SYNC replies, and
 * syncs the IMAP inbox into [VvmStore]. Network work must run off the main thread.
 */
object VvmSync {

    private const val TAG = "M5Vvm"

    // OMTP client identity used in the Activate/Status messages.
    private const val PROTOCOL_VERSION = "12"
    private const val CLIENT_TYPE = "Google"

    /** Turn VVM on for the default subscription: register the SMS filter and ask
     *  the carrier to activate. Returns false if unsupported or not permitted. */
    fun enable(context: Context): Boolean {
        val cfg = VvmConfig.read(context)
        if (cfg == null) { Log.w(TAG, "enable: no carrier config"); return false }
        Log.d(
            TAG, "enable: type=${cfg.type} dest=${cfg.destinationNumber} port=${cfg.port} " +
                "prefix=${cfg.clientPrefix} ssl=${cfg.sslEnabled} supported=${cfg.isSupported}"
        )
        if (!cfg.isSupported) return false
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return false
        try {
            tm.setVisualVoicemailSmsFilterSettings(buildFilter(cfg))
            Log.d(TAG, "SMS filter set (port=${cfg.port}); sending activation")
            sendActivate(context, tm, cfg)
            VvmPrefs.setEnabled(context, true)
            return true
        } catch (e: SecurityException) {
            Log.w(TAG, "enable denied (need to be default dialer): ${e.message}")
            return false
        } catch (e: Exception) {
            Log.w(TAG, "enable failed: ${e.message}")
            return false
        }
    }

    /** Turn VVM off: clear the SMS filter and forget stored credentials. */
    fun disable(context: Context) {
        val tm = context.getSystemService(TelephonyManager::class.java)
        try {
            tm?.setVisualVoicemailSmsFilterSettings(null)
        } catch (_: Exception) {
        }
        VvmPrefs.setEnabled(context, false)
        VvmPrefs.clear(context)
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
        val b = VisualVoicemailSmsFilterSettings.Builder()
            .setClientPrefix(cfg.clientPrefix)
        if (cfg.port != 0) b.setDestinationPort(cfg.port)
        return b.build()
    }

    private fun sendActivate(context: Context, tm: TelephonyManager, cfg: VvmConfig) {
        val text = when (cfg.type) {
            // Verizon VVM3 provisions through a STATUS request first.
            VVM_TYPE_VVM3 -> "STATUS"
            else -> "Activate:pv=$PROTOCOL_VERSION;ct=$CLIENT_TYPE"
        }
        Log.d(TAG, "sendVisualVoicemailSms text='$text' to ${cfg.destinationNumber}:${cfg.port}")
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val sent = PendingIntent.getBroadcast(
            context, 0, Intent(context, VvmSmsSentReceiver::class.java), flags
        )
        tm.sendVisualVoicemailSms(cfg.destinationNumber, cfg.port, text, sent)
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
            "STATUS" -> if (storeCredentials(context, fields)) sync(context)
            "SYNC" -> sync(context)
        }
    }

    /** Pull IMAP host/user/password out of an OMTP STATUS message. */
    private fun storeCredentials(context: Context, f: Bundle): Boolean {
        val provisioning = f.getString("st").orEmpty()
        // "N"/"U"/"B" => not ready; "R" (ready) or creds present => good.
        val server = f.getString("srv").orEmpty()
        val host = server.substringBefore(':').trim()
        val portFromSrv = server.substringAfter(':', "").toIntOrNull()
        val imapPort = f.getString("ipt")?.toIntOrNull() ?: portFromSrv ?: 143
        val user = f.getString("u").orEmpty()
        val pass = f.getString("pw").orEmpty()
        val cfg = VvmConfig.read(context)
        val cr = VvmCredentials(host, imapPort, user, pass, cfg?.sslEnabled ?: false)
        if (!cr.isComplete) {
            Log.w(TAG, "STATUS not provisioned (st=$provisioning, host=$host)")
            return false
        }
        VvmPrefs.saveCredentials(context, cr)
        return true
    }

    /** Reconcile the carrier IMAP inbox into the local voicemail store. */
    fun sync(context: Context): Boolean {
        val cr = VvmPrefs.credentials(context) ?: return false
        val client = ImapClient(cr)
        return try {
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
            true
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            false
        } finally {
            client.close()
        }
    }

    /** Delete a voicemail locally and from the carrier server. */
    fun deleteVoicemail(context: Context, id: Long) {
        val uid = VvmStore.sourceData(context, id)
        VvmStore.delete(context, id)
        if (uid != null) {
            val cr = VvmPrefs.credentials(context) ?: return
            val client = ImapClient(cr)
            try {
                client.connect(); client.login(); client.selectInbox()
                client.delete(uid)
            } catch (_: Exception) {
            } finally {
                client.close()
            }
        }
    }
}
