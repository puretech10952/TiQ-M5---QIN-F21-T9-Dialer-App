package com.puretech.dialer

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Shows a "What's new" bottom sheet the first time the app is opened after an
 * update — never on a fresh install. [WelcomeActivity] calls
 * [recordCurrentVersion] when onboarding completes, baselining a fresh
 * install immediately; that's what lets [maybeShowIfUpdated] tell "existing
 * user who just updated" apart from "brand new install" purely from whether
 * a baseline is already set, including the very first release this feature
 * itself ships in.
 */
object WhatsNewSheet {

    private fun currentVersionCode(c: Context): Long = try {
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode
    } catch (e: Exception) {
        0L
    }

    fun recordCurrentVersion(c: Context) {
        Prefs.setLastSeenVersionCode(c, currentVersionCode(c))
    }

    fun maybeShowIfUpdated(activity: AppCompatActivity) {
        val current = currentVersionCode(activity)
        if (current == 0L) return
        val lastSeen = Prefs.lastSeenVersionCode(activity)
        if (current <= lastSeen) return

        Prefs.setLastSeenVersionCode(activity, current)
        val versionName = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_whats_new, null)
        view.findViewById<android.widget.TextView>(R.id.whatsNewTitle).text =
            activity.getString(R.string.whats_new_title, versionName)
        view.findViewById<android.widget.TextView>(R.id.whatsNewBody).text =
            activity.getString(R.string.whats_new_body)
        view.findViewById<android.view.View>(R.id.whatsNewGotIt).setOnClickListener { sheet.dismiss() }
        sheet.setContentView(view)
        sheet.setCancelable(true)
        sheet.show()
    }
}
