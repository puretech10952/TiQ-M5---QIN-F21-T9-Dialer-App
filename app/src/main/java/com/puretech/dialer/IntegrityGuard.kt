package com.puretech.dialer

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Anti-tamper check: confirms the running app is signed with our own key and uses
 * the expected package name. Any modification to the code, resources, or package
 * name forces an attacker to re-sign the APK with a different key (they can't use
 * ours), so the signature no longer matches and the app refuses to run.
 *
 * NOTE: this is a strong deterrent, not an unbreakable lock — client-side checks
 * can in principle be patched out by a skilled reverse-engineer (raise the bar
 * further with R8/obfuscation). It does NOT prevent installing a re-signed copy,
 * only stops it from functioning.
 */
object IntegrityGuard {

    private const val EXPECTED_PACKAGE = "com.puretech.dialer"

    // SHA-256 of our signing certificates (uppercase hex, no separators).
    // Both are accepted because of key rotation: Android 13+ runs under the
    // release key, Android 12 and below under the original debug key.
    private val PINNED = setOf(
        "B66FEDA338FFB4C40B08987A677CBF88704E4817488706CE83528E8683493D61", // release (PureTech)
        "A35486E7B9D924499F2CA3C88BBCDB511B6C0A63B009D58336713FC02DD58E4B"  // debug (rotation root)
    )

    // Some OEM ROMs return an incomplete/stale signing-cert list from
    // PackageManager on the first query or two right after the process is
    // started fresh (e.g. right after the app was killed from Recents) —
    // observed as the app self-killing on launch for a few tries in a row
    // before working normally. Retry briefly before concluding real tampering.
    private const val SIGNATURE_RETRIES = 3
    private const val SIGNATURE_RETRY_DELAY_MS = 150L

    /** True if the app is genuine. Fails closed on a mismatch that persists
     *  across retries (wrong key or wrong package); fails open if signatures
     *  can't be read at all, so an OEM quirk never bricks a legitimate install. */
    fun isGenuine(context: Context): Boolean {
        if (context.packageName != EXPECTED_PACKAGE) return false
        repeat(SIGNATURE_RETRIES) { attempt ->
            val hashes = try {
                signingHashes(context)
            } catch (e: Exception) {
                return true   // couldn't determine — don't brick a real install
            }
            if (hashes.isEmpty()) return true
            if (hashes.any { it in PINNED }) return true
            if (attempt < SIGNATURE_RETRIES - 1) Thread.sleep(SIGNATURE_RETRY_DELAY_MS)
        }
        return false
    }

    private fun signingHashes(context: Context): Set<String> {
        val pm = context.packageManager
        @Suppress("PackageManagerGetSignatures")
        val info = pm.getPackageInfo(
            context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
        )
        val signing = info.signingInfo ?: return emptySet()
        // Current signer(s) + the whole rotation history.
        val certs = if (signing.hasMultipleSigners())
            signing.apkContentsSigners
        else
            signing.apkContentsSigners + signing.signingCertificateHistory
        val md = MessageDigest.getInstance("SHA-256")
        return certs.mapNotNull { sig ->
            md.reset()
            md.digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
        }.toSet()
    }
}
