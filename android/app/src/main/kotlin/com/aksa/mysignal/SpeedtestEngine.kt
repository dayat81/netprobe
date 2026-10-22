package com.aksa.mysignal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * HTTP speedtest against Azure OoklaServer-like endpoints.
 *
 * Aligned with Ookla RE on azvm: 4 streams, 5–15 s measure, discard ramp,
 * EMA live needle, trimmed-mean score over post-ramp 200 ms windows.
 */
class SpeedtestEngine(
    private val baseUrl: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pingCount: Int = 10,
    private val pingWarmup: Int = 2,
    private val streams: Int = 4,
    private val minDurationMs: Long = 5_000L,
    private val maxDurationMs: Long = 15_000L,
    private val rampMs: Long = 2_000L,
    private val sampleMs: Long = 200L,
    private val downloadChunk: Int = 4 * 1024 * 1024,
    private val uploadChunk: Int = 1 * 1024 * 1024,
    private val emaAlpha: Double = 0.35,
) {
    enum class Phase { IDLE, PING, DOWNLOAD, UPLOAD, DONE, FAILED }

    data class Progress(
        val phase: Phase,
        val liveMbps: Double = 0.0,
        val pingMs: Long? = null,
        val jitterMs: Long? = null,
        val downloadMbps: Double? = null,
        val uploadMbps: Double? = null,
        val error: String? = null,
        val serverLabel: String = "",
    )

    data class Result(
        val pingMs: Long,
        val jitterMs: Long,
        val downloadMbps: Double,
        val uploadMbps: Double,
        val serverLabel: String,
    )

    suspend fun run(onProgress: (Progress) -> Unit): Result = withContext(io) {
        val root = baseUrl.trimEnd('/')
        onProgress(Progress(Phase.PING, serverLabel = root))
        val pingSamples = ping(root)
        val pingMs = SpeedtestMath.medianMs(pingSamples)
        val jitterMs = SpeedtestMath.jitterMs(pingSamples)
        onProgress(
            Progress(Phase.DOWNLOAD, pingMs = pingMs, jitterMs = jitterMs, serverLabel = root),
        )
        val download = throughput(root, download = true, onMbps = { mbps ->
            onProgress(
                Progress(
                    Phase.DOWNLOAD,
                    liveMbps = mbps,
                    pingMs = pingMs,
                    jitterMs = jitterMs,
                    serverLabel = root,
                ),
            )
        })
        onProgress(
            Progress(
                Phase.UPLOAD,
                pingMs = pingMs,
                jitterMs = jitterMs,
                downloadMbps = download,
                serverLabel = root,
            ),
        )
        val upload = throughput(root, download = false, onMbps = { mbps ->
            onProgress(
                Progress(
                    Phase.UPLOAD,
                    liveMbps = mbps,
                    pingMs = pingMs,
                    jitterMs = jitterMs,
                    downloadMbps = download,
                    serverLabel = root,
                ),
            )
        })
        val result = Result(pingMs, jitterMs, download, upload, root)
        onProgress(
            Progress(
                Phase.DONE,
                liveMbps = 0.0,
                pingMs = pingMs,
                jitterMs = jitterMs,
                downloadMbps = download,
                uploadMbps = upload,
                serverLabel = root,
            ),
        )
        result
    }

    private suspend fun ping(root: String): List<Long> {
        val samples = ArrayList<Long>(pingCount)
        repeat(pingCount) { index ->
            if (!coroutineContext.isActive) return samples
            val started = System.nanoTime()
            val connection = open(helloUrl(root), "GET")
            try {
                connection.connect()
                drain(connection.inputStream)
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("ping HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
            val ms = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
            if (index >= pingWarmup) samples += ms
            yield()
        }
        if (samples.isEmpty()) throw IllegalStateException("no ping samples")
        return samples
    }

    private suspend fun throughput(
        root: String,
        download: Boolean,
        onMbps: (Double) -> Unit,
    ): Double = coroutineScope {
        val bytes = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val started = System.nanoTime()
        val scoreSamples = CopyOnWriteArrayList<Double>()
        val workers = List(streams) {
            async(io) {
                while (!stop.get() && coroutineContext.isActive) {
                    val n = if (download) downloadOnce(root) else uploadOnce(root)
                    bytes.addAndGet(n.toLong())
                }
            }
        }
        val ticker = async(io) {
            var lastBytes = 0L
            var lastElapsed = 0L
            var live = 0.0
            try {
                while (!stop.get() && coroutineContext.isActive) {
                    delay(sampleMs)
                    val total = bytes.get()
                    val elapsed = (System.nanoTime() - started) / 1_000_000L
                    val windowMs = (elapsed - lastElapsed).coerceAtLeast(1L)
                    val window = SpeedtestMath.mbps(total - lastBytes, windowMs)
                    lastBytes = total
                    lastElapsed = elapsed
                    if (elapsed >= rampMs) {
                        scoreSamples += window
                    }
                    live = if (live <= 0.0) window else emaAlpha * window + (1.0 - emaAlpha) * live
                    onMbps(live)
                    val pastMin = elapsed >= minDurationMs
                    val pastMax = elapsed >= maxDurationMs
                    val enough = scoreSamples.size >= ((minDurationMs - rampMs) / sampleMs).toInt().coerceAtLeast(5)
                    if (pastMax || (pastMin && enough)) stop.set(true)
                }
            } finally {
                stop.set(true)
            }
        }
        try {
            workers.awaitAll()
            ticker.await()
        } finally {
            stop.set(true)
        }
        val scored = SpeedtestMath.trimmedMeanMbps(scoreSamples.toList())
        if (scored > 0.0) scored
        else {
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            SpeedtestMath.mbps(bytes.get(), elapsed)
        }
    }

    private fun downloadOnce(root: String): Int {
        val connection = open(downloadUrl(root, downloadChunk), "GET")
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("download HTTP ${connection.responseCode}")
            }
            return drain(connection.inputStream)
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadOnce(root: String): Int {
        val payload = ByteArray(uploadChunk)
        val connection = open(uploadUrl(root), "POST")
        try {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.connect()
            connection.outputStream.use { it.write(payload) }
            drain(connection.inputStream)
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("upload HTTP ${connection.responseCode}")
            }
            return payload.size
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept-Encoding", "identity")
        }

    private fun drain(stream: InputStream): Int {
        val buf = ByteArray(256 * 1024)
        var total = 0
        stream.use {
            while (true) {
                val n = it.read(buf)
                if (n <= 0) break
                total += n
            }
        }
        return total
    }

    companion object {
        fun helloUrl(base: String): String = base.trimEnd('/') + "/hello"
        fun downloadUrl(base: String, size: Int): String = base.trimEnd('/') + "/download?size=$size"
        fun uploadUrl(base: String): String = base.trimEnd('/') + "/upload"

        fun defaultBaseUrl(): String =
            "https://${BuildConfig.COLLECTOR_HOST}${BuildConfig.SPEEDTEST_PATH}"
    }
}
