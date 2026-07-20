package com.puretech.dialer

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * Decides whether an incoming call should take over the full screen or just
 * ring as a heads-up notification. Verified against the real Google Dialer
 * on a Pixel (live logcat, not just its source): it does NOT distinguish
 * home screen from any other app — unlocked is always just the CallStyle
 * notification banner; the full-screen activity only ever launches when the
 * device is locked (its launch there carries the BAL_ALLOW_NON_APP_VISIBLE_WINDOW
 * exemption, specifically for drawing over the keyguard) or when the user
 * taps the notification themselves. So the only signal that matters is lock
 * state, not what app happens to be in front.
 *
 * User-overridable via [Prefs.alwaysFullScreenCalls] — when set, always
 * take over the full screen for a ringing call, matching the classic
 * always-full-screen behavior this replaced as the default.
 */
object CallUiGate {

    fun shouldShowFullScreen(context: Context): Boolean {
        if (Prefs.alwaysFullScreenCalls(context)) return true
        val km = context.getSystemService(KeyguardManager::class.java)
        if (km == null || km.isKeyguardLocked) return true
        val pm = context.getSystemService(PowerManager::class.java)
        return pm?.isInteractive != true
    }
}
