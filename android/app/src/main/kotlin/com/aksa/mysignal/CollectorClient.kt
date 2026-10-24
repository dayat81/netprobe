package com.aksa.mysignal

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HMAC-signed POSTs to the OpenSignal mirror collector, shared by MEASURE and nr-status uploads. */
object CollectorClient {
    const val MEASURE_PATH = "/v1/measurements"
    const val NR_STATUS_PATH = "/v1/nr-status"
    const val RUM_PATH = "/v1/rum"
    private val random = SecureRandom()

    @Volatile
    private var cachedDeviceId: String? = null

    val measureEnabled: Boolean get() = BuildConfig.MEASURE_ENABLED
    val nrStatusEnabled: Boolean get() = BuildConfig.REPORTING_ENABLED

    fun post(path: String, body: ByteArray, nowMs: Long = System.currentTimeMillis()): Int {
        val timestamp = (nowMs / 1000L).toString()
        val nonce = "n" + randomHex(16)
        val signature = hmacHex(hexToBytes(BuildConfig.HMAC_KEY_HEX), canonical(path, timestamp, nonce, body))
        val url = URL("https://${BuildConfig.COLLECTOR_HOST}$path")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Osm-Key-Id", BuildConfig.HMAC_KEY_ID)
            setRequestProperty("X-Osm-Timestamp", timestamp)
            setRequestProperty("X-Osm-Nonce", nonce)
            setRequestProperty("X-Osm-Signature", signature)
            setRequestProperty("Connection", "close")
        }
        try {
            connection.outputStream.use { it.write(body) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    fun postMeasurement(context: Context, plain: JSONObject): Int {
        val now = System.currentTimeMillis()
        val envelope = measurementEnvelope(
            deviceId = deviceId(context),
            batchId = "bat" + randomHex(12),
            sentAtMs = now,
            key = hexToBytes(BuildConfig.MEASURE_KEY_HEX),
            iv = randomBytes(12),
            plain = plain.toString().toByteArray(StandardCharsets.UTF_8),
        )
        return post(MEASURE_PATH, envelope.toString().toByteArray(StandardCharsets.UTF_8), now)
    }

    fun canonical(path: String, timestamp: String, nonce: String, body: ByteArray): String =
        "POST\n$path\n$timestamp\n$nonce\n${sha256Hex(body)}"

    /** AES-256-GCM with the tag appended; AAD binds device, batch and send time like the collector expects. */
    fun measurementEnvelope(
        deviceId: String,
        batchId: String,
        sentAtMs: Long,
        key: ByteArray,
        iv: ByteArray,
        plain: ByteArray,
    ): JSONObject {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("$deviceId\n$batchId\n$sentAtMs".toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(plain)
        val encoder = Base64.getEncoder()
        return JSONObject()
            .put("schema_version", 1)
            .put("device_id", deviceId)
            .put("batch_id", batchId)
            .put("sent_at_ms", sentAtMs)
            .put("alg", "AES-256-GCM")
            .put("iv_b64", encoder.encodeToString(iv))
            .put("ciphertext_b64", encoder.encodeToString(encrypted))
    }

    /** ANDROID_ID when usable, otherwise a random id persisted in app storage. */
    fun deviceId(context: Context): String {
        cachedDeviceId?.let { return it }
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.replace(Regex("[^A-Za-z0-9_-]"), "").orEmpty()
        val id = if (androidId.length >= 8) {
            androidId.take(64)
        } else {
            val file = File(context.filesDir, "mysignal-device-id")
            val stored = runCatching { file.readText().trim() }.getOrNull()
            if (stored != null && stored.length >= 8) {
                stored
            } else {
                ("dev" + randomHex(16)).also { runCatching { file.writeText(it) } }
            }
        }
        cachedDeviceId = id
        return id
    }

    fun randomHex(bytes: Int): String = hex(randomBytes(bytes))

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun sha256Hex(body: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(body))

    fun hmacHex(key: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return hex(mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)))
    }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "bad hex" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }
}
