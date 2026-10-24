package com.aksa.magnon

import android.app.Application

/** Installs crash capture before [RadioService] or the activity run. */
class MySignalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RumReporter.start(this)
    }
}
