package com.example.sonygps

import android.location.Location
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Builds the Sony-proprietary GPS BLE payload.
 *
 * Reverse-engineered from Sony Creators App v3.3.1
 * Source: BluetoothLeUtil.setLocationAndTime() + TransferringLocationInfoWithLockState.onLocationUpdated()
 *
 * Format A (91 bytes) — camera without timezone support (EnumCameraInfo.None)
 * Format B (95 bytes) — camera with timezone support  (EnumCameraInfo.Timezone)
 *
 * Byte layout:
 *  [0]      = 0x00
 *  [1]      = 89 (Format A) / 93 (Format B)
 *  [2]      = 0x08
 *  [3]      = 0x02
 *  [4]      = 0xFC (-4)
 *  [5]      = 0x00 (Format A) / 0x03 (Format B)
 *  [6]      = 0x00
 *  [7]      = 0x00
 *  [8]      = 0x10
 *  [9]      = 0x10
 *  [10]     = 0x10
 *  [11-14]  = latitude  * 1e7 as big-endian int32
 *  [15-18]  = longitude * 1e7 as big-endian int32
 *  [19-20]  = UTC year  as big-endian int16
 *  [21]     = UTC month (1-based)
 *  [22]     = UTC day
 *  [23]     = UTC hour
 *  [24]     = UTC minute
 *  [25]     = UTC second
 *  [26-90]  = reserved / padding (zeros)
 *  -- Format B only --
 *  [91-92]  = timezone rawOffset in minutes (big-endian int16)
 *  [93-94]  = DST savings in minutes        (big-endian int16)
 */
object SonyGpsPacket {

    fun build(location: Location, timezoneSupport: Boolean): ByteArray {
        val buf = if (timezoneSupport) ByteArray(95) else ByteArray(91)

        // Fixed header
        buf[0]  = 0x00
        buf[1]  = if (timezoneSupport) 93 else 89
        buf[2]  = 0x08
        buf[3]  = 0x02
        buf[4]  = (-4).toByte()
        buf[5]  = if (timezoneSupport) 0x03 else 0x00
        buf[6]  = 0x00
        buf[7]  = 0x00
        buf[8]  = 0x10
        buf[9]  = 0x10
        buf[10] = 0x10

        // Latitude: degrees * 1e7, big-endian int32
        val latInt = (location.latitude * 1e7).toInt()
        val lonInt = (location.longitude * 1e7).toInt()
        ByteBuffer.allocate(4).putInt(latInt).array().copyInto(buf, 11)
        ByteBuffer.allocate(4).putInt(lonInt).array().copyInto(buf, 15)

        // UTC timestamp from location.time
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.time = Date(location.time)
        ByteBuffer.allocate(2).putShort(cal.get(Calendar.YEAR).toShort()).array().copyInto(buf, 19)
        buf[21] = (cal.get(Calendar.MONTH) + 1).toByte()
        buf[22] = cal.get(Calendar.DAY_OF_MONTH).toByte()
        buf[23] = cal.get(Calendar.HOUR_OF_DAY).toByte()
        buf[24] = cal.get(Calendar.MINUTE).toByte()
        buf[25] = cal.get(Calendar.SECOND).toByte()

        // Timezone (only Format B)
        if (timezoneSupport) {
            val tz = TimeZone.getDefault()
            val rawOffsetMin = (tz.rawOffset / 60000).toShort()
            val dstMin = if (tz.inDaylightTime(Date(location.time))) (tz.dstSavings / 60000).toShort() else 0
            ByteBuffer.allocate(2).putShort(rawOffsetMin).array().copyInto(buf, 91)
            ByteBuffer.allocate(2).putShort(dstMin).array().copyInto(buf, 93)
        }

        return buf
    }
}
