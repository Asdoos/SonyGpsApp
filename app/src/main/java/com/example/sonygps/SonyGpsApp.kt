package com.example.sonygps

import android.app.Application

/** Applies the chosen app language before any activity or service starts. */
class SonyGpsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocale.applyStored(this)
    }
}
