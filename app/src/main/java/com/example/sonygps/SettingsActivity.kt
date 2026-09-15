package com.example.sonygps

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
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import java.io.File

/**
 * Settings screen (AndroidX Preference) for everything that is not part of the
 * live session: auto-connect incl. its permission flow, battery saver, GPX
 * recording and sharing, version info.
 *
 * The preferences write straight into CameraPrefs' SharedPreferences file, so
 * the service and AutoConnect read them with no extra plumbing. The service
 * listens for changes and applies the GPS mode to a running session.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Einstellungen"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
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
            if (!granted) toast("Kein Standort im Hintergrund — Kamera wird per Benachrichtigung angeboten")
            finishAutoConnectSetup()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = CameraPrefs.FILE
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
            setPreferencesFromResource(R.xml.preferences, rootKey)
            prefs = CameraPrefs(requireContext())

            findPreference<SwitchPreferenceCompat>(CameraPrefs.KEY_AUTO_CONNECT)?.apply {
                val camera = prefs.cameraName ?: prefs.cameraAddress
                isEnabled = camera != null
                if (camera == null) summary = "Zuerst eine Kamera verbinden — sie wird dann gespeichert"
                else summary = "Sitzung starten, sobald $camera in der Nähe ist"
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

            findPreference<Preference>("version")?.summary =
                UpdateChecker.installedVersion(requireContext())?.toString() ?: "?"
        }

        override fun onResume() {
            super.onResume()
            updateBatterySummary()
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
                    .setTitle("Standort im Hintergrund")
                    .setMessage(
                        "Damit die App die GPS-Übertragung selbst starten kann, während sie geschlossen ist, " +
                        "braucht sie Standortzugriff „Immer zulassen“. Ohne diese Berechtigung erscheint " +
                        "stattdessen eine Benachrichtigung, sobald die Kamera in der Nähe ist."
                    )
                    .setPositiveButton("Weiter") { _, _ ->
                        backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    .setNegativeButton("Später") { _, _ -> finishAutoConnectSetup() }
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
                if (force) toast("Akku-Optimierung ist bereits deaktiviert")
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
                toast("Akku-Optimierung bitte manuell für Sony GPS Link deaktivieren")
            }
        }

        private fun updateBatterySummary() {
            val ctx = context ?: return
            val pm  = ctx.getSystemService(PowerManager::class.java)
            findPreference<Preference>("battery_exemption")?.summary =
                if (pm.isIgnoringBatteryOptimizations(ctx.packageName))
                    "Deaktiviert — die App darf Sitzungen aus dem Hintergrund starten"
                else
                    "Aktiv — Android kann den automatischen Start blockieren. Tippen zum Ausnehmen."
        }

        // ── GPX tracks ──────────────────────────────────────────────────────────

        private fun showTracks() {
            val ctx    = requireContext()
            val tracks = TrackRecorder.listTracks(ctx)
            if (tracks.isEmpty()) { toast("Noch keine Tracks aufgezeichnet"); return }
            val labels = tracks.map {
                "${it.nameWithoutExtension}  (${(it.length() + 1023) / 1024} KB)"
            }.toTypedArray()
            AlertDialog.Builder(ctx)
                .setTitle("Track teilen")
                .setItems(labels) { _, idx -> shareTracks(listOf(tracks[idx])) }
                .setNeutralButton("Alle teilen") { _, _ -> shareTracks(tracks) }
                .setNegativeButton("Abbrechen", null)
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
            startActivity(Intent.createChooser(send, "GPX-Track teilen"))
        }

        private fun toast(msg: String) =
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
