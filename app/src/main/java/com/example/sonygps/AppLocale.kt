package com.example.sonygps

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * App language switch (settings → "App language": system / Deutsch / English).
 *
 * The choice is stored in CameraPrefs as a BCP-47 tag ("" = follow the system) and
 * applied in two ways:
 *
 *  - Activities: AppCompatDelegate.setApplicationLocales(). On Android 13+ this
 *    goes to the system's per-app language setting, which then covers every
 *    component of the app. Below 13 AppCompat only localizes AppCompatActivity.
 *  - Service, tile, receivers (notifications, log lines) below Android 13: the
 *    component wraps its base context with [wrap], which applies the stored
 *    locale to a configuration context. Without this, notifications would stay
 *    in the system language while the UI shows the chosen one.
 *
 * [applyStored] runs in [SonyGpsApp.onCreate] so the setting is live before the
 * first activity, also when the process is started by auto-connect.
 */
object AppLocale {

    const val SYSTEM = ""

    /** Applies the stored choice to AppCompat (activities). Safe to call repeatedly. */
    fun applyStored(context: Context) {
        val tag = CameraPrefs(context).appLanguage
        val wanted = if (tag == SYSTEM) LocaleListCompat.getEmptyLocaleList()
                     else LocaleListCompat.forLanguageTags(tag)
        if (AppCompatDelegate.getApplicationLocales() != wanted) {
            AppCompatDelegate.setApplicationLocales(wanted)
        }
    }

    /** Stores [tag] ("" for system) and applies it; running activities are recreated by AppCompat. */
    fun set(context: Context, tag: String) {
        CameraPrefs(context).appLanguage = tag
        applyStored(context)
    }

    /**
     * Context whose resources follow the stored app language. Identity on Android 13+
     * (the system does it) and when the setting is "system".
     */
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val tag = CameraPrefs(context).appLanguage
        if (tag == SYSTEM) return context
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config)
    }
}
