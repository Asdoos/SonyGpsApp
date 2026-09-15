package com.example.sonygps

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Quick Settings tile: start or stop a session without opening the app.
 *
 *  - Session running   → tile ACTIVE, tap stops it (same path as the notification's "Stoppen")
 *  - Camera remembered → tile INACTIVE, tap connects to the remembered camera
 *  - No camera / permissions missing / start refused → tap opens the app
 *
 * The tile is declared as an active tile (META_DATA_ACTIVE_TILE), so the service
 * can push state changes via [requestUpdate] while the panel is closed. While a
 * TileService handles onClick the process counts as foreground, which is what
 * allows starting the location foreground service from here on Android 12+.
 */
class SessionTileService : TileService() {

    /** Resources follow the app language below Android 13 (see AppLocale). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onTileAdded() = requestUpdate(this)

    override fun onStartListening() = refresh()

    override fun onClick() {
        val prefs = CameraPrefs(this)
        when {
            GpsForegroundService.sessionActive -> {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, GpsForegroundService::class.java).apply {
                        action = GpsForegroundService.ACTION_STOP
                    }
                )
            }
            prefs.cameraAddress == null || !hasPermissions() -> openApp()
            else -> try {
                ContextCompat.startForegroundService(this, GpsForegroundService.connectIntent(this))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException — let the app do it in the foreground
                Log.w(TAG, "Start from tile refused — opening app", e)
                DiagnosticLog.log(this, TAG, getString(R.string.tile_start_refused), e)
                openApp()
            }
        }
        refresh()
    }

    private fun refresh() {
        val tile   = qsTile ?: return
        val prefs  = CameraPrefs(this)
        val active = GpsForegroundService.sessionActive
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !active                 -> prefs.cameraName ?: getString(R.string.tile_no_camera)
                GpsForegroundService.readyForGps -> getString(R.string.notif_gps_active)
                else                    -> getString(R.string.tile_connecting)
            }
        }
        tile.updateTile()
    }

    // The Intent variant throws on API 34+, hence the SDK check; below that it's the only option.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun hasPermissions(): Boolean {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val ble  = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            granted(Manifest.permission.BLUETOOTH_CONNECT)
        return fine && ble
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "SessionTile"

        /** Asks the system to bind the tile so [onStartListening] re-renders the state. */
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, SessionTileService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "requestListeningState failed", e)
            }
        }
    }
}
