package com.example.sonygps

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import com.example.sonygps.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
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
 *      — or "Verbinden" on the remembered camera (no scan needed)
 *   2. startForegroundService(connectIntent) + bindService()
 *   3. Service connects BLE, starts GPS, sends APO keepalive every 9 s
 *   4. MainActivity receives status via StatusListener while bound
 *   5. Pressing home unbinds but service keeps running (persistent notification)
 *   6. Returning to app → onStart() binds again, UI reflects current state
 *   7. "Trennen" → service.stopTransfer() → service stops itself → stopSelf()
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), GpsForegroundService.StatusListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: CameraPrefs

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false

    private val foundCameras = mutableListOf<ScanResult>()
    private val mainHandler  = Handler(Looper.getMainLooper())

    /** Scope for update check / download; cancelled in onDestroy. */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null
    /** APK waiting to be installed once the user has allowed installs from this app. */
    private var pendingApk: File? = null

    private companion object {
        const val REQ_PERMISSIONS         = 1
        const val REQ_BACKGROUND_LOCATION = 2
    }

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
        prefs = CameraPrefs(this)

        binding.btnScan.setOnClickListener {
            if (isScanning) stopScan() else startScan()
        }
        binding.btnDisconnect.setOnClickListener {
            gpsService?.stopTransfer()
        }
        binding.btnConnectLast.setOnClickListener { connectToRememberedCamera() }
        binding.btnForget.setOnClickListener { forgetCamera() }
        binding.btnTracks.setOnClickListener { showTracks() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdates(manual = true) }
        binding.tvVersion.text = "Version ${UpdateChecker.installedVersion(this) ?: "?"}"

        // Set initial state before attaching listeners, so they only react to the user
        binding.swAutoConnect.isChecked = prefs.autoConnect
        binding.swRecordTrack.isChecked = prefs.recordTrack
        binding.swAutoConnect.setOnCheckedChangeListener { _, checked -> setAutoConnect(checked) }
        binding.swRecordTrack.setOnCheckedChangeListener { _, checked ->
            prefs.recordTrack = checked
            log(if (checked) "GPX-Aufzeichnung an" else "GPX-Aufzeichnung aus")
        }

        requestPermissions()
        updateUi()
    }

    override fun onStart() {
        super.onStart()
        // Bind to service if it's already running (e.g. user returns to app)
        val intent = Intent(this, GpsForegroundService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        // Re-arm in case Bluetooth was toggled or a permission granted meanwhile
        AutoConnect.arm(this)
        updateUi()
        checkForUpdates(manual = false)
    }

    override fun onResume() {
        super.onResume()
        // Back from the "install unknown apps" setting → continue the install
        val apk = pendingApk
        if (apk != null && UpdateChecker.canInstall(this)) {
            pendingApk = null
            launchInstaller(apk)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
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
        startSession(result.device.address)
    }

    private fun connectToRememberedCamera() {
        if (bluetoothAdapter?.isEnabled != true) { toast("Bluetooth einschalten"); return }
        val name = prefs.cameraName ?: prefs.cameraAddress ?: return
        stopScan()
        binding.tvStatus.text = "Verbinde mit $name…"
        log("Starte Service und verbinde mit gespeicherter Kamera $name")
        startSession(null)
    }

    /** [address] null → remembered camera. */
    private fun startSession(address: String?) {
        // Start service as foreground (persists beyond app lifecycle)
        val serviceIntent = GpsForegroundService.connectIntent(this, address)
        startForegroundService(serviceIntent)

        // Bind if not already bound (onStart may have already done this)
        if (!serviceBound) {
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        updateUi()
    }

    // ── Remembered camera / auto-connect ──────────────────────────────────────

    private fun forgetCamera() {
        AlertDialog.Builder(this)
            .setTitle("Kamera vergessen?")
            .setMessage("${prefs.cameraName ?: prefs.cameraAddress} wird nicht mehr automatisch verbunden.")
            .setPositiveButton("Vergessen") { _, _ ->
                AutoConnect.disarm(this)
                prefs.forgetCamera()
                log("Gespeicherte Kamera entfernt")
                updateUi()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun setAutoConnect(enabled: Boolean) {
        prefs.autoConnect = enabled
        if (!enabled) {
            AutoConnect.disarm(this)
            log("Automatisches Verbinden aus")
            return
        }
        log("Automatisches Verbinden an")
        if (!AutoConnect.hasBackgroundLocation(this)) {
            AlertDialog.Builder(this)
                .setTitle("Standort im Hintergrund")
                .setMessage(
                    "Damit die App die GPS-Übertragung selbst starten kann, während sie geschlossen ist, " +
                    "braucht sie Standortzugriff „Immer zulassen“. Ohne diese Berechtigung erscheint " +
                    "stattdessen eine Benachrichtigung, sobald die Kamera in der Nähe ist."
                )
                .setPositiveButton("Weiter") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND_LOCATION
                    )
                }
                .setNegativeButton("Später") { _, _ -> finishAutoConnectSetup() }
                .show()
            return
        }
        finishAutoConnectSetup()
    }

    private fun finishAutoConnectSetup() {
        requestBatteryOptimizationExemption()
        AutoConnect.arm(this)
    }

    /**
     * Android 12+ only lets exempt apps start a foreground service from the background.
     * Ignoring battery optimizations is such an exemption.
     */
    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: ActivityNotFoundException) {
            log("Akku-Optimierung bitte manuell für Sony GPS Link deaktivieren")
        }
    }

    // ── GPX tracks ────────────────────────────────────────────────────────────

    private fun showTracks() {
        val tracks = TrackRecorder.listTracks(this)
        if (tracks.isEmpty()) { toast("Noch keine Tracks aufgezeichnet"); return }
        val labels = tracks.map { "${it.nameWithoutExtension}  (${(it.length() + 1023) / 1024} KB)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Track teilen")
            .setItems(labels) { _, idx -> shareTracks(listOf(tracks[idx])) }
            .setNeutralButton("Alle teilen") { _, _ -> shareTracks(tracks) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun shareTracks(files: List<File>) {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = "application/gpx+xml"
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "GPX-Track teilen"))
    }

    // ── In-app update ─────────────────────────────────────────────────────────

    /**
     * Asks GitHub for the latest release.
     * Automatic checks run at most once a day, stay silent when up to date or
     * offline, and skip a release the user dismissed. Manual checks always report.
     */
    private fun checkForUpdates(manual: Boolean) {
        if (updateJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.lastUpdateCheck < UpdateChecker.CHECK_INTERVAL_MS) return

        val installed = UpdateChecker.installedVersion(this)
        if (manual) log("Suche nach Updates…")
        updateJob = uiScope.launch {
            try {
                val release = UpdateChecker.fetchLatest()
                prefs.lastUpdateCheck = System.currentTimeMillis()
                if (release == null || installed == null || release.version <= installed) {
                    if (manual) toast("Sony GPS Link ist aktuell (${installed ?: "?"})")
                    return@launch
                }
                if (!manual && release.tag == prefs.skippedUpdateTag) return@launch
                log("Update verfügbar: ${release.tag}")
                showUpdateDialog(release, installed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) {
                    log("Update-Prüfung fehlgeschlagen: ${e.message}")
                    toast("Update-Prüfung fehlgeschlagen")
                }
            }
        }
    }

    private fun showUpdateDialog(release: UpdateChecker.Release, installed: UpdateChecker.Version) {
        val sb = StringBuilder("Version ${release.version} ist verfügbar, installiert ist $installed.")
        if (release.notes.isNotEmpty()) sb.append("\n\n").append(release.notes.take(1500))
        if (UpdateChecker.isDebugBuild(this)) {
            sb.append("\n\n⚠ Dies ist ein Debug-Build. Die Release-APK hat eine andere Signatur " +
                      "und lässt sich nicht darüber installieren.")
        }
        AlertDialog.Builder(this)
            .setTitle("Update verfügbar")
            .setMessage(sb)
            .setPositiveButton("Installieren") { _, _ -> downloadAndInstall(release) }
            .setNegativeButton("Später", null)
            .setNeutralButton("Überspringen") { _, _ ->
                prefs.skippedUpdateTag = release.tag
                log("Update ${release.tag} übersprungen")
            }
            .show()
    }

    private fun downloadAndInstall(release: UpdateChecker.Release) {
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val label = TextView(this).apply { text = release.apkName }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Update wird geladen…")
            .setView(content)
            .setCancelable(false)
            .setNegativeButton("Abbrechen") { _, _ -> updateJob?.cancel() }
            .show()

        log("Lade ${release.apkName}…")
        updateJob = uiScope.launch {
            try {
                val apk = UpdateChecker.download(this@MainActivity, release) { pct ->
                    // Called on the IO thread; View.post is thread-safe
                    progress.post {
                        if (pct < 0) progress.isIndeterminate = true
                        else { progress.isIndeterminate = false; progress.progress = pct }
                    }
                }
                dialog.dismiss()
                log("Download fertig — Installer wird geöffnet")
                startInstall(apk)
            } catch (e: CancellationException) {
                dialog.dismiss()
                log("Download abgebrochen")
            } catch (e: Exception) {
                dialog.dismiss()
                log("Download fehlgeschlagen: ${e.message}")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Download fehlgeschlagen")
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /** Android 8+ only lets the installer run once the user has allowed this app as a source. */
    private fun startInstall(apk: File) {
        if (UpdateChecker.canInstall(this)) { launchInstaller(apk); return }
        pendingApk = apk
        AlertDialog.Builder(this)
            .setTitle("Installation erlauben")
            .setMessage(
                "Android verlangt einmalig die Erlaubnis, dass Sony GPS Link Updates installieren darf. " +
                "Nach dem Zurückkehren wird die Installation fortgesetzt."
            )
            .setPositiveButton("Einstellungen") { _, _ ->
                try {
                    startActivity(UpdateChecker.unknownSourcesSettingsIntent(this))
                } catch (e: ActivityNotFoundException) {
                    pendingApk = null
                    launchInstaller(apk)  // the installer shows its own prompt then
                }
            }
            .setNegativeButton("Abbrechen") { _, _ -> pendingApk = null }
            .show()
    }

    private fun launchInstaller(apk: File) {
        try {
            startActivity(UpdateChecker.installIntent(this, apk))
        } catch (e: ActivityNotFoundException) {
            log("Kein Paket-Installer gefunden")
            toast("Kein Paket-Installer gefunden")
        }
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

    override fun onServiceLog(msg: String) {
        log(msg)
        // The service ends a session without a dedicated callback (stop, reconnects exhausted)
        updateUi()
    }

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
            }
            svc.isConnected  -> {
                binding.tvStatus.text = "Verbunden — GPS-Protokoll…"
            }
        }
        updateUi()
    }

    private fun updateUi() {
        val connected = gpsService?.isConnected ?: false
        val session   = GpsForegroundService.sessionActive
        binding.btnScan.text            = if (isScanning) "Scan stoppen" else "Kamera suchen"
        binding.btnScan.isEnabled       = !connected
        binding.btnDisconnect.isEnabled = connected || session

        val address = prefs.cameraAddress
        binding.tvCamera.text = when (address) {
            null -> "Keine Kamera gespeichert"
            else -> "${prefs.cameraName ?: "Sony-Kamera"}  ($address)"
        }
        binding.btnConnectLast.isEnabled = address != null && !session
        binding.btnForget.isEnabled      = address != null && !session
        binding.swAutoConnect.isEnabled  = address != null
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
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val denied = permissions.zip(grantResults.toTypedArray())
            .filter { (_, r) -> r != PackageManager.PERMISSION_GRANTED }
            .map { (p, _) -> p.substringAfterLast('.') }
        when (requestCode) {
            REQ_PERMISSIONS -> {
                if (denied.isNotEmpty())
                    log("Verweigerte Berechtigungen: ${denied.joinToString()}")
                AutoConnect.arm(this)
            }
            REQ_BACKGROUND_LOCATION -> {
                if (denied.isNotEmpty())
                    log("Kein Standort im Hintergrund — Kamera wird per Benachrichtigung angeboten")
                finishAutoConnectSetup()
            }
        }
    }
}
