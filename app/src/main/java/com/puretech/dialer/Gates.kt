package com.puretech.dialer

import android.app.Activity
import android.content.Intent

/** First-run gates that must pass before the app's UI is usable. */
object Gates {
    /**
     * Enforce, in order: (1) accept the Terms of Service & Privacy Policy, then
     * (2) pick a screen profile, then (3) be the default phone app. Each is a
     * full-screen blocking step.
     * @return true if a blocking gate was shown — the caller should return now.
     */
    fun enforce(activity: Activity): Boolean {
        if (!Prefs.welcomeShown(activity)) {
            activity.startActivity(Intent(activity, WelcomeActivity::class.java))
            return true
        }
        if (!Prefs.screenProfileChosen(activity)) {
            activity.startActivity(
                Intent(activity, ScreenProfileActivity::class.java)
                    .putExtra(ScreenProfileActivity.EXTRA_ONBOARDING, true)
            )
            return true
        }
        return SetDefaultActivity.gateIfNeeded(activity)
    }
}
