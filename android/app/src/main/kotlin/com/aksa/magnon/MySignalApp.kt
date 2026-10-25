package com.aksa.magnon

import android.app.Application

/** Installs crash capture before the activity runs. A blocked release build does not report. */
class MySignalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.INTEGRITY_GATE && DeviceIntegrity.blocked(this)) return
        RumReporter.start(this)
    }
}
