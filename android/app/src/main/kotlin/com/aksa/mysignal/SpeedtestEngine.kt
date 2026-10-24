package com.aksa.mysignal

import android.net.TrafficStats
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import kotlin.coroutines.coroutineContext

/**
 * HTTP speedtest against Azure OoklaServer-like endpoints.
 *
 * REA of Ookla `libandroidsharedsuite` (ThreadedStage + ConnectionScaling): multi
 * TCP connections that stay open and scale. Download uses staggered persistent
 * GET streams; upload uses long fixed-length POSTs. On stop we disconnect live
 * sockets so blocked write/read cannot hang the suite (Ookla ends upload cleanly).
 * Latency uses one keep-alive TLS socket and times TTFB only.
 */
class SpeedtestEngine(
    private val target: SpeedtestTarget,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pingCount: Int = 16,
    private val pingWarmup: Int = 6,
    /** Drop cold measured samples before idle/jitter (TLS settle). */
    private val pingSettleDrop: Int = 4,
    /**
     * Classic Ookla docs: up to 4 HTTP threads; native ConnectionScaling goes higher.
     * 8/4 sits between — 12 streams were overshooting Ookla UI results on the same radio.
     */
    private val streams: Int = 8,
    private val uploadStreams: Int = 4,
    /** Open this many sockets first, then add the rest in waves. */
    private val streamStartBatch: Int = 4,
    private val streamStaggerMs: Long = 250L,
    private val warmupMs: Long = 1_500L,
    /** Shorter upload warmup — needle left at 0 felt like a hang vs Ookla. */
    private val uploadWarmupMs: Long = 600L,
    private val minDurationMs: Long = 10_000L,
    private val maxDurationMs: Long = 22_000L,
    private val rampMs: Long = 1_200L,
    private val uploadRampMs: Long = 800L,
    private val sampleMs: Long = 200L,
    /** Half-second goodput samples — more points, earlier stop when stable. */
    private val scoreIntervalMs: Long = 500L,
    /** Upload score uses 1 s windows so HTTP body gaps average out. */
    private val uploadScoreIntervalMs: Long = 1_000L,
    /** Per request stays under Cloud Run's 32 MiB body cap; the phase loops until time/bytes. */
    private val downloadChunk: Int = 16 * 1024 * 1024,
    private val uploadBodyBytes: Int = 16 * 1024 * 1024,
    private val readBufBytes: Int = 1024 * 1024,
    /** Smaller writes → steadier TCP pacing on uplink (less 0/500 Mbps sawtooth). */
    private val uploadWriteBytes: Int = 256 * 1024,
    /** Soft EMA on a sliding average — 200 ms windows alone spike the needle. */
    private val emaAlpha: Double = 0.18,
    private val liveSmoothMs: Long = 1_200L,
    /** Upload needle: longer window + slow rise/fall like Ookla. */
    private val uploadEmaAlpha: Double = 0.12,
    private val uploadLiveSmoothMs: Long = 2_000L,
    /** Cap needle climb per 200 ms tick so the first measure window cannot jump to 100+. */
    private val maxRiseMbpsPerTick: Double = 14.0,
    private val uploadMaxRiseMbpsPerTick: Double = 12.0,
    private val uploadMaxFallMbpsPerTick: Double = 6.0,
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
        val pingSamples = ping()
        val pingMs = SpeedtestMath.idlePingMs(pingSamples)
        val jitterMs = SpeedtestMath.jitterMs(pingSamples)
        Log.i(
            TAG,
            "ping ${target.family.label} ${target.serverIp} " +
                "idle=${pingMs}ms jitter=${jitterMs}ms samples=$pingSamples",
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
     * One TLS socket, keep-alive GET /hello, time TTFB only (body drain outside the
     * stopwatch). Avoids HttpURLConnection pool / header parsing overhead between samples.
     */
    private suspend fun ping(): List<Long> {
        val path = helloPath()
        val request = buildHelloRequest(path)
        val socket: SSLSocket = SpeedtestHttps.openSocket(target)
        try {
            val out = socket.outputStream
            val inp = BufferedInputStream(socket.inputStream, 8 * 1024)
            repeat(pingWarmup) {
                if (!coroutineContext.isActive) return emptyList()
                helloExchange(out, inp, request)
                yield()
            }
            val raw = ArrayList<Long>(pingCount)
            repeat(pingCount) {
                if (!coroutineContext.isActive) return settlePingSamples(raw)
                val started = System.nanoTime()
                val contentLength = writeAndReadHeaders(out, inp, request)
                val ms = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
                drainExact(inp, contentLength)
                raw += ms
                yield()
            }
            return settlePingSamples(raw)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun helloPath(): String {
        val prefix = target.pathPrefix.trimEnd('/')
        val base = if (prefix.isEmpty() || prefix == "/") "" else prefix
        return "$base/hello"
    }

    private fun buildHelloRequest(path: String): ByteArray {
        val p = if (path.startsWith("/")) path else "/$path"
        return (
            "GET $p HTTP/1.1\r\n" +
                "Host: ${target.hostname}\r\n" +
                "Connection: keep-alive\r\n" +
                "Accept-Encoding: identity\r\n" +
                "Cache-Control: no-cache\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
    }

    private fun helloExchange(out: OutputStream, inp: InputStream, request: ByteArray) {
        val len = writeAndReadHeaders(out, inp, request)
        drainExact(inp, len)
    }

    /** Write request, read status+headers; return Content-Length (0 if absent). */
    private fun writeAndReadHeaders(out: OutputStream, inp: InputStream, request: ByteArray): Int {
        out.write(request)
        out.flush()
        val headers = readHttpHeaders(inp)
        val status = headers.lineSequence().firstOrNull().orEmpty()
        if (!status.contains(" 200")) {
            throw IllegalStateException("ping HTTP $status")
        }
        val length = headers.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: 0
        return length.coerceAtLeast(0)
    }

    private fun readHttpHeaders(inp: InputStream): String {
        val buf = ByteArray(1)
        val out = StringBuilder(256)
        var state = 0 // count trailing \r\n\r\n
        while (state < 4) {
            val n = inp.read(buf)
            if (n <= 0) throw IllegalStateException("ping EOF in headers")
            val c = buf[0].toInt() and 0xff
            out.append(c.toChar())
            state = when {
                state == 0 && c == '\r'.code -> 1
                state == 1 && c == '\n'.code -> 2
                state == 2 && c == '\r'.code -> 3
                state == 3 && c == '\n'.code -> 4
                c == '\r'.code -> 1
                else -> 0
            }
        }
        return out.toString()
    }

    private fun drainExact(inp: InputStream, length: Int) {
        if (length <= 0) return
        var left = length
        val buf = ByteArray(minOf(4096, length))
        while (left > 0) {
            val n = inp.read(buf, 0, minOf(buf.size, left))
            if (n <= 0) break
            left -= n
        }
    }

    private fun settlePingSamples(raw: List<Long>): List<Long> {
        if (raw.isEmpty()) throw IllegalStateException("no ping samples")
        val drop = pingSettleDrop.coerceAtMost((raw.size - 3).coerceAtLeast(0))
        return if (drop > 0) raw.drop(drop) else raw
    }

    private suspend fun throughput(
        root: String,
        download: Boolean,
        onMbps: (Double) -> Unit,
    ): Double = coroutineScope {
        val bytes = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val liveConnections = ConcurrentLinkedQueue<HttpURLConnection>()
        val wallStarted = System.nanoTime()
        val scoreSamples = CopyOnWriteArrayList<Double>()
        val workerCount = if (download) streams else uploadStreams
        // Download: count InputStream reads (TrafficStats uid rx updates in rare
        // jumps → peak200=2000+ Mbps and a thrashing needle, see 2.5.64 logs).
        // Upload: prefer uid tx (write() into the SSL buffer overstates goodput).
        val trafficStart = if (download) -1L else uidTrafficBytes(download = false)
        val phaseWarmup = if (download) warmupMs else uploadWarmupMs
        val phaseRamp = if (download) rampMs else uploadRampMs
        val tickSmoothMs = if (download) liveSmoothMs else uploadLiveSmoothMs
        val tickEma = if (download) emaAlpha else uploadEmaAlpha
        val tickRise = if (download) maxRiseMbpsPerTick else uploadMaxRiseMbpsPerTick
        val tickFall = if (download) maxRiseMbpsPerTick * 2.0 else uploadMaxFallMbpsPerTick
        val tickScoreInterval = if (download) scoreIntervalMs else uploadScoreIntervalMs
        fun transferred(): Long {
            if (!download && trafficStart >= 0L) {
                val now = uidTrafficBytes(download = false)
                if (now >= trafficStart) {
                    val wire = now - trafficStart
                    val app = bytes.get()
                    // Coalesced TrafficStats jumps can briefly outrun app writes; clamp.
                    if (app > 0L && wire > app + uploadWriteBytes) {
                        return app
                    }
                    return wire
                }
            }
            return bytes.get()
        }
        fun abortConnections() {
            stop.set(true)
            while (true) {
                val connection = liveConnections.poll() ?: break
                runCatching { connection.disconnect() }
            }
        }
        val workers = List(workerCount) { index ->
            async(io) {
                val wave = index / streamStartBatch
                if (wave > 0) delay(wave * streamStaggerMs)
                if (stop.get() || !coroutineContext.isActive) return@async
                try {
                    if (download) {
                        downloadStream(root, bytes, stop, liveConnections)
                    } else {
                        uploadStream(root, bytes, stop, liveConnections)
                    }
                } catch (error: Exception) {
                    // Abort/disconnect on stop is expected; never fail the round.
                    if (!stop.get()) {
                        Log.w(TAG, "${if (download) "dl" else "ul"} worker: ${error.message}")
                    }
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
                ((minDurationMs - phaseRamp) / tickScoreInterval).toInt().coerceAtLeast(6)
            val meter = if (trafficStart >= 0L) "wire" else "app"
            try {
                while (!stop.get() && coroutineContext.isActive) {
                    delay(sampleMs)
                    val total = transferred()
                    val wallElapsed = (System.nanoTime() - wallStarted) / 1_000_000L

                    if (!measuring) {
                        if (wallElapsed < phaseWarmup) {
                            // Upload preview during warmup so the needle is not frozen at 0.
                            if (!download && total > 0L) {
                                val preview = SpeedtestMath.mbps(total, wallElapsed.coerceAtLeast(1L))
                                    .coerceAtMost(tickRise)
                                if (preview > live) live = preview
                                onMbps(live)
                            }
                            continue
                        }
                        measuring = true
                        measureStarted = System.nanoTime()
                        lastBytes = total
                        lastElapsed = 0L
                        scoreMarkBytes = total
                        scoreMarkElapsed = 0L
                        liveMarks.clear()
                        liveMarks.addLast(0L to total)
                        if (download) {
                            live = 0.0
                            onMbps(0.0)
                        } else {
                            onMbps(live)
                        }
                        continue
                    }

                    val elapsed = (System.nanoTime() - measureStarted) / 1_000_000L
                    val windowMs = (elapsed - lastElapsed).coerceAtLeast(1L)
                    val deltaBytes = (total - lastBytes).coerceAtLeast(0L)
                    val window = SpeedtestMath.mbps(deltaBytes, windowMs)
                    lastBytes = total
                    lastElapsed = elapsed
                    if (window > peakWindow) peakWindow = window

                    if (elapsed >= phaseRamp) {
                        if (scoreMarkElapsed < phaseRamp) {
                            scoreMarkBytes = total
                            scoreMarkElapsed = elapsed
                        } else if (elapsed - scoreMarkElapsed >= tickScoreInterval) {
                            val scoreMs = (elapsed - scoreMarkElapsed).coerceAtLeast(1L)
                            val scoreDelta = (total - scoreMarkBytes).coerceAtLeast(0L)
                            scoreSamples += SpeedtestMath.mbps(scoreDelta, scoreMs)
                            scoreMarkBytes = total
                            scoreMarkElapsed = elapsed
                        }
                    }

                    liveMarks.addLast(elapsed to total)
                    while (liveMarks.size > 1 && liveMarks.first().first < elapsed - tickSmoothMs) {
                        liveMarks.removeFirst()
                    }
                    val (t0, b0) = liveMarks.first()
                    val smooth = SpeedtestMath.mbps(
                        (total - b0).coerceAtLeast(0L),
                        (elapsed - t0).coerceAtLeast(1L),
                    )
                    val targetLive = if (live <= 0.0) {
                        smooth.coerceAtMost(tickRise)
                    } else {
                        tickEma * smooth + (1.0 - tickEma) * live
                    }
                    live = when {
                        targetLive > live -> minOf(targetLive, live + tickRise)
                        targetLive < live -> maxOf(targetLive, live - tickFall)
                        else -> targetLive
                    }
                    if (live > peakLive) peakLive = live
                    onMbps(live)

                    val pastMin = elapsed >= minDurationMs
                    val pastMax = elapsed >= maxDurationMs
                    val enough = scoreSamples.size >= minScoreSamples
                    if (pastMax || (pastMin && enough)) {
                        abortConnections()
                    }
                }
            } finally {
                abortConnections()
                val list = scoreSamples.toList()
                val scored = if (download) {
                    SpeedtestMath.stableMbps(list)
                } else {
                    SpeedtestMath.ooklaUploadMbps(list)
                }
                val positive = list.filter { it > 0.0 }
                val p50 = SpeedtestMath.medianDouble(
                    (if (positive.size >= 5) positive else list).sorted(),
                )
                Log.i(
                    TAG,
                    "${if (download) "dl" else "ul"} ${target.family.label} warmup=${phaseWarmup}ms " +
                        "streams=$workerCount meter=$meter " +
                        "peak200=${"%.1f".format(peakWindow)} peakLive=${"%.1f".format(peakLive)} " +
                        "p50=${"%.1f".format(p50)} score=${"%.1f".format(scored)} " +
                        "appBytes=${bytes.get()} n=${list.size}",
                )
            }
        }
        try {
            // Await ticker first (sets stop + disconnect). Never wait on blocked writers
            // the way 2.5.62 did — that left ~15s dead air after upload vs Ookla.
            runCatching { ticker.await() }
        } finally {
            abortConnections()
        }
        withTimeoutOrNull(400L) {
            workers.forEach { runCatching { it.await() } }
        }
        workers.forEach { it.cancel() }
        val list = scoreSamples.toList()
        val scored = if (download) {
            SpeedtestMath.stableMbps(list)
        } else {
            SpeedtestMath.ooklaUploadMbps(list)
        }
        if (scored > 0.0) scored
        else {
            val elapsed = if (scoreSamples.isEmpty()) {
                ((System.nanoTime() - wallStarted) / 1_000_000L).coerceAtLeast(1L)
            } else {
                minDurationMs
            }
            SpeedtestMath.mbps(transferred(), elapsed)
        }
    }

    /** UID rx/tx counters; -1 if unsupported on this device. */
    private fun uidTrafficBytes(download: Boolean): Long {
        val uid = Process.myUid()
        val value = if (download) {
            TrafficStats.getUidRxBytes(uid)
        } else {
            TrafficStats.getUidTxBytes(uid)
        }
        return if (value < 0L) -1L else value
    }

    /**
     * Persistent download worker: one large HTTPS body, keep reading until [stop]
     * or EOF, then reopen. Avoids TLS/TCP restart every few MB (Ookla ThreadedStage).
     */
    private fun downloadStream(
        root: String,
        bytes: AtomicLong,
        stop: AtomicBoolean,
        liveConnections: ConcurrentLinkedQueue<HttpURLConnection>,
    ) {
        val buf = ByteArray(readBufBytes)
        while (!stop.get()) {
            val connection = open(downloadUrl(root, downloadChunk), "GET")
            liveConnections.add(connection)
            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("download HTTP ${connection.responseCode}")
                }
                connection.inputStream.use { input ->
                    while (!stop.get()) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        bytes.addAndGet(n.toLong())
                    }
                }
            } finally {
                liveConnections.remove(connection)
                runCatching { connection.disconnect() }
            }
        }
    }

    /**
     * Fixed-length 128 MiB POST: count bytes as written, skip HTTP response wait.
     * Registered in [liveConnections] so stop can disconnect and unblock write().
     */
    private fun uploadStream(
        root: String,
        bytes: AtomicLong,
        stop: AtomicBoolean,
        liveConnections: ConcurrentLinkedQueue<HttpURLConnection>,
    ) {
        val buf = ByteArray(uploadWriteBytes)
        while (!stop.get()) {
            val connection = open(uploadUrl(root), "POST")
            liveConnections.add(connection)
            try {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(uploadBodyBytes)
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.readTimeout = 8_000
                connection.connect()
                if (stop.get()) return
                val out = connection.outputStream
                var left = uploadBodyBytes
                while (left > 0 && !stop.get()) {
                    val n = minOf(buf.size, left)
                    out.write(buf, 0, n)
                    bytes.addAndGet(n.toLong())
                    left -= n
                }
                if (stop.get()) return
                runCatching {
                    out.flush()
                    out.close()
                }
            } finally {
                liveConnections.remove(connection)
                runCatching { connection.disconnect() }
            }
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        SpeedtestHttps.open(target, url, method)

    private fun drain(stream: InputStream): Int {
        val buf = ByteArray(readBufBytes)
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
