package com.puretech.dialer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils

/** Shared call-placement logic: assisted-dialing normalization + default SIM. */
object Dialer {

    /**
     * Normalize a dialed string to E.164 when possible. MMI/USSD codes (with
     * `*`/`#`) and already-international numbers pass through untouched. With
     * assisted dialing on, the home country supplies the country code.
     */
    fun normalize(context: Context, raw: String): String {
        // Split off any post-dial sequence (pause ',' / wait ';' and what follows)
        // so it survives normalization and is sent as DTMF after the call connects.
        val pdIdx = raw.indexOfFirst { it == ',' || it == ';' }
        val mainRaw = if (pdIdx >= 0) raw.substring(0, pdIdx) else raw
        val postDial = if (pdIdx >= 0)
            raw.substring(pdIdx).filter { it.isDigit() || it in ",;+*#" } else ""

        val s = mainRaw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (s.isEmpty() || s.contains('*') || s.contains('#') || s.startsWith("+")) return s + postDial
        val main = if (Prefs.assistedDialing(context)) {
            PhoneNumberUtils.formatNumberToE164(s, Prefs.homeCountry(context)) ?: usFallback(s)
        } else usFallback(s)
        return main + postDial
    }

    private fun usFallback(s: String): String = when {
        s.length == 10 -> "+1$s"
        s.length == 11 && s.startsWith("1") -> "+$s"
        else -> s
    }

    /** Extras carrying the user's chosen default SIM, when one is set. */
    fun accountExtras(context: Context): Bundle {
        val extras = Bundle()
        CallingAccounts.defaultHandle(context)?.let {
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
        }
        return extras
    }

    /** Place a call to an already-normalized number using the default SIM. */
    fun place(context: Context, normalized: String) {
        if (normalized.isEmpty()) return
        val uri = if (normalized.any { it == '#' || it == '*' || it == ',' || it == ';' })
            Uri.parse("tel:" + Uri.encode(normalized))
        else Uri.fromParts("tel", normalized, null)
        context.getSystemService(TelecomManager::class.java)?.placeCall(uri, accountExtras(context))
    }
}
