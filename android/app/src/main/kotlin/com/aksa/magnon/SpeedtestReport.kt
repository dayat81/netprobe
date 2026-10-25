package com.aksa.magnon

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.roundToLong

/** Uploads one finished speedtest into the existing measurement pipeline. */
object SpeedtestReport {
    private const val TAG = "MySignalUpload"

    fun upload(
        context: Context,
        result: SpeedtestEngine.Result,
        kecamatan: String = "",
        transport: String = "",
        kelurahan: String = "",
        kabupaten: String = "",
        provinsi: String = "",
        latitude: Double? = null,
        longitude: Double? = null,
    ) {
        RumReporter.noteSpeed(result)
        ComplaintStore.saveSpeed(context, result, kecamatan, transport, kelurahan, kabupaten, provinsi, latitude, longitude)
        if (!CollectorClient.measureEnabled) return
        val app = context.applicationContext
        thread(name = "mysignal-speed-upload", start = true) {
            val test = JSONObject()
                .put("event_id", "myspd" + UUID.randomUUID().toString().replace("-", "").take(16))
                .put("ts_ms", System.currentTimeMillis())
                .put("source", "network_fallback")
                .put("test_type", "combined")
                .put("download_bps", bps(result.downloadMbps))
                .put("upload_bps", bps(result.uploadMbps))
                .put("latency_ms", result.pingMs)
                .put("jitter_ms", result.jitterMs)
                .put("loss_pct", JSONObject.NULL)
                .put("server", result.serverLabel.take(120))
                .put("summary", "mysignal")
                .apply {
                    if (latitude != null && longitude != null) {
                        put("latitude", latitude)
                        put("longitude", longitude)
                    }
                }
            val plain = JSONObject()
                .put("snapshots", JSONArray())
                .put("tests", JSONArray().put(test))
            val code = try {
                CollectorClient.postMeasurement(app, plain)
            } catch (error: Exception) {
                AppLog.w(TAG, "speedtest upload failed", error)
                -1
            }
            AppLog.i(
                TAG,
                "speedtest HTTP $code dl=${result.downloadMbps} ul=${result.uploadMbps}",
            )
        }
    }

    fun uploadVideo(
        context: Context,
        result: VideoTestEngine.Result,
        kecamatan: String = "",
        transport: String = "",
        kelurahan: String = "",
        kabupaten: String = "",
        provinsi: String = "",
        latitude: Double? = null,
        longitude: Double? = null,
    ) {
        ComplaintStore.saveVideo(context, result, kecamatan, transport, kelurahan, kabupaten, provinsi, latitude, longitude)
        if (!CollectorClient.measureEnabled) return
        val app = context.applicationContext
        thread(name = "mysignal-video-upload", start = true) {
            val summary = "mysignal-video load_ms=${result.loadTimeMs} res=${result.maxResolution} buf=${"%.1f".format(result.bufferingPct)}"
            val test = JSONObject()
                .put("event_id", "mysvd" + UUID.randomUUID().toString().replace("-", "").take(16))
                .put("ts_ms", System.currentTimeMillis())
                .put("source", "network_fallback")
                .put("test_type", "combined")
                .put("download_bps", JSONObject.NULL)
                .put("upload_bps", JSONObject.NULL)
                .put("latency_ms", result.loadTimeMs)
                .put("jitter_ms", JSONObject.NULL)
                .put("loss_pct", result.bufferingPct.coerceIn(0.0, 100.0))
                .put("server", result.serverLabel.take(120))
                .put("summary", summary.take(240))
                .apply {
                    if (latitude != null && longitude != null) {
                        put("latitude", latitude)
                        put("longitude", longitude)
                    }
                }
            val plain = JSONObject()
                .put("snapshots", JSONArray())
                .put("tests", JSONArray().put(test))
            val code = try {
                CollectorClient.postMeasurement(app, plain)
            } catch (error: Exception) {
                AppLog.w(TAG, "video upload failed", error)
                -1
            }
            AppLog.i(TAG, "video HTTP $code $summary")
        }
    }

: JSONObject? {
        val v4 = hopArray(hops4)
        val v6 = hopArray(hops6)
        if (v4.length() == 0 && v6.length() == 0) return null
        val trace = JSONObject()
        if (v4.length() > 0) trace.put("ipv4", v4)
        if (v6.length() > 0) trace.put("ipv6", v6)
        return trace
    }

: JSONArray {
        val array = JSONArray()
        hops.take(30).forEach { hop ->
            val item = JSONObject().put("ttl", hop.ttl.coerceIn(1, 64))
            hop.host?.trim()?.takeIf { it.isNotEmpty() }?.let { item.put("host", it.take(64)) }
            hop.rttMs?.takeIf { it.isFinite() && it >= 0.0 }?.let { item.put("rtt_ms", it) }
            array.put(item)
        }
        return array
    }

    private fun bps(mbps: Double): Long = (mbps * 1_000_000.0).roundToLong().coerceAtLeast(0L)
}
