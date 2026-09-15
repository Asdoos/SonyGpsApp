package com.example.sonygps

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.*
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.sonygps.databinding.ActivityMainBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
 *   1. User taps "Search for camera" → BLE scan in MainActivity (short-lived)
 *      — or "Connect" on the remembered camera (no scan needed)
 *   2. startForegroundService(connectIntent) + bindService()
 *   3. Service connects BLE, starts GPS, sends APO keepalive every 9 s
 *   4. MainActivity receives status via StatusListener while bound
 *   5. Pressing home unbinds but service keeps running (persistent notification)
 *   6. Returning to app → onStart() binds again, UI reflects current state
 *   7. "Disconnect" → service.stopTransfer() → service stops itself → stopSelf()
 *
 * The screen is rendered from a single [Phase] so every state change goes
 * through [render] and the hero card, buttons and position card stay in sync.
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), GpsForegroundService.StatusListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: CameraPrefs
    private lateinit var updates: UpdateFlow

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false

    private val foundCameras = mutableListOf<ScanResult>()
    private val mainHandler  = Handler(Looper.getMainLooper())

    private companion object {
        const val REQ_PERMISSIONS = 1
        const val RSSI_STRONG = -60
        const val RSSI_MEDIUM = -75
    }

    // ── Screen state ─────────────────────────────────────────────────────────

    private enum class Phase { IDLE, SCANNING, CONNECTING, LINKING, ACTIVE, ENDED, SCAN_FAILED }

    private var phase = Phase.IDLE
    /** Camera the current attempt targets (for the hero text). */
    private var targetName: String? = null
    private var scanErrorCode = 0
    /** True once the service reported an active session for the current attempt. */
    private var sessionSeen = false

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
        prefs   = CameraPrefs(this)
        updates = UpdateFlow(this) { log(it) }

        binding.btnPrimary.setOnClickListener {
            if (prefs.cameraAddress != null) connectToRememberedCamera() else startScan()
        }
        binding.btnStop.setOnClickListener {
            if (isScanning) stopScan() else gpsService?.stopTransfer()
        }
        binding.btnSecondary.setOnClickListener { startScan() }
        binding.btnForget.setOnClickListener { forgetCamera() }
        binding.rowActivity.setOnClickListener { toggleActivity() }

        requestPermissions()
        render()
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
        render()
        updates.check(manual = false)
    }

    override fun onResume() {
        super.onResume()
        updates.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        updates.destroy()
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
        phase = Phase.SCANNING
        log(getString(R.string.log_scan_started))
        render()
    }

    private fun stopScan() {
        if (!isScanning) return
        scanner?.stopScan(scanCallback)
        isScanning = false
        if (phase == Phase.SCANNING) phase = Phase.IDLE
        log(getString(R.string.log_scan_stopped_fmt, foundCameras.size))
        render()
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
            isScanning    = false
            scanErrorCode = errorCode
            phase         = Phase.SCAN_FAILED
            render()
        }
    }

    private fun showCameraChooser() {
        if (gpsService?.isConnected == true || foundCameras.isEmpty()) return
        stopScan()
        val names = foundCameras.map { r ->
            val signal = when {
                r.rssi > RSSI_STRONG -> R.string.signal_strong
                r.rssi > RSSI_MEDIUM -> R.string.signal_medium
                else                 -> R.string.signal_weak
            }
            getString(R.string.camera_item_fmt, r.device.name ?: getString(R.string.default_camera_name), getString(signal))
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_choose_camera)
            .setItems(names) { _, idx -> connectToCamera(foundCameras[idx]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Connect via Service ───────────────────────────────────────────────────

    private fun connectToCamera(result: ScanResult) {
        val name = result.device.name ?: getString(R.string.default_camera_name)
        log(getString(R.string.log_connecting_fmt, name))
        startSession(result.device.address, name)
    }

    private fun connectToRememberedCamera() {
        if (bluetoothAdapter?.isEnabled != true) { toast(getString(R.string.toast_enable_bluetooth)); return }
        if (prefs.cameraAddress == null) return
        val name = prefs.cameraName ?: getString(R.string.default_camera_name)
        stopScan()
        log(getString(R.string.log_connecting_remembered_fmt, name))
        startSession(null, name)
    }

    /** [address] null → remembered camera. */
    private fun startSession(address: String?, name: String) {
        targetName  = name
        sessionSeen = false
        phase       = Phase.CONNECTING

        // Start service as foreground (persists beyond app lifecycle)
        val serviceIntent = GpsForegroundService.connectIntent(this, address)
        startForegroundService(serviceIntent)

        // Bind if not already bound (onStart may have already done this)
        if (!serviceBound) {
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        render()
    }

    // ── Remembered camera / auto-connect ──────────────────────────────────────

    private fun forgetCamera() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_forget_title)
            .setMessage(getString(R.string.dialog_forget_msg_fmt, prefs.cameraName ?: prefs.cameraAddress))
            .setPositiveButton(R.string.btn_forget) { _, _ ->
                AutoConnect.disarm(this)
                prefs.forgetCamera()
                log(getString(R.string.log_camera_forgotten))
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── GpsForegroundService.StatusListener ──────────────────────────────────

    override fun onServiceConnected() {
        phase = Phase.LINKING
        log(getString(R.string.log_gatt_connected))
        render()
    }

    override fun onServiceReady() {
        phase = Phase.ACTIVE
        log(getString(R.string.log_gps_active))
        render()
    }

    override fun onServiceDisconnected() {
        clearPosition()
        phase = Phase.ENDED
        log(getString(R.string.log_disconnected))
        render()
    }

    override fun onServiceLog(msg: String) {
        // The service already wrote the line to DiagnosticLog — UI only
        log(msg, persist = false)
        // The service ends a session without a dedicated callback (stop, reconnects exhausted)
        render()
    }

    override fun onServiceGpsUpdate(lat: Double, lon: Double, accuracy: Float, speedKmh: Int) {
        binding.tvAccuracy.text = getString(R.string.accuracy_fmt, accuracy)
        binding.tvSpeed.text    = getString(R.string.speed_fmt, speedKmh)
        binding.tvCoords.text   = getString(
            R.string.coords_fmt,
            Math.abs(lat), getString(if (lat >= 0) R.string.hemi_n else R.string.hemi_s),
            Math.abs(lon), getString(if (lon >= 0) R.string.hemi_e else R.string.hemi_w),
        )
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun syncUiWithServiceState() {
        val svc = gpsService ?: return
        val name = prefs.cameraName ?: getString(R.string.default_camera_name)
        when {
            svc.isReady     -> { phase = Phase.ACTIVE;     targetName = name }
            svc.isConnected -> { phase = Phase.LINKING;    targetName = name }
            GpsForegroundService.sessionActive -> { phase = Phase.CONNECTING; targetName = name }
        }
        render()
    }

    private fun clearPosition() {
        binding.tvAccuracy.text = "—"
        binding.tvSpeed.text    = "—"
        binding.tvCoords.text   = getString(R.string.position_waiting)
    }

    private fun render() {
        val connected = gpsService?.isConnected ?: false
        val session   = GpsForegroundService.sessionActive
        if (session) sessionSeen = true

        // A session the service gave up on (reconnects exhausted, stopped from the
        // notification) ends without a callback of its own.
        if (phase in listOf(Phase.CONNECTING, Phase.LINKING, Phase.ACTIVE) &&
            sessionSeen && !session && !connected) {
            clearPosition()
            phase = Phase.ENDED
        }

        val address    = prefs.cameraAddress
        val cameraName = prefs.cameraName ?: getString(R.string.default_camera_name)
        val hasCamera  = address != null

        // ── Hero card ──
        when (phase) {
            Phase.IDLE -> hero(
                R.string.status_not_connected,
                if (hasCamera) getString(R.string.hint_camera_saved_fmt, cameraName) else getString(R.string.hint_no_camera),
                R.drawable.ic_bluetooth_disabled, Tone.NEUTRAL, busy = false)
            Phase.SCANNING -> hero(
                R.string.status_scanning, getString(R.string.hint_scanning),
                R.drawable.ic_bluetooth_searching, Tone.BUSY, busy = true)
            Phase.CONNECTING -> hero(
                getString(R.string.status_connecting_fmt, targetName ?: cameraName), getString(R.string.hint_connecting),
                R.drawable.ic_sync, Tone.BUSY, busy = true)
            Phase.LINKING -> hero(
                R.string.status_connected_protocol, getString(R.string.hint_connecting),
                R.drawable.ic_sync, Tone.BUSY, busy = true)
            Phase.ACTIVE -> hero(
                R.string.status_gps_active, getString(R.string.hint_active_fmt, targetName ?: cameraName),
                R.drawable.ic_check_circle, Tone.ACTIVE, busy = false)
            Phase.ENDED -> hero(
                R.string.status_disconnected, getString(R.string.hint_lost),
                R.drawable.ic_bluetooth_disabled, Tone.NEUTRAL, busy = false)
            Phase.SCAN_FAILED -> hero(
                R.string.status_scan_failed, getString(R.string.hint_scan_failed_fmt, scanErrorCode),
                R.drawable.ic_error, Tone.ERROR, busy = false)
        }

        // ── Buttons ──
        val inSession = connected || session || phase == Phase.CONNECTING || phase == Phase.LINKING
        val showStop  = isScanning || inSession
        binding.btnStop.visibility    = if (showStop) View.VISIBLE else View.GONE
        binding.btnPrimary.visibility = if (showStop) View.GONE else View.VISIBLE
        binding.btnStop.text = getString(if (isScanning) R.string.btn_stop_scan else R.string.btn_disconnect)
        binding.btnStop.setIconResource(if (isScanning) R.drawable.ic_bluetooth_disabled else R.drawable.ic_link_off)
        binding.btnPrimary.text = getString(if (hasCamera) R.string.btn_connect else R.string.btn_scan)
        binding.btnPrimary.setIconResource(if (hasCamera) R.drawable.ic_camera else R.drawable.ic_bluetooth_searching)
        binding.btnSecondary.visibility = if (hasCamera && !showStop) View.VISIBLE else View.GONE

        // ── Position ──
        binding.cardPosition.visibility = if (phase == Phase.ACTIVE) View.VISIBLE else View.GONE

        // ── Camera card ──
        if (hasCamera) {
            binding.tvCamera.text = cameraName
            val modes = buildList {
                if (prefs.autoConnect)  add(getString(R.string.mode_auto_connect))
                if (prefs.batterySaver) add(getString(R.string.mode_battery_saver_fmt, (prefs.saverIntervalMs / 1000).toInt()))
                if (prefs.recordTrack)  add(getString(R.string.mode_gpx))
            }
            binding.tvModes.text = if (modes.isEmpty()) getString(R.string.modes_default) else modes.joinToString(" · ")
            binding.btnForget.visibility = View.VISIBLE
            binding.btnForget.isEnabled  = !inSession
        } else {
            binding.tvCamera.text = getString(R.string.camera_none)
            binding.tvModes.text  = getString(R.string.camera_none_hint)
            binding.btnForget.visibility = View.GONE
        }
    }

    private enum class Tone { NEUTRAL, BUSY, ACTIVE, ERROR }

    private fun hero(title: Int, hint: String, @DrawableRes icon: Int, tone: Tone, busy: Boolean) =
        hero(getString(title), hint, icon, tone, busy)

    private fun hero(title: String, hint: String, @DrawableRes icon: Int, tone: Tone, busy: Boolean) {
        binding.tvStatus.text     = title
        binding.tvStatusHint.text = hint
        binding.ivStatus.setImageResource(icon)
        binding.progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        val (bg, fg) = when (tone) {
            Tone.NEUTRAL -> attr(com.google.android.material.R.attr.colorSurfaceVariant) to attr(com.google.android.material.R.attr.colorOnSurfaceVariant)
            Tone.BUSY    -> attr(com.google.android.material.R.attr.colorPrimaryContainer) to attr(com.google.android.material.R.attr.colorOnPrimaryContainer)
            Tone.ACTIVE  -> color(R.color.status_active_container) to color(R.color.status_active)
            Tone.ERROR   -> attr(com.google.android.material.R.attr.colorErrorContainer) to attr(com.google.android.material.R.attr.colorOnErrorContainer)
        }
        binding.statusIconFrame.backgroundTintList = ColorStateList.valueOf(bg)
        binding.ivStatus.imageTintList             = ColorStateList.valueOf(fg)
    }

    private fun attr(@AttrRes id: Int) = MaterialColors.getColor(binding.root, id)
    private fun color(@ColorRes id: Int) = ContextCompat.getColor(this, id)

    private fun toggleActivity() {
        val show = binding.tvLog.visibility != View.VISIBLE
        binding.tvLog.visibility = if (show) View.VISIBLE else View.GONE
        binding.ivActivityChevron.animate().rotation(if (show) 180f else 0f).setDuration(150).start()
    }

    /** Shows [msg] in the on-screen log; [persist] also writes it to the diagnostic log. */
    private fun log(msg: String, persist: Boolean = true) {
        if (persist) DiagnosticLog.log(this, "App", msg)
        val time = sdf.format(Date())
        logBuilder.insert(0, "$time  $msg\n")
        if (logBuilder.length > 6000) logBuilder.setLength(6000)
        binding.tvLog.text = logBuilder.toString().trimEnd()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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
