package com.anri.sonygps

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import java.io.File

/**
 * Settings screen (AndroidX Preference) for everything that is not part of the
 * live session: app language, auto-connect incl. its permission flow, battery
 * saver, GPX recording and sharing, diagnostics export, version info.
 *
 * The preferences write straight into CameraPrefs' SharedPreferences file, so
 * the service and AutoConnect read them with no extra plumbing. The service
 * listens for changes and applies the GPS mode to a running session. The
 * language switch goes through [AppLocale]; AppCompat recreates the activity.
 */
class SettingsActivity : AppCompatActivity() {

    /** Manual update check from the version entry; the dialogs need an activity. */
    lateinit var updates: UpdateFlow
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.menu_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Edge-to-edge on Android 15+: keep the preference list clear of the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
        updates = UpdateFlow(this) { DiagnosticLog.log(this, "App", it) }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        updates.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        updates.destroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var prefs: CameraPrefs

        private val backgroundLocation = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) toast(getString(R.string.toast_no_bg_location))
            finishAutoConnectSetup()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = CameraPrefs.FILE
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
            setPreferencesFromResource(R.xml.preferences, rootKey)
            prefs = CameraPrefs(requireContext())

            findPreference<ListPreference>(CameraPrefs.KEY_APP_LANGUAGE)?.setOnPreferenceChangeListener { _, value ->
                AppLocale.set(requireContext(), value as String)
                true
            }

            findPreference<SwitchPreferenceCompat>(CameraPrefs.KEY_AUTO_CONNECT)?.apply {
                val camera = prefs.cameraName ?: prefs.cameraAddress
                isEnabled = camera != null
                summary = if (camera == null) getString(R.string.pref_auto_connect_no_camera)
                          else getString(R.string.pref_auto_connect_summary_fmt, camera)
                setOnPreferenceChangeListener { _, value ->
                    onAutoConnectChanged(value as Boolean)
                    true
                }
            }

            findPreference<Preference>("battery_exemption")?.setOnPreferenceClickListener {
                requestBatteryOptimizationExemption(force = true)
                true
            }

            findPreference<Preference>("share_tracks")?.setOnPreferenceClickListener {
                showTracks()
                true
            }

            findPreference<Preference>("export_diagnostics")?.setOnPreferenceClickListener {
                confirmExportDiagnostics()
                true
            }

            findPreference<Preference>("clear_diagnostics")?.setOnPreferenceClickListener {
                confirmClearDiagnostics()
                true
            }

            findPreference<Preference>("version")?.apply {
                summary = getString(R.string.pref_version_summary_fmt,
                    UpdateChecker.installedVersion(requireContext())?.toString() ?: "?")
                setOnPreferenceClickListener {
                    (activity as? SettingsActivity)?.updates?.check(manual = true)
                    true
                }
            }
        }

        override fun onResume() {
            super.onResume()
            updateBatterySummary()
            updateDiagnosticsSummary()
        }

        // ── Auto-connect ────────────────────────────────────────────────────────

        private fun onAutoConnectChanged(enabled: Boolean) {
            val ctx = requireContext()
            if (!enabled) {
                // The preference persists the value itself; only disarm here.
                AutoConnect.disarm(ctx)
                return
            }
            if (!AutoConnect.hasBackgroundLocation(ctx)) {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.bg_location_title)
                    .setMessage(R.string.bg_location_msg)
                    .setPositiveButton(R.string.next) { _, _ ->
                        backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    .setNegativeButton(R.string.later) { _, _ -> finishAutoConnectSetup() }
                    .show()
                return
            }
            finishAutoConnectSetup()
        }

        /** Runs after the preference value has been persisted (listener returns true first). */
        private fun finishAutoConnectSetup() {
            requestBatteryOptimizationExemption(force = false)
            view?.post { AutoConnect.arm(requireContext()) }
        }

        /**
         * Android 12+ only lets exempt apps start a foreground service from the
         * background; ignoring battery optimizations is such an exemption.
         */
        @SuppressLint("BatteryLife")
        private fun requestBatteryOptimizationExemption(force: Boolean) {
            val ctx = requireContext()
            val pm  = ctx.getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                if (force) toast(getString(R.string.toast_battery_already))
                return
            }
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")
                    )
                )
            } catch (e: ActivityNotFoundException) {
                toast(getString(R.string.toast_battery_manual))
            }
        }

        private fun updateBatterySummary() {
            val ctx = context ?: return
            val pm  = ctx.getSystemService(PowerManager::class.java)
            findPreference<Preference>("battery_exemption")?.summary =
                if (pm.isIgnoringBatteryOptimizations(ctx.packageName))
                    getString(R.string.battery_summary_exempt)
                else
                    getString(R.string.battery_summary_active)
        }

        // ── GPX tracks ──────────────────────────────────────────────────────────

        private fun showTracks() {
            val ctx    = requireContext()
            val tracks = TrackRecorder.listTracks(ctx)
            if (tracks.isEmpty()) { toast(getString(R.string.toast_no_tracks)); return }
            val labels = tracks.map {
                "${it.nameWithoutExtension}  (${(it.length() + 1023) / 1024} KB)"
            }.toTypedArray()
            AlertDialog.Builder(ctx)
                .setTitle(R.string.dialog_share_track)
                .setItems(labels) { _, idx -> shareTracks(listOf(tracks[idx])) }
                .setNeutralButton(R.string.share_all) { _, _ -> shareTracks(tracks) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun shareTracks(files: List<File>) {
            val ctx  = requireContext()
            val uris = ArrayList(files.map {
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it)
            })
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            send.type = "application/gpx+xml"
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share_gpx_chooser)))
        }

        // ── Diagnostics ─────────────────────────────────────────────────────────

        private fun updateDiagnosticsSummary() {
            val ctx = context ?: return
            val kb  = (DiagnosticLog.size(ctx) + 1023) / 1024
            findPreference<Preference>("clear_diagnostics")?.apply {
                summary   = if (kb == 0L) getString(R.string.diag_summary_empty)
                            else getString(R.string.diag_summary_size_fmt, kb)
                isEnabled = kb > 0
            }
        }

        /** The report contains the camera address and positions — say so before sharing. */
        private fun confirmExportDiagnostics() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_export_diag_title)
                .setMessage(R.string.diag_export_msg)
                .setPositiveButton(R.string.share) { _, _ -> exportDiagnostics() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun exportDiagnostics() {
            val ctx = requireContext().applicationContext
            // writeReport blocks on the log writer thread and reads the whole log → off the main thread
            Thread {
                val result = runCatching { DiagnosticLog.writeReport(ctx) }
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    result.onSuccess { shareDiagnostics(it) }
                          .onFailure { toast(getString(R.string.toast_export_failed_fmt, it.message ?: it.toString())) }
                }
            }.start()
        }

        private fun shareDiagnostics(file: File) {
            val ctx = requireContext()
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diag_share_subject))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.diag_share_chooser)))
        }

        private fun confirmClearDiagnostics() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.diag_clear_title)
                .setMessage(R.string.diag_clear_msg)
                .setPositiveButton(R.string.delete) { _, _ ->
                    DiagnosticLog.clear(requireContext())
                    updateDiagnosticsSummary()
                    toast(getString(R.string.toast_log_cleared))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun toast(msg: String) =
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
