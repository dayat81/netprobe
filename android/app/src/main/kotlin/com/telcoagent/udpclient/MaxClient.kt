package com.telcoagent.udpclient

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class MaxConfig(
    val host: String = "netprobe.xyz",
    val port: Int = 8765,
    val packetSize: Int = 1400,
    val streamLevels: List<Int> = listOf(1, 2, 4, 8, 16, 32),
    val packetsPerStream: Int = 50,
    val burstDelayMs: Long = 5,
    val recvTimeoutMs: Int = 2000,
    val pauseBetweenLevelsMs: Long = 500,
    val burstDurationMs: Long = 5000,
)

/** Per-sample time-series data point */
data class BurstSample(
    val t: Double,         // elapsed seconds
    val kbps: Double,      // throughput in kbps (bidirectional)
    val loss: Double,      // loss percentage in this sample
    val delayMs: Double,   // AIMD delay at this point
)

data class MaxStreamLevelStats(
    val streamCount: Int,
    val totalSent: Int,
    val totalReceived: Int,
    val lossPct: Double,
    val avgRttMs: Double,
    val jitterMs: Double,
    val throughputKBps: Double,
)

data class MaxResult(
    val packetSize: Int,
    val levelStats: List<MaxStreamLevelStats>,
    val optimalStreams: Int,
    val optimalThroughputKBps: Double,
    val burstThroughputKBps: Double,
    val burstTotalSent: Int,
    val burstTotalReceived: Int,
    val burstLossPct: Double,
    val burstSamples: List<BurstSample> = emptyList(),
)

class MaxClient {

    companion object {
        // AIMD constants
        private const val LOSS_THRESHOLD = 5.0         // target max loss %
        private const val AIMD_LOW_LOSS = 2.0          // below this → speed up
        private const val AIMD_INITIAL_DELAY_MS = 0.0  // start with no delay
        private const val AIMD_MIN_DELAY_MS = 0.0
        private const val AIMD_MAX_DELAY_MS = 50.0
        private const val AIMD_INCREASE_FACTOR = 1.5   // multiplicative decrease
        private const val AIMD_DECREASE_STEP_MS = 1.0  // additive increase step
        private const val SAMPLE_INTERVAL_MS = 500L    // AIMD sampling interval

        fun formatThroughputKB(kbps: Double): String {
            return when {
                kbps >= 1024 -> "${"%.1f".format(kbps / 1024)} MB/s"
                else -> "${"%.1f".format(kbps)} KB/s"
            }
        }

        fun fmtMs(ms: Double): String =
            if (ms >= 1000) "${"%.1f".format(ms / 1000)}s" else "${"%.1f".format(ms)}ms"

        fun fmtPct(pct: Double): String = "${"%.1f".format(pct)}%"
    }

    fun runMaxTest(
        config: MaxConfig = MaxConfig(),
        onLog: (String) -> Unit,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): MaxResult {
        val targetAddress = InetAddress.getByName(config.host)
        val packetSize = config.packetSize

        onLog("MAX Throughput Test to ${config.host}:${config.port}")
        onLog("Packet size: ${packetSize}B (ABR optimal)")
        onLog("Stream levels: ${config.streamLevels.joinToString(", ")}")
        onLog("Packets per stream: ${config.packetsPerStream}")
        onLog("Loss threshold: ${LOSS_THRESHOLD}%")
        onLog("AIMD: increase_factor=${AIMD_INCREASE_FACTOR}, decrease_step=${AIMD_DECREASE_STEP_MS}ms")

        // ── Phase 1: Stream Sweep ──
        onLog("\n--- Phase 1: Stream Sweep ---")
        val levelStats = mutableListOf<MaxStreamLevelStats>()

        for ((levelIdx, streamCount) in config.streamLevels.withIndex()) {
            if (isCancelled()) break

            onProgress(levelIdx, config.streamLevels.size)

            val stats = runStreamLevel(
                config = config,
                targetAddress = targetAddress,
                packetSize = packetSize,
                streamCount = streamCount,
                onLog = onLog,
                isCancelled = isCancelled,
            )
            levelStats.add(stats)

            onLog("  ${streamCount} streams: ${formatThroughputKB(stats.throughputKBps)} " +
                "loss=${fmtPct(stats.lossPct)} rtt=${fmtMs(stats.avgRttMs)}")

            if (levelIdx < config.streamLevels.size - 1 && !isCancelled()) {
                Thread.sleep(config.pauseBetweenLevelsMs)
            }
        }

        // ── Find saturation point ──
        // Capacity: highest throughput with 0% loss
        val capacityLevel = levelStats
            .filter { it.lossPct < 0.5 && it.throughputKBps > 0 }
            .maxByOrNull { it.throughputKBps }

        // Optimal: highest throughput with loss < threshold
        val optimal = levelStats
            .filter { it.lossPct < LOSS_THRESHOLD && it.throughputKBps > 0 }
            .maxByOrNull { it.throughputKBps }
            ?: levelStats.filter { it.throughputKBps > 0 }.minByOrNull { it.lossPct }

        val optimalStreams = optimal?.streamCount ?: 1
        val optimalThroughput = optimal?.throughputKBps ?: 0.0

        onLog("")
        if (capacityLevel != null) {
            onLog("Capacity (0% loss): ${capacityLevel.streamCount} streams = ${formatThroughputKB(capacityLevel.throughputKBps)}")
        }
        if (optimal != null) {
            onLog("Saturation (<${LOSS_THRESHOLD}% loss): ${optimal.streamCount} streams = ${formatThroughputKB(optimal.throughputKBps)} (loss=${fmtPct(optimal.lossPct)})")
        }
        if (optimal == null && capacityLevel == null) {
            onLog("No valid throughput level found, using 1 stream")
        }

        // ── Phase 2: Adaptive Burst with AIMD ──
        var burstSamples = emptyList<BurstSample>()
        var burstThroughputKBps = 0.0
        var burstTotalSent = 0
        var burstTotalReceived = 0
        var burstLossPct = 0.0

        if (!isCancelled()) {
            onLog("\n--- Phase 2: Adaptive Burst ($optimalStreams streams, ${config.burstDurationMs}ms, AIMD) ---")
            onLog("  Target: loss < ${LOSS_THRESHOLD}%, adjusting delay dynamically")

            val burstResult = runAdaptiveBurst(
                config = config,
                targetAddress = targetAddress,
                packetSize = packetSize,
                streamCount = optimalStreams,
                onLog = onLog,
                isCancelled = isCancelled,
            )

            burstSamples = burstResult.samples
            burstThroughputKBps = burstResult.throughputKBps
            burstTotalSent = burstResult.totalSent
            burstTotalReceived = burstResult.totalReceived
            burstLossPct = burstResult.lossPct

            val stableCount = burstSamples.count { it.loss < LOSS_THRESHOLD }
            onLog("  Adaptive burst: ${formatThroughputKB(burstThroughputKBps)} " +
                "(${burstTotalReceived}/${burstTotalSent} pkts, loss=${fmtPct(burstLossPct)})")
            onLog("  AIMD: $stableCount/${burstSamples.size} samples below ${LOSS_THRESHOLD}% loss")
        }

        onLog("\n--- Results ---")
        if (capacityLevel != null) {
            onLog("Capacity (0% loss): ${capacityLevel.streamCount} streams x ${packetSize}B = ${formatThroughputKB(capacityLevel.throughputKBps)}")
        }
        if (optimal != null) {
            onLog("Saturation point: ${optimal.streamCount} streams x ${packetSize}B = ${formatThroughputKB(optimal.throughputKBps)} (loss=${fmtPct(optimal.lossPct)})")
        }
        onLog("Adaptive burst: ${formatThroughputKB(burstThroughputKBps)} over ${config.burstDurationMs}ms (loss=${fmtPct(burstLossPct)})")
        onLog("MAX test complete")

        return MaxResult(
            packetSize = packetSize,
            levelStats = levelStats,
            optimalStreams = optimalStreams,
            optimalThroughputKBps = optimalThroughput,
            burstThroughputKBps = burstThroughputKBps,
            burstTotalSent = burstTotalSent,
            burstTotalReceived = burstTotalReceived,
            burstLossPct = burstLossPct,
            burstSamples = burstSamples,
        )
    }

    // ── Phase 1: Stream Sweep (request-response) ──────────────────────

    private fun runStreamLevel(
        config: MaxConfig,
        targetAddress: InetAddress,
        packetSize: Int,
        streamCount: Int,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): MaxStreamLevelStats {
        val allSent = IntArray(streamCount)
        val allReceived = IntArray(streamCount)
        val allRttSamples = Array(streamCount) { mutableListOf<Double>() }
        val allTotalBytes = IntArray(streamCount)
        val allWallTimeMs = LongArray(streamCount)

        val barrier = java.util.concurrent.CyclicBarrier(streamCount)

        val threads = Array(streamCount) { streamId ->
            Thread {
                try {
                    val socket = DatagramSocket()
                    socket.soTimeout = config.recvTimeoutMs
                    try {
                        barrier.await() // synchronized start

                        val rttSamples = mutableListOf<Double>()
                        var sent = 0
                        var received = 0
                        var totalBytes = 0
                        val tStart = System.currentTimeMillis()

                        for (seq in 1..config.packetsPerStream) {
                            if (isCancelled()) break

                            sent++
                            val payload = buildPaddedPayload(packetSize, "MAX:$streamId:$seq:")
                            val sendPacket = DatagramPacket(payload, payload.size, targetAddress, config.port)
                            val startNs = System.nanoTime()
                            socket.send(sendPacket)

                            try {
                                val receiveBuffer = ByteArray(maxOf(packetSize + 64, 1024))
                                val replyPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                                socket.receive(replyPacket)
                                val rttMs = (System.nanoTime() - startNs) / 1_000_000.0
                                rttSamples.add(rttMs)
                                received++
                                totalBytes += replyPacket.length
                            } catch (_: SocketTimeoutException) {
                                // loss
                            }

                            if (seq < config.packetsPerStream) {
                                Thread.sleep(config.burstDelayMs)
                            }
                        }

                        val wallTime = System.currentTimeMillis() - tStart
                        allSent[streamId] = sent
                        allReceived[streamId] = received
                        allRttSamples[streamId] = rttSamples
                        allTotalBytes[streamId] = totalBytes
                        allWallTimeMs[streamId] = wallTime
                    } finally {
                        socket.close()
                    }
                } catch (e: Exception) {
                    onLog("Stream $streamId error: ${e.message}")
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(config.recvTimeoutMs.toLong() * config.packetsPerStream + 10000) }

        val totalSent = allSent.sum()
        val totalReceived = allReceived.sum()
        val allRtt = allRttSamples.flatMap { it }
        val maxWallTime = allWallTimeMs.maxOrNull() ?: 1L
        val totalBytes = allTotalBytes.sum()

        val lossPct = if (totalSent > 0) ((totalSent - totalReceived).toDouble() / totalSent) * 100.0 else 0.0
        val avgRtt = if (allRtt.isNotEmpty()) allRtt.average() else 0.0
        val jitter = computeJitter(allRtt)
        val throughputKBps = if (maxWallTime > 0) (totalBytes.toDouble() / 1024.0) / (maxWallTime / 1000.0) else 0.0

        return MaxStreamLevelStats(
            streamCount = streamCount,
            totalSent = totalSent,
            totalReceived = totalReceived,
            lossPct = lossPct,
            avgRttMs = avgRtt,
            jitterMs = jitter,
            throughputKBps = throughputKBps,
        )
    }

    // ── Phase 2: Adaptive Burst with AIMD ─────────────────────────────

    private data class AdaptiveBurstResult(
        val samples: List<BurstSample>,
        val totalSent: Int,
        val totalReceived: Int,
        val lossPct: Double,
        val throughputKBps: Double,
    )

    private fun runAdaptiveBurst(
        config: MaxConfig,
        targetAddress: InetAddress,
        packetSize: Int,
        streamCount: Int,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): AdaptiveBurstResult {
        val allSent = IntArray(streamCount)
        val allReceived = IntArray(streamCount)
        val allTotalBytes = IntArray(streamCount)
        val allSamples = Array(streamCount) { mutableListOf<BurstSample>() }

        val barrier = java.util.concurrent.CyclicBarrier(streamCount)
        val endTime = System.currentTimeMillis() + config.burstDurationMs
        val sampleIntervalMs = SAMPLE_INTERVAL_MS

        val threads = Array(streamCount) { streamId ->
            Thread {
                try {
                    val socket = DatagramSocket()
                    socket.soTimeout = 100 // short timeout for responsiveness
                    try {
                        barrier.await() // synchronized start

                        var sent = 0
                        var received = 0
                        var totalBytes = 0
                        var seq = 0
                        var delayMs = AIMD_INITIAL_DELAY_MS

                        // Per-sample tracking
                        var sampleStartMs = System.currentTimeMillis()
                        var sampleSent = 0
                        var sampleRecv = 0
                        var sampleBytes = 0

                        while (System.currentTimeMillis() < endTime && !isCancelled()) {
                            seq++
                            sent++
                            sampleSent++

                            val payload = buildPaddedPayload(packetSize, "MAXB:$streamId:$seq:")
                            val sendPacket = DatagramPacket(payload, payload.size, targetAddress, config.port)
                            val sendStartNs = System.nanoTime()
                            socket.send(sendPacket)
                            sampleBytes += payload.size

                            // Try to receive response
                            try {
                                val receiveBuffer = ByteArray(maxOf(packetSize + 64, 1024))
                                val replyPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                                socket.receive(replyPacket)
                                received++
                                sampleRecv++
                                totalBytes += replyPacket.length
                                sampleBytes += replyPacket.length
                            } catch (_: SocketTimeoutException) {
                                // loss
                            }

                            // Rate control: sleep for configured delay
                            if (delayMs > 0) {
                                val elapsedMs = (System.nanoTime() - sendStartNs) / 1_000_000.0
                                val remainingMs = delayMs - elapsedMs
                                if (remainingMs > 0) {
                                    Thread.sleep(remainingMs.toLong())
                                }
                            }

                            // AIMD sampling
                            val now = System.currentTimeMillis()
                            if (now - sampleStartMs >= sampleIntervalMs) {
                                if (sampleSent > 0) {
                                    val lossPct = (sampleSent - sampleRecv).toDouble() / sampleSent * 100.0
                                    val elapsedS = (now - sampleStartMs) / 1000.0
                                    val kbps = sampleBytes * 8.0 / 1000.0 / elapsedS

                                    // AIMD: adjust delay based on loss
                                    if (lossPct > LOSS_THRESHOLD) {
                                        delayMs = min(delayMs * AIMD_INCREASE_FACTOR + 2.0, AIMD_MAX_DELAY_MS)
                                    } else if (lossPct < AIMD_LOW_LOSS) {
                                        delayMs = max(delayMs - AIMD_DECREASE_STEP_MS, AIMD_MIN_DELAY_MS)
                                    }
                                    // else: loss in [2%, 5%] → maintain current rate

                                    val tElapsed = (now - (endTime - config.burstDurationMs)) / 1000.0
                                    allSamples[streamId].add(BurstSample(tElapsed, kbps, lossPct, delayMs))
                                }

                                sampleStartMs = now
                                sampleSent = 0
                                sampleRecv = 0
                                sampleBytes = 0
                            }
                        }

                        // Final partial sample
                        if (sampleSent > 0) {
                            val now = System.currentTimeMillis()
                            val elapsedS = (now - sampleStartMs) / 1000.0
                            val kbps = if (elapsedS > 0) sampleBytes * 8.0 / 1000.0 / elapsedS else 0.0
                            val lossPct = if (sampleSent > 0) (sampleSent - sampleRecv).toDouble() / sampleSent * 100.0 else 0.0
                            val tElapsed = (now - (endTime - config.burstDurationMs)) / 1000.0
                            allSamples[streamId].add(BurstSample(tElapsed, kbps, lossPct, delayMs))
                        }

                        // Drain remaining responses
                        val drainEnd = System.currentTimeMillis() + 1000
                        while (System.currentTimeMillis() < drainEnd) {
                            try {
                                val receiveBuffer = ByteArray(maxOf(packetSize + 64, 1024))
                                val replyPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                                socket.receive(replyPacket)
                                received++
                                totalBytes += replyPacket.length
                            } catch (_: SocketTimeoutException) {
                                break
                            }
                        }

                        allSent[streamId] = sent
                        allReceived[streamId] = received
                        allTotalBytes[streamId] = totalBytes
                    } finally {
                        socket.close()
                    }
                } catch (e: Exception) {
                    onLog("Burst stream $streamId error: ${e.message}")
                }
            }
        }

        val burstStartTime = System.currentTimeMillis()
        threads.forEach { it.start() }
        threads.forEach { it.join(config.burstDurationMs + 5000) }
        val burstWallTime = System.currentTimeMillis() - burstStartTime

        val totalSent = allSent.sum()
        val totalReceived = allReceived.sum()
        val totalBytes = allTotalBytes.sum()
        val lossPct = if (totalSent > 0) ((totalSent - totalReceived).toDouble() / totalSent) * 100.0 else 0.0
        val throughputKBps = if (burstWallTime > 0) (totalBytes.toDouble() / 1024.0) / (burstWallTime / 1000.0) else 0.0

        // Merge samples from all streams into single time-series
        val mergedSamples = mergeSamples(allSamples, streamCount)

        return AdaptiveBurstResult(
            samples = mergedSamples,
            totalSent = totalSent,
            totalReceived = totalReceived,
            lossPct = lossPct,
            throughputKBps = throughputKBps,
        )
    }

    /** Merge per-stream samples into a single time-series by averaging overlapping time buckets */
    private fun mergeSamples(allSamples: Array<MutableList<BurstSample>>, streamCount: Int): List<BurstSample> {
        if (streamCount == 0) return emptyList()

        // Collect all unique time buckets (rounded to sample interval)
        val bucketMap = mutableMapOf<Long, MutableList<BurstSample>>()
        for (streamSamples in allSamples) {
            for (sample in streamSamples) {
                val bucket = (sample.t * 2).toLong() // 0.5s buckets
                bucketMap.getOrPut(bucket) { mutableListOf() }.add(sample)
            }
        }

        return bucketMap.toSortedMap().map { (_, samples) ->
            BurstSample(
                t = samples.map { it.t }.average(),
                kbps = samples.map { it.kbps }.sum(), // sum across streams
                loss = samples.map { it.loss }.average(),
                delayMs = samples.map { it.delayMs }.average(),
            )
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun buildPaddedPayload(targetSize: Int, prefix: String): ByteArray {
        val payload = ByteArray(targetSize)
        val prefixBytes = prefix.toByteArray()
        val copyLen = minOf(prefixBytes.size, targetSize)
        System.arraycopy(prefixBytes, 0, payload, 0, copyLen)
        for (i in copyLen until targetSize) {
            payload[i] = 'A'.code.toByte()
        }
        return payload
    }

    private fun computeJitter(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val diffs = (1 until values.size).map { abs(values[it] - values[it - 1]) }
        return diffs.average()
    }
}
