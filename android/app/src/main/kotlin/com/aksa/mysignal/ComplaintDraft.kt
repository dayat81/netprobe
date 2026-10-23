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
        val server: String = "",
        val ipStack: String = "",
        val provider: String = "",
        val publicIp: String = "",
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
        val stack = result.ipFamily.label
        val public = ClientNetworkInfo.lastPublic
        val provider = public?.provider?.takeIf { it.isNotBlank() && it != "—" }.orEmpty()
        val publicIp = public?.ip?.takeIf { it.isNotBlank() && it != "—" }.orEmpty()
        shownSpeed = Speed(
            System.currentTimeMillis(),
            result.downloadMbps,
            result.uploadMbps,
            result.pingMs,
            result.jitterMs,
            transportNow(),
            result.serverLabel,
            stack,
            provider,
            publicIp,
        )
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("speed_ts", System.currentTimeMillis())
            .putFloat("speed_dl", result.downloadMbps.toFloat())
            .putFloat("speed_ul", result.uploadMbps.toFloat())
            .putLong("speed_ping", result.pingMs)
            .putLong("speed_jitter", result.jitterMs)
            .putString("speed_transport", transportNow())
            .putString("speed_server", result.serverLabel)
            .putString("speed_stack", stack)
            .putString("speed_provider", provider)
            .putString("speed_public_ip", publicIp)
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
            prefs.getString("speed_server", "").orEmpty(),
            prefs.getString("speed_stack", "").orEmpty(),
            prefs.getString("speed_provider", "").orEmpty(),
            prefs.getString("speed_public_ip", "").orEmpty(),
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
        val speed = ComplaintStore.currentSpeed()
        val video = ComplaintStore.currentVideo()
        if (speed == null && video == null) {
            return Review(
                false,
                "Run a Speed or Video test first.\nJev will classify the result; you decide whether to share.",
                "",
            )
        }
        val provider = resolveProvider(speed)
        val transport = testTransport(speed, video, snap)
        return try {
            val response = postComplaint(brief(snap, speed, video, provider, transport))
            val sentence = response.optString("sentence").trim()
            val summary = response.optString("summary")
            val url = normalizeCrowdUrl(response.optString("url"))
            val intent = response.optString("post_intent")
            val quality = response.optString("quality_band").ifBlank { "—" }
            val draftReady = sentence.isNotBlank()
            val headline = when {
                !draftReady -> "Draft unavailable"
                intent == "praise" -> "Draft · praise · quality $quality"
                intent == "complaint" -> "Draft · complaint · quality $quality"
                else -> "Draft · quality $quality"
            }
            val tip = if (draftReady) "\nShare is optional — you choose." else ""
            val statusLine = postStatusLine(transport, provider, snap)
            val tweet = when {
                !draftReady -> ""
                else -> listOf(sentence, statusLine, url)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            }
            // eligible = share button enabled; user decides whether to actually post.
            Review(draftReady, "$headline$tip\n$summary", tweet, reviewed = true)
        } catch (error: Exception) {
            Review(false, "Compose failed\n${error.message?.take(160) ?: "network"}", "")
        }
    }

    private fun testTransport(
        speed: ComplaintStore.Speed?,
        video: ComplaintStore.Video?,
        snap: RadioSnapshot?,
    ): String = listOf(speed?.transport, video?.transport, snap?.network?.transport)
        .map { it.orEmpty().trim() }
        .firstOrNull { it.isNotEmpty() && it != "unknown" }
        ?: "unknown"

    private fun isWifi(transport: String): Boolean =
        transport.equals("wifi", ignoreCase = true) || transport.equals("wi-fi", ignoreCase = true)

    private fun normalizeCrowdUrl(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.startsWith("/") -> "https://${BuildConfig.COLLECTOR_HOST}$value"
            else -> "https://${BuildConfig.COLLECTOR_HOST}/crowd/c/$value"
        }
    }

    private fun postStatusLine(transport: String, provider: String, snap: RadioSnapshot?): String {
        val via = if (isWifi(transport)) "Wi-Fi" else if (transport == "cellular") "cellular" else transport
        val isp = provider.ifBlank {
            snap?.primary?.carrier.orEmpty()
        }.ifBlank { "—" }
        val signal = signalStatus(transport, snap)
        return listOf("via $via", isp, signal)
            .filter { it.isNotBlank() && it != "—" }
            .distinct()
            .joinToString(" · ")
    }

    private fun signalStatus(transport: String, snap: RadioSnapshot?): String {
        if (isWifi(transport)) {
            val wifi = snap?.wifi ?: return ""
            if (wifi.status == WifiSignal.DISABLED.status || wifi.status == WifiSignal.NOT_ASSOCIATED.status) {
                return "Wi-Fi ${wifi.status}"
            }
            return listOfNotNull(
                wifi.rssiDbm?.let { "$it dBm" },
                wifi.linkSpeedMbps?.let { "link ${it} Mbps" },
                wifi.channel?.let { "ch $it" },
            ).joinToString(" · ")
        }
        val sub = snap?.primary ?: return ""
        val cells = snap?.cells.orEmpty().filter { it.chartServing && (it.subId == null || it.subId == sub.subId) }
        val lte = cells.filter { it.rat == "LTE" }.maxByOrNull { it.rsrp ?: Int.MIN_VALUE }
        val nr = cells.filter { it.rat == "NR" }.maxByOrNull { it.rsrp ?: Int.MIN_VALUE }
        return listOfNotNull(
            lte?.rsrp?.let { "LTE RSRP $it" },
            lte?.sinr?.let { "SINR $it" },
            nr?.rsrp?.let { "NR RSRP $it" },
            sub.nrState?.let { "nrState $it" },
        ).joinToString(" · ")
    }

    private fun resolveProvider(speed: ComplaintStore.Speed?): String {
        speed?.provider?.takeIf { it.isNotBlank() && it != "—" }?.let { return it }
        ClientNetworkInfo.lastProvider().takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    fun text(context: Context): String = review(context).draft

    private fun brief(
        snap: RadioSnapshot?,
        speed: ComplaintStore.Speed?,
        video: ComplaintStore.Video?,
        provider: String,
        transport: String,
    ): JSONObject {
        val sub = snap?.primary
        val plmn = sub?.plmn.orEmpty()
        val wifi = isWifi(transport)
        // On Wi-Fi tag the ISP from public IP; on cellular prefer SIM operator mention.
        val mention = if (wifi) {
            mentionForProvider(provider).ifEmpty { provider }
        } else {
            when (plmn) {
                "510-01" -> "@indosat"
                "510-10" -> "@Telkomsel"
                "510-11" -> "@myXL"
                "510-89" -> "@triindonesia"
                "510-09" -> "@smartfren"
                else -> mentionForProvider(provider).ifEmpty {
                    sub?.carrier?.takeIf { it.isNotBlank() }.orEmpty()
                }
            }.ifEmpty { mentionForProvider(provider) }
        }
        val carrier = if (wifi) {
            provider.ifBlank { sub?.carrier.orEmpty() }
        } else {
            sub?.carrier?.takeIf { it.isNotBlank() }.orEmpty().ifEmpty { provider }
        }
        val slot = if (wifi || sub == null) "" else RadioChartLabels.slotLabel(RadioChartLabels.slotId(plmn, sub.slot))
        val signal = signalStatus(transport, snap)
        val radio = if (wifi) {
            wifiLine(snap?.wifi)
        } else {
            radioLine(snap, sub)
        }
        return JSONObject()
            .put("transport", transport)
            .put("plmn", if (wifi) "" else plmn)
            .put("slot", slot)
            .put("carrier", carrier)
            .put("provider", provider.ifBlank { carrier })
            .put("public_ip", speed?.publicIp.orEmpty().ifEmpty {
                ClientNetworkInfo.lastPublic?.ip?.takeIf { it != "—" }.orEmpty()
            })
            .put("mention", mention)
            .put("signal", signal)
            .put("radio", radio)
            .put("location", location(snap))
            .put("when", clock.format(Date(snap?.tsMs ?: System.currentTimeMillis())))
            .put("speed", JSONObject()
                .put("download_mbps", speed?.downloadMbps ?: JSONObject.NULL)
                .put("upload_mbps", speed?.uploadMbps ?: JSONObject.NULL)
                .put("ping_ms", speed?.pingMs ?: JSONObject.NULL)
                .put("jitter_ms", speed?.jitterMs ?: JSONObject.NULL)
                .put("transport", speed?.transport.orEmpty().ifEmpty { transport })
                .put("server", speed?.server.orEmpty())
                .put("ip_stack", speed?.ipStack.orEmpty())
                .put("provider", speed?.provider.orEmpty().ifEmpty { provider }))
            .put("video", JSONObject()
                .put("load_ms", video?.loadMs ?: JSONObject.NULL)
                .put("resolution", video?.resolution.orEmpty())
                .put("buffering_pct", video?.bufferingPct ?: JSONObject.NULL)
                .put("transport", video?.transport.orEmpty().ifEmpty { transport }))
    }

    private fun wifiLine(wifi: WifiSignal?): String {
        if (wifi == null) return "Wi-Fi"
        if (wifi.status == WifiSignal.DISABLED.status || wifi.status == WifiSignal.NOT_ASSOCIATED.status) {
            return "Wi-Fi ${wifi.status}"
        }
        return listOfNotNull(
            "Wi-Fi",
            wifi.ssid.takeIf { it.isNotBlank() },
            wifi.rssiDbm?.let { "RSSI $it dBm" },
            wifi.linkSpeedMbps?.let { "link ${it} Mbps" },
            wifi.channel?.let { "ch $it" },
        ).joinToString(" · ")
    }

    private fun mentionForProvider(provider: String): String = when (provider.trim().lowercase()) {
        "telkomsel" -> "@Telkomsel"
        "indosat" -> "@indosat"
        "xl" -> "@myXL"
        "tri" -> "@triindonesia"
        "smartfren" -> "@smartfren"
        "telkom" -> "@TelkomIndonesia"
        else -> ""
    }

    private fun postComplaint(body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val path = "/crowd/compose"
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
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/intent/tweet?text=${Uri.encode(text)}"))
        context.startActivity(web)
    }

    private fun radioLine(snap: RadioSnapshot?, sub: SubscriptionRadio?): String {
        if (sub == null) return "No radio sample yet"
        val cells = snap?.cells.orEmpty().filter { it.chartServing && (it.subId == null || it.subId == sub.subId) }
        val lte = cells.filter { it.rat == "LTE" }.maxByOrNull { it.rsrp ?: Int.MIN_VALUE }
        val nr = cells.filter { it.rat == "NR" }.maxByOrNull { it.rsrp ?: Int.MIN_VALUE }
        val lteCh = sub.channel ?: lte?.earfcn
        val bw = if (sub.cellBandwidthKhz.isEmpty()) "" else " ${RadioRules.mhzLabel(sub.cellBandwidthKhz)}"
        val rsrp = lte?.rsrp?.let { " RSRP $it" }.orEmpty()
        val lteSnr = lte?.sinr?.let { " SINR $it dB" }.orEmpty()
        val nrCh = nr?.nrarfcn?.let { ", NR $it" }.orEmpty()
        val nrRsrp = nr?.rsrp?.let { " RSRP $it" }.orEmpty()
        val nrState = sub.nrState?.let { ", nrState $it" }.orEmpty()
        return "LTE ${lteCh ?: "—"}$rsrp$lteSnr$nrCh$nrRsrp$nrState$bw"
    }

    private fun location(snap: RadioSnapshot?): String {
        val loc = snap?.location ?: return ""
        val lat = loc.latitude
        val lon = loc.longitude
        if (loc.status != "available" || lat == null || lon == null) return ""
        return String.format(Locale.US, "%.5f, %.5f", lat, lon)
    }
}
