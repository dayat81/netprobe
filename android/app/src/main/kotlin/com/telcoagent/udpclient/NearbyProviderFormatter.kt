package com.telcoagent.udpclient

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object NearbyProviderFormatter {
    fun providerName(operator: String, operatorName: String?): String {
        if (operator.isBlank()) return "—"
        val friendlyName = operatorName?.takeIf { it.isNotBlank() && it != "null" }
        return when {
            friendlyName == null -> OperatorNames.format(operator)
            friendlyName == operator -> operator
            else -> "$friendlyName ($operator)"
        }
    }

    fun formatMs(value: Double?): String {
        return value?.let { "${"%.1f".format(it)} ms" } ?: "—"
    }

    fun formatPct(value: Double?): String {
        return value?.let { "${"%.1f".format(it)}%" } ?: "—"
    }

    fun formatLastSeen(raw: String?): String {
        if (raw.isNullOrBlank()) return "—"
        return runCatching {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(raw.take(19)) ?: return raw
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(date)
        }.getOrDefault(raw)
    }

    fun formatConnection(
        tech: String?,
        techSummary: String?,
        networkType: String?,
        connectionLabel: String? = null,
    ): String {
        connectionLabel?.takeIf { it.isNotBlank() }?.let { return it }
        val transport = networkType?.trim()?.uppercase()
        return when {
            transport == "WIFI" -> "WiFi"
            transport == "ETHERNET" -> "Ethernet"
            tech?.trim()?.equals("WEB-WT", ignoreCase = true) == true -> "WEB-WT"
            transport == "CELLULAR" -> techSummary?.takeIf { it.isNotBlank() }
                ?: tech?.takeIf { it.isNotBlank() }
                ?: "CELLULAR"
            !techSummary.isNullOrBlank() -> techSummary
            !tech.isNullOrBlank() -> tech
            !networkType.isNullOrBlank() -> networkType
            else -> "—"
        }
    }
}
