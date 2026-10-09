package com.anri.sonygps

import java.nio.ByteBuffer

/**
 * Battery state the camera reports on characteristic 0000CC10 (read + notify).
 *
 * Reverse-engineered from Sony Creators App v3.5.0
 * Source: BluetoothCharacteristicParser.parseCameraBatteryInfo() + BluetoothCameraBatteryParser
 *
 * Byte layout:
 *  [0]      = length of the record (bytes that follow)
 *  [1-2]    = data type, 0x0000
 *  [3]      = 2 when battery info is readable (0/1 = not available)
 *  [4]      = pack 1 flags, bit 0 = pack present
 *  [5]      = pack 2 flags (second battery, e.g. vertical grip)
 *  [6], [7] = position of pack 1 / pack 2
 *  [8], [9] = level step of pack 1 / pack 2 (1 = nearly empty … 5 = full, 6–11 = on USB power)
 *  [10-13]  = pack 1 remaining charge in percent, big-endian int32
 *  [14-17]  = pack 2 remaining charge in percent, big-endian int32
 *  [18]     = power supply: 0 = undefined, 1 = none, 2 = powered via USB (newer bodies only)
 */
data class CameraBattery(val percent: Int, val externalPower: Boolean) {

    companion object {
        private const val READABLE: Byte = 2
        private const val POWERING: Byte = 2

        fun parse(data: ByteArray): CameraBattery? {
            if (data.size < 14 || data[3] != READABLE) return null
            val percent = when {
                data[4].toInt() and 1 == 1 -> ByteBuffer.wrap(data, 10, 4).int
                data.size >= 18 && data[5].toInt() and 1 == 1 -> ByteBuffer.wrap(data, 14, 4).int
                else -> return null
            }
            if (percent !in 0..100) return null
            return CameraBattery(percent, externalPower = data.size >= 19 && data[18] == POWERING)
        }
    }
}
