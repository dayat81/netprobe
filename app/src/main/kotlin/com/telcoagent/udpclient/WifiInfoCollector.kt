package com.telcoagent.udpclient

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WifiInfoSnapshot(
    val ssid: String = "—",
    val bssid: String = "—",
    val rssi: String = "—",
    val frequency: String = "—",
    val linkSpeed: String = "—",
    val channel: String = "—",
    val security: String = "—",
    val isConnected: Boolean = false,
    val updatedAt: String = "—",
    val error: String? = null,
) {
    companion object {
        val empty = WifiInfoSnapshot()
    }
}

class WifiInfoCollector(private val context: Context) {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    @SuppressLint("MissingPermission")
    fun collect(): WifiInfoSnapshot {
        val wm = wifiManager ?: return WifiInfoSnapshot(error = "WifiManager unavailable")

        if (!wm.isWifiEnabled) {
            return WifiInfoSnapshot(error = "WiFi disabled")
        }

        val info: WifiInfo = try {
            wm.connectionInfo
        } catch (e: Exception) {
            return WifiInfoSnapshot(error = "Cannot read WiFi info")
        }

        if (info.networkId == -1 && info.ssid == WifiManager.UNKNOWN_SSID) {
            return WifiInfoSnapshot(error = "WiFi not connected")
        }

        val ssid = cleanSsid(info.ssid)
        val bssid = info.bssid ?: "—"
        val rssi = info.rssi
        val rssiStr = if (rssi != 0 && rssi != -127) "$rssi" else "—"
        val freq = info.frequency
        val freqStr = if (freq > 0) "$freq" else "—"
        val linkSpeed = info.linkSpeed
        val linkSpeedStr = if (linkSpeed > 0) "$linkSpeed" else "—"
        val channel = freqToChannel(freq)
        val channelStr = if (channel > 0) "$channel" else "—"
        val security = resolveSecurity(info)
        val updatedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        return WifiInfoSnapshot(
            ssid = ssid,
            bssid = bssid,
            rssi = rssiStr,
            frequency = freqStr,
            linkSpeed = linkSpeedStr,
            channel = channelStr,
            security = security,
            isConnected = true,
            updatedAt = updatedAt,
        )
    }

    private fun cleanSsid(raw: String?): String {
        if (raw == null) return "—"
        val s = raw.trim()
        if (s == WifiManager.UNKNOWN_SSID || s == "<unknown ssid>") return "—"
        return s.removeSurrounding("\"")
    }

    private fun freqToChannel(freqMhz: Int): Int {
        return when {
            freqMhz in 2412..2484 -> {
                if (freqMhz == 2484) 14
                else (freqMhz - 2412) / 5 + 1
            }
            freqMhz in 5170..5825 -> (freqMhz - 5170) / 5 + 34
            freqMhz in 5955..7115 -> (freqMhz - 5955) / 5 + 1 // Wi-Fi 6E / 6 GHz
            else -> 0
        }
    }

    @Suppress("deprecation")
    private fun resolveSecurity(info: WifiInfo): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.currentSecurityType) {
                    1 -> "OPEN"
                    2 -> "WEP"
                    3 -> "PSK"
                    4 -> "EAP"
                    5 -> "SAE"
                    6 -> "OWE"
                    7 -> "SUITE_B_192"
                    else -> "—"
                }
            } else {
                "—"
            }
        } catch (_: Exception) {
            "—"
        }
    }
}
