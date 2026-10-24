package com.aksa.mysignal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.roundToLong

/** Uploads one finished speedtest into the existing measurement pipeline. */
object SpeedtestReport {
    private const val TAG = "MySignalUpload"

    fun upload(context: Context, result: SpeedtestEngine.Result) {
        RumReporter.noteSpeed(result)
        ComplaintStore.saveSpeed(context, result)
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
            val plain = JSONObject()
                .put("snapshots", JSONArray())
                .put("tests", JSONArray().put(test))
            val code = try {
                CollectorClient.postMeasurement(app, plain)
            } catch (error: Exception) {
                AppLog.w(TAG, "speedtest upload failed", error)
                -1
            }
            AppLog.i(TAG, "speedtest HTTP $code dl=${result.downloadMbps} ul=${result.uploadMbps}")
        }
    }

    fun uploadVideo(context: Context, result: VideoTestEngine.Result) {
        ComplaintStore.saveVideo(context, result)
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

    private fun bps(mbps: Double): Long = (mbps * 1_000_000.0).roundToLong().coerceAtLeast(0L)
}
