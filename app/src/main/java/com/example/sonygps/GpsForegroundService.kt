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
 * GPS: fixes are requested every 5 s (HIGH_ACCURACY) or, in battery-saver mode,
 * every 10–60 s with balanced priority. Independently of that, the latest fix is
 * sent to the camera every 5 s (SEND_INTERVAL) with a fresh timestamp — the camera
 * flags its position as invalid when packets stop for more than a few seconds,
 * while the expensive part is the GPS chip, not the BLE write. Without a fix for
 * maxFixAge (3 fix intervals, at least 60 s) the sending stops so the camera
 * honestly shows "no GPS".
 *
 * Wake lock: the ticker and the APO keepalive are Handler timers, i.e. based on
 * uptimeMillis, which does not advance while the CPU is in deep sleep. A foreground
 * service does not keep the CPU awake by itself. In normal mode the GPS fix every
 * 5 s wakes it anyway; in battery-saver mode there are 20–60 s between fixes and
 * the packets stopped with the screen off, so the camera toggled between valid and
 * invalid. A partial wake lock (CPU only, no screen) is held for the whole session.
 * The saving of the battery-saver mode comes from the idle GPS chip, not from a
 * sleeping CPU — the BLE write every 5 s needs the CPU anyway.
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

    /** Latest usable fix; sent to the camera by [sendRunnable] until it is older than [maxFixAge]. */
    private var lastFix: Location? = null
    private var staleLogged = false
    /** elapsedRealtime of the last ticker run; detects a ticker that stood still (CPU asleep). */
    private var lastTickElapsed = 0L
    private val sendRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (lastTickElapsed != 0L) {
                val gapMs = now - lastTickElapsed
                if (gapMs > TICK_GAP_WARN) log("Sende-Takt ${gapMs / 1000} s ausgesetzt (Gerät im Tiefschlaf?)")
            }
            lastTickElapsed = now
            sendLatestFix()
            handler.postDelayed(this, SEND_INTERVAL)
        }
    }

    /** Keeps the CPU awake for the session so the Handler timers keep firing with the screen off. */
    private var wakeLock: PowerManager.WakeLock? = null

    /** Settings changed in SettingsActivity apply to the running session. */
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == CameraPrefs.KEY_BATTERY_SAVER || key == CameraPrefs.KEY_SAVER_INTERVAL) applyGpsMode()
    }

    companion object {
        const val CHANNEL_ID     = "sony_gps_channel"
        const val NOTIF_ID       = 1
        const val ACTION_STOP    = "com.example.sonygps.ACTION_STOP"
        const val ACTION_CONNECT = "com.example.sonygps.ACTION_CONNECT"
        private const val EXTRA_ADDRESS = "address"

        private const val MAX_RECONNECT     = 10
        private const val NORMAL_INTERVAL   = 5_000L   // ms, fix request in normal mode
        private const val SEND_INTERVAL     = 5_000L   // ms, packet cadence towards the camera
        private const val MIN_FIX_AGE_LIMIT = 60_000L  // ms, lower bound for maxFixAge
        private const val TICK_GAP_WARN     = 10_000L  // ms, log when the ticker paused longer
        private const val RECONNECT_DELAY   = 4_000L   // ms

        /** True from connect until the session ends (stop, reconnects exhausted). */
        @Volatile
        var sessionActive = false
            private set

        /** True while the handshake is complete and GPS packets are being sent (for the QS tile). */
        @Volatile
        var readyForGps = false
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

    // ── GPS request ───────────────────────────────────────────────────────────
    // Normal: HIGH_ACCURACY every 5 s. The camera only needs a new fix when the
    // position actually changes; 5 s vs 2 s saves ~15 % GPS wakeup overhead.
    // Battery saver: BALANCED_POWER_ACCURACY every saverIntervalMs. Fused location
    // may then serve fixes from WiFi/cell and keep the GPS chip off in between —
    // for geotagging photos a position that is a few seconds old is irrelevant.
    private val normalRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, NORMAL_INTERVAL
    ).setMinUpdateIntervalMillis(3_000L).build()

    private fun saverRequest(intervalMs: Long) = LocationRequest.Builder(
        Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs
    ).setMinUpdateIntervalMillis(intervalMs / 2).build()

    /** Fix interval (ms) the running location request was started with; null while GPS is off. */
    private var gpsIntervalActive: Long? = null

    /** Interval the current settings ask for. */
    private val wantedInterval: Long
        get() = if (prefs.batterySaver) prefs.saverIntervalMs else NORMAL_INTERVAL

    /**
     * Sending stops when the fix is older than this: three fix intervals, at least
     * 60 s. A fixed 60 s equalled the largest saver interval, so one slightly late
     * fix already caused a gap.
     */
    private val maxFixAge: Long
        get() = maxOf(MIN_FIX_AGE_LIMIT, 3 * wantedInterval)

    @SuppressLint("MissingPermission")
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val speedKmh = (loc.speed * 3.6f).toInt()
            val first = lastFix == null
            lastFix = loc
            staleLogged = false
            // First fix of the session: don't wait for the next tick, the camera is waiting
            if (first && isReady) {
                handler.removeCallbacks(sendRunnable)
                handler.post(sendRunnable)
            }
            recordTrackPoint(loc)
            statusListener?.onServiceGpsUpdate(loc.latitude, loc.longitude, loc.accuracy, speedKmh)
        }
    }

    /**
     * Sends the latest fix to the camera, re-stamped with the current time. Called
     * every SEND_INTERVAL regardless of how often fixes arrive. Re-stamping matters:
     * the camera uses the packet time for its clock correction, so repeating an old
     * timestamp would set the camera clock back by up to one fix interval.
     */
    private fun sendLatestFix() {
        if (!isReady) return
        val fix = lastFix ?: return
        val ageMs = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000L
        if (ageMs > maxFixAge) {
            if (!staleLogged) {
                staleLogged = true
                log("Kein aktueller Fix seit ${ageMs / 1000} s — sende nichts mehr")
                updateNotification("Warte auf GPS…")
            }
            return
        }
        val now = Location(fix).apply {
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        cameraGatt?.sendLocation(now)
        val speedKmh = (fix.speed * 3.6f).toInt()
        updateNotification(
            "GPS aktiv — %.5f, %.5f  %d km/h".format(fix.latitude, fix.longitude, speedKmh)
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        prefs = CameraPrefs(this)
        prefs.registerOnChange(prefsListener)
        createNotificationChannel(this)
        // Start foreground immediately so Android doesn't kill us before a camera is connected
        startForeground(NOTIF_ID, buildNotification("Bereit…"))
        DiagnosticLog.log(this, "Service", "Service gestartet")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP    -> stopTransfer()
            ACTION_CONNECT -> connectByAddress(intent.getStringExtra(EXTRA_ADDRESS))
            // START_STICKY restart after the process was killed: the session state is lost
            null           -> DiagnosticLog.log(this, "Service", "Vom System neu gestartet (Prozess wurde beendet)")
        }
        // START_STICKY: system restarts the service if killed (with null intent)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnChange(prefsListener)
        handler.removeCallbacks(reconnectRunnable)
        stopGps()
        closeTrack()
        cameraGatt?.stopTransfer()
        cameraGatt?.disconnect()
        cameraGatt?.close()
        cameraGatt = null
        if (sessionActive) {
            setSessionActive(false)
            AutoConnect.arm(this)
        }
        DiagnosticLog.log(this, "Service", "Service beendet")
    }

    // ── Public API (called by MainActivity via binder) ────────────────────────

    @SuppressLint("MissingPermission")
    fun connectToCamera(device: BluetoothDevice) {
        if (!sessionActive) {
            setSessionActive(true)
            AutoConnect.disarm(this)
            acquireWakeLock()
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
        readyForGps    = true
        SessionTileService.requestUpdate(this)
        reconnectCount = 0
        lastDevice?.let { prefs.rememberCamera(it.address, it.name) }
        updateNotification("GPS aktiv")
        log("Bereit — starte GPS + APO-Keepalive (9 s)")
        startGps()
        // Packet ticker: sends the latest fix every SEND_INTERVAL (also across reconnects)
        handler.removeCallbacks(sendRunnable)
        lastTickElapsed = 0L   // the pause during a reconnect is not a ticker stall
        handler.post(sendRunnable)
        statusListener?.onServiceReady()
    }

    override fun onDisconnected() {
        isConnected = false
        isReady     = false
        readyForGps = false
        SessionTileService.requestUpdate(this)
        handler.removeCallbacks(sendRunnable)
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
            setSessionActive(false)
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
        val saver    = prefs.batterySaver
        val interval = wantedInterval
        gpsIntervalActive = interval
        fusedLocation.requestLocationUpdates(
            if (saver) saverRequest(interval) else normalRequest, locationCallback, Looper.getMainLooper()
        )
        log(if (saver) "GPS: Akku-Modus (Fix alle ${interval / 1000} s, ausgeglichen; Paket alle ${SEND_INTERVAL / 1000} s)"
            else "GPS: Normal (alle ${interval / 1000} s, hohe Genauigkeit)")
    }

    private fun stopGps() {
        gpsRunning = false
        gpsIntervalActive = null
        lastFix = null
        handler.removeCallbacks(sendRunnable)
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    /**
     * Re-reads the battery-saver settings and restarts the location request if the
     * interval changed, so a change in SettingsActivity applies to the running session.
     * The packet ticker keeps running; lastFix is kept so the camera sees no gap.
     */
    private fun applyGpsMode() {
        if (!gpsRunning || gpsIntervalActive == wantedInterval) return
        val keep = lastFix
        stopGps()
        startGps()
        lastFix = keep
        if (isReady) handler.post(sendRunnable)
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

    /** Updates the session flag and pushes the new state to the Quick Settings tile. */
    private fun setSessionActive(active: Boolean) {
        sessionActive = active
        if (!active) {
            readyForGps = false
            releaseWakeLock()
        }
        SessionTileService.requestUpdate(this)
    }

    /** No timeout: a session may last for hours; the lock is released with the session. */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SonyGpsLink:session")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire()
        log("WakeLock gehalten — CPU bleibt für den 5-s-Takt wach")
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            lock.release()
            log("WakeLock freigegeben")
        }
    }

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

    /** Goes to the bound activity (if any) and always to the persistent diagnostic log. */
    private fun log(msg: String) {
        DiagnosticLog.log(this, "Service", msg)
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
