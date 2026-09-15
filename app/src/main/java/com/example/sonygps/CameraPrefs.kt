package com.example.sonygps

import android.content.Context

/**
 * Persistent user settings.
 *
 *  - Remembered camera: saved after the first successful GPS handshake, so the
 *    next session can connect directly without a scan.
 *  - Auto-connect: start the session as soon as the remembered camera advertises.
 *  - Track recording: write every GPS fix of a session to a GPX file.
 *  - Battery saver: request GPS less often and with balanced priority.
 *  - Update check: when GitHub was last asked for a new release, and which
 *    release the user chose to skip.
 */
class CameraPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sony_gps", Context.MODE_PRIVATE)

    val cameraAddress: String? get() = prefs.getString(KEY_ADDRESS, null)
    val cameraName: String?    get() = prefs.getString(KEY_NAME, null)

    var autoConnect: Boolean
        get()  = prefs.getBoolean(KEY_AUTO_CONNECT, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_CONNECT, v).apply()

    var recordTrack: Boolean
        get()  = prefs.getBoolean(KEY_RECORD_TRACK, false)
        set(v) = prefs.edit().putBoolean(KEY_RECORD_TRACK, v).apply()

    /** GPS every 20 s with balanced priority instead of every 5 s with high accuracy. */
    var batterySaver: Boolean
        get()  = prefs.getBoolean(KEY_BATTERY_SAVER, false)
        set(v) = prefs.edit().putBoolean(KEY_BATTERY_SAVER, v).apply()

    /** Auto-connect is suppressed until this wall-clock time (ms), e.g. after a manual stop. */
    var autoConnectSnoozeUntil: Long
        get()  = prefs.getLong(KEY_SNOOZE_UNTIL, 0L)
        set(v) = prefs.edit().putLong(KEY_SNOOZE_UNTIL, v).apply()

    /** Wall-clock time (ms) of the last auto-connect attempt, used for rate limiting. */
    var lastAutoConnectAttempt: Long
        get()  = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_ATTEMPT, v).apply()

    /** Wall-clock time (ms) of the last automatic update check. */
    var lastUpdateCheck: Long
        get()  = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, v).apply()

    /** Release tag the user dismissed with "skip"; not offered again automatically. */
    var skippedUpdateTag: String?
        get()  = prefs.getString(KEY_SKIPPED_UPDATE, null)
        set(v) = prefs.edit().putString(KEY_SKIPPED_UPDATE, v).apply()

    fun rememberCamera(address: String, name: String?) {
        prefs.edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_NAME, name)
            .apply()
    }

    fun forgetCamera() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
    }

    private companion object {
        const val KEY_ADDRESS      = "camera_address"
        const val KEY_NAME         = "camera_name"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_RECORD_TRACK = "record_track"
        const val KEY_BATTERY_SAVER = "battery_saver"
        const val KEY_SNOOZE_UNTIL = "auto_connect_snooze_until"
        const val KEY_LAST_ATTEMPT = "auto_connect_last_attempt"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_SKIPPED_UPDATE    = "skipped_update_tag"
    }
}
