package com.anri.sonygps

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
 * Reverse-engineered from Sony Creators App v3.3.1, re-checked against v3.5.0
 *
 * ── Sony BLE UUIDs ──────────────────────────────────────────────────────────
 *
 * Service (camera control / APO avoidance):
 *   8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF
 *   Characteristic 0000CC02  WRITE  — generic camera control commands
 *   Characteristic 0000CC0F  READ+NOTIFY — memory card (see CameraMedia)
 *   Characteristic 0000CC10  READ+NOTIFY — camera battery (see CameraBattery)
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
 *   2. requestMtu(158)       (so the 91/95-byte payload fits one write)
 *   3. discoverServices()
 *   4. Enable notifications on DD01
 *   5. Write {0x01} → DD30   (acquire lock)
 *   6. Write {0x01} → DD31   (enable location)
 *   7. Read  DD32, DD33      (store settings)
 *   8. Read  DD21            (detect timezone support)
 *   9. Listener.onReady()    → start GPS loop + APO keepalive timer
 *  10. Every 9 s             → write APO-avoidance command to CC02
 *  11. sendLocation()        → build 91/95-byte packet → Write → DD11
 *  12. stopAndDisconnect()   → cancel keepalive, Write {0x00} → DD31 → DD30,
 *                              then disconnect (after 3 s at the latest)
 *
 * ── Threading ───────────────────────────────────────────────────────────────
 *   The public API must be called on the main thread. GATT callbacks arrive on
 *   a binder thread and are re-posted to the main thread, so the op-queue and
 *   all state are only ever touched there.
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
        fun onBattery(battery: CameraBattery)
        fun onMedia(media: CameraMedia)
        fun onError(msg: String)
        fun onLog(msg: String)
    }

    // ── Constants ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "SonyCameraGatt"

        // Camera control service (APO avoidance)
        private val CTRL_SERVICE_UUID: UUID = UUID.fromString("8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF")
        private const val CHAR_CAMERA_CTRL    = "0000CC02"
        private const val CHAR_MEDIA          = "0000CC0F"
        private const val CHAR_BATTERY        = "0000CC10"
        /** Optional status characteristics: read once, then kept current by notifications. */
        private val CAMERA_INFO_CHARS = listOf(CHAR_BATTERY, CHAR_MEDIA)

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

        /** Source: BluetoothGattAgent.requestMtu() — requestMtu(158) */
        private const val MTU = 158

        /**
         * Camera-specific GATT status codes on the DD30 lock write.
         * Source: TransferringLocationInfoLockingState.onGattCharacteristicWrite()
         */
        private const val STATUS_LOCK_UNAVAILABLE    = 157
        private const val STATUS_LOCK_ALREADY_LOCKED = 159

        /** MTU exchange + service discovery; Sony allows 30 s per command. */
        private const val SETUP_TIMEOUT_MS = 30_000L
        /** A single read/write the camera never answers. */
        private const val OP_TIMEOUT_MS = 10_000L
        /** Upper bound for the stop sequence before the link is dropped anyway. */
        private const val STOP_TIMEOUT_MS = 3_000L
        /** Android normally confirms disconnect() within a second or two. */
        private const val DISCONNECT_TIMEOUT_MS = 5_000L
    }

    // ── State ────────────────────────────────────────────────────────────────

    private var gatt: BluetoothGatt? = null
    private var timezoneSupport   = false
    private var isLocationEnabled = false
    private var gpsWriteFailCount = 0
    private var awaitingMtu       = false
    private var disconnectAfterStop = false
    private var linkUp            = false

    /**
     * One queued GATT operation. [start] returns false when Android refuses to
     * start it — then no callback will ever arrive. [onStartFailed] == null
     * marks a handshake step whose failure aborts the connection.
     */
    private class Op(
        val label: String,
        val onStartFailed: (() -> Unit)? = null,
        val start: () -> Boolean
    )

    // Serialised op-queue — BLE only allows one operation at a time
    private val opQueue   = ArrayDeque<Op>()
    private var opPending = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // APO keepalive runnable — rescheduled after every successful write
    private val apoRunnable: Runnable = Runnable { sendApoAvoidance() }

    // Fires when the camera leaves a setup step or a queued op unanswered
    private val watchdog: Runnable = Runnable { fail("No response from camera — disconnecting") }

    // Fires when the camera does not answer the stop sequence in time
    private val stopTimeout: Runnable = Runnable {
        log("Stop sequence not confirmed — disconnecting anyway")
        requestDisconnect()
    }

    // Fires when Android never confirms a disconnect we asked for
    private val disconnectTimeout: Runnable = Runnable {
        log("Disconnect not confirmed — giving up on this link")
        resetSession()
        listener.onDisconnected()
    }

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
        enqueue(Op("GPS write", onStartFailed = ::onGpsWriteFailed) {
            writeChar(GPS_SERVICE_UUID, CHAR_GPS_DATA, payload)
        })
    }

    fun stopTransfer() {
        if (!isLocationEnabled) return
        log("Stopping GPS transfer")
        isLocationEnabled = false
        stopKeepalive()
        enqueue(Op("Location disable", onStartFailed = ::onStopFinished) {
            writeChar(GPS_SERVICE_UUID, CHAR_LOCATION_ENABLE, LOC_OFF)
        })
    }

    /**
     * Ends the session cleanly: location off (DD31), lock released (DD30), and
     * only then the BLE disconnect — so the camera is not left holding the lock.
     */
    fun stopAndDisconnect() {
        if (!isLocationEnabled) { disconnect(); return }
        disconnectAfterStop = true
        mainHandler.postDelayed(stopTimeout, STOP_TIMEOUT_MS)
        stopTransfer()
    }

    fun disconnect() {
        val g = gatt ?: return
        if (linkUp) { requestDisconnect(); return }
        // Still connecting: Android cancels the attempt without any callback
        resetSession()
        g.disconnect()
        onMain(g) { listener.onDisconnected() }
    }

    fun close() {
        resetSession()
        linkUp = false
        gatt?.close()
        gatt = null
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

    private fun rescheduleKeepalive() {
        if (!isLocationEnabled) return
        mainHandler.removeCallbacks(apoRunnable)
        mainHandler.postDelayed(apoRunnable, APO_INTERVAL_MS)
    }

    /**
     * Enqueues one APO avoidance write, then reschedules itself.
     * Written to the camera control characteristic 0000CC02 in service CC00.
     */
    private fun sendApoAvoidance() {
        if (!isLocationEnabled) return
        log("APO keepalive →")
        // Next keepalive scheduled in onCharacteristicWrite after CC02 ack
        enqueue(Op("APO keepalive", onStartFailed = {
            log("APO write could not be started — will retry next interval")
            rescheduleKeepalive()
        }) { writeChar(CTRL_SERVICE_UUID, CHAR_CAMERA_CTRL, APO_CMD) })
    }

    // ── Op-Queue ─────────────────────────────────────────────────────────────

    private fun enqueue(op: Op) {
        opQueue.addLast(op)
        if (!opPending) drain()
    }

    private fun drain() {
        val op = opQueue.removeFirstOrNull() ?: run { opPending = false; return }
        opPending = true
        armWatchdog(OP_TIMEOUT_MS)
        if (op.start()) return

        // Android refused the operation, so no callback will complete it
        val onStartFailed = op.onStartFailed
        if (onStartFailed == null) {
            fail("${op.label} could not be started")
        } else {
            onStartFailed()
            opDone()
        }
    }

    private fun opDone() {
        mainHandler.removeCallbacks(watchdog)
        opPending = false
        drain()
    }

    private fun armWatchdog(timeoutMs: Long) {
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, timeoutMs)
    }

    /** Drops everything that belongs to the current link; the GATT object itself stays. */
    private fun resetSession() {
        mainHandler.removeCallbacks(watchdog)
        mainHandler.removeCallbacks(disconnectTimeout)
        mainHandler.removeCallbacks(stopTimeout)
        disconnectAfterStop = false
        stopKeepalive()
        isLocationEnabled = false
        awaitingMtu = false
        opQueue.clear()
        opPending = false
    }

    /**
     * Reports [msg] and tears the link down. The listener gets onDisconnected()
     * once Android confirms, which is where the service decides about a retry.
     */
    private fun fail(msg: String) {
        error(msg)
        requestDisconnect()
    }

    private fun requestDisconnect() {
        val g = gatt ?: return
        resetSession()
        mainHandler.postDelayed(disconnectTimeout, DISCONNECT_TIMEOUT_MS)
        g.disconnect()
    }

    /** End of the stop sequence, whether the lock release went through or not. */
    private fun onStopFinished() {
        if (disconnectAfterStop) requestDisconnect()
    }

    private fun onGpsWriteFailed() {
        gpsWriteFailCount++
        log("GPS write failed (fail #$gpsWriteFailCount)")
        if (gpsWriteFailCount >= MAX_GPS_FAIL_COUNT) {
            error("GPS write failed $MAX_GPS_FAIL_COUNT× — aborting")
            stopTransfer()
        }
    }

    // ── GATT operations ──────────────────────────────────────────────────────
    // Each returns false when the operation could not be started.

    private fun findChar(serviceUuid: UUID, prefix: String): BluetoothGattCharacteristic? =
        gatt?.getService(serviceUuid)?.characteristics?.firstOrNull {
            it.uuid.toString().uppercase().startsWith(prefix)
        }

    private fun writeChar(serviceUuid: UUID, prefix: String, value: ByteArray): Boolean {
        val c = findChar(serviceUuid, prefix)
        if (c == null) { log("Characteristic $prefix not found"); return false }
        c.value     = value
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return gatt?.writeCharacteristic(c) == true
    }

    private fun readChar(serviceUuid: UUID, prefix: String): Boolean {
        val c = findChar(serviceUuid, prefix)
        if (c == null) { log("Characteristic $prefix not found"); return false }
        return gatt?.readCharacteristic(c) == true
    }

    private fun enableNotify(serviceUuid: UUID, prefix: String): Boolean {
        val c = findChar(serviceUuid, prefix)
        if (c == null) { log("Characteristic $prefix not found"); return false }
        gatt?.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD_UUID)
        if (d == null) { log("CCCD not found on $prefix"); return false }
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return gatt?.writeDescriptor(d) == true
    }

    // ── GATT Callback ────────────────────────────────────────────────────────

    /**
     * Runs [block] on the main thread, unless the callback belongs to a GATT
     * object that has been closed or replaced in the meantime.
     */
    private fun onMain(g: BluetoothGatt, block: () -> Unit) {
        mainHandler.post { if (g === gatt) block() }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = onMain(g) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    linkUp = true
                    log("GATT connected — requesting MTU $MTU…")
                    listener.onConnected()
                    armWatchdog(SETUP_TIMEOUT_MS)
                    awaitingMtu = true
                    if (!g.requestMtu(MTU)) {
                        awaitingMtu = false
                        log("MTU request not started — continuing with default MTU")
                        startDiscovery(g)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("GATT disconnected (status=$status)")
                    linkUp = false
                    resetSession()
                    listener.onDisconnected()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = onMain(g) {
            // The camera may renegotiate later; only the answer to our request continues setup
            if (!awaitingMtu) return@onMain
            awaitingMtu = false
            log("MTU $mtu (status=$status) — discovering services…")
            startDiscovery(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = onMain(g) {
            mainHandler.removeCallbacks(watchdog)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed: $status"); return@onMain
            }
            if (g.getService(GPS_SERVICE_UUID) == null) {
                fail("Sony GPS service not found — unsupported camera?"); return@onMain
            }
            log("Services discovered. Starting GPS protocol…")
            enqueue(Op("Notification subscribe") { enableNotify(GPS_SERVICE_UUID, CHAR_NOTIFY_DISABLE) })
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            val prefix = descriptor.characteristic.uuid.toString().uppercase().take(8)
            onMain(g) { handleDescriptorWrite(prefix, status) }
        }

        private fun handleDescriptorWrite(prefix: String, status: Int) {
            if (isCameraInfo(prefix)) {
                // Optional extra — never a reason to drop the link
                if (status != BluetoothGatt.GATT_SUCCESS) log("Notifications for $prefix not available ($status)")
                opDone()
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Descriptor write failed: $status"); return
            }
            log("CCCD subscribed")
            opDone()
            enqueue(Op("Lock acquire") { writeChar(GPS_SERVICE_UUID, CHAR_LOCK, LOCK_ON) })
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val prefix = characteristic.uuid.toString().uppercase().take(8)
            // Copied here: the characteristic object is reused by the next write
            val value  = characteristic.value?.clone()
            onMain(g) { handleWrite(prefix, value, status) }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val prefix = characteristic.uuid.toString().uppercase().take(8)
            val value  = characteristic.value?.clone()
            onMain(g) { handleRead(prefix, value, status) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val prefix = characteristic.uuid.toString().uppercase().take(8)
            val value  = characteristic.value?.clone()
            onMain(g) {
                if (isCameraInfo(prefix)) { handleCameraInfo(prefix, value); return@onMain }
                // LOCATION_TRANSFER_DISABLE = {3, 1, 2, 0}
                if (prefix.startsWith(CHAR_NOTIFY_DISABLE) &&
                    value?.contentEquals(byteArrayOf(3, 1, 2, 0)) == true
                ) {
                    log("Camera remotely disabled location transfer")
                    isLocationEnabled = false
                    stopKeepalive()
                    listener.onDisconnected()
                }
            }
        }
    }

    private fun startDiscovery(g: BluetoothGatt) {
        if (!g.discoverServices()) fail("Service discovery could not be started")
    }

    private fun handleWrite(prefix: String, value: ByteArray?, status: Int) {
        val ok = status == BluetoothGatt.GATT_SUCCESS
        log("Write [$prefix] status=$status")

        when {
            // ── CC02 APO keepalive ack ──────────────────────────────────
            prefix.startsWith(CHAR_CAMERA_CTRL) -> {
                if (!ok) log("APO write failed (status=$status) — will retry next interval")
                opDone()
                rescheduleKeepalive()
            }

            // ── DD30 Lock acquired ──────────────────────────────────────
            prefix.startsWith(CHAR_LOCK) && value.contentEquals(LOCK_ON) -> {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        opDone()
                        enqueue(Op("Location enable") {
                            writeChar(GPS_SERVICE_UUID, CHAR_LOCATION_ENABLE, LOC_ON)
                        })
                    }
                    STATUS_LOCK_UNAVAILABLE ->
                        fail("Camera refused the location lock (status $status) — " +
                            "is location linking enabled in the camera menu?")
                    STATUS_LOCK_ALREADY_LOCKED ->
                        fail("Location lock is held by another device (status $status) — " +
                            "close the Sony Creators' App or the other phone")
                    else -> fail("Failed to acquire lock ($status)")
                }
            }

            // ── DD31 Location enabled ───────────────────────────────────
            prefix.startsWith(CHAR_LOCATION_ENABLE) && value.contentEquals(LOC_ON) -> {
                if (!ok) { fail("Failed to enable location ($status)"); return }
                opDone()
                enqueue(Op("Time-correction read") { readChar(GPS_SERVICE_UUID, CHAR_TIME_CORRECTION) })
            }

            // ── DD31 Location disabled (stop path) ──────────────────────
            prefix.startsWith(CHAR_LOCATION_ENABLE) && value.contentEquals(LOC_OFF) -> {
                opDone()
                enqueue(Op("Lock release", onStartFailed = ::onStopFinished) {
                    writeChar(GPS_SERVICE_UUID, CHAR_LOCK, LOCK_OFF)
                })
            }

            // ── DD30 Lock released (stop path) ──────────────────────────
            prefix.startsWith(CHAR_LOCK) && value.contentEquals(LOCK_OFF) -> {
                log("Lock released — transfer fully stopped")
                opDone()
                onStopFinished()
            }

            // ── DD11 GPS data ────────────────────────────────────────────
            prefix.startsWith(CHAR_GPS_DATA) -> {
                if (ok) gpsWriteFailCount = 0 else onGpsWriteFailed()
                opDone()
            }

            else -> opDone()
        }
    }

    private fun isCameraInfo(prefix: String) = CAMERA_INFO_CHARS.any { prefix.startsWith(it) }

    /**
     * Subscribes to battery and memory card state and reads each once. Purely
     * optional: characteristics a camera lacks are skipped and a failure never
     * ends the session.
     */
    private fun requestCameraInfo() {
        for (char in CAMERA_INFO_CHARS) {
            if (findChar(CTRL_SERVICE_UUID, char) == null) {
                log("Camera does not offer $char"); continue
            }
            enqueue(Op("$char subscribe", onStartFailed = {}) { enableNotify(CTRL_SERVICE_UUID, char) })
            enqueue(Op("$char read", onStartFailed = {}) { readChar(CTRL_SERVICE_UUID, char) })
        }
    }

    private fun handleCameraInfo(prefix: String, value: ByteArray?) {
        if (value == null) return
        val hex = value.joinToString(" ") { "%02X".format(it) }
        if (prefix.startsWith(CHAR_BATTERY)) {
            val battery = CameraBattery.parse(value)
            log("Battery [$hex] → " + (battery ?: "not readable"))
            if (battery != null) listener.onBattery(battery)
        } else {
            val media = CameraMedia.parse(value)
            log("Media [$hex] → " + (media ?: "not readable"))
            if (media != null) listener.onMedia(media)
        }
    }

    private fun handleRead(prefix: String, value: ByteArray?, status: Int) {
        log("Read  [$prefix] status=$status")

        if (isCameraInfo(prefix)) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleCameraInfo(prefix, value)
            opDone()
            return
        }

        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("Read failed: $prefix ($status)"); return
        }

        when {
            prefix.startsWith(CHAR_TIME_CORRECTION) -> {
                opDone(); enqueue(Op("Area-adjustment read") { readChar(GPS_SERVICE_UUID, CHAR_AREA_ADJUSTMENT) })
            }
            prefix.startsWith(CHAR_AREA_ADJUSTMENT) -> {
                opDone(); enqueue(Op("Capability read") { readChar(GPS_SERVICE_UUID, CHAR_CAPABILITY) })
            }
            prefix.startsWith(CHAR_CAPABILITY) -> {
                timezoneSupport = value != null && value.size >= 5 && (value[4].toInt() and 0x02) != 0
                log("Timezone support: $timezoneSupport")
                isLocationEnabled = true
                gpsWriteFailCount = 0
                opDone()
                // Start keepalive timer now that the session is fully active
                startKeepalive()
                listener.onReady()
                requestCameraInfo()
            }
            else -> opDone()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    // Only called on the main thread, so the listener is invoked directly.

    private fun error(msg: String) {
        Log.e(TAG, msg)
        listener.onError(msg)
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        listener.onLog(msg)
    }
}
