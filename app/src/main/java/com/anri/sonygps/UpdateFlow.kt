package com.anri.sonygps

import android.content.ActivityNotFoundException
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * In-app update UI: check GitHub, offer the release, download, hand the APK to
 * the installer. Shared by the main screen (silent daily check) and the
 * settings screen (manual check from the version entry).
 *
 * Call [onResume] from the hosting activity so an install interrupted by the
 * "install unknown apps" permission screen continues, and [destroy] in onDestroy.
 */
class UpdateFlow(
    private val activity: AppCompatActivity,
    private val log: (String) -> Unit,
) {
    private val prefs = CameraPrefs(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    /** APK waiting to be installed once the user has allowed installs from this app. */
    private var pendingApk: File? = null

    /**
     * Automatic checks run at most once a day, stay silent when up to date or
     * offline, and skip a release the user dismissed. Manual checks always report.
     */
    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.lastUpdateCheck < UpdateChecker.CHECK_INTERVAL_MS) return

        val installed = UpdateChecker.installedVersion(activity)
        if (manual) log(activity.getString(R.string.log_checking_updates))
        job = scope.launch {
            try {
                val release = UpdateChecker.fetchLatest()
                prefs.lastUpdateCheck = System.currentTimeMillis()
                if (release == null || installed == null || release.version <= installed) {
                    if (manual) toast(activity.getString(R.string.toast_up_to_date_fmt, installed?.toString() ?: "?"))
                    return@launch
                }
                if (!manual && release.tag == prefs.skippedUpdateTag) return@launch
                log(activity.getString(R.string.log_update_available_fmt, release.tag))
                showUpdateDialog(release, installed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) {
                    log(activity.getString(R.string.log_update_check_failed_fmt, e.message))
                    toast(activity.getString(R.string.toast_update_check_failed))
                }
            }
        }
    }

    /** Back from the "install unknown apps" setting → continue the install. */
    fun onResume() {
        val apk = pendingApk ?: return
        if (UpdateChecker.canInstall(activity)) {
            pendingApk = null
            launchInstaller(apk)
        }
    }

    fun destroy() = scope.cancel()

    private fun showUpdateDialog(release: UpdateChecker.Release, installed: UpdateChecker.Version) {
        val sb = StringBuilder(activity.getString(R.string.update_dialog_msg_fmt, release.version.toString(), installed.toString()))
        if (release.notes.isNotEmpty()) sb.append("\n\n").append(release.notes.take(1500))
        if (UpdateChecker.isDebugBuild(activity)) {
            sb.append("\n\n").append(activity.getString(R.string.update_debug_warning))
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_dialog_title)
            .setMessage(sb)
            .setPositiveButton(R.string.install) { _, _ -> downloadAndInstall(release) }
            .setNegativeButton(R.string.later, null)
            .setNeutralButton(R.string.skip) { _, _ ->
                prefs.skippedUpdateTag = release.tag
                log(activity.getString(R.string.log_update_skipped_fmt, release.tag))
            }
            .show()
    }

    private fun downloadAndInstall(release: UpdateChecker.Release) {
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val label = TextView(activity).apply { text = release.apkName }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.downloading_title)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> job?.cancel() }
            .show()

        log(activity.getString(R.string.log_downloading_fmt, release.apkName))
        job = scope.launch {
            try {
                val apk = UpdateChecker.download(activity, release) { pct ->
                    // Called on the IO thread; View.post is thread-safe
                    progress.post {
                        if (pct < 0) progress.isIndeterminate = true
                        else { progress.isIndeterminate = false; progress.progress = pct }
                    }
                }
                dialog.dismiss()
                log(activity.getString(R.string.log_download_done))
                startInstall(apk)
            } catch (e: CancellationException) {
                dialog.dismiss()
                log(activity.getString(R.string.log_download_cancelled))
            } catch (e: Exception) {
                dialog.dismiss()
                log(activity.getString(R.string.log_download_failed_fmt, e.message))
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.download_failed_title)
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    /** Android 8+ only lets the installer run once the user has allowed this app as a source. */
    private fun startInstall(apk: File) {
        if (UpdateChecker.canInstall(activity)) { launchInstaller(apk); return }
        pendingApk = apk
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.allow_install_title)
            .setMessage(R.string.allow_install_msg)
            .setPositiveButton(R.string.menu_settings) { _, _ ->
                try {
                    activity.startActivity(UpdateChecker.unknownSourcesSettingsIntent(activity))
                } catch (e: ActivityNotFoundException) {
                    pendingApk = null
                    launchInstaller(apk)  // the installer shows its own prompt then
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> pendingApk = null }
            .show()
    }

    private fun launchInstaller(apk: File) {
        try {
            activity.startActivity(UpdateChecker.installIntent(activity, apk))
        } catch (e: ActivityNotFoundException) {
            log(activity.getString(R.string.no_installer))
            toast(activity.getString(R.string.no_installer))
        }
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
