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
 *
 * ── Robustness ──────────────────────────────────────────────────────────────
 *   GATT callbacks arrive on a binder thread; they are handed to the main thread
 *   so the op-queue is only touched from one thread. A first connection attempt
 *   can stall without any callback (connectGatt never completes, discovery or a
 *   handshake write fails while the bonded link is still being encrypted). Every
 *   such case ends the link via [dropLink] → Listener.onDisconnected(), so the
 *   service retries on its own instead of the user having to cancel and connect
 *   again. A watchdog does the same if the handshake isn't done in time.
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

        /** connect() → onReady() must finish within this, otherwise the link is dropped and retried. */
        private const val SETUP_TIMEOUT_MS = 20_000L

        /**
         * Bonded camera: wait before service discovery so the stack can finish link
         * encryption first. Discovering right away can fail or return an incomplete
         * service list, after which the handshake stalled.
         */
        private const val DISCOVERY_DELAY_BONDED_MS = 600L

        /** disconnect() → STATE_DISCONNECTED normally takes well under a second. */
        private const val DISCONNECT_CONFIRM_MS = 3_000L
    }

    // ── State ────────────────────────────────────────────────────────────────

    private var gatt: BluetoothGatt? = null
    private var timezoneSupport   = false
    private var isLocationEnabled = false
    private var gpsWriteFailCount = 0
    /** True between STATE_CONNECTED and the end of the link. */
    private var linkUp     = false
    /** Set once the link has ended, so onDisconnected() is reported exactly once. */
    private var linkClosed = false

    // Serialised op-queue — BLE only allows one operation at a time.
    // Only touched on the main thread (GATT callbacks are posted there).
    private val opQueue   = ArrayDeque<() -> Unit>()
    private var opPending = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // APO keepalive runnable — rescheduled after every successful write
    private val apoRunnable: Runnable = Runnable { sendApoAvoidance() }

    /** Watchdog for connect + handshake; waits longer while Android shows a pairing request. */
    private val setupTimeout: Runnable = object : Runnable {
        override fun run() {
            if (isLocationEnabled || linkClosed) return
            if (device.bondState == BluetoothDevice.BOND_BONDING) {
                log("Pairing in progress — waiting")
                mainHandler.postDelayed(this, SETUP_TIMEOUT_MS)
                return
            }
            fail(if (linkUp) "Handshake timed out" else "Connection attempt timed out")
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun connect() {
        log("Connecting to ${device.address} (${device.name ?: "?"})")
        mainHandler.postDelayed(setupTimeout, SETUP_TIMEOUT_MS)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) fail("connectGatt() returned null")
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

    /**
     * Ends the link. While a connection attempt is still pending Android reports
     * no disconnect, so the link is dropped right away in that case; otherwise
     * after [DISCONNECT_CONFIRM_MS] if the stack never confirms it.
     */
    fun disconnect() {
        if (!linkUp) { dropLink(); return }
        gatt?.disconnect()
        mainHandler.postDelayed({ dropLink() }, DISCONNECT_CONFIRM_MS)
    }

    fun close() {
        stopKeepalive()
        // Also drops GATT callbacks still queued for this (now dead) connection
        mainHandler.removeCallbacksAndMessages(null)
        gatt?.close()
        gatt = null
        linkUp = false
        opQueue.clear()
        opPending = false
    }

    // ── Link end ─────────────────────────────────────────────────────────────

    /** Handshake or connection failed: end the link so the service reconnects. */
    private fun fail(msg: String) {
        error("$msg — dropping link")
        dropLink()
    }

    /**
     * Closes the GATT client and reports onDisconnected() once. The report is
     * posted so that a close() right after (service shutdown) suppresses it.
     */
    private fun dropLink() {
        if (linkClosed) return
        linkClosed        = true
        linkUp            = false
        isLocationEnabled = false
        stopKeepalive()
        mainHandler.removeCallbacks(setupTimeout)
        opQueue.clear()
        opPending = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        mainHandler.post { listener.onDisconnected() }
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
        if (gatt?.writeCharacteristic(c) != true) { error("Write $prefix not started"); opDone() }
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
        if (gatt?.writeCharacteristic(c) != true) { error("Write $prefix not started"); opDone() }
    }

    /** Handshake read; [onMissing] continues the handshake if the read can't be started. */
    private fun readChar(prefix: String, onMissing: () -> Unit) {
        val svc = gatt?.getService(GPS_SERVICE_UUID)
        val c   = svc?.characteristics?.firstOrNull {
            it.uuid.toString().uppercase().startsWith(prefix)
        }
        if (c == null || gatt?.readCharacteristic(c) != true) {
            log("Read $prefix not possible — skipped")
            opDone(); onMissing(); return
        }
    }

    private fun enableNotify(prefix: String) {
        val svc = gatt?.getService(GPS_SERVICE_UUID)
        val c   = svc?.characteristics?.firstOrNull {
            it.uuid.toString().uppercase().startsWith(prefix)
        }
        if (c == null) { fail("GPS char $prefix not found"); return }
        gatt?.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD_UUID)
        if (d == null) { fail("CCCD not found on $prefix"); return }
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (gatt?.writeDescriptor(d) != true) fail("CCCD write not started")
    }

    // ── Handshake steps ──────────────────────────────────────────────────────

    private fun readTimeCorrection() = enqueue { readChar(CHAR_TIME_CORRECTION) { readAreaAdjustment() } }
    private fun readAreaAdjustment() = enqueue { readChar(CHAR_AREA_ADJUSTMENT) { readCapability() } }
    private fun readCapability()     = enqueue { readChar(CHAR_CAPABILITY) { onHandshakeDone(null) } }

    /** Last handshake step: [cap] is the DD21 value (null if it couldn't be read). */
    private fun onHandshakeDone(cap: ByteArray?) {
        timezoneSupport = cap != null && cap.size >= 5 && (cap[4].toInt() and 0x02) != 0
        log("Timezone support: $timezoneSupport")
        isLocationEnabled = true
        mainHandler.removeCallbacks(setupTimeout)
        // Start keepalive timer now that the session is fully active
        startKeepalive()
        listener.onReady()
    }

    // ── GATT Callback ────────────────────────────────────────────────────────

    /**
     * Runs [block] on the main thread, unless the callback belongs to a GATT
     * client that has been closed or replaced in the meantime.
     */
    private fun onMain(g: BluetoothGatt, block: () -> Unit) {
        mainHandler.post { if (g === gatt && !linkClosed) block() }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = onMain(g) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                linkUp = true
                log("GATT connected — discovering services…")
                listener.onConnected()
                val bonded = device.bondState == BluetoothDevice.BOND_BONDED
                mainHandler.postDelayed({
                    if (gatt === g && !linkClosed && !g.discoverServices()) fail("Service discovery not started")
                }, if (bonded) DISCOVERY_DELAY_BONDED_MS else 0L)
            } else {
                // Includes a failed connect (e.g. status 133) — the service retries
                log("GATT disconnected (status=$status, state=$newState)")
                dropLink()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = onMain(g) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed: $status"); return@onMain
            }
            if (g.getService(GPS_SERVICE_UUID) == null) {
                fail("Sony GPS service not found — unsupported camera?"); return@onMain
            }
            log("Services discovered. Starting GPS protocol…")
            enqueue { enableNotify(CHAR_NOTIFY_DISABLE) }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) = onMain(g) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Typically insufficient authentication while the link is still being encrypted
                fail("Descriptor write failed: $status"); return@onMain
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
            val value  = characteristic.value?.copyOf()
            onMain(g) { handleWrite(prefix, value, status) }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val prefix = characteristic.uuid.toString().uppercase().take(8)
            val value  = characteristic.value?.copyOf()
            onMain(g) { handleRead(prefix, value, status) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val prefix = characteristic.uuid.toString().uppercase().take(8)
            val value  = characteristic.value?.copyOf()
            onMain(g) {
                // LOCATION_TRANSFER_DISABLE = {3, 1, 2, 0}
                if (prefix.startsWith(CHAR_NOTIFY_DISABLE) &&
                    value?.contentEquals(byteArrayOf(3, 1, 2, 0)) == true) {
                    log("Camera remotely disabled location transfer")
                    dropLink()
                }
            }
        }
    }

    private fun handleWrite(prefix: String, value: ByteArray?, status: Int) {
        log("Write [$prefix] status=$status")

        when {
            // ── CC02 APO keepalive ack ──────────────────────────────────────
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

            // ── DD30 Lock acquired ──────────────────────────────────────────
            prefix.startsWith(CHAR_LOCK) && value.contentEquals(LOCK_ON) -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Failed to acquire lock ($status)"); return
                }
                opDone()
                enqueue { writeChar(CHAR_LOCATION_ENABLE, LOC_ON) }
            }

            // ── DD31 Location enabled ───────────────────────────────────────
            prefix.startsWith(CHAR_LOCATION_ENABLE) && value.contentEquals(LOC_ON) -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Failed to enable location ($status)"); return
                }
                opDone()
                readTimeCorrection()
            }

            // ── DD31 Location disabled (stop path) ──────────────────────────
            prefix.startsWith(CHAR_LOCATION_ENABLE) && value.contentEquals(LOC_OFF) -> {
                opDone()
                enqueue { writeChar(CHAR_LOCK, LOCK_OFF) }
            }

            // ── DD30 Lock released (stop path) ──────────────────────────────
            prefix.startsWith(CHAR_LOCK) && value.contentEquals(LOCK_OFF) -> {
                log("Lock released — transfer fully stopped")
                opDone()
            }

            // ── DD11 GPS data ────────────────────────────────────────────────
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

    /** DD32/DD33 are informational and DD21 has a safe default, so a failed read doesn't stop the handshake. */
    private fun handleRead(prefix: String, value: ByteArray?, status: Int) {
        log("Read  [$prefix] status=$status")
        val ok = status == BluetoothGatt.GATT_SUCCESS
        if (!ok) log("Read failed: $prefix ($status) — continuing")
        opDone()

        when {
            prefix.startsWith(CHAR_TIME_CORRECTION) -> readAreaAdjustment()
            prefix.startsWith(CHAR_AREA_ADJUSTMENT) -> readCapability()
            prefix.startsWith(CHAR_CAPABILITY)      -> onHandshakeDone(if (ok) value else null)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Always called on the main thread. */
    private fun error(msg: String) {
        Log.e(TAG, msg)
        listener.onError(msg)
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        listener.onLog(msg)
    }
}
