package com.anri.sonygps

import java.nio.ByteBuffer

/**
 * Memory card state the camera reports on characteristic 0000CC0F (read + notify).
 *
 * Reverse-engineered from Sony Creators App v3.5.0
 * Source: BluetoothCharacteristicParser.parseCameraMediaInfo() + BluetoothCameraMediaParser
 * The meaning of the flag bits and the unit of the recording time are not in
 * the Sony code; they were observed on an ILCE-6700.
 *
 * Byte layout (24 bytes):
 *  [0]      = 23, length of the record
 *  [1-2]    = data type, 0x0000
 *  [3]      = 2 when media info is readable (0/1 = not available)
 *  [4], [5] = flags of slot 1 / slot 2: 0x02 = shot count valid (photo mode),
 *             0x04 = recording time valid (movie mode)
 *  [6], [7] = status of slot 1 / slot 2: 1 = OK, 2 = no card, 3 = error, 4 = unavailable, 5 = PC
 *  [8-11]   = slot 1 remaining shots, big-endian int32 (the Sony app caps it at 9999)
 *  [12-15]  = slot 2 remaining shots
 *  [16-19]  = slot 1 remaining recording time in seconds
 *  [20-23]  = slot 2 remaining recording time in seconds
 *
 * Exactly one of [remainingShots] / [remainingSeconds] is set when a card is
 * usable, depending on the camera's shooting mode.
 */
data class CameraMedia(
    val hasCard: Boolean,
    val remainingShots: Int? = null,
    val remainingSeconds: Int? = null
) {

    companion object {
        private const val READABLE: Byte = 2
        private const val STATUS_OK: Byte = 1
        private const val STATUS_NO_CARD: Byte = 2
        private const val FLAG_SHOTS = 0x02
        private const val FLAG_TIME  = 0x04
        private const val MAX_SHOTS = 9999

        fun parse(data: ByteArray): CameraMedia? {
            if (data.size < 24 || data[3] != READABLE) return null
            // Slot the camera can write to; with two cards the first one wins
            val slot = (0..1).firstOrNull { data[6 + it] == STATUS_OK }
            if (slot == null) {
                val noCard = (0..1).any { data[6 + it] == STATUS_NO_CARD }
                return if (noCard) CameraMedia(hasCard = false) else null
            }
            val flags = data[4 + slot].toInt()
            val shots = ByteBuffer.wrap(data, 8 + slot * 4, 4).int
            val seconds = ByteBuffer.wrap(data, 16 + slot * 4, 4).int
            return when {
                flags and FLAG_TIME != 0 && seconds >= 0 -> CameraMedia(true, remainingSeconds = seconds)
                flags and FLAG_SHOTS != 0 && shots >= 0  -> CameraMedia(true, remainingShots = minOf(shots, MAX_SHOTS))
                else -> null
            }
        }
    }
}
