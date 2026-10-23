package com.aksa.mysignal

import android.util.Log
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * HTTP speedtest against Azure OoklaServer-like endpoints.
 *
 * 4 streams, silent warmup then 12–25 s measure, ramp discard, slew-limited EMA
 * live needle, trimmed-mean score. Keep-alive HTTPS so TLS is not counted every chunk.
 * [target] forces IPv4 or IPv6 peer while SNI stays on the collector hostname.
 */
class SpeedtestEngine(
    private val target: SpeedtestTarget,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pingCount: Int = 10,
    private val pingWarmup: Int = 2,
    private val streams: Int = 4,
    private val warmupMs: Long = 2_000L,
    private val minDurationMs: Long = 12_000L,
    private val maxDurationMs: Long = 25_000L,
    private val rampMs: Long = 2_000L,
    private val sampleMs: Long = 200L,
    /** Final score uses 1 s goodput samples (gaps average out vs 200 ms). */
    private val scoreIntervalMs: Long = 1_000L,
    /** Keep-alive reuses the socket; size balances fill rate vs post-stop overrun. */
    private val downloadChunk: Int = 8 * 1024 * 1024,
    private val uploadChunk: Int = 1 * 1024 * 1024,
    /** Soft EMA on a 1 s sliding average — 200 ms windows alone spike the needle. */
    private val emaAlpha: Double = 0.18,
    private val liveSmoothMs: Long = 1_000L,
    /** Cap needle climb per 200 ms tick so the first measure window cannot jump to 100+. */
    private val maxRiseMbpsPerTick: Double = 12.0,
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
        val ipFamily: IpFamily? = null,
        val serverIp: String = "",
    )

    data class Result(
        val pingMs: Long,
        val jitterMs: Long,
        val downloadMbps: Double,
        val uploadMbps: Double,
        val serverLabel: String,
        val ipFamily: IpFamily,
        val serverIp: String,
    )

    /** Shared zero-fill upload body — avoid per-request allocations / GC stalls. */
    private val uploadPayload = ByteArray(uploadChunk)

    private fun progress(
        phase: Phase,
        liveMbps: Double = 0.0,
        pingMs: Long? = null,
        jitterMs: Long? = null,
        downloadMbps: Double? = null,
        uploadMbps: Double? = null,
        error: String? = null,
    ) = Progress(
        phase = phase,
        liveMbps = liveMbps,
        pingMs = pingMs,
        jitterMs = jitterMs,
        downloadMbps = downloadMbps,
        uploadMbps = uploadMbps,
        error = error,
        serverLabel = target.serverLabel,
        ipFamily = target.family,
        serverIp = target.serverIp,
    )

    suspend fun run(onProgress: (Progress) -> Unit): Result = withContext(io) {
        val root = target.root()
        onProgress(progress(Phase.PING))
        val pingSamples = ping(root)
        val pingMs = SpeedtestMath.medianMs(pingSamples)
        val jitterMs = SpeedtestMath.jitterMs(pingSamples)
        Log.i(
            TAG,
            "ping ${target.family.label} ${target.serverIp} " +
                "median=${pingMs}ms jitter=${jitterMs}ms samples=$pingSamples",
        )
        onProgress(progress(Phase.DOWNLOAD, pingMs = pingMs, jitterMs = jitterMs))
        val download = throughput(root, download = true, onMbps = { mbps ->
            onProgress(
                progress(
                    Phase.DOWNLOAD,
                    liveMbps = mbps,
                    pingMs = pingMs,
                    jitterMs = jitterMs,
                ),
            )
        })
        onProgress(
            progress(
                Phase.UPLOAD,
                pingMs = pingMs,
                jitterMs = jitterMs,
                downloadMbps = download,
            ),
        )
        val upload = throughput(root, download = false, onMbps = { mbps ->
            onProgress(
                progress(
                    Phase.UPLOAD,
                    liveMbps = mbps,
                    pingMs = pingMs,
                    jitterMs = jitterMs,
                    downloadMbps = download,
                ),
            )
        })
        Log.i(
            TAG,
            "score ${target.family.label} dl=${"%.2f".format(download)} ul=${"%.2f".format(upload)} Mbps",
        )
        val result = Result(
            pingMs = pingMs,
            jitterMs = jitterMs,
            downloadMbps = download,
            uploadMbps = upload,
            serverLabel = target.serverLabel,
            ipFamily = target.family,
            serverIp = target.serverIp,
        )
        onProgress(
            progress(
                Phase.DONE,
                pingMs = pingMs,
                jitterMs = jitterMs,
                downloadMbps = download,
                uploadMbps = upload,
            ),
        )
        result
    }

    /**
     * Warm TLS once, then time keep-alive GETs. Never [HttpURLConnection.disconnect]
     * between samples so the pool reuses the socket.
     */
    private suspend fun ping(root: String): List<Long> {
        val samples = ArrayList<Long>(pingCount)
        repeat(pingWarmup) {
            if (!coroutineContext.isActive) return samples
            helloOnce(root)
            yield()
        }
        repeat(pingCount) {
            if (!coroutineContext.isActive) return samples
            val started = System.nanoTime()
            helloOnce(root)
            val ms = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
            samples += ms
            yield()
        }
        if (samples.isEmpty()) throw IllegalStateException("no ping samples")
        return samples
    }

    private fun helloOnce(root: String) {
        val connection = open(helloUrl(root), "GET")
        connection.connect()
        drain(connection.inputStream)
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("ping HTTP ${connection.responseCode}")
        }
    }

    private suspend fun throughput(
        root: String,
        download: Boolean,
        onMbps: (Double) -> Unit,
    ): Double = coroutineScope {
        val bytes = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val wallStarted = System.nanoTime()
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
            var measuring = false
            var measureStarted = 0L
            var lastBytes = 0L
            var lastElapsed = 0L
            var scoreMarkBytes = 0L
            var scoreMarkElapsed = 0L
            var live = 0.0
            var peakWindow = 0.0
            var peakLive = 0.0
            val liveMarks = ArrayDeque<Pair<Long, Long>>()
            val minScoreSamples =
                ((minDurationMs - rampMs) / scoreIntervalMs).toInt().coerceAtLeast(5)
            try {
                while (!stop.get() && coroutineContext.isActive) {
                    delay(sampleMs)
                    val total = bytes.get()
                    val wallElapsed = (System.nanoTime() - wallStarted) / 1_000_000L

                    if (!measuring) {
                        if (wallElapsed < warmupMs) {
                            continue
                        }
                        measuring = true
                        measureStarted = System.nanoTime()
                        lastBytes = total
                        lastElapsed = 0L
                        scoreMarkBytes = total
                        scoreMarkElapsed = 0L
                        live = 0.0
                        liveMarks.clear()
                        liveMarks.addLast(0L to total)
                        onMbps(0.0)
                        continue
                    }

                    val elapsed = (System.nanoTime() - measureStarted) / 1_000_000L
                    val windowMs = (elapsed - lastElapsed).coerceAtLeast(1L)
                    val deltaBytes = (total - lastBytes).coerceAtLeast(0L)
                    val window = SpeedtestMath.mbps(deltaBytes, windowMs)
                    lastBytes = total
                    lastElapsed = elapsed
                    if (window > peakWindow) peakWindow = window

                    if (elapsed >= rampMs) {
                        if (scoreMarkElapsed < rampMs) {
                            scoreMarkBytes = total
                            scoreMarkElapsed = elapsed
                        } else if (elapsed - scoreMarkElapsed >= scoreIntervalMs) {
                            val scoreMs = (elapsed - scoreMarkElapsed).coerceAtLeast(1L)
                            val scoreDelta = (total - scoreMarkBytes).coerceAtLeast(0L)
                            scoreSamples += SpeedtestMath.mbps(scoreDelta, scoreMs)
                            scoreMarkBytes = total
                            scoreMarkElapsed = elapsed
                        }
                    }

                    liveMarks.addLast(elapsed to total)
                    while (liveMarks.size > 1 && liveMarks.first().first < elapsed - liveSmoothMs) {
                        liveMarks.removeFirst()
                    }
                    val (t0, b0) = liveMarks.first()
                    val smooth = SpeedtestMath.mbps(
                        (total - b0).coerceAtLeast(0L),
                        (elapsed - t0).coerceAtLeast(1L),
                    )
                    val targetLive = if (live <= 0.0) {
                        smooth.coerceAtMost(maxRiseMbpsPerTick)
                    } else {
                        emaAlpha * smooth + (1.0 - emaAlpha) * live
                    }
                    live = when {
                        targetLive > live -> minOf(targetLive, live + maxRiseMbpsPerTick)
                        else -> targetLive
                    }
                    if (live > peakLive) peakLive = live
                    onMbps(live)

                    val pastMin = elapsed >= minDurationMs
                    val pastMax = elapsed >= maxDurationMs
                    val enough = scoreSamples.size >= minScoreSamples
                    if (pastMax || (pastMin && enough)) stop.set(true)
                }
            } finally {
                stop.set(true)
                val list = scoreSamples.toList()
                val scored = SpeedtestMath.stableMbps(list)
                val p50 = SpeedtestMath.medianDouble(list.sorted())
                Log.i(
                    TAG,
                    "${if (download) "dl" else "ul"} ${target.family.label} warmup=${warmupMs}ms " +
                        "peak200=${"%.1f".format(peakWindow)} peakLive=${"%.1f".format(peakLive)} " +
                        "p50=${"%.1f".format(p50)} score=${"%.1f".format(scored)} n=${list.size}",
                )
            }
        }
        try {
            workers.awaitAll()
            ticker.await()
        } finally {
            stop.set(true)
        }
        val scored = SpeedtestMath.stableMbps(scoreSamples.toList())
        if (scored > 0.0) scored
        else {
            val elapsed = if (scoreSamples.isEmpty()) {
                ((System.nanoTime() - wallStarted) / 1_000_000L).coerceAtLeast(1L)
            } else {
                minDurationMs
            }
            SpeedtestMath.mbps(bytes.get(), elapsed)
        }
    }

    private fun downloadOnce(root: String): Int {
        val connection = open(downloadUrl(root, downloadChunk), "GET")
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("download HTTP ${connection.responseCode}")
        }
        return drain(connection.inputStream)
    }

    private fun uploadOnce(root: String): Int {
        val connection = open(uploadUrl(root), "POST")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(uploadPayload.size)
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.connect()
        connection.outputStream.use { it.write(uploadPayload) }
        drain(connection.inputStream)
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("upload HTTP ${connection.responseCode}")
        }
        return uploadPayload.size
    }

    private fun open(url: String, method: String): HttpURLConnection =
        SpeedtestHttps.open(target, url, method)

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
        private const val TAG = "MySignalSpeed"

        fun helloUrl(base: String): String = base.trimEnd('/') + "/hello"
        fun downloadUrl(base: String, size: Int): String = base.trimEnd('/') + "/download?size=$size"
        fun uploadUrl(base: String): String = base.trimEnd('/') + "/upload"

        fun defaultBaseUrl(): String =
            "https://${BuildConfig.COLLECTOR_HOST}${BuildConfig.SPEEDTEST_PATH}"
    }
}
