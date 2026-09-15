package com.example.sonygps

import android.content.Context
import android.content.SharedPreferences

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
 *
 * The switches are also edited by [SettingsActivity] through AndroidX Preference,
 * which writes to the same file ([FILE]) under the same keys.
 */
class CameraPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val cameraAddress: String? get() = prefs.getString(KEY_ADDRESS, null)
    val cameraName: String?    get() = prefs.getString(KEY_NAME, null)

    var autoConnect: Boolean
        get()  = prefs.getBoolean(KEY_AUTO_CONNECT, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_CONNECT, v).apply()

    var recordTrack: Boolean
        get()  = prefs.getBoolean(KEY_RECORD_TRACK, false)
        set(v) = prefs.edit().putBoolean(KEY_RECORD_TRACK, v).apply()

    /** Request GPS fixes less often with balanced priority; packets still go out every 5 s. */
    var batterySaver: Boolean
        get()  = prefs.getBoolean(KEY_BATTERY_SAVER, false)
        set(v) = prefs.edit().putBoolean(KEY_BATTERY_SAVER, v).apply()

    /** Fix interval in battery-saver mode (ms). Stored as a string because ListPreference does. */
    val saverIntervalMs: Long
        get() = (prefs.getString(KEY_SAVER_INTERVAL, null)?.toLongOrNull()
            ?: DEFAULT_SAVER_INTERVAL_SEC) * 1000L

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

    /** App language as BCP-47 tag ("de", "en"); "" follows the system. See [AppLocale]. */
    var appLanguage: String
        get()  = prefs.getString(KEY_APP_LANGUAGE, AppLocale.SYSTEM) ?: AppLocale.SYSTEM
        set(v) = prefs.edit().putString(KEY_APP_LANGUAGE, v).apply()

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

    /** The listener must be kept referenced by the caller; SharedPreferences holds it weakly. */
    fun registerOnChange(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterOnChange(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val FILE = "sony_gps"

        const val KEY_ADDRESS        = "camera_address"
        const val KEY_NAME           = "camera_name"
        const val KEY_AUTO_CONNECT   = "auto_connect"
        const val KEY_RECORD_TRACK   = "record_track"
        const val KEY_BATTERY_SAVER  = "battery_saver"
        const val KEY_SAVER_INTERVAL = "saver_interval_sec"
        const val KEY_APP_LANGUAGE   = "app_language"
        const val DEFAULT_SAVER_INTERVAL_SEC = 20L

        private const val KEY_SNOOZE_UNTIL      = "auto_connect_snooze_until"
        private const val KEY_LAST_ATTEMPT      = "auto_connect_last_attempt"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_SKIPPED_UPDATE    = "skipped_update_tag"
    }
}
