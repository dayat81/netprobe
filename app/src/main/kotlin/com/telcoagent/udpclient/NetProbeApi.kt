package com.telcoagent.udpclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class NearbyProvider(
    val operator: String,
    val operatorName: String?,
    val sampleCount: Int,
    val sessionCount: Int,
    val avgUdpLatencyMs: Double?,
    val avgUdpUplinkMs: Double?,
    val avgUdpDownlinkMs: Double?,
    val avgUdpLossPct: Double?,
    val avgUdpJitterMs: Double?,
    val avgRsrp: Double?,
    val nearestKm: Double?,
    val lastSeen: String?,
)

data class NearbyProvidersResult(
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,
    val providers: List<NearbyProvider>,
)

data class NearbyProbeSession(
    val sessionId: Int,
    val filename: String?,
    val deviceId: String?,
    val uploadedAt: String?,
    val techSummary: String?,
    val tech: String?,
    val config: String?,
    val networkType: String?,
    val connectionLabel: String?,
    val distanceKm: Double?,
    val failed: Boolean,
    val failureMessage: String?,
    val udpLatencyMs: Double?,
    val udpUplinkMs: Double?,
    val udpDownlinkMs: Double?,
    val udpLossPct: Double?,
    val udpJitterMs: Double?,
)

data class NearbyProviderSessionsResult(
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,
    val operator: String,
    val sessions: List<NearbyProbeSession>,
)

class NetProbeApi {
    suspend fun fetchNearbyProviders(
        lat: Double,
        lon: Double,
        radiusKm: Double = 5.0,
    ): Result<NearbyProvidersResult> = withContext(Dispatchers.IO) {
        try {
            val query = buildString {
                append("lat=").append(URLEncoder.encode(lat.toString(), "UTF-8"))
                append("&lon=").append(URLEncoder.encode(lon.toString(), "UTF-8"))
                append("&radius_km=").append(URLEncoder.encode(radiusKm.toString(), "UTF-8"))
            }
            val url = URL("${BuildConfig.NETPROBE_API_BASE_URL}/api/sessions/nearby?$query")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                return@withContext Result.failure(Exception("HTTP $code: $err"))
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val queryObj = json.getJSONObject("query")
            val providers = parseProviders(json.getJSONArray("providers"))
            Result.success(
                NearbyProvidersResult(
                    lat = queryObj.getDouble("lat"),
                    lon = queryObj.getDouble("lon"),
                    radiusKm = queryObj.getDouble("radius_km"),
                    providers = providers,
                ),
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchNearbyProviderSessions(
        lat: Double,
        lon: Double,
        operator: String,
        radiusKm: Double = 5.0,
        limit: Int = 10,
    ): Result<NearbyProviderSessionsResult> = withContext(Dispatchers.IO) {
        try {
            val query = buildString {
                append("lat=").append(URLEncoder.encode(lat.toString(), "UTF-8"))
                append("&lon=").append(URLEncoder.encode(lon.toString(), "UTF-8"))
                append("&radius_km=").append(URLEncoder.encode(radiusKm.toString(), "UTF-8"))
                append("&operator=").append(URLEncoder.encode(operator, "UTF-8"))
                append("&limit=").append(URLEncoder.encode(limit.toString(), "UTF-8"))
            }
            val url = URL("${BuildConfig.NETPROBE_API_BASE_URL}/api/sessions/nearby/sessions?$query")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                return@withContext Result.failure(Exception("HTTP $code: $err"))
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val queryObj = json.getJSONObject("query")
            val sessions = parseSessions(json.getJSONArray("sessions"))
            Result.success(
                NearbyProviderSessionsResult(
                    lat = queryObj.getDouble("lat"),
                    lon = queryObj.getDouble("lon"),
                    radiusKm = queryObj.getDouble("radius_km"),
                    operator = queryObj.getString("operator"),
                    sessions = sessions,
                ),
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseSessions(array: JSONArray): List<NearbyProbeSession> {
        val sessions = mutableListOf<NearbyProbeSession>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            sessions += NearbyProbeSession(
                sessionId = item.getInt("session_id"),
                filename = item.optStringOrNull("filename"),
                deviceId = item.optStringOrNull("device_id"),
                uploadedAt = item.optStringOrNull("uploaded_at"),
                techSummary = item.optStringOrNull("tech_summary"),
                tech = item.optStringOrNull("tech"),
                config = item.optStringOrNull("config"),
                networkType = item.optStringOrNull("network_type"),
                connectionLabel = item.optStringOrNull("connection_label"),
                distanceKm = item.optDoubleOrNull("distance_km"),
                failed = item.optBoolean("failed", false),
                failureMessage = item.optStringOrNull("failure_message"),
                udpLatencyMs = item.optDoubleOrNull("udp_latency_ms"),
                udpUplinkMs = item.optDoubleOrNull("udp_uplink_ms"),
                udpDownlinkMs = item.optDoubleOrNull("udp_downlink_ms"),
                udpLossPct = item.optDoubleOrNull("udp_loss_pct"),
                udpJitterMs = item.optDoubleOrNull("udp_jitter_ms"),
            )
        }
        return sessions
    }

    private fun parseProviders(array: JSONArray): List<NearbyProvider> {
        val providers = mutableListOf<NearbyProvider>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            providers += NearbyProvider(
                operator = item.getString("operator"),
                operatorName = item.optStringOrNull("operator_name"),
                sampleCount = item.getInt("sample_count"),
                sessionCount = item.getInt("session_count"),
                avgUdpLatencyMs = item.optDoubleOrNull("avg_udp_latency_ms"),
                avgUdpUplinkMs = item.optDoubleOrNull("avg_udp_uplink_ms"),
                avgUdpDownlinkMs = item.optDoubleOrNull("avg_udp_downlink_ms"),
                avgUdpLossPct = item.optDoubleOrNull("avg_udp_loss_pct"),
                avgUdpJitterMs = item.optDoubleOrNull("avg_udp_jitter_ms"),
                avgRsrp = item.optDoubleOrNull("avg_rsrp"),
                nearestKm = item.optDoubleOrNull("nearest_km"),
                lastSeen = item.optStringOrNull("last_seen"),
            )
        }
        return providers
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key)
    }
}
