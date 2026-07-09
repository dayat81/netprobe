package com.telcoagent.udpclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * ICMP-based MTU finder using binary search with `ping -M do` (Don't Fragment).
 * Discovers Path MTU then derives WireGuard MTU by subtracting overhead.
 */
data class MtuProbeStep(
    val probeSize: Int,
    val success: Boolean,    // true = packet passed, false = fragmentation needed
    val rttMs: Double?,      // RTT if successful, null if failed
    val rawOutput: String,   // raw ping output for debugging
)

data class AbrResult(
    val probeSteps: List<MtuProbeStep>,
    val pmtu: Int?,           // Path MTU (largest successful probe)
    val wgMtu: Int?,          // WireGuard MTU = pmtu - 60
    val host: String,
)

data class AbrProgress(
    val probeNo: Int,
    val totalProbes: Int,
    val probeSize: Int,
    val phase: String,  // "searching" or "confirming"
)

class AbrClient {

    companion object {
        private const val WG_OVERHEAD = 60  // IPv4(20) + UDP(8) + WG header(32)
        private const val IP_ICMP_OVERHEAD = 28  // IPv4 header(20) + ICMP header(8)
        private const val MIN_MTU = 576
        private const val MAX_MTU = 1500
        private const val PING_TIMEOUT_S = 2
        private const val CONFIRM_PROBES = 3  // number of confirmation pings

        fun fmtMs(ms: Double): String =
            if (ms >= 1000) "${"%.1f".format(ms / 1000)}s" else "${"%.1f".format(ms)}ms"

        fun fmtPct(pct: Double): String = "${"%.1f".format(pct)}%"

        fun formatThroughput(bps: Double): String = when {
            bps >= 1_000_000 -> "${"%.1f".format(bps / 1_000_000)} MB/s"
            bps >= 1_000 -> "${"%.1f".format(bps / 1_000)} KB/s"
            else -> "${"%.0f".format(bps)} B/s"
        }

        // Kept for DriveClient backward compat
        fun median(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val n = sorted.size
            return if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0 else sorted[n / 2]
        }

        fun jitter(values: List<Double>): Double {
            if (values.size < 2) return 0.0
            val diffs = (1 until values.size).map { kotlin.math.abs(values[it] - values[it - 1]) }
            return diffs.average()
        }

        fun stddev(values: List<Double>): Double {
            if (values.size < 2) return 0.0
            val avg = values.average()
            val variance = values.map { (it - avg) * (it - avg) }.sum() / (values.size - 1)
            return kotlin.math.sqrt(variance)
        }

        /**
         * Derive WireGuard MTU from a discovered Path MTU.
         */
        fun wgMtuFromPmtu(pmtu: Int): Int = (pmtu - WG_OVERHEAD).coerceIn(MIN_MTU, MAX_MTU)
    }

    /**
     * Run ICMP MTU discovery via binary search.
     *
     * @param host Target hostname or IP to ping
     * @param onLog Log callback
     * @param onProgress Progress callback
     * @return AbrResult with discovered PMTU and WG MTU
     */
    suspend fun runMtuProbe(
        host: String = "netprobe.xyz",
        onLog: suspend (String) -> Unit,
        onProgress: suspend (AbrProgress) -> Unit = {},
    ): AbrResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<MtuProbeStep>()
        var lo = MIN_MTU
        var hi = MAX_MTU
        var probeCount = 0
        // Binary search: ceil(log2(1500-576+1)) ≈ 10 steps max
        val maxProbes = 12 + CONFIRM_PROBES

        onLog("ICMP MTU probe → $host")
        onLog("Range: $MIN_MTU–$MAX_MTU bytes, WG overhead: ${WG_OVERHEAD}B")

        // --- Binary search for Path MTU ---
        while (lo < hi) {
            coroutineContext.ensureActive()
            val mid = (lo + hi + 1) / 2
            probeCount++

            onProgress(AbrProgress(probeCount, maxProbes, mid, "searching"))
            onLog("Probe #$probeCount: $mid B …")

            val result = pingWithSize(host, mid)
            steps.add(result)

            if (result.success) {
                lo = mid
                onLog("  ✓ pass (${fmtMs(result.rttMs ?: 0.0)})")
            } else {
                hi = mid - 1
                onLog("  ✗ frag needed")
            }

            delay(100) // brief pause between probes
        }

        // lo = largest ICMP payload that passes without fragmentation
        // Actual Path MTU = lo + 20 (IP header) + 8 (ICMP header)
        val icmpPayloadPmtu = lo
        val pmtu = icmpPayloadPmtu + IP_ICMP_OVERHEAD

        // --- Confirmation: send 3 pings at max payload to verify ---
        onLog("\nConfirm PMTU = $pmtu B (ICMP payload $icmpPayloadPmtu) with $CONFIRM_PROBES pings…")
        var confirmPass = 0
        for (i in 1..CONFIRM_PROBES) {
            coroutineContext.ensureActive()
            probeCount++
            onProgress(AbrProgress(probeCount, maxProbes, icmpPayloadPmtu, "confirming"))

            val result = pingWithSize(host, icmpPayloadPmtu)
            steps.add(result)
            if (result.success) confirmPass++
            onLog("  Confirm #${i}: ${icmpPayloadPmtu}B → ${if (result.success) "✓" else "✗"}")
            delay(100)
        }

        val confirmed = confirmPass == CONFIRM_PROBES
        val finalPmtu = if (confirmed) pmtu else null
        val wgMtu = if (finalPmtu != null) wgMtuFromPmtu(finalPmtu) else null

        onLog("\n═══ Result ═══")
        if (finalPmtu != null) {
            onLog("Path MTU: $finalPmtu B")
            onLog("WireGuard MTU: $wgMtu B (PMTU − $WG_OVERHEAD)")
        } else {
            onLog("MTU discovery failed — confirmation failed ($confirmPass/$CONFIRM_PROBES)")
            onLog("Using fallback WG MTU: ${wgMtuFromPmtu(MIN_MTU)}")
        }

        AbrResult(
            probeSteps = steps,
            pmtu = finalPmtu,
            wgMtu = wgMtu ?: wgMtuFromPmtu(MIN_MTU),
            host = host,
        )
    }

    /**
     * Ping host with a specific payload size using Don't Fragment flag.
     * Returns MtuProbeStep indicating if the packet passed or was fragmented.
     *
     * Uses ProcessBuilder to merge stderr→stdout (avoids pipe deadlock),
     * with explicit timeout and process cleanup.
     */
    private suspend fun pingWithSize(host: String, size: Int): MtuProbeStep {
        return withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                // -M do = set DF (Don't Fragment) bit — Linux/toybox specific
                // -s = ICMP payload size (header 8B excluded)
                // -c 1 = one packet, -W timeout in seconds
                val cmd = listOf("ping", "-M", "do", "-s", "$size", "-c", "1", "-W", "$PING_TIMEOUT_S", host)
                val pb = ProcessBuilder(cmd)
                pb.redirectErrorStream(true) // merge stderr → stdout, prevents deadlock
                process = pb.start()

                val output = process.inputStream.bufferedReader().readText()

                // Wait with timeout (API 26+: waitFor(seconds, TimeUnit))
                val timeoutMs = (PING_TIMEOUT_S + 2) * 1000L
                val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                val exitCode = if (finished) process.exitValue() else -1

                if (!finished) {
                    process.destroyForcibly()
                    return@withContext MtuProbeStep(
                        probeSize = size,
                        success = false,
                        rttMs = null,
                        rawOutput = "Timeout after ${timeoutMs}ms",
                    )
                }

                // Detect unsupported "-M do" flag: ping errors with "invalid" or "usage"
                // Retry once without -M do as degraded fallback
                if (exitCode != 0 && (output.contains("invalid", ignoreCase = true) ||
                        output.contains("usage:", ignoreCase = true))) {
                    return@withContext pingWithSizeFallback(host, size)
                }

                val success = parsePingSuccess(output, exitCode)
                val rtt = if (success) parseRtt(output) else null

                MtuProbeStep(
                    probeSize = size,
                    success = success,
                    rttMs = rtt,
                    rawOutput = output.trim(),
                )
            } catch (e: Exception) {
                MtuProbeStep(
                    probeSize = size,
                    success = false,
                    rttMs = null,
                    rawOutput = "Error: ${e.message}",
                )
            } finally {
                try { process?.destroyForcibly() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Fallback: ping without -M do (DF bit).
     * Less accurate — packets may fragment silently — but works on all Android devices.
     * Heuristic: if RTT jumps significantly vs smaller sizes, likely fragmentation occurred.
     * We just test reachability and report as "pass" (caller should treat with caution).
     */
    private suspend fun pingWithSizeFallback(host: String, size: Int): MtuProbeStep {
        return withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                val cmd = listOf("ping", "-s", "$size", "-c", "1", "-W", "$PING_TIMEOUT_S", host)
                val pb = ProcessBuilder(cmd)
                pb.redirectErrorStream(true)
                process = pb.start()
                val output = process.inputStream.bufferedReader().readText()
                val timeoutMs = (PING_TIMEOUT_S + 2) * 1000L
                val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                val exitCode = if (finished) process.exitValue() else -1

                val success = parsePingSuccess(output, exitCode)
                val rtt = if (success) parseRtt(output) else null

                MtuProbeStep(
                    probeSize = size,
                    success = success,
                    rttMs = rtt,
                    rawOutput = "[no-df] ${output.trim()}",
                )
            } catch (e: Exception) {
                MtuProbeStep(
                    probeSize = size,
                    success = false,
                    rttMs = null,
                    rawOutput = "Fallback error: ${e.message}",
                )
            } finally {
                try { process?.destroyForcibly() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Parse ping output to determine if the packet was delivered without fragmentation.
     * Success: exit code 0, no "Frag needed" message.
     * Failure: "message too long" / "Frag needed" / exit code non-zero with no successful replies.
     */
    private fun parsePingSuccess(output: String, exitCode: Int): Boolean {
        val lower = output.lowercase()
        // Explicit fragmentation indicators
        if (lower.contains("frag needed") || lower.contains("message too long") ||
            lower.contains("too large") || lower.contains("fragmentation needed")) {
            return false
        }
        // Check for successful reply
        if (lower.contains("bytes from") || lower.contains("icmp_seq")) {
            return true
        }
        // 100% packet loss or no reply = failure
        if (lower.contains("100% packet loss") || lower.contains("0 received")) {
            return false
        }
        // Fall back to exit code
        return exitCode == 0
    }

    /**
     * Parse RTT from ping output. Example: "time=12.3 ms"
     */
    private fun parseRtt(output: String): Double? {
        val regex = Regex("""time[=<](\d+\.?\d*)\s*ms""", RegexOption.IGNORE_CASE)
        return regex.find(output)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}
