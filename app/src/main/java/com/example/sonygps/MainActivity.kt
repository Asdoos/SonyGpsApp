package com.example.sonygps

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
        const val REQ_PERMISSIONS = 1
    }

    // ── Service binding ───────────────────────────────────────────────────────

    private var gpsService: GpsForegroundService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            gpsService = (binder as GpsForegroundService.LocalBinder).service
            gpsService?.statusListener = this@MainActivity
            serviceBound = true
            log(getString(R.string.log_service_bound))
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
        binding.btnCheckUpdate.setOnClickListener { checkForUpdates(manual = true) }
        binding.tvVersion.text = getString(R.string.version_fmt, UpdateChecker.installedVersion(this)?.toString() ?: "?")

        requestPermissions()
        updateUi()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
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
        if (bluetoothAdapter?.isEnabled != true) { toast(getString(R.string.toast_enable_bluetooth)); return }
        foundCameras.clear()
        scanner = bluetoothAdapter!!.bluetoothLeScanner
        val filter   = ScanFilter.Builder().setManufacturerData(301, byteArrayOf()).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
        binding.tvStatus.text = getString(R.string.status_scanning)
        log(getString(R.string.log_scan_started))
        updateUi()
    }

    private fun stopScan() {
        if (!isScanning) return
        scanner?.stopScan(scanCallback)
        isScanning = false
        log(getString(R.string.log_scan_stopped_fmt, foundCameras.size))
        updateUi()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (foundCameras.any { it.device.address == result.device.address }) return
            log(getString(R.string.log_found_fmt, result.device.name ?: getString(R.string.default_camera_name), result.device.address, result.rssi))
            foundCameras.add(result)
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({ showCameraChooser() }, 1500)
        }

        override fun onScanFailed(errorCode: Int) {
            log(getString(R.string.log_scan_failed_fmt, errorCode))
            isScanning = false
            binding.tvStatus.text = getString(R.string.status_scan_failed_fmt, errorCode)
            updateUi()
        }
    }

    private fun showCameraChooser() {
        if (gpsService?.isConnected == true || foundCameras.isEmpty()) return
        stopScan()
        val names = foundCameras.map { r ->
            "${r.device.name ?: getString(R.string.default_camera_name)}  (${r.device.address})  ${r.rssi} dBm"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_choose_camera)
            .setItems(names) { _, idx -> connectToCamera(foundCameras[idx]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Connect via Service ───────────────────────────────────────────────────

    private fun connectToCamera(result: ScanResult) {
        val name = result.device.name ?: result.device.address
        binding.tvStatus.text = getString(R.string.status_connecting_fmt, name)
        log(getString(R.string.log_connecting_fmt, name))
        startSession(result.device.address)
    }

    private fun connectToRememberedCamera() {
        if (bluetoothAdapter?.isEnabled != true) { toast(getString(R.string.toast_enable_bluetooth)); return }
        val name = prefs.cameraName ?: prefs.cameraAddress ?: return
        stopScan()
        binding.tvStatus.text = getString(R.string.status_connecting_fmt, name)
        log(getString(R.string.log_connecting_remembered_fmt, name))
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
            .setTitle(R.string.dialog_forget_title)
            .setMessage(getString(R.string.dialog_forget_msg_fmt, prefs.cameraName ?: prefs.cameraAddress))
            .setPositiveButton(R.string.btn_forget) { _, _ ->
                AutoConnect.disarm(this)
                prefs.forgetCamera()
                log(getString(R.string.log_camera_forgotten))
                updateUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        if (manual) log(getString(R.string.log_checking_updates))
        updateJob = uiScope.launch {
            try {
                val release = UpdateChecker.fetchLatest()
                prefs.lastUpdateCheck = System.currentTimeMillis()
                if (release == null || installed == null || release.version <= installed) {
                    if (manual) toast(getString(R.string.toast_up_to_date_fmt, installed?.toString() ?: "?"))
                    return@launch
                }
                if (!manual && release.tag == prefs.skippedUpdateTag) return@launch
                log(getString(R.string.log_update_available_fmt, release.tag))
                showUpdateDialog(release, installed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) {
                    log(getString(R.string.log_update_check_failed_fmt, e.message))
                    toast(getString(R.string.toast_update_check_failed))
                }
            }
        }
    }

    private fun showUpdateDialog(release: UpdateChecker.Release, installed: UpdateChecker.Version) {
        val sb = StringBuilder(getString(R.string.update_dialog_msg_fmt, release.version.toString(), installed.toString()))
        if (release.notes.isNotEmpty()) sb.append("\n\n").append(release.notes.take(1500))
        if (UpdateChecker.isDebugBuild(this)) {
            sb.append("\n\n").append(getString(R.string.update_debug_warning))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(sb)
            .setPositiveButton(R.string.install) { _, _ -> downloadAndInstall(release) }
            .setNegativeButton(R.string.later, null)
            .setNeutralButton(R.string.skip) { _, _ ->
                prefs.skippedUpdateTag = release.tag
                log(getString(R.string.log_update_skipped_fmt, release.tag))
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
            .setTitle(R.string.downloading_title)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> updateJob?.cancel() }
            .show()

        log(getString(R.string.log_downloading_fmt, release.apkName))
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
                log(getString(R.string.log_download_done))
                startInstall(apk)
            } catch (e: CancellationException) {
                dialog.dismiss()
                log(getString(R.string.log_download_cancelled))
            } catch (e: Exception) {
                dialog.dismiss()
                log(getString(R.string.log_download_failed_fmt, e.message))
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.download_failed_title)
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    /** Android 8+ only lets the installer run once the user has allowed this app as a source. */
    private fun startInstall(apk: File) {
        if (UpdateChecker.canInstall(this)) { launchInstaller(apk); return }
        pendingApk = apk
        AlertDialog.Builder(this)
            .setTitle(R.string.allow_install_title)
            .setMessage(R.string.allow_install_msg)
            .setPositiveButton(R.string.menu_settings) { _, _ ->
                try {
                    startActivity(UpdateChecker.unknownSourcesSettingsIntent(this))
                } catch (e: ActivityNotFoundException) {
                    pendingApk = null
                    launchInstaller(apk)  // the installer shows its own prompt then
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> pendingApk = null }
            .show()
    }

    private fun launchInstaller(apk: File) {
        try {
            startActivity(UpdateChecker.installIntent(this, apk))
        } catch (e: ActivityNotFoundException) {
            log(getString(R.string.no_installer))
            toast(getString(R.string.no_installer))
        }
    }

    // ── GpsForegroundService.StatusListener ──────────────────────────────────

    override fun onServiceConnected() {
        binding.tvStatus.text = getString(R.string.status_connected_protocol)
        log(getString(R.string.log_gatt_connected))
        updateUi()
    }

    override fun onServiceReady() {
        binding.tvStatus.text = getString(R.string.status_gps_active)
        log(getString(R.string.log_gps_active))
        updateUi()
    }

    override fun onServiceDisconnected() {
        binding.tvGps.text    = "—"
        binding.tvStatus.text = getString(R.string.status_disconnected)
        log(getString(R.string.log_disconnected))
        updateUi()
    }

    override fun onServiceLog(msg: String) {
        // The service already wrote the line to DiagnosticLog — UI only
        log(msg, persist = false)
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
                binding.tvStatus.text = getString(R.string.status_gps_active)
            }
            svc.isConnected  -> {
                binding.tvStatus.text = getString(R.string.status_connected_protocol)
            }
        }
        updateUi()
    }

    private fun updateUi() {
        val connected = gpsService?.isConnected ?: false
        val session   = GpsForegroundService.sessionActive
        binding.btnScan.text            = getString(if (isScanning) R.string.btn_stop_scan else R.string.btn_scan)
        binding.btnScan.isEnabled       = !connected
        binding.btnDisconnect.isEnabled = connected || session

        val address = prefs.cameraAddress
        binding.tvCamera.text = when (address) {
            null -> getString(R.string.camera_none)
            else -> "${prefs.cameraName ?: getString(R.string.default_camera_name)}  ($address)"
        }
        binding.btnConnectLast.isEnabled = address != null && !session
        binding.btnForget.isEnabled      = address != null && !session

        // Compact summary of the settings that shape a session (edited in SettingsActivity)
        val modes = buildList {
            if (prefs.autoConnect)  add(getString(R.string.mode_auto_connect))
            if (prefs.batterySaver) add(getString(R.string.mode_battery_saver_fmt, (prefs.saverIntervalMs / 1000).toInt()))
            if (prefs.recordTrack)  add(getString(R.string.mode_gpx))
        }
        binding.tvModes.text = if (modes.isEmpty()) getString(R.string.modes_default) else modes.joinToString(" · ")
    }

    /** Shows [msg] in the on-screen log; [persist] also writes it to the diagnostic log. */
    private fun log(msg: String, persist: Boolean = true) {
        if (persist) DiagnosticLog.log(this, "App", msg)
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
                    log(getString(R.string.log_denied_permissions_fmt, denied.joinToString()))
                AutoConnect.arm(this)
            }
        }
    }
}
