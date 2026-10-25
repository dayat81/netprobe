package com.aksa.magnon

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File

/** Release gate. Debug builds leave [BuildConfig.INTEGRITY_GATE] false so a USB install can run. */
object DeviceIntegrity {
    private val suPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/su",
        "/system/bin/.ext/.su",
        "/system/usr/we-need-root/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/su/bin/su",
        "/magisk/.core/bin/su",
    )

    fun blocked(context: Context): Boolean = rooted() || developerOptions(context)

    private fun rooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        return suPaths.any { path -> runCatching { File(path).exists() }.getOrDefault(false) }
    }

    private fun developerOptions(context: Context): Boolean =
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) == 1
}
