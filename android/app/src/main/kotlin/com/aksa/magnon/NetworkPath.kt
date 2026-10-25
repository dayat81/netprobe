package com.aksa.magnon

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.Locale

/** Active path for one test round. Wifi wins when that is the default route. */
object NetworkPath {
    fun kind(context: Context): String = runCatching {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching ""
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching ""
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> ""
        }
    }.getOrDefault("")

    fun canonical(kind: String): String = when (kind.trim().lowercase(Locale.US)) {
        "wifi", "wi-fi" -> "wifi"
        "cell", "cellular" -> "cell"
        else -> ""
    }

    fun label(kind: String): String = when (canonical(kind)) {
        "wifi" -> "WIFI"
        "cell" -> "CELL"
        else -> ""
    }
}
