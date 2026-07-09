package com.telcoagent.udpclient

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

data class DeviceInfo(
    val imsi: String? = null,
    val imei: String? = null,
    val deviceId: String? = null,
    val operator: String? = null,
)

object DeviceInfoReader {
    @SuppressLint("HardwareIds", "MissingPermission")
    fun read(context: Context, telephonyManager: TelephonyManager?): DeviceInfo {
        val tm = telephonyManager ?: return DeviceInfo(deviceId = androidId(context))

        val imei = try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    tm.imei ?: readImeiForSubscriptions(tm)
                }
                else -> {
                    @Suppress("DEPRECATION")
                    tm.deviceId
                }
            }
        } catch (_: SecurityException) {
            null
        }

        val imsi = try {
            readImsi(context, tm)
        } catch (_: SecurityException) {
            null
        }

        val operator = tm.networkOperator?.takeIf { it.isNotBlank() && it != "00000" }
            ?: tm.simOperator?.takeIf { it.isNotBlank() && it != "00000" }

        return DeviceInfo(
            imsi = imsi?.takeIf { it.isNotBlank() },
            imei = imei?.takeIf { it.isNotBlank() },
            deviceId = androidId(context),
            operator = operator,
        )
    }

    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String? {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun readImsi(context: Context, tm: TelephonyManager): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager
            val activeSubs = subManager?.activeSubscriptionInfoList
            if (!activeSubs.isNullOrEmpty()) {
                for (info in activeSubs) {
                    val subTm = tm.createForSubscriptionId(info.subscriptionId)
                    @Suppress("DEPRECATION")
                    val id = subTm.subscriberId
                    if (!id.isNullOrBlank()) return id
                }
            }
        }
        @Suppress("DEPRECATION")
        return tm.subscriberId
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun readImeiForSubscriptions(tm: TelephonyManager): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        for (slot in 0 until tm.phoneCount) {
            try {
                val imei = tm.getImei(slot)
                if (!imei.isNullOrBlank()) return imei
            } catch (_: SecurityException) {
                continue
            }
        }
        return null
    }
}
