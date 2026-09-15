package com.example.sonygps

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * Persistent diagnostic log behind "Export diagnostics" in the settings.
 *
 * The session log in MainActivity only lives while the activity is bound to the
 * service, so anything that goes wrong while the app is closed (auto-connect,
 * reconnects, GPS stalls) is gone by the time the user looks. Every log line of
 * the service, auto-connect and the tile lands here as well, in a file that
 * survives process death and can be shared as one report.
 *
 * Storage: filesDir/diagnostics/log.txt, rotated to log.1.txt at MAX_SIZE, so at
 * most ~1 MB of history is kept. Writes happen on a single background thread so
 * the callers (BLE callbacks, main thread) are never blocked by disk I/O.
 *
 * The exported report contains the camera's Bluetooth address and the positions
 * that appear in log lines — the settings screen says so before sharing. Its
 * labels follow the app language ([AppLocale]).
 */
object DiagnosticLog {

    private const val DIR      = "diagnostics"
    private const val FILE     = "log.txt"
    private const val ROTATED  = "log.1.txt"
    private const val REPORT_PREFIX = "diagnose_"
    private const val MAX_SIZE = 512 * 1024L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DiagnosticLog").apply { isDaemon = true }
    }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var dir: File? = null

    private fun dir(context: Context): File =
        dir ?: File(context.applicationContext.filesDir, DIR).also { dir = it }

    /** Appends one line; safe to call from any thread. */
    fun log(context: Context, tag: String, msg: String) {
        val line = synchronized(stamp) { "${stamp.format(Date())} [$tag] $msg\n" }
        val d = dir(context)
        executor.execute {
            try {
                d.mkdirs()
                val file = File(d, FILE)
                if (file.length() > MAX_SIZE) {
                    File(d, ROTATED).delete()
                    file.renameTo(File(d, ROTATED))
                }
                file.appendText(line)
            } catch (e: Exception) {
                Log.w("DiagnosticLog", "write failed", e)
            }
        }
    }

    /** Logs a throwable including its stack trace. */
    fun log(context: Context, tag: String, msg: String, e: Throwable) =
        log(context, tag, msg + "\n" + Log.getStackTraceString(e).trimEnd())

    /** Removes all history and old reports. */
    fun clear(context: Context) {
        val d = dir(context)
        executor.submit {
            File(d, FILE).delete()
            File(d, ROTATED).delete()
            deleteReports(d)
        }.get()
    }

    /** Size (bytes) of the stored log, for the settings summary. */
    fun size(context: Context): Long {
        val d = dir(context)
        return File(d, FILE).length() + File(d, ROTATED).length()
    }

    /**
     * Writes the report (environment snapshot + full log) to a fresh file in the
     * FileProvider-shared directory and returns it. Blocking: waits for pending
     * log writes first, so call it off the main thread. Older reports are deleted.
     */
    fun writeReport(context: Context): File {
        val d = dir(context).apply { mkdirs() }
        // Everything queued before this point is on disk once the empty task ran
        executor.submit {}.get()
        deleteReports(d)

        val res  = AppLocale.wrap(context.applicationContext)
        val name = REPORT_PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".txt"
        val out  = File(d, name)
        out.bufferedWriter().use { w ->
            w.write(header(context))
            w.write("\n══════════ ${res.getString(R.string.diag_log_header)} ══════════\n")
            var any = false
            for (part in listOf(ROTATED, FILE)) {
                val f = File(d, part)
                if (!f.exists()) continue
                any = true
                f.bufferedReader().use { it.copyTo(w) }
            }
            if (!any) w.write(res.getString(R.string.diag_empty) + "\n")
        }
        return out
    }

    private fun deleteReports(d: File) {
        d.listFiles { f -> f.name.startsWith(REPORT_PREFIX) }?.forEach { it.delete() }
    }

    // ── Environment snapshot ─────────────────────────────────────────────────

    private fun header(context: Context): String = buildString {
        val ctx   = context.applicationContext
        val res   = AppLocale.wrap(ctx)
        val prefs = CameraPrefs(ctx)
        val pkg   = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = pkg.versionCode

        fun s(id: Int) = res.getString(id)
        fun time(ms: Long) = synchronized(stamp) { stamp.format(Date(ms)) }
        fun timeOrDash(ms: Long) = if (ms > 0) time(ms) else "—"
        fun onOff(v: Boolean) = if (v) s(R.string.on) else s(R.string.off)
        fun line(label: String, value: Any?) = appendLine((label + ":").padEnd(20) + value)
        fun section(id: Int) { appendLine(); appendLine("── ${s(id)} ──") }

        appendLine(s(R.string.diag_title))
        line(s(R.string.diag_created), "${time(System.currentTimeMillis())} (${TimeZone.getDefault().id})")

        section(R.string.diag_sec_app)
        line(s(R.string.diag_version), "${pkg.versionName} ($versionCode)" +
            if (UpdateChecker.isDebugBuild(ctx)) " ${s(R.string.diag_debug)}" else "")
        line(s(R.string.diag_package), ctx.packageName)
        line(s(R.string.diag_installed), time(pkg.firstInstallTime))
        line(s(R.string.diag_updated), time(pkg.lastUpdateTime))

        section(R.string.diag_sec_device)
        line(s(R.string.diag_manufacturer), Build.MANUFACTURER)
        line(s(R.string.diag_model), "${Build.MODEL} (${Build.DEVICE})")
        line(s(R.string.diag_android), res.getString(
            R.string.diag_android_fmt, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.VERSION.SECURITY_PATCH))
        line(s(R.string.diag_build), Build.DISPLAY)

        section(R.string.diag_sec_system)
        val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        line(s(R.string.diag_bluetooth), when {
            bt == null   -> s(R.string.diag_no_adapter)
            bt.isEnabled -> s(R.string.on)
            else         -> s(R.string.off)
        })
        line(s(R.string.diag_location), onOff(locationEnabled(ctx)))
        val pm = ctx.getSystemService(PowerManager::class.java)
        line(s(R.string.diag_battery_opt),
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) s(R.string.diag_battery_exempt)
            else s(R.string.diag_battery_active))
        line(s(R.string.diag_power_save), onOff(pm.isPowerSaveMode))

        section(R.string.diag_sec_permissions)
        for (p in relevantPermissions()) {
            val granted = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
            appendLine("${if (granted) "✓" else "✗"} ${p.substringAfterLast('.')}")
        }

        section(R.string.diag_sec_settings)
        line(s(R.string.diag_language),
            prefs.appLanguage.ifEmpty { s(R.string.lang_system) })
        line(s(R.string.diag_camera),
            "${prefs.cameraName ?: "—"} (${prefs.cameraAddress ?: s(R.string.diag_none_saved)})")
        line(s(R.string.diag_auto_connect), onOff(prefs.autoConnect))
        line(s(R.string.diag_battery_saver), res.getString(
            R.string.diag_battery_saver_fmt, onOff(prefs.batterySaver), prefs.saverIntervalMs / 1000))
        line(s(R.string.diag_gpx), onOff(prefs.recordTrack))
        val snooze = prefs.autoConnectSnoozeUntil
        line(s(R.string.diag_snooze_until), if (snooze > System.currentTimeMillis()) time(snooze) else "—")
        line(s(R.string.diag_last_attempt), timeOrDash(prefs.lastAutoConnectAttempt))
        line(s(R.string.diag_last_update_check), timeOrDash(prefs.lastUpdateCheck))

        section(R.string.diag_sec_session)
        line(s(R.string.diag_active), onOff(GpsForegroundService.sessionActive))
        line(s(R.string.diag_sending), onOff(GpsForegroundService.readyForGps))
        val tracks = TrackRecorder.listTracks(ctx)
        val newest = tracks.firstOrNull()?.let {
            res.getString(R.string.diag_newest_fmt, it.name, (it.length() + 1023) / 1024)
        } ?: ""
        line(s(R.string.diag_tracks), "${tracks.size}$newest")
    }

    private fun locationEnabled(ctx: Context): Boolean {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun relevantPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
