package com.example.sonygps

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*

/**
 * Foreground service that keeps GPS + BLE alive regardless of app lifecycle.
 *
 * Why a foreground service?
 *  - Android Doze mode freezes Handler.postDelayed() for background processes →
 *    the APO keepalive would stop firing after ~3 min of screen-off
 *  - Android may kill background processes under memory pressure
 *  - A foreground service with a visible notification is exempt from both
 *
 * Architecture:
 *  - MainActivity does the BLE scan (short-lived, no service needed)
 *  - Once a device is selected: MainActivity calls connectToCamera() via binder
 *  - Service starts foreground, manages SonyCameraGatt + GPS for entire session
 *  - MainActivity binds for live UI updates; unbinding does NOT stop the service
 *  - User stops via notification action or "Trennen" button → stopSelf()
 *
 * GPS interval: 5 s (camera doesn't need sub-second accuracy, saves ~15% vs 2 s)
 */
class GpsForegroundService : Service(), SonyCameraGatt.Listener {

    // ── Binder ───────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        val service get() = this@GpsForegroundService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ── Listener (→ MainActivity) ─────────────────────────────────────────────

    interface StatusListener {
        fun onServiceConnected()
        fun onServiceReady()
        fun onServiceDisconnected()
        fun onServiceLog(msg: String)
        fun onServiceGpsUpdate(lat: Double, lon: Double, accuracy: Float, speedKmh: Int)
    }

    var statusListener: StatusListener? = null

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var fusedLocation: FusedLocationProviderClient
    private var cameraGatt: SonyCameraGatt? = null
    private var lastDevice: BluetoothDevice? = null
    var isConnected = false
        private set
    var isReady = false
        private set

    private var userStopped      = false
    private var reconnectCount   = 0
    private val handler          = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { attemptReconnect() }

    companion object {
        const val CHANNEL_ID   = "sony_gps_channel"
        const val NOTIF_ID     = 1
        const val ACTION_STOP  = "com.example.sonygps.ACTION_STOP"

        private const val MAX_RECONNECT     = 10
        private const val RECONNECT_DELAY   = 4_000L   // ms
    }

    // ── GPS request: 5 s interval ─────────────────────────────────────────────
    // The camera only needs a new fix when the position actually changes.
    // 5 s vs 2 s saves ~15 % GPS wakeup overhead with negligible accuracy loss.
    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 5_000L
    ).setMinUpdateIntervalMillis(3_000L).build()

    @SuppressLint("MissingPermission")
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            cameraGatt?.sendLocation(loc)
            val speedKmh = (loc.speed * 3.6f).toInt()
            updateNotification(
                "GPS aktiv — %.5f, %.5f  %d km/h".format(loc.latitude, loc.longitude, speedKmh)
            )
            statusListener?.onServiceGpsUpdate(loc.latitude, loc.longitude, loc.accuracy, speedKmh)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        // Start foreground immediately so Android doesn't kill us before connectToCamera() is called
        startForeground(NOTIF_ID, buildNotification("Bereit…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            userStopped = true
            shutdownAndStop()
        }
        // START_STICKY: system restarts the service if killed (with null intent)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(reconnectRunnable)
        stopGps()
        cameraGatt?.stopTransfer()
        cameraGatt?.disconnect()
        cameraGatt?.close()
        cameraGatt = null
    }

    // ── Public API (called by MainActivity via binder) ────────────────────────

    @SuppressLint("MissingPermission")
    fun connectToCamera(device: BluetoothDevice) {
        lastDevice     = device
        userStopped    = false
        reconnectCount = 0
        val name = device.name ?: device.address
        log("Verbinde mit $name…")
        updateNotification("Verbinde mit $name…")
        cameraGatt?.close()
        cameraGatt = SonyCameraGatt(this, device, this)
        cameraGatt?.connect()
    }

    fun stopTransfer() {
        userStopped = true
        shutdownAndStop()
    }

    // ── SonyCameraGatt.Listener ───────────────────────────────────────────────

    override fun onConnected() {
        isConnected = true
        isReady     = false
        updateNotification("Verbunden — GPS-Protokoll…")
        log("GATT verbunden")
        statusListener?.onServiceConnected()
    }

    override fun onReady() {
        isReady        = true
        reconnectCount = 0
        updateNotification("GPS aktiv")
        log("Bereit — starte GPS + APO-Keepalive (9 s)")
        startGps()
        statusListener?.onServiceReady()
    }

    override fun onDisconnected() {
        isConnected = false
        isReady     = false
        stopGps()
        cameraGatt?.close()
        cameraGatt = null
        statusListener?.onServiceDisconnected()

        if (userStopped) {
            updateNotification("Getrennt")
            stopSelf()
        } else {
            scheduleReconnect()
        }
    }

    override fun onError(msg: String) = log("FEHLER: $msg")
    override fun onLog(msg: String)   = log(msg)

    // ── Reconnect ─────────────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        if (reconnectCount >= MAX_RECONNECT) {
            log("Maximale Reconnect-Versuche erreicht")
            updateNotification("Verbindung verloren")
            stopSelf()
            return
        }
        reconnectCount++
        val msg = "Reconnect in ${RECONNECT_DELAY / 1000}s… ($reconnectCount/$MAX_RECONNECT)"
        log(msg)
        updateNotification(msg)
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY)
    }

    private fun attemptReconnect() {
        lastDevice?.let { connectToCamera(it) }
    }

    // ── GPS ───────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startGps() {
        fusedLocation.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    private fun stopGps() {
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun shutdownAndStop() {
        handler.removeCallbacks(reconnectRunnable)
        stopGps()
        cameraGatt?.stopTransfer()
        cameraGatt?.disconnect()
        // onDisconnected() will call stopSelf() once BLE confirms
    }

    private fun log(msg: String) {
        statusListener?.onServiceLog(msg)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Sony GPS Link",
            NotificationManager.IMPORTANCE_LOW   // no sound, no pop-up
        ).apply {
            description = "GPS-Übertragung zur Sony-Kamera"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        // Tap → open app
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        // "Stoppen" action → send ACTION_STOP to this service
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, GpsForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sony GPS Link")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPi)
            .addAction(0, "Stoppen", stopPi)
            .setOngoing(true)        // user cannot swipe away
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
