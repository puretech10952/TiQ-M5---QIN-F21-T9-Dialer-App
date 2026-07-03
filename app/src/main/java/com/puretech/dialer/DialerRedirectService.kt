package com.puretech.dialer

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent

/**
 * Optional, opt-in helper that works around this phone's launcher hardcoding the
 * Home-screen Send key to the stock dialer (com.android.dialer). A normal app
 * can't redirect an explicit component launch, so instead we watch — via an
 * Accessibility service scoped to ONLY the stock dialer — for its main screen
 * appearing and instantly bring our own recents (or the in-call screen, if a
 * call is ongoing) to the front.
 *
 * Disabled until the user turns it on in Settings → Accessibility.
 */
class DialerRedirectService : AccessibilityService() {

    private var lastRedirect = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != STOCK_DIALER) return

        // Only the stock dialer's main/recents screen (what the Send key opens),
        // not its dialogs or other activities.
        val cls = event.className?.toString() ?: return
        if (!cls.contains("MainActivity") && !cls.contains("Dialtacts")) return

        // Don't hijack unless we're actually the user's default dialer.
        if (!isDefaultDialer()) return

        // Debounce: a single open can fire several window events.
        val now = SystemClock.uptimeMillis()
        if (now - lastRedirect < 1500) return
        lastRedirect = now

        // While a call is ongoing, the Send key should bring up the call screen,
        // not the recents/dialer home screen.
        val target = if (CallManager.calls.isNotEmpty()) InCallActivity::class.java
                     else HomeActivity::class.java

        startActivity(
            Intent(this, target).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        )
    }

    override fun onInterrupt() {}

    private fun isDefaultDialer(): Boolean = try {
        getSystemService(TelecomManager::class.java)?.defaultDialerPackage == packageName
    } catch (e: Throwable) {
        true
    }

    companion object {
        private const val STOCK_DIALER = "com.android.dialer"

        /** True if the user has enabled this accessibility service. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, DialerRedirectService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == expected
            }
        }
    }
}
