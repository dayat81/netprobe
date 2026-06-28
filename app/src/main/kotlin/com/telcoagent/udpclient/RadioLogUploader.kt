package com.telcoagent.udpclient

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

class RadioLogUploader {
    suspend fun upload(file: File): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val boundary = "----UdpProbe${System.currentTimeMillis()}"
            val conn = (URL(BuildConfig.NETPROBE_UPLOAD_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connectTimeout = 30_000
                readTimeout = 60_000
            }

            DataOutputStream(conn.outputStream).use { out ->
                fun writeField(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.writeBytes(value)
                    out.writeBytes("\r\n")
                }

                writeField("device_id", "${Build.MANUFACTURER} ${Build.MODEL}")
                writeField("app_version", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

                out.writeBytes("--$boundary\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n",
                )
                out.writeBytes("Content-Type: text/csv\r\n\r\n")
                FileInputStream(file).use { input -> input.copyTo(out) }
                out.writeBytes("\r\n")
                out.writeBytes("--$boundary--\r\n")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                return@withContext Result.failure(Exception("HTTP $code: $err"))
            }

            val body = conn.inputStream.bufferedReader().readText()
            val sessionId = runCatching { JSONObject(body).getInt("session_id") }.getOrDefault(-1)
            Result.success(sessionId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
