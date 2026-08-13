package com.puretech.dialer

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** Resolves the current phone's carrier voicemail number. Read live so it's
 *  always whatever SIM/carrier is inserted — the app ships to many phones, so
 *  this is never hardcoded. Prefers the default *voice* subscription (dual-SIM
 *  aware) and falls back to the plain default TelephonyManager. Returns null if
 *  the carrier never provisioned a voicemail number. */
object Voicemail {

    fun number(context: Context): String? = try {
        val base = context.getSystemService(TelephonyManager::class.java)
        val subId = SubscriptionManager.getDefaultVoiceSubscriptionId()
        val tm = if (base != null && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            base.createForSubscriptionId(subId) else base
        (tm?.voiceMailNumber ?: base?.voiceMailNumber)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }
}
