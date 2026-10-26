package com.aksa.magnon

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Sends one finished capture to a private bucket through a signed upload URL. */
internal object PcapUpload {
    private const val LIMIT = 50L * 1024L * 1024L

    fun send(context: Context, file: File): String? {
        if (!file.isFile) return null
        val size = file.length()
        if (size <= 0L || size > LIMIT) return null
        val token = MagnonAccount.session(context) ?: return null
        val slot = MagnonAccount.post(
            context,
            "/api/magnon/pcap/slot",
            JSONObject()
                .put("device_id", CollectorClient.deviceId(context))
                .put("bytes", size),
            token,
        )
        val upload = slot.optString("upload_url")
        val objectName = slot.optString("object")
        if (upload.isBlank() || objectName.isBlank() || slot.has("error")) return null
        if (!put(upload, file, size)) return null
        return objectName
    }

    private fun put(upload: String, file: File, size: Long): Boolean {
        val connection = (URL(upload).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setFixedLengthStreamingMode(size)
            setRequestProperty("Content-Type", "application/vnd.tcpdump.pcap")
            setRequestProperty("Content-Range", "bytes 0-${size - 1}/$size")
        }
        return try {
            file.inputStream().use { input -> connection.outputStream.use { input.copyTo(it) } }
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}
