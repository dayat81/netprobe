package com.aksa.mysignal

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Last speed and video results, kept so the complaint tab can preview them. */
object ComplaintStore {
    private const val PREFS = "mysignal_complaint"

    data class Speed(
        val tsMs: Long,
        val downloadMbps: Double,
        val uploadMbps: Double,
        val pingMs: Long,
        val jitterMs: Long,
        val transport: String,
    )
    data class Video(
        val tsMs: Long,
        val loadMs: Long,
        val resolution: String,
        val bufferingPct: Double,
        val transport: String,
    )

    @Volatile
    private var shownSpeed: Speed? = null

    @Volatile
    private var shownVideo: Video? = null

    fun currentSpeed(): Speed? = shownSpeed

    fun currentVideo(): Video? = shownVideo

    fun saveSpeed(context: Context, result: SpeedtestEngine.Result) {
        shownSpeed = Speed(
            System.currentTimeMillis(),
            result.downloadMbps,
            result.uploadMbps,
            result.pingMs,
            result.jitterMs,
            transportNow(),
        )
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("speed_ts", System.currentTimeMillis())
            .putFloat("speed_dl", result.downloadMbps.toFloat())
            .putFloat("speed_ul", result.uploadMbps.toFloat())
            .putLong("speed_ping", result.pingMs)
            .putLong("speed_jitter", result.jitterMs)
            .putString("speed_transport", transportNow())
            .apply()
    }

    fun saveVideo(context: Context, result: VideoTestEngine.Result) {
        shownVideo = Video(
            System.currentTimeMillis(),
            result.loadTimeMs,
            result.maxResolution,
            result.bufferingPct,
            transportNow(),
        )
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("video_ts", System.currentTimeMillis())
            .putLong("video_load", result.loadTimeMs)
            .putString("video_res", result.maxResolution)
            .putFloat("video_buf", result.bufferingPct.toFloat())
            .putString("video_transport", transportNow())
            .apply()
    }

    private fun transportNow(): String = RadioState.latest?.network?.transport ?: "unknown"

    fun speed(context: Context): Speed? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong("speed_ts", 0L)
        if (ts <= 0L) return null
        return Speed(
            ts,
            prefs.getFloat("speed_dl", 0f).toDouble(),
            prefs.getFloat("speed_ul", 0f).toDouble(),
            prefs.getLong("speed_ping", 0L),
            prefs.getLong("speed_jitter", 0L),
            prefs.getString("speed_transport", "").orEmpty(),
        )
    }

    fun video(context: Context): Video? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong("video_ts", 0L)
        if (ts <= 0L) return null
        return Video(
            ts,
            prefs.getLong("video_load", 0L),
            prefs.getString("video_res", "").orEmpty(),
            prefs.getFloat("video_buf", 0f).toDouble(),
            prefs.getString("video_transport", "").orEmpty(),
        )
    }

    data class Entry(val tsMs: Long, val verdict: String, val draft: String, val eligible: Boolean)

    fun remember(context: Context, review: ComplaintDraft.Review) {
        if (review.verdict.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = JSONArray(prefs.getString("history", "[]"))
        if (existing.length() > 0) {
            val last = existing.getJSONObject(0)
            if (last.optString("verdict") == review.verdict && last.optString("draft") == review.draft) return
        }
        val next = JSONArray()
        next.put(
            JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("verdict", review.verdict.take(800))
                .put("draft", review.draft.take(400))
                .put("eligible", review.eligible),
        )
        for (index in 0 until minOf(existing.length(), 29)) next.put(existing.getJSONObject(index))
        prefs.edit().putString("history", next.toString()).apply()
    }

    fun history(context: Context): List<Entry> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("history", "[]")
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Entry(
                item.optLong("ts"),
                item.optString("verdict"),
                item.optString("draft"),
                item.optBoolean("eligible"),
            )
        }
    }
}

/** Complaint text for the active SIM, ready to read before it is sent to X. */
object ComplaintDraft {
    data class Review(val eligible: Boolean, val verdict: String, val draft: String, val reviewed: Boolean = false)

    private val clock = SimpleDateFormat("dd/MM HH:mm", Locale("id", "ID")).apply {
        timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    }

    fun review(context: Context): Review {
        val snap = RadioState.latest
        val speedAll = ComplaintStore.currentSpeed()
        val videoAll = ComplaintStore.currentVideo()
        val missing = listOfNotNull(
            if (speedAll == null) "Speed" else null,
            if (videoAll == null) "Video" else null,
        )
        if (missing.isNotEmpty()) {
            return Review(
                false,
                "Test on the ${missing.joinToString(" and ")} ${if (missing.size == 1) "tab" else "tabs"} first.\nThe review uses the results currently shown on ${if (missing.size == 1) "that tab" else "those tabs"}.",
                "",
            )
        }
        val speed = speedAll?.takeIf { it.transport == "cellular" }
        val video = videoAll?.takeIf { it.transport == "cellular" }
        val wifiTabs = listOfNotNull(
            if (speedAll != null && speed == null) "Speed" else null,
            if (videoAll != null && video == null) "Video" else null,
        )
        if (wifiTabs.isNotEmpty()) {
            return Review(
                false,
                "Not ready to post\nThe ${wifiTabs.joinToString(" and ")} ${if (wifiTabs.size == 1) "result" else "results"} used Wi-Fi. Run ${if (wifiTabs.size == 1) "that test" else "those tests"} again on cellular data.",
                "",
            )
        }
        return try {
            val response = postComplaint(brief(snap, speed, video))
            val eligible = response.optBoolean("eligible") && response.optString("sentence").isNotBlank()
            val sentence = response.optString("sentence")
            val summary = response.optString("summary")
            val url = response.optString("url")
            val headline = if (eligible) "Ready to post" else "Not ready to post"
            val tweet = if (eligible && url.isNotBlank()) "$sentence\n$url" else ""
            Review(eligible && tweet.isNotBlank(), "$headline\n$summary", tweet, reviewed = true)
        } catch (error: Exception) {
            Review(false, "OTel review failed\n${error.message?.take(160) ?: "network"}", "")
        }
    }

    fun text(context: Context): String = review(context).draft

    private fun brief(snap: RadioSnapshot?, speed: ComplaintStore.Speed?, video: ComplaintStore.Video?): JSONObject {
        val sub = snap?.primary
        val plmn = sub?.plmn.orEmpty()
        val mention = when (plmn) {
            "510-01" -> "@indosat"
            "510-11" -> "@myXL"
            else -> sub?.carrier?.takeIf { it.isNotBlank() } ?: ""
        }
        val slot = if (sub == null) "" else RadioChartLabels.slotLabel(RadioChartLabels.slotId(plmn, sub.slot))
        return JSONObject()
            .put("transport", snap?.network?.transport ?: "unknown")
            .put("plmn", plmn)
            .put("slot", slot)
            .put("carrier", sub?.carrier.orEmpty())
            .put("mention", mention)
            .put("radio", radioLine(snap, sub))
            .put("location", location(snap))
            .put("when", clock.format(Date(snap?.tsMs ?: System.currentTimeMillis())))
            .put("speed", JSONObject()
                .put("download_mbps", speed?.downloadMbps ?: JSONObject.NULL)
                .put("upload_mbps", speed?.uploadMbps ?: JSONObject.NULL)
                .put("ping_ms", speed?.pingMs ?: JSONObject.NULL)
                .put("transport", speed?.transport.orEmpty()))
            .put("video", JSONObject()
                .put("load_ms", video?.loadMs ?: JSONObject.NULL)
                .put("resolution", video?.resolution.orEmpty())
                .put("buffering_pct", video?.bufferingPct ?: JSONObject.NULL)
                .put("transport", video?.transport.orEmpty()))
    }

    private fun postComplaint(body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val path = "/crowd/complaint"
        val now = System.currentTimeMillis()
        val timestamp = (now / 1000L).toString()
        val nonce = "n" + CollectorClient.randomHex(16)
        val signature = CollectorClient.hmacHex(
            CollectorClient.hexToBytes(BuildConfig.HMAC_KEY_HEX),
            CollectorClient.canonical(path, timestamp, nonce, bytes),
        )
        val url = URL("https://${BuildConfig.COLLECTOR_HOST}$path")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 45_000
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Osm-Key-Id", BuildConfig.HMAC_KEY_ID)
            setRequestProperty("X-Osm-Timestamp", timestamp)
            setRequestProperty("X-Osm-Nonce", nonce)
            setRequestProperty("X-Osm-Signature", signature)
            setRequestProperty("Connection", "close")
        }
        connection.outputStream.use { it.write(bytes) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(120)}")
        return JSONObject(text)
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        for (pkg in listOf("com.twitter.android", "com.x.android")) {
            val targeted = Intent(send).setPackage(pkg)
            if (targeted.resolveActivity(context.packageManager) != null) {
                context.startActivity(targeted)
                return
            }
        }
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://twitter.com/intent/tweet?text=${Uri.encode(text)}"))
        context.startActivity(web)
    }

    private fun radioLine(snap: RadioSnapshot?, sub: SubscriptionRadio?): String {
        if (sub == null) return "No radio sample yet"
        val cells = snap?.cells.orEmpty().filter { it.chartServing && (it.subId == null || it.subId == sub.subId) }
        val lte = cells.filter { it.rat == "LTE" }.maxByOrNull { it.rsrp ?: Int.MIN_VALUE }
        val nr = cells.filter { it.rat == "NR" }.maxByOrNull { it.rsrp ?: Int.MIN_VALUE }
        val lteCh = sub.channel ?: lte?.earfcn
        val bw = if (sub.cellBandwidthKhz.isEmpty()) "" else " ${RadioRules.mhzLabel(sub.cellBandwidthKhz)}"
        val lteSnr = lte?.sinr?.let { " SNR $it dB" }.orEmpty()
        val nrCh = nr?.nrarfcn?.let { ", NR $it" }.orEmpty()
        val nrState = sub.nrState?.let { ", nrState $it" }.orEmpty()
        return "LTE ${lteCh ?: "—"}$lteSnr$nrCh$nrState$bw"
    }

    private fun location(snap: RadioSnapshot?): String {
        val loc = snap?.location ?: return ""
        val lat = loc.latitude
        val lon = loc.longitude
        if (loc.status != "available" || lat == null || lon == null) return ""
        return String.format(Locale.US, "%.5f, %.5f", lat, lon)
    }
}
