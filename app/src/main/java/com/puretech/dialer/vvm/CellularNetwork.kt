package com.puretech.dialer.vvm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Acquires the device's cellular data transport for carriers (e.g. Verizon
 * VVM3, per [VvmConfig.cellularDataRequired]) whose IMAP gateway is only
 * reachable over their own cellular data, not Wi-Fi -- mirrors what AOSP
 * Dialer's PIN-change flow does: explicitly request/bind to
 * TRANSPORT_CELLULAR rather than following the phone's default route.
 */
object CellularNetwork {
    private const val TAG = "M5Vvm"
    private const val TIMEOUT_MS = 15_000L

    /** Runs [block] with a [Network] bound to cellular data. Passes null (the
     *  default route) when [required] is false, cellular is unavailable, or
     *  the request times out -- callers should tolerate a null network. */
    fun <T> withCellular(context: Context, required: Boolean, block: (Network?) -> T): T {
        if (!required) return block(null)
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return block(null)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val latch = CountDownLatch(1)
        var network: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                network = net
                latch.countDown()
            }
        }
        // requestNetwork() needs CHANGE_NETWORK_STATE and can throw
        // SecurityException if that's ever missing/revoked -- this runs on a
        // background thread with no caller-side catch, and this app is the
        // default dialer, so an uncaught exception here would crash the
        // phone's dialer entirely. Degrade to the default route instead.
        try {
            cm.requestNetwork(request, callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "cellular network request denied: ${e.message}")
            return block(null)
        }
        try {
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "cellular network request timed out; falling back to default route")
            }
            return block(network)
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }
}
