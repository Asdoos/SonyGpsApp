package com.example.sonygps

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Applies the chosen app language before any activity or service starts and
 * lets Android 12+ colour the UI from the system wallpaper (Material You).
 */
class SonyGpsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocale.applyStored(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
