package com.example.sonygps

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
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
 *  - A session is started with connectIntent(): either for a scanned device
 *    (MainActivity) or for the remembered camera (MainActivity, AutoConnect)
 *  - Service starts foreground, manages SonyCameraGatt + GPS for entire session
 *  - MainActivity binds for live UI updates; unbinding does NOT stop the service
 *  - User stops via notification action or "Trennen" button → stopSelf()
 *  - On the first successful handshake the camera is remembered (CameraPrefs)
 *  - Optional GPX recording (TrackRecorder); while recording, GPS keeps running
 *    during reconnect attempts so the track has no gaps
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
    private lateinit var prefs: CameraPrefs
    private var cameraGatt: SonyCameraGatt? = null
    private var lastDevice: BluetoothDevice? = null
    private var track: TrackRecorder? = null
    private var gpsRunning = false
    var isConnected = false
        private set
    var isReady = false
        private set

    private var userStopped      = false
    private var reconnectCount   = 0
    private val handler          = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { attemptReconnect() }

    companion object {
        const val CHANNEL_ID     = "sony_gps_channel"
        const val NOTIF_ID       = 1
        const val ACTION_STOP    = "com.example.sonygps.ACTION_STOP"
        const val ACTION_CONNECT = "com.example.sonygps.ACTION_CONNECT"
        private const val EXTRA_ADDRESS = "address"

        private const val MAX_RECONNECT     = 10
        private const val RECONNECT_DELAY   = 4_000L   // ms

        /** True from connect until the session ends (stop, reconnects exhausted). */
        @Volatile
        var sessionActive = false
            private set

        /** Starts a session with [address], or with the remembered camera if null. */
        fun connectIntent(context: Context, address: String? = null) =
            Intent(context, GpsForegroundService::class.java).apply {
                action = ACTION_CONNECT
                if (address != null) putExtra(EXTRA_ADDRESS, address)
            }

        fun createNotificationChannel(context: Context) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Sony GPS Link",
                NotificationManager.IMPORTANCE_LOW   // no sound, no pop-up
            ).apply {
                description = "GPS-Übertragung zur Sony-Kamera"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
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
            val speedKmh = (loc.speed * 3.6f).toInt()
            if (isReady) {
                cameraGatt?.sendLocation(loc)
                updateNotification(
                    "GPS aktiv — %.5f, %.5f  %d km/h".format(loc.latitude, loc.longitude, speedKmh)
                )
            }
            recordTrackPoint(loc)
            statusListener?.onServiceGpsUpdate(loc.latitude, loc.longitude, loc.accuracy, speedKmh)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        prefs = CameraPrefs(this)
        createNotificationChannel(this)
        // Start foreground immediately so Android doesn't kill us before a camera is connected
        startForeground(NOTIF_ID, buildNotification("Bereit…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP    -> stopTransfer()
            ACTION_CONNECT -> connectByAddress(intent.getStringExtra(EXTRA_ADDRESS))
        }
        // START_STICKY: system restarts the service if killed (with null intent)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(reconnectRunnable)
        stopGps()
        closeTrack()
        cameraGatt?.stopTransfer()
        cameraGatt?.disconnect()
        cameraGatt?.close()
        cameraGatt = null
        if (sessionActive) {
            sessionActive = false
            AutoConnect.arm(this)
        }
    }

    // ── Public API (called by MainActivity via binder) ────────────────────────

    @SuppressLint("MissingPermission")
    fun connectToCamera(device: BluetoothDevice) {
        if (!sessionActive) {
            sessionActive = true
            AutoConnect.disarm(this)
        }
        AutoConnect.cancelNearbyNotification(this)
        handler.removeCallbacks(reconnectRunnable)
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
        AutoConnect.snoozeAfterManualStop(this)
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

    @SuppressLint("MissingPermission")
    override fun onReady() {
        isReady        = true
        reconnectCount = 0
        lastDevice?.let { prefs.rememberCamera(it.address, it.name) }
        updateNotification("GPS aktiv")
        log("Bereit — starte GPS + APO-Keepalive (9 s)")
        startGps()
        statusListener?.onServiceReady()
    }

    override fun onDisconnected() {
        isConnected = false
        isReady     = false
        cameraGatt?.close()
        cameraGatt = null
        statusListener?.onServiceDisconnected()

        if (userStopped) {
            updateNotification("Getrennt")
            finishSession()
        } else {
            // Keep the GPX track going while we try to get the camera back
            if (track == null) stopGps()
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
            finishSession()
            return
        }
        reconnectCount++
        val msg = "Reconnect in ${RECONNECT_DELAY / 1000}s… ($reconnectCount/$MAX_RECONNECT)"
        log(msg)
        updateNotification(msg)
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY)
    }

    private fun attemptReconnect() {
        val device = lastDevice ?: return
        // connectToCamera() resets the counter — keep counting across attempts
        val count = reconnectCount
        connectToCamera(device)
        reconnectCount = count
    }

    // ── Session helpers ───────────────────────────────────────────────────────

    /** [address] null → remembered camera. */
    private fun connectByAddress(address: String?) {
        val target  = address ?: prefs.cameraAddress
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (target == null || adapter == null || !adapter.isEnabled) {
            log(if (target == null) "Keine gespeicherte Kamera" else "Bluetooth ist ausgeschaltet")
            if (!sessionActive) stopSelf()
            return
        }
        connectToCamera(adapter.getRemoteDevice(target))
    }

    /** Ends the session: no more GPS, track closed, auto-connect armed again. */
    private fun finishSession() {
        handler.removeCallbacks(reconnectRunnable)
        stopGps()
        closeTrack()
        if (sessionActive) {
            sessionActive = false
            AutoConnect.arm(this)
            log("Sitzung beendet")
        }
        stopSelf()
    }

    // ── GPS ───────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsRunning) return
        gpsRunning = true
        fusedLocation.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    private fun stopGps() {
        gpsRunning = false
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    // ── GPX track ─────────────────────────────────────────────────────────────

    /** Follows the setting live, so toggling it in the UI applies to the running session. */
    private fun recordTrackPoint(loc: Location) {
        if (!prefs.recordTrack) {
            closeTrack()
            return
        }
        try {
            val recorder = track ?: TrackRecorder.start(this).also {
                track = it
                log("GPX-Aufzeichnung: ${it.file.name}")
            }
            recorder.add(loc)
        } catch (e: Exception) {
            log("FEHLER: GPX-Aufzeichnung — ${e.message}")
            closeTrack()
        }
    }

    private fun closeTrack() {
        val recorder = track ?: return
        track = null
        try {
            recorder.close()
            log("GPX-Track gespeichert (${recorder.pointCount} Punkte)")
        } catch (e: Exception) {
            log("FEHLER: GPX-Track schließen — ${e.message}")
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun shutdownAndStop() {
        handler.removeCallbacks(reconnectRunnable)
        stopGps()
        val gatt = cameraGatt
        if (gatt == null) {
            // Waiting for a reconnect — no BLE link that could confirm the disconnect
            finishSession()
            return
        }
        gatt.stopTransfer()
        gatt.disconnect()
        // onDisconnected() will call finishSession() once BLE confirms
    }

    private fun log(msg: String) {
        statusListener?.onServiceLog(msg)
    }

    // ── Notification ──────────────────────────────────────────────────────────

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
