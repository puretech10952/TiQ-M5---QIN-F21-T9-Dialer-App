package com.puretech.dialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.puretech.dialer.databinding.ActivityUpdateBinding
import java.io.File
import java.util.Locale

/**
 * In-app updater: checks the GitHub Releases API for a newer build, shows the
 * "what's new" notes, and downloads + installs the APK directly (no browser).
 * Reached from the navigation drawer.
 */
class UpdateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUpdateBinding
    private var pending: Updater.Release? = null
    private var downloadedApk: File? = null
    private var currentVersionName: String = "0"

    // Remembers the schedule the user just picked so we can apply it once they
    // grant (or deny) the Android 13 notification permission.
    private var pendingFrequency: Int? = null

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { applyPendingFrequency() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        binding.menu.setOnClickListener { showScheduleMenu(it) }
        updateScheduleSummary()

        val info = try { packageManager.getPackageInfo(packageName, 0) } catch (e: Exception) { null }
        currentVersionName = info?.versionName ?: "0"
        val code = info?.longVersionCode ?: 0L
        binding.currentVersion.text = getString(R.string.about_version, currentVersionName, code)

        binding.btnCheck.setOnClickListener { checkForUpdates() }
        binding.btnInstall.setOnClickListener { downloadAndInstall() }
    }

    override fun onResume() {
        super.onResume()
        // If we bounced the user to the "install unknown apps" screen, a freshly
        // downloaded APK can be installed straight away on return.
        if (downloadedApk?.exists() == true && canInstall()) {
            launchInstaller(downloadedApk!!)
        }
    }

    // --- Automatic-check schedule (3-dot menu) --------------------------------

    private val freqOrder = intArrayOf(
        Prefs.UPDATE_MANUAL, Prefs.UPDATE_DAILY, Prefs.UPDATE_WEEKLY, Prefs.UPDATE_MONTHLY
    )

    private fun showScheduleMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        // Non-clickable title at the top so it's clear what the choices control.
        popup.menu.add(HEADER_GROUP, HEADER_ID, 0, R.string.update_check).isEnabled = false
        val labels = intArrayOf(
            R.string.update_auto_manual, R.string.update_auto_daily,
            R.string.update_auto_weekly, R.string.update_auto_monthly
        )
        val current = Prefs.updateFrequency(this)
        labels.forEachIndexed { i, label ->
            popup.menu.add(GROUP, i, i + 1, label).isCheckable = true
        }
        popup.menu.setGroupCheckable(GROUP, true, true)
        popup.menu.findItem(freqOrder.indexOf(current))?.isChecked = true
        popup.setOnMenuItemClickListener { item ->
            if (item.groupId == GROUP) chooseFrequency(freqOrder[item.itemId])
            true
        }
        popup.show()
    }

    private fun chooseFrequency(frequency: Int) {
        // Turning automatic checks on needs the notification permission to be of
        // any use — ask for it first (Android 13+), then apply.
        if (frequency != Prefs.UPDATE_MANUAL &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingFrequency = frequency
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        pendingFrequency = frequency
        applyPendingFrequency()
    }

    private fun applyPendingFrequency() {
        val frequency = pendingFrequency ?: return
        pendingFrequency = null
        Prefs.setUpdateFrequency(this, frequency)
        UpdateScheduler.reschedule(this)
        updateScheduleSummary()
    }

    private fun updateScheduleSummary() {
        binding.autoCheckSummary.text = when (Prefs.updateFrequency(this)) {
            Prefs.UPDATE_DAILY -> getString(R.string.update_auto_summary_fmt, getString(R.string.update_auto_daily))
            Prefs.UPDATE_WEEKLY -> getString(R.string.update_auto_summary_fmt, getString(R.string.update_auto_weekly))
            Prefs.UPDATE_MONTHLY -> getString(R.string.update_auto_summary_fmt, getString(R.string.update_auto_monthly))
            else -> getString(R.string.update_auto_summary_manual)
        }
    }

    // --- Check ----------------------------------------------------------------

    private fun checkForUpdates() {
        setBusy(true)
        binding.status.visibility = View.GONE
        binding.latestCard.visibility = View.GONE
        binding.btnInstall.visibility = View.GONE
        Thread {
            try {
                val release = Updater.fetchLatest()
                runOnUiThread { onChecked(release) }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    showStatus(getString(R.string.update_failed))
                }
            }
        }.start()
    }

    private fun onChecked(release: Updater.Release) {
        setBusy(false)
        pending = release
        val newer = Updater.isNewer(release.versionName, currentVersionName)

        binding.latestVersion.text = getString(R.string.update_latest_fmt, release.versionName)
        binding.whatsNew.text =
            if (release.notes.isBlank()) getString(R.string.update_no_notes)
            else ReleaseNotesFormatter.format(release.notes)
        binding.latestCard.visibility = View.VISIBLE

        if (newer && release.apkUrl != null) {
            if (release.sizeBytes > 0) {
                val mb = release.sizeBytes / (1024.0 * 1024.0)
                showStatus(getString(R.string.update_available_size_fmt, String.format(Locale.US, "%.1f", mb)))
            } else {
                showStatus(getString(R.string.update_available))
            }
            binding.btnInstall.visibility = View.VISIBLE
        } else if (newer && release.apkUrl == null) {
            showStatus(getString(R.string.update_no_asset))
        } else {
            showStatus(getString(R.string.update_up_to_date))
        }
    }

    // --- Download + install ---------------------------------------------------

    private fun downloadAndInstall() {
        val release = pending ?: return
        binding.btnInstall.isEnabled = false
        binding.downloadProgress.visibility = View.VISIBLE
        binding.downloadProgress.progress = 0
        showStatus(getString(R.string.update_downloading, 0))
        Thread {
            try {
                val apk = Updater.downloadApk(applicationContext, release) { pct ->
                    runOnUiThread {
                        binding.downloadProgress.progress = pct
                        binding.status.text = getString(R.string.update_downloading, pct)
                    }
                }
                runOnUiThread {
                    downloadedApk = apk
                    binding.btnInstall.isEnabled = true
                    binding.downloadProgress.visibility = View.GONE
                    binding.status.visibility = View.GONE
                    promptInstall(apk)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnInstall.isEnabled = true
                    binding.downloadProgress.visibility = View.GONE
                    showStatus(getString(R.string.update_failed))
                }
            }
        }.start()
    }

    /** Ensure we're allowed to install, then hand the APK to the system installer. */
    private fun promptInstall(apk: File) {
        if (!canInstall()) {
            // Android 8+ requires per-app "install unknown apps" consent first.
            Toast.makeText(this, R.string.update_install_sources, Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
            }
            return
        }
        launchInstaller(apk)
    }

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            showStatus(getString(R.string.update_failed))
        }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    // --- UI helpers -----------------------------------------------------------

    private fun setBusy(busy: Boolean) {
        binding.btnCheck.isEnabled = !busy
        binding.checkSpinner.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showStatus(text: String) {
        binding.status.text = text
        binding.status.visibility = View.VISIBLE
    }

    companion object {
        private const val GROUP = 1
        private const val HEADER_GROUP = 2
        private const val HEADER_ID = 100
    }
}
