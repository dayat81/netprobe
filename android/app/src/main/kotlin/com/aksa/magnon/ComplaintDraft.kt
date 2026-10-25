package com.aksa.magnon

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

    data class Sample(val tMs: Long, val mbps: Double)

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
        val downloadSamples: List<Sample> = emptyList(),
        val uploadSamples: List<Sample> = emptyList(),
        val kecamatan: String = "",
        val kelurahan: String = "",
        val kabupaten: String = "",
        val provinsi: String = "",
        val latitude: Double? = null,
        val longitude: Double? = null,
    )
    data class Stage(
        val resolution: String,
        val loadMs: Long,
        val playedMs: Long,
        val bufferMs: Long,
    )
    data class Video(
        val tsMs: Long,
        val loadMs: Long,
        val resolution: String,
        val bufferingPct: Double,
        val transport: String,
        val stages: List<Stage> = emptyList(),
        val kecamatan: String = "",
        val kelurahan: String = "",
        val kabupaten: String = "",
        val provinsi: String = "",
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    @Volatile
    private var shownSpeed: Speed? = null

    @Volatile
    private var shownVideo: Video? = null

    fun currentSpeed(): Speed? = shownSpeed

    fun currentVideo(): Video? = shownVideo

    fun postLanguage(context: Context): String {
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("post_language", "en")
        return if (saved == "id") "id" else "en"
    }

    fun rememberLanguage(context: Context, language: String) {
        val value = if (language == "id") "id" else "en"
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("post_language", value)
            .apply()
    }

    fun postStyle(context: Context): String {
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("post_style", "biasa")
        return if (saved in setOf("biasa", "santai", "serius", "gaul")) saved!! else "biasa"
    }

    fun rememberStyle(context: Context, style: String) {
        val value = if (style in setOf("biasa", "santai", "serius", "gaul")) style else "biasa"
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("post_style", value)
            .apply()
    }

    fun autoTag(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("post_auto_tag", true)

    fun rememberAutoTag(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("post_auto_tag", enabled)
            .apply()
    }

    fun saveSpeed(
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
        val stack = result.ipFamily.label
        val public = ClientNetworkInfo.lastPublic
        val provider = public?.provider?.takeIf { it.isNotBlank() && it != "—" }.orEmpty()
        val publicIp = public?.ip?.takeIf { it.isNotBlank() && it != "—" }.orEmpty()
        val samplesDown = result.downloadSamples.map { Sample(it.tMs, it.mbps) }
        val samplesUp = result.uploadSamples.map { Sample(it.tMs, it.mbps) }
        val place = kecamatan.trim().take(80)
        val village = kelurahan.trim().take(80)
        val regency = kabupaten.trim().take(80)
        val province = provinsi.trim().take(80)
        val ts = System.currentTimeMillis()
        val path = NetworkPath.canonical(transport)
        val saved = Speed(
            ts,
            result.downloadMbps,
            result.uploadMbps,
            result.pingMs,
            result.jitterMs,
            path,
            result.serverLabel,
            stack,
            provider,
            publicIp,
            samplesDown,
            samplesUp,
            place,
            village,
            regency,
            province,
            latitude = latitude?.takeIf { it.isFinite() },
            longitude = longitude?.takeIf { it.isFinite() },
        )
        shownSpeed = saved
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("speed_ts", ts)
            .putFloat("speed_dl", result.downloadMbps.toFloat())
            .putFloat("speed_ul", result.uploadMbps.toFloat())
            .putLong("speed_ping", result.pingMs)
            .putLong("speed_jitter", result.jitterMs)
            .putString("speed_transport", path)
            .putString("speed_server", result.serverLabel)
            .putString("speed_stack", stack)
            .putString("speed_provider", provider)
            .putString("speed_public_ip", publicIp)
            .putString("speed_kecamatan", place)
            .putString("speed_kelurahan", village)
            .putString("speed_kabupaten", regency)
            .putString("speed_provinsi", province)
            .putString("speed_lat", coordString(saved.latitude))
            .putString("speed_lon", coordString(saved.longitude))
            .putString("speed_download_samples", samplesJson(samplesDown))
            .putString("speed_upload_samples", samplesJson(samplesUp))
            .apply()
        appendSpeedHistory(prefs, saved)
    }

    private const val SPEED_HISTORY_MAX = 50

    private fun appendSpeedHistory(prefs: android.content.SharedPreferences, entry: Speed) {
        val existing = JSONArray(prefs.getString("speed_history", "[]"))
        val next = JSONArray()
        next.put(
            JSONObject()
                .put("ts", entry.tsMs)
                .put("dl", entry.downloadMbps)
                .put("ul", entry.uploadMbps)
                .put("ping", entry.pingMs)
                .put("jitter", entry.jitterMs)
                .put("transport", entry.transport)
                .put("server", entry.server.take(120))
                .put("stack", entry.ipStack)
                .put("provider", entry.provider.take(80))
                .put("public_ip", entry.publicIp.take(64))
                .put("kecamatan", entry.kecamatan.take(80))
                .put("kelurahan", entry.kelurahan.take(80))
                .put("kabupaten", entry.kabupaten.take(80))
                .put("provinsi", entry.provinsi.take(80))
                .put("lat", entry.latitude ?: JSONObject.NULL)
                .put("lon", entry.longitude ?: JSONObject.NULL),
        )
        for (index in 0 until minOf(existing.length(), SPEED_HISTORY_MAX - 1)) {
            next.put(existing.getJSONObject(index))
        }
        prefs.edit().putString("speed_history", next.toString()).apply()
    }

    fun speedHistory(context: Context): List<Speed> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("speed_history", "[]")
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Speed(
                item.optLong("ts"),
                item.optDouble("dl"),
                item.optDouble("ul"),
                item.optLong("ping"),
                item.optLong("jitter"),
                item.optString("transport"),
                item.optString("server"),
                item.optString("stack"),
                item.optString("provider"),
                item.optString("public_ip"),
                kecamatan = item.optString("kecamatan"),
                kelurahan = item.optString("kelurahan"),
                kabupaten = item.optString("kabupaten"),
                provinsi = item.optString("provinsi"),
                latitude = jsonCoord(item, "lat"),
                longitude = jsonCoord(item, "lon"),
            )
        }
    }

    fun saveVideo(
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
        val stages = result.stages.map { Stage(it.resolution, it.loadMs, it.playedMs, it.bufferMs) }
        val place = kecamatan.trim().take(80)
        val village = kelurahan.trim().take(80)
        val regency = kabupaten.trim().take(80)
        val province = provinsi.trim().take(80)
        val path = NetworkPath.canonical(transport)
        val ts = System.currentTimeMillis()
        val saved = Video(
            ts,
            result.loadTimeMs,
            result.maxResolution,
            result.bufferingPct,
            path,
            stages,
            place,
            village,
            regency,
            province,
            latitude = latitude?.takeIf { it.isFinite() },
            longitude = longitude?.takeIf { it.isFinite() },
        )
        shownVideo = saved
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("video_ts", ts)
            .putLong("video_load", result.loadTimeMs)
            .putString("video_res", result.maxResolution)
            .putFloat("video_buf", result.bufferingPct.toFloat())
            .putString("video_transport", path)
            .putString("video_kecamatan", place)
            .putString("video_kelurahan", village)
            .putString("video_kabupaten", regency)
            .putString("video_provinsi", province)
            .putString("video_lat", coordString(saved.latitude))
            .putString("video_lon", coordString(saved.longitude))
            .putString("video_stages", stagesJson(stages))
            .apply()
        appendVideoHistory(context, saved)
    }

    private const val VIDEO_HISTORY_MAX = 50

    private fun appendVideoHistory(context: Context, entry: Video) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = JSONArray(prefs.getString("video_history", "[]"))
        val next = JSONArray()
        next.put(
            JSONObject()
                .put("ts", entry.tsMs)
                .put("load", entry.loadMs)
                .put("res", entry.resolution)
                .put("buf", entry.bufferingPct)
                .put("transport", entry.transport)
                .put("kecamatan", entry.kecamatan.take(80))
                .put("kelurahan", entry.kelurahan.take(80))
                .put("kabupaten", entry.kabupaten.take(80))
                .put("provinsi", entry.provinsi.take(80))
                .put("lat", entry.latitude ?: JSONObject.NULL)
                .put("lon", entry.longitude ?: JSONObject.NULL),
        )
        for (index in 0 until minOf(existing.length(), VIDEO_HISTORY_MAX - 1)) {
            next.put(existing.getJSONObject(index))
        }
        prefs.edit().putString("video_history", next.toString()).apply()
    }

    fun videoHistory(context: Context): List<Video> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("video_history", "[]")
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Video(
                item.optLong("ts"),
                item.optLong("load"),
                item.optString("res"),
                item.optDouble("buf"),
                item.optString("transport"),
                kecamatan = item.optString("kecamatan"),
                kelurahan = item.optString("kelurahan"),
                kabupaten = item.optString("kabupaten"),
                provinsi = item.optString("provinsi"),
                latitude = jsonCoord(item, "lat"),
                longitude = jsonCoord(item, "lon"),
            )
        }
    }

    private fun samplesJson(samples: List<Sample>): String {
        val array = JSONArray()
        for (sample in samples) {
            array.put(JSONObject().put("t_ms", sample.tMs).put("mbps", sample.mbps))
        }
        return array.toString()
    }

    private fun stagesJson(stages: List<Stage>): String {
        val array = JSONArray()
        for (stage in stages) {
            array.put(
                JSONObject()
                    .put("resolution", stage.resolution)
                    .put("load_ms", stage.loadMs)
                    .put("played_ms", stage.playedMs)
                    .put("buffer_ms", stage.bufferMs),
            )
        }
        return array.toString()
    }

    private fun parseSamples(raw: String?): List<Sample> {
        val array = JSONArray(raw ?: "[]")
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Sample(item.optLong("t_ms"), item.optDouble("mbps"))
        }
    }

    private fun parseStages(raw: String?): List<Stage> {
        val array = JSONArray(raw ?: "[]")
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Stage(
                item.optString("resolution"),
                item.optLong("load_ms"),
                item.optLong("played_ms"),
                item.optLong("buffer_ms"),
            )
        }
    }

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
            parseSamples(prefs.getString("speed_download_samples", "[]")),
            parseSamples(prefs.getString("speed_upload_samples", "[]")),
            prefs.getString("speed_kecamatan", "").orEmpty(),
            prefs.getString("speed_kelurahan", "").orEmpty(),
            prefs.getString("speed_kabupaten", "").orEmpty(),
            prefs.getString("speed_provinsi", "").orEmpty(),
            latitude = readCoord(prefs.getString("speed_lat", "")),
            longitude = readCoord(prefs.getString("speed_lon", "")),
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
            parseStages(prefs.getString("video_stages", "[]")),
            prefs.getString("video_kecamatan", "").orEmpty(),
            prefs.getString("video_kelurahan", "").orEmpty(),
            prefs.getString("video_kabupaten", "").orEmpty(),
            prefs.getString("video_provinsi", "").orEmpty(),
            latitude = readCoord(prefs.getString("video_lat", "")),
            longitude = readCoord(prefs.getString("video_lon", "")),
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

    private fun coordString(value: Double?): String = value?.takeIf { it.isFinite() }?.toString().orEmpty()

    private fun readCoord(raw: String?): Double? = raw?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun jsonCoord(item: JSONObject, key: String): Double? {
        if (!item.has(key) || item.isNull(key)) return null
        return item.optDouble(key).takeIf { it.isFinite() }
    }
}

/** Complaint text for the active SIM, ready to read before it is sent to X. */
object ComplaintDraft {
    data class Review(val eligible: Boolean, val verdict: String, val draft: String, val reviewed: Boolean = false)

    private val clock = SimpleDateFormat("dd/MM HH:mm", Locale("id", "ID")).apply {
        timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    }

    fun review(context: Context): Review {
        val speed = ComplaintStore.currentSpeed() ?: ComplaintStore.speed(context)
        val video = ComplaintStore.currentVideo() ?: ComplaintStore.video(context)
        if (speed == null && video == null) {
            return Review(
                false,
                "Run a Speed or Video test first.\nJev will classify the result; you decide whether to share.",
                "",
            )
        }
        val provider = resolveProvider(speed)
        val transport = testTransport(speed, video)
        return try {
            val response = postComplaint(brief(context, speed, video, provider, transport))
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
            val tweet = when {
                !draftReady -> ""
                else -> listOf(sentence, url)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            }
            // eligible = share button enabled; user decides whether to actually post.
            Review(draftReady, "$headline$tip\n$summary", tweet, reviewed = true)
        } catch (error: Exception) {
            AppLog.w("MagnonPost", "compose failed", error)
            Review(false, "Compose failed\n${error.message?.take(160) ?: "network"}", "")
        }
    }

    private fun testTransport(
        speed: ComplaintStore.Speed?,
        video: ComplaintStore.Video?,
    ): String {
        val ranked = listOfNotNull(
            speed?.let { it.tsMs to NetworkPath.canonical(it.transport) },
            video?.let { it.tsMs to NetworkPath.canonical(it.transport) },
        ).sortedByDescending { it.first }
        return ranked.firstOrNull { it.second.isNotEmpty() }?.second ?: "unknown"
    }

    private fun draftTransport(transport: String): String = when (NetworkPath.canonical(transport)) {
        "wifi" -> "wifi"
        "cell" -> "cellular"
        else -> transport
    }

    private fun normalizeCrowdUrl(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.startsWith("/") -> "https://${BuildConfig.COLLECTOR_HOST}$value"
            else -> "https://${BuildConfig.COLLECTOR_HOST}/crowd/c/$value"
        }
    }


    private fun resolveProvider(speed: ComplaintStore.Speed?): String {
        speed?.provider?.takeIf { it.isNotBlank() && it != "—" }?.let { return it }
        ClientNetworkInfo.lastProvider().takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    fun text(context: Context): String = review(context).draft

    private fun brief(
        context: Context,
        speed: ComplaintStore.Speed?,
        video: ComplaintStore.Video?,
        provider: String,
        transport: String,
    ): JSONObject {
        val mention = mentionForProvider(provider).ifEmpty { provider }
        val auto = ComplaintStore.autoTag(context)
        val namedMention = if (auto) mention else ""
        val whenMs = speed?.tsMs ?: video?.tsMs ?: System.currentTimeMillis()
        return JSONObject()
            .put("language", ComplaintStore.postLanguage(context))
            .put("style", ComplaintStore.postStyle(context))
            .put("kelurahan", postedVillage(speed, video))
            .put("kecamatan", postedDistrict(speed, video))
            .put("kabupaten", postedRegency(speed, video))
            .put("provinsi", postedProvince(speed, video))
            .put("device_id", CollectorClient.deviceId(context))
            .apply {
                val fix = postedFix(speed, video)
                if (fix != null) {
                    put("latitude", fix.latitude)
                    put("longitude", fix.longitude)
                }
            }
            .put("transport", draftTransport(transport))
            .put("plmn", "")
            .put("slot", "")
            .put("carrier", provider)
            .put("provider", provider)
            .put("public_ip", speed?.publicIp.orEmpty().ifEmpty {
                ClientNetworkInfo.lastPublic?.ip?.takeIf { it != "—" }.orEmpty()
            })
            .put("mention", namedMention)
            .put("signal", "")
            .put("radio", "")
            .put("when", clock.format(Date(whenMs)))
            .put("speed", JSONObject()
                .put("download_mbps", speed?.downloadMbps ?: JSONObject.NULL)
                .put("upload_mbps", speed?.uploadMbps ?: JSONObject.NULL)
                .put("ping_ms", speed?.pingMs ?: JSONObject.NULL)
                .put("jitter_ms", speed?.jitterMs ?: JSONObject.NULL)
                .put("transport", speed?.transport.orEmpty().ifEmpty { transport })
                .put("server", speed?.server.orEmpty())
                .put("ip_stack", speed?.ipStack.orEmpty())
                .put("provider", speed?.provider.orEmpty().ifEmpty { provider })
                .put("samples", JSONObject()
                    .put("download", sampleArray(speed?.downloadSamples))
                    .put("upload", sampleArray(speed?.uploadSamples))))
            .put("video", JSONObject()
                .put("load_ms", video?.loadMs ?: JSONObject.NULL)
                .put("resolution", video?.resolution.orEmpty())
                .put("buffering_pct", video?.bufferingPct ?: JSONObject.NULL)
                .put("transport", video?.transport.orEmpty().ifEmpty { transport })
                .put("stages", stageArray(video?.stages)))
    }

    private fun sampleArray(samples: List<ComplaintStore.Sample>?): JSONArray {
        val array = JSONArray()
        for (sample in samples.orEmpty()) {
            array.put(JSONObject().put("t_ms", sample.tMs).put("mbps", sample.mbps))
        }
        return array
    }

    private fun stageArray(stages: List<ComplaintStore.Stage>?): JSONArray {
        val array = JSONArray()
        for (stage in stages.orEmpty()) {
            array.put(
                JSONObject()
                    .put("resolution", stage.resolution)
                    .put("load_ms", stage.loadMs)
                    .put("played_ms", stage.playedMs)
                    .put("buffer_ms", stage.bufferMs),
            )
        }
        return array
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


    private data class Fix(val tsMs: Long, val latitude: Double, val longitude: Double)

    private fun postedFix(speed: ComplaintStore.Speed?, video: ComplaintStore.Video?): Fix? {
        val found = mutableListOf<Fix>()
        val speedLat = speed?.latitude
        val speedLon = speed?.longitude
        if (speed != null && speedLat != null && speedLon != null) {
            found.add(Fix(speed.tsMs, speedLat, speedLon))
        }
        val videoLat = video?.latitude
        val videoLon = video?.longitude
        if (video != null && videoLat != null && videoLon != null) {
            found.add(Fix(video.tsMs, videoLat, videoLon))
        }
        return found.maxByOrNull { it.tsMs }
    }

    private data class RankedPlace(
        val tsMs: Long,
        val kelurahan: String,
        val kecamatan: String,
        val kabupaten: String,
        val provinsi: String,
    )

    private fun postedDistrict(speed: ComplaintStore.Speed?, video: ComplaintStore.Video?): String =
        postedPlace(speed, video).kecamatan

    private fun postedVillage(speed: ComplaintStore.Speed?, video: ComplaintStore.Video?): String =
        postedPlace(speed, video).kelurahan

    private fun postedRegency(speed: ComplaintStore.Speed?, video: ComplaintStore.Video?): String =
        postedPlace(speed, video).kabupaten

    private fun postedProvince(speed: ComplaintStore.Speed?, video: ComplaintStore.Video?): String =
        postedPlace(speed, video).provinsi

    private fun postedPlace(speed: ComplaintStore.Speed?, video: ComplaintStore.Video?): RankedPlace {
        val ranked = listOfNotNull(
            speed?.let {
                RankedPlace(it.tsMs, it.kelurahan.trim(), it.kecamatan.trim(), it.kabupaten.trim(), it.provinsi.trim())
            },
            video?.let {
                RankedPlace(it.tsMs, it.kelurahan.trim(), it.kecamatan.trim(), it.kabupaten.trim(), it.provinsi.trim())
            },
        ).sortedByDescending { it.tsMs }
        return ranked.firstOrNull {
            it.kelurahan.isNotEmpty() || it.kecamatan.isNotEmpty() || it.kabupaten.isNotEmpty() || it.provinsi.isNotEmpty()
        } ?: RankedPlace(0, "", "", "", "")
    }
}
