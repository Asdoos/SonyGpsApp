package com.anri.sonygps

import java.nio.ByteBuffer

/**
 * Memory card state the camera reports on characteristic 0000CC0F (read + notify).
 *
 * Reverse-engineered from Sony Creators App v3.5.0
 * Source: BluetoothCharacteristicParser.parseCameraMediaInfo() + BluetoothCameraMediaParser
 *
 * Byte layout (24 bytes):
 *  [0]      = 23, length of the record
 *  [1-2]    = data type, 0x0000
 *  [3]      = 2 when media info is readable (0/1 = not available)
 *  [4], [5] = flags of slot 1 / slot 2
 *  [6], [7] = status of slot 1 / slot 2: 1 = OK, 2 = no card, 3 = error, 4 = unavailable, 5 = PC
 *  [8-11]   = slot 1 remaining shots, big-endian int32 (the Sony app caps it at 9999)
 *  [12-15]  = slot 2 remaining shots
 *  [16-19]  = slot 1 remaining recording time
 *  [20-23]  = slot 2 remaining recording time
 */
data class CameraMedia(val hasCard: Boolean, val remainingShots: Int) {

    companion object {
        private const val READABLE: Byte = 2
        private const val STATUS_OK: Byte = 1
        private const val STATUS_NO_CARD: Byte = 2
        private const val MAX_SHOTS = 9999

        fun parse(data: ByteArray): CameraMedia? {
            if (data.size < 16 || data[3] != READABLE) return null
            // Slot the camera can write to; with two cards the first one wins
            val slot = (0..1).firstOrNull { data[6 + it] == STATUS_OK }
            if (slot != null) {
                val shots = ByteBuffer.wrap(data, 8 + slot * 4, 4).int
                if (shots < 0) return null
                return CameraMedia(hasCard = true, remainingShots = minOf(shots, MAX_SHOTS))
            }
            val noCard = (0..1).any { data[6 + it] == STATUS_NO_CARD }
            return if (noCard) CameraMedia(hasCard = false, remainingShots = 0) else null
        }
    }
}
