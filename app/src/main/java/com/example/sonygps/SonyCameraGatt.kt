package com.example.sonygps

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Handles all BLE GATT communication with a Sony camera for GPS transfer.
 *
 * Reverse-engineered from Sony Creators App v3.3.1
 *
 * ── Sony BLE UUIDs ──────────────────────────────────────────────────────────
 *
 * Service (camera control / APO avoidance):
 *   8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF
 *   Characteristic 0000CC02  WRITE  — generic camera control commands
 *
 * Service (GPS / Location data):
 *   8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF
 *   Characteristics — matched by UUID-string prefix:
 *   0000DD01  NOTIFY — camera → "location transfer disabled" signal
 *   0000DD11  WRITE  — GPS payload (91 or 95 bytes, see SonyGpsPacket)
 *   0000DD21  READ   — capability flags; byte[4] & 0x02 → timezone support
 *   0000DD30  WRITE  — lock:            {0x01} = acquire, {0x00} = release
 *   0000DD31  WRITE  — location enable: {0x01} = on,      {0x00} = off
 *   0000DD32  READ   — time-correction setting (informational)
 *   0000DD33  READ   — area-adjustment setting (informational)
 *
 * ── APO (Auto Power Off) avoidance ─────────────────────────────────────────
 *   The camera enters sleep/disconnects BLE after ~30 s of inactivity.
 *   To prevent this the original Sony app writes {3, 8, 16, 0} to 0000CC02
 *   every 9 seconds (confirmed from ExecutingApoAvoidanceState.kt).
 *
 * ── Protocol flow ───────────────────────────────────────────────────────────
 *   1. connectGatt()
 *   2. discoverServices()
 *   3. Enable notifications on DD01
 *   4. Write {0x01} → DD30   (acquire lock)
 *   5. Write {0x01} → DD31   (enable location)
 *   6. Read  DD32, DD33      (store settings)
 *   7. Read  DD21            (detect timezone support)
 *   8. Listener.onReady()    → start GPS loop + APO keepalive timer
 *   9. Every 9 s             → write APO-avoidance command to CC02
 *  10. sendLocation()        → build 91/95-byte packet → Write → DD11
 *  11. stopTransfer()        → cancel keepalive, Write {0x00} → DD31 → DD30
 */
@SuppressLint("MissingPermission")
class SonyCameraGatt(
    private val context: Context,
    private val device: BluetoothDevice,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected()
        fun onReady()
        fun onDisconnected()
        fun onError(msg: String)
        fun onLog(msg: String)
    }

    // ── Constants ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "SonyCameraGatt"

        // Camera control service (APO avoidance)
        private val CTRL_SERVICE_UUID: UUID = UUID.fromString("8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF")
        private const val CHAR_CAMERA_CTRL    = "0000CC02"

        // GPS service
        val GPS_SERVICE_UUID: UUID = UUID.fromString("8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF")
        private const val CHAR_NOTIFY_DISABLE  = "0000DD01"
        private const val CHAR_GPS_DATA        = "0000DD11"
        private const val CHAR_CAPABILITY      = "0000DD21"
        private const val CHAR_LOCK            = "0000DD30"
        private const val CHAR_LOCATION_ENABLE = "0000DD31"
        private const val CHAR_TIME_CORRECTION = "0000DD32"
        private const val CHAR_AREA_ADJUSTMENT = "0000DD33"

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val LOCK_ON  = byteArrayOf(0x01)
        private val LOCK_OFF = byteArrayOf(0x00)
        private val LOC_ON   = byteArrayOf(0x01)
        private val LOC_OFF  = byteArrayOf(0x00)

        /**
         * APO avoidance command — keeps the camera awake.
         * Written to CC02 every APO_INTERVAL_MS milliseconds.
         * Source: BluetoothGattUtil.APO_AVOIDANCE_COMMAND_CHARACTERISTIC
         */
        private val APO_CMD = byteArrayOf(3, 8, 16, 0)

        /**
         * Keepalive interval: 9 000 ms.
         * Source: ExecutingApoAvoidanceState — startCommandTimeout(..., 9000L)
         * Camera sleeps after ~30 s, so 9 s gives comfortable margin.
         */
        private const val APO_INTERVAL_MS = 9_000L

        private const val MAX_LOCATION_AGE_SEC = 10L
        private const val MAX_GPS_FAIL_COUNT    = 3
    }

    // ── State ────────────────────────────────────────────────────────────────

    private var gatt: BluetoothGatt? = null
    private var timezoneSupport   = false
    private var isLocationEnabled = false
    private var gpsWriteFailCount = 0

    // Serialised op-queue — BLE only allows one operation at a time
    private val opQueue   = ArrayDeque<() -> Unit>()
    private var opPending = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // APO keepalive runnable — rescheduled after every successful write
    private val apoRunnable: Runnable = Runnable { sendApoAvoidance() }

    // ── Public API ───────────────────────────────────────────────────────────

    fun connect() {
        log("Connecting to ${device.address} (${device.name ?: "?"})")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun sendLocation(location: Location) {
        if (!isLocationEnabled) return
        val ageSec = TimeUnit.NANOSECONDS.toSeconds(
            SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        )
        if (ageSec >= MAX_LOCATION_AGE_SEC) {
            log("GPS fix too old (${ageSec}s) — skipped"); return
        }
        val payload = SonyGpsPacket.build(location, timezoneSupport)
        log("GPS → %.6f, %.6f  tz=$timezoneSupport  (${payload.size}B)".format(
            location.latitude, location.longitude
        ))
        enqueue { writeChar(CHAR_GPS_DATA, payload) }
    }

    fun stopTransfer() {
        if (!isLocationEnabled) return
        log("Stopping GPS transfer")
        isLocationEnabled = false
        stopKeepalive()
        enqueue { writeChar(CHAR_LOCATION_ENABLE, LOC_OFF) }
    }

    fun disconnect() { gatt?.disconnect() }

    fun close() {
        stopKeepalive()
        gatt?.close()
        gatt = null
        opQueue.clear()
        opPending = false
    }

    // ── APO keepalive ────────────────────────────────────────────────────────

    private fun startKeepalive() {
        mainHandler.removeCallbacks(apoRunnable)
        mainHandler.postDelayed(apoRunnable, APO_INTERVAL_MS)
        log("APO keepalive started (interval ${APO_INTERVAL_MS / 1000}s)")
    }

    private fun stopKeepalive() {
        mainHandler.removeCallbacks(apoRunnable)
    }

    /**
     * Enqueues one APO avoidance write, then reschedules itself.
     * Written to the camera control characteristic 0000CC02 in service CC00.
     */
    private fun sendApoAvoidance() {
        if (!isLocationEnabled) return
        log("APO keepalive →")
        enqueue { writeCtrlChar(CHAR_CAMERA_CTRL, APO_CMD) }
        // Next keepalive scheduled in onCharacteristicWrite after CC02 ack
    }

    // ── Op-Queue ─────────────────────────────────────────────────────────────

    private fun enqueue(op: () -> Unit) {
        opQueue.addLast(op)
        if (!opPending) drain()
    }

    private fun drain() {
        if (opQueue.isEmpty()) { opPending = false; return }
        opPending = true
        opQueue.removeFirst().invoke()
    }

    private fun opDone() {
        opPending = false
        drain()
    }

    // ── GATT operations ──────────────────────────────────────────────────────

    /** Write to a characteristic in the GPS service (DD00). */
    private fun writeChar(prefix: String, value: ByteArray) {
        val svc = gatt?.getService(GPS_SERVICE_UUID)
        val c   = svc?.characteristics?.firstOrNull {
            it.uuid.toString().uppercase().startsWith(prefix)
        }
        if (c == null) { error("GPS char $prefix not found"); opDone(); return }
        c.value     = value
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt?.writeCharacteristic(c) ?: run { error("GATT null"); opDone() }
    }

    /** Write to a characteristic in the control service (CC00). */
    private fun writeCtrlChar(prefix: String, value: ByteArray) {
        val svc = gatt?.getService(CTRL_SERVICE_UUID)
        val c   = svc?.characteristics?.firstOrNull {
            it.uuid.toString().uppercase().startsWith(prefix)
        }
        if (c == null) { error("Ctrl char $prefix not found"); opDone(); return }
        c.value     = value
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt?.writeCharacteristic(c) ?: run { error("GATT null"); opDone() }
    }

    private fun readChar(prefix: String) {
        val svc = gatt?.getService(GPS_SERVICE_UUID)
        val c   = svc?.characteristics?.firstOrNull {
            it.uuid.toString().uppercase().startsWith(prefix)
        }
        if (c == null) { error("GPS char $prefix not found"); opDone(); return }
        gatt?.readCharacteristic(c) ?: run { error("GATT null"); opDone() }
    }

    private fun enableNotify(prefix: String) {
        val svc = gatt?.getService(GPS_SERVICE_UUID)
        val c   = svc?.characteristics?.firstOrNull {
            it.uuid.toString().uppercase().startsWith(prefix)
        }
        if (c == null) { error("GPS char $prefix not found"); opDone(); return }
        gatt?.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD_UUID)
        if (d == null) { error("CCCD not found on $prefix"); opDone(); return }
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt?.writeDescriptor(d) ?: run { error("GATT null"); opDone() }
    }

    // ── GATT Callback ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("GATT connected — discovering services…")
                    mainHandler.post { listener.onConnected() }
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("GATT disconnected (status=$status)")
                    isLocationEnabled = false
                    stopKeepalive()
                    opQueue.clear()
                    opPending = false
                    mainHandler.post { listener.onDisconnected() }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                error("Service discovery failed: $status"); return
            }
            if (g.getService(GPS_SERVICE_UUID) == null) {
                error("Sony GPS service not found — unsupported camera?"); return
            }
            log("Services discovered. Starting GPS protocol…")
            enqueue { enableNotify(CHAR_NOTIFY_DISABLE) }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                error("Descriptor write failed: $status"); opDone(); return
            }
            log("CCCD subscribed")
            opDone()
            enqueue { writeChar(CHAR_LOCK, LOCK_ON) }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val prefix = characteristic.uuid.toString().uppercase().take(8)
            val value  = characteristic.value
            log("Write [$prefix] status=$status")

            when {
                // ── CC02 APO keepalive ack ──────────────────────────────────
                prefix.startsWith(CHAR_CAMERA_CTRL) -> {
                    if (status != BluetoothGatt.GATT_SUCCESS)
                        log("APO write failed (status=$status) — will retry next interval")
                    opDone()
                    // Reschedule next keepalive
                    if (isLocationEnabled) {
                        mainHandler.removeCallbacks(apoRunnable)
                        mainHandler.postDelayed(apoRunnable, APO_INTERVAL_MS)
                    }
                }

                // ── DD30 Lock acquired ──────────────────────────────────────
                prefix.startsWith(CHAR_LOCK) && value.contentEquals(LOCK_ON) -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        error("Failed to acquire lock ($status)"); opDone(); return
                    }
                    opDone()
                    enqueue { writeChar(CHAR_LOCATION_ENABLE, LOC_ON) }
                }

                // ── DD31 Location enabled ───────────────────────────────────
                prefix.startsWith(CHAR_LOCATION_ENABLE) && value.contentEquals(LOC_ON) -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        error("Failed to enable location ($status)"); opDone(); return
                    }
                    opDone()
                    enqueue { readChar(CHAR_TIME_CORRECTION) }
                }

                // ── DD31 Location disabled (stop path) ──────────────────────
                prefix.startsWith(CHAR_LOCATION_ENABLE) && value.contentEquals(LOC_OFF) -> {
                    opDone()
                    enqueue { writeChar(CHAR_LOCK, LOCK_OFF) }
                }

                // ── DD30 Lock released (stop path) ──────────────────────────
                prefix.startsWith(CHAR_LOCK) && value.contentEquals(LOCK_OFF) -> {
                    log("Lock released — transfer fully stopped")
                    opDone()
                }

                // ── DD11 GPS data ────────────────────────────────────────────
                prefix.startsWith(CHAR_GPS_DATA) -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        gpsWriteFailCount = 0
                    } else {
                        gpsWriteFailCount++
                        log("GPS write failed (fail #$gpsWriteFailCount)")
                        if (gpsWriteFailCount >= MAX_GPS_FAIL_COUNT) {
                            error("GPS write failed $MAX_GPS_FAIL_COUNT× — aborting")
                            opDone(); stopTransfer(); return
                        }
                    }
                    opDone()
                }

                else -> opDone()
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val prefix = characteristic.uuid.toString().uppercase().take(8)
            log("Read  [$prefix] status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                error("Read failed: $prefix ($status)"); opDone(); return
            }

            when {
                prefix.startsWith(CHAR_TIME_CORRECTION) -> {
                    opDone(); enqueue { readChar(CHAR_AREA_ADJUSTMENT) }
                }
                prefix.startsWith(CHAR_AREA_ADJUSTMENT) -> {
                    opDone(); enqueue { readChar(CHAR_CAPABILITY) }
                }
                prefix.startsWith(CHAR_CAPABILITY) -> {
                    val cap = characteristic.value
                    timezoneSupport = cap != null && cap.size >= 5 && (cap[4].toInt() and 0x02) != 0
                    log("Timezone support: $timezoneSupport")
                    isLocationEnabled = true
                    opDone()
                    // Start keepalive timer now that the session is fully active
                    startKeepalive()
                    mainHandler.post { listener.onReady() }
                }
                else -> opDone()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val prefix = characteristic.uuid.toString().uppercase().take(8)
            if (prefix.startsWith(CHAR_NOTIFY_DISABLE)) {
                // LOCATION_TRANSFER_DISABLE = {3, 1, 2, 0}
                if (characteristic.value?.contentEquals(byteArrayOf(3, 1, 2, 0)) == true) {
                    log("Camera remotely disabled location transfer")
                    isLocationEnabled = false
                    stopKeepalive()
                    mainHandler.post { listener.onDisconnected() }
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun error(msg: String) {
        Log.e(TAG, msg)
        mainHandler.post { listener.onError(msg) }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        mainHandler.post { listener.onLog(msg) }
    }
}
