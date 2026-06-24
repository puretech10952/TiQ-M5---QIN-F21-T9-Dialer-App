package com.puretech.dialer.vvm

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Receives the send result of the OMTP Activate SMS so we can log whether it
 *  actually left the device (vs. a silent modem/carrier rejection). */
class VvmSmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = resultCode == Activity.RESULT_OK
        Log.d("M5Vvm", "Activate SMS send result=$resultCode (${if (ok) "OK" else "ERROR"})")
    }
}
