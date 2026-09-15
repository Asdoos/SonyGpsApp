package com.example.sonygps

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Starts a GPS session automatically when the remembered camera comes into range.
 *
 * Mechanism: a background BLE scan that delivers results via PendingIntent
 * (BluetoothLeScanner.startScan(filters, settings, PendingIntent), API 26+).
 * The scan runs inside the Bluetooth stack, so it survives the app process
 * being killed and costs no app wakeups until the camera actually advertises.
 *
 *  - armed:    auto-connect enabled, camera remembered, no session running
 *  - disarmed: while a session runs (the service is connected anyway)
 *  - re-armed: when the session ends, on app start, after boot / app update
 *
 * Android 12+ blocks foreground-service starts from the background unless the
 * app is exempt (e.g. ignoring battery optimizations). If the start is refused,
 * a "camera nearby — tap to connect" notification is shown instead, because a
 * notification tap is always allowed to start a foreground service.
 */
@SuppressLint("MissingPermission")
object AutoConnect {

    private const val TAG = "AutoConnect"

    /** Sony Corporation, Bluetooth SIG company ID. */
    private const val SONY_MANUFACTURER_ID = 301

    /** After the user stops a session manually, don't reconnect straight away. */
    private const val SNOOZE_AFTER_MANUAL_STOP_MS = 30 * 60_000L

    /** The scan reports every few seconds while the camera advertises. */
    private const val MIN_ATTEMPT_INTERVAL_MS = 60_000L

    private const val NEARBY_NOTIF_ID = 2

    fun arm(context: Context) {
        val prefs   = CameraPrefs(context)
        val address = prefs.cameraAddress
        if (!prefs.autoConnect || address == null || GpsForegroundService.sessionActive) return
        if (!hasScanPermission(context)) {
            diag(context, R.string.ac_not_armed_permission)
            return
        }
        val scanner = scanner(context) ?: run {
            diag(context, R.string.ac_not_armed_bt)
            return
        }
        val filter = ScanFilter.Builder()
            .setDeviceAddress(address)
            .setManufacturerData(SONY_MANUFACTURER_ID, byteArrayOf())
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            val pi = scanPendingIntent(context)
            scanner.stopScan(pi)   // re-arming must not stack scans
            val rc = scanner.startScan(listOf(filter), settings, pi)
            if (rc != 0) diag(context, R.string.ac_scan_failed_fmt, rc) else diag(context, R.string.ac_armed_fmt, address)
        } catch (e: Exception) {
            diag(context, R.string.ac_arm_failed, e = e)
        }
    }

    fun disarm(context: Context) {
        try {
            scanner(context)?.stopScan(scanPendingIntent(context))
        } catch (e: Exception) {
            diag(context, R.string.ac_disarm_failed, e = e)
        }
    }

    fun snoozeAfterManualStop(context: Context) {
        CameraPrefs(context).autoConnectSnoozeUntil = System.currentTimeMillis() + SNOOZE_AFTER_MANUAL_STOP_MS
    }

    fun cancelNearbyNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NEARBY_NOTIF_ID)
    }

    /** Called by [CameraNearbyReceiver] for every scan result of the remembered camera. */
    internal fun onCameraNearby(context: Context) {
        val prefs = CameraPrefs(context)
        val now   = System.currentTimeMillis()
        if (!prefs.autoConnect || prefs.cameraAddress == null) {
            disarm(context)
            return
        }
        if (GpsForegroundService.sessionActive || now < prefs.autoConnectSnoozeUntil) return
        if (now - prefs.lastAutoConnectAttempt < MIN_ATTEMPT_INTERVAL_MS) return
        prefs.lastAutoConnectAttempt = now

        // Without background location, a location-type foreground service started from
        // the background gets no fixes (and throws on Android 14) → let the user tap instead.
        if (!hasBackgroundLocation(context)) {
            diag(context, R.string.ac_nearby_no_bg_location)
            showNearbyNotification(context, prefs.cameraName)
            return
        }
        try {
            ContextCompat.startForegroundService(context, GpsForegroundService.connectIntent(context))
            diag(context, R.string.ac_nearby_started)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+, app not exempt)
            diag(context, R.string.ac_start_refused, e = e)
            showNearbyNotification(context, prefs.cameraName)
        }
    }

    /**
     * Logcat + persistent diagnostic log; auto-connect runs while nobody is watching.
     * The message follows the app language ([AppLocale.wrap]) like the rest of the log.
     */
    internal fun diag(context: Context, resId: Int, vararg args: Any, e: Throwable? = null) {
        val msg = AppLocale.wrap(context).getString(resId, *args)
        if (e == null) {
            Log.i(TAG, msg)
            DiagnosticLog.log(context, TAG, msg)
        } else {
            Log.w(TAG, msg, e)
            DiagnosticLog.log(context, TAG, msg, e)
        }
    }

    fun hasBackgroundLocation(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun hasScanPermission(context: Context) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            granted(context, Manifest.permission.BLUETOOTH_SCAN)
        else
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun scanner(context: Context): BluetoothLeScanner? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf { it.isEnabled }
            ?.bluetoothLeScanner

    private fun scanPendingIntent(context: Context): PendingIntent {
        // Must be mutable: the Bluetooth stack attaches the scan results as extras
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(
            context, 0, Intent(context, CameraNearbyReceiver::class.java), flags
        )
    }

    private fun showNearbyNotification(context: Context, cameraName: String?) {
        val res = AppLocale.wrap(context)
        GpsForegroundService.createNotificationChannel(res)
        val connectPi = PendingIntent.getForegroundService(
            context, 1,
            GpsForegroundService.connectIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, GpsForegroundService.CHANNEL_ID)
            .setContentTitle(res.getString(R.string.notif_nearby_title_fmt, cameraName ?: res.getString(R.string.default_camera_name)))
            .setContentText(res.getString(R.string.notif_nearby_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(connectPi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NEARBY_NOTIF_ID, notification)
    }
}

/** Receives background scan results for the remembered camera. */
class CameraNearbyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra(BluetoothLeScanner.EXTRA_ERROR_CODE)) {
            AutoConnect.diag(context, R.string.ac_scan_error_fmt, intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0))
            return
        }
        AutoConnect.onCameraNearby(context)
    }
}

/** PendingIntent scans don't survive a reboot or an app update — re-arm afterwards. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                AutoConnect.diag(context, R.string.ac_boot)
                AutoConnect.arm(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AutoConnect.diag(context, R.string.ac_updated_fmt, UpdateChecker.installedVersion(context)?.toString() ?: "?")
                // The update APK has done its job — free the cache
                UpdateChecker.clearDownloads(context)
                AutoConnect.arm(context)
            }
        }
    }
}
