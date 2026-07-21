package com.puretech.dialer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Opens an email composer pre-addressed to us, with the subject pre-filled with
 *  the version the user is sending feedback from. Shared by the drawer entry and
 *  (formerly) the About screen. */
object Feedback {
    fun send(context: Context) {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
        val version = info?.versionName ?: ""
        val email = context.getString(R.string.about_feedback_email)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.about_feedback_subject, version))
        }
        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.about_feedback)))
        } catch (e: Exception) {
            Toast.makeText(context, R.string.about_feedback_none, Toast.LENGTH_LONG).show()
        }
    }
}
