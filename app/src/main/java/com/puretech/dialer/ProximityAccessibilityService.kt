package com.puretech.dialer

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Optional, opt-in helper for [ProximityController]'s latch mode: we don't act
 * on any accessibility event here (onAccessibilityEvent is a no-op) -- the
 * only reason this service exists is that [AccessibilityService.performGlobalAction]
 * with GLOBAL_ACTION_LOCK_SCREEN is a public, reliable way for a normal app to
 * actually lock/blank the screen on demand, unlike PROXIMITY_SCREEN_OFF_WAKE_LOCK
 * (which some OEM firmware handles inconsistently -- see ProximityController).
 *
 * Disabled until the user turns it on in Settings → Accessibility.
 */
class ProximityAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    companion object {
        @Volatile
        private var instance: ProximityAccessibilityService? = null

        /** True if the user has enabled this accessibility service. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ProximityAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == expected
            }
        }

        /** Locks the screen via the running service instance, if bound.
         *  Returns false if the service isn't connected (e.g. a brief window
         *  right after being enabled, or the user hasn't enabled it at all). */
        fun lockScreen(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
    }
}
