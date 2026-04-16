package com.example.sonygps

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.sonygps.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages UI and BLE scan only.
 * GPS + BLE session logic lives in GpsForegroundService so it survives:
 *   - App going to background
 *   - Screen turning off (Doze mode)
 *   - System memory pressure
 *
 * Flow:
 *   1. User taps "Kamera suchen" → BLE scan in MainActivity (short-lived)
 *   2. User picks device from dialog → startForegroundService() + bindService()
 *   3. Service connects BLE, starts GPS, sends APO keepalive every 9 s
 *   4. MainActivity receives status via StatusListener while bound
 *   5. Pressing home unbinds but service keeps running (persistent notification)
 *   6. Returning to app → onStart() binds again, UI reflects current state
 *   7. "Trennen" → service.stopTransfer() → service stops itself → stopSelf()
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), GpsForegroundService.StatusListener {

    private lateinit var binding: ActivityMainBinding

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false

    private val foundCameras = mutableListOf<ScanResult>()
    private val mainHandler  = Handler(Looper.getMainLooper())

    // ── Service binding ───────────────────────────────────────────────────────

    private var gpsService: GpsForegroundService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            gpsService = (binder as GpsForegroundService.LocalBinder).service
            gpsService?.statusListener = this@MainActivity
            serviceBound = true
            log("Service verbunden")
            // Sync UI with whatever state the service is already in
            syncUiWithServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            gpsService?.statusListener = null
            gpsService      = null
            serviceBound    = false
        }
    }

    // ── Log ───────────────────────────────────────────────────────────────────

    private val logBuilder = StringBuilder()
    private val sdf        = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothAdapter = (getSystemService(BluetoothManager::class.java))?.adapter

        binding.btnScan.setOnClickListener {
            if (isScanning) stopScan() else startScan()
        }
        binding.btnDisconnect.setOnClickListener {
            gpsService?.stopTransfer()
        }

        requestPermissions()
        updateUi()
    }

    override fun onStart() {
        super.onStart()
        // Bind to service if it's already running (e.g. user returns to app)
        val intent = Intent(this, GpsForegroundService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        // Unbind so we don't leak, but service keeps running in background
        if (serviceBound) {
            gpsService?.statusListener = null
            unbindService(serviceConnection)
            serviceBound = false
            gpsService   = null
        }
        stopScan()
    }

    // ── BLE Scan ──────────────────────────────────────────────────────────────

    private fun startScan() {
        if (bluetoothAdapter?.isEnabled != true) { toast("Bluetooth einschalten"); return }
        foundCameras.clear()
        scanner = bluetoothAdapter!!.bluetoothLeScanner
        val filter   = ScanFilter.Builder().setManufacturerData(301, byteArrayOf()).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
        binding.tvStatus.text = "Suche nach Sony-Kameras…"
        log("BLE-Scan gestartet")
        updateUi()
    }

    private fun stopScan() {
        if (!isScanning) return
        scanner?.stopScan(scanCallback)
        isScanning = false
        log("Scan gestoppt — ${foundCameras.size} Kamera(s) gefunden")
        updateUi()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (foundCameras.any { it.device.address == result.device.address }) return
            log("Gefunden: ${result.device.name ?: "Sony Camera"} (${result.device.address}) ${result.rssi} dBm")
            foundCameras.add(result)
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({ showCameraChooser() }, 1500)
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan-Fehler: $errorCode")
            isScanning = false
            binding.tvStatus.text = "Scan fehlgeschlagen (Code $errorCode)"
            updateUi()
        }
    }

    private fun showCameraChooser() {
        if (gpsService?.isConnected == true || foundCameras.isEmpty()) return
        stopScan()
        val names = foundCameras.map { r ->
            "${r.device.name ?: "Sony Camera"}  (${r.device.address})  ${r.rssi} dBm"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Kamera auswählen")
            .setItems(names) { _, idx -> connectToCamera(foundCameras[idx]) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ── Connect via Service ───────────────────────────────────────────────────

    private fun connectToCamera(result: ScanResult) {
        val name = result.device.name ?: result.device.address
        binding.tvStatus.text = "Verbinde mit $name…"
        log("Starte Service und verbinde mit $name")

        // Start service as foreground (persists beyond app lifecycle)
        val serviceIntent = Intent(this, GpsForegroundService::class.java)
        startForegroundService(serviceIntent)

        // Bind if not already bound (onStart may have already done this)
        if (!serviceBound) {
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Pass device — if service isn't bound yet, retry briefly
        val device = result.device
        mainHandler.postDelayed({
            gpsService?.connectToCamera(device)
                ?: log("WARNUNG: Service nicht erreichbar")
        }, 200)

        updateUi()
    }

    // ── GpsForegroundService.StatusListener ──────────────────────────────────

    override fun onServiceConnected() {
        binding.tvStatus.text = "Verbunden — GPS-Protokoll…"
        log("GATT verbunden")
        updateUi()
    }

    override fun onServiceReady() {
        binding.tvStatus.text = "✓ GPS aktiv — Koordinaten werden gesendet"
        log("GPS aktiv, APO-Keepalive alle 9 s")
        updateUi()
    }

    override fun onServiceDisconnected() {
        binding.tvGps.text    = "—"
        binding.tvStatus.text = "Getrennt"
        log("Verbindung getrennt")
        updateUi()
    }

    override fun onServiceLog(msg: String) = log(msg)

    override fun onServiceGpsUpdate(lat: Double, lon: Double, accuracy: Float, speedKmh: Int) {
        binding.tvGps.text = "%.6f, %.6f   ±%.0fm   %d km/h"
            .format(lat, lon, accuracy, speedKmh)
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun syncUiWithServiceState() {
        val svc = gpsService ?: return
        when {
            svc.isReady      -> {
                binding.tvStatus.text = "✓ GPS aktiv — Koordinaten werden gesendet"
                updateUi()
            }
            svc.isConnected  -> {
                binding.tvStatus.text = "Verbunden — GPS-Protokoll…"
                updateUi()
            }
        }
    }

    private fun updateUi() {
        val connected = gpsService?.isConnected ?: false
        binding.btnScan.text            = if (isScanning) "Scan stoppen" else "Kamera suchen"
        binding.btnScan.isEnabled       = !connected
        binding.btnDisconnect.isEnabled = connected
    }

    private fun log(msg: String) {
        val time = sdf.format(Date())
        logBuilder.insert(0, "[$time] $msg\n")
        if (logBuilder.length > 6000) logBuilder.setLength(6000)
        binding.tvLog.text = logBuilder.toString()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestPermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty())
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { (_, r) -> r != PackageManager.PERMISSION_GRANTED }
                .map { (p, _) -> p.substringAfterLast('.') }
            if (denied.isNotEmpty())
                log("Verweigerte Berechtigungen: ${denied.joinToString()}")
        }
    }
}
