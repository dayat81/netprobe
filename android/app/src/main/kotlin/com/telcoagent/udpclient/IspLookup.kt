package com.telcoagent.udpclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class IspInfo(
    val isp: String?,
    val org: String?,
    val publicIp: String?,
    val fetchedAt: Long = System.currentTimeMillis(),
)

object IspLookup {
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    @Volatile
    private var cache: IspInfo? = null

    fun displayName(info: IspInfo?): String? {
        if (info == null) return null
        return info.isp?.takeIf { it.isNotBlank() }
            ?: info.org?.takeIf { it.isNotBlank() }
    }

    fun cached(): IspInfo? {
        val entry = cache ?: return null
        if (System.currentTimeMillis() - entry.fetchedAt > CACHE_TTL_MS) {
            return null
        }
        return entry
    }

    suspend fun refresh(force: Boolean = false): Result<IspInfo> = withContext(Dispatchers.IO) {
        if (!force) {
            cached()?.let { return@withContext Result.success(it) }
        }
        try {
            val conn = (URL(BuildConfig.ISP_LOOKUP_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                return@withContext Result.failure(Exception("HTTP $code: $err"))
            }

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val info = IspInfo(
                isp = json.optString("isp").takeIf { it.isNotBlank() },
                org = json.optString("org").takeIf { it.isNotBlank() },
                publicIp = json.optString("ip").takeIf { it.isNotBlank() },
            )
            cache = info
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
