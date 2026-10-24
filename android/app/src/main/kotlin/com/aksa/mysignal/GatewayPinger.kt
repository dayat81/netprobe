package com.aksa.mysignal

import android.content.Context
import android.net.ConnectivityManager
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/** ICMP / reachability RTT to the default gateway. */
object GatewayPinger {
    data class Sample(val tMs: Long, val rttMs: Double?)

    private const val DEFAULT_MTU = 1500
    /** IPv4 header 20 + ICMP echo 8. */
    private const val V4_OVERHEAD = 28
    /** IPv6 header 40 + ICMPv6 echo 8. */
    private const val V6_OVERHEAD = 48

    fun pingOnce(host: String, timeoutMs: Int = 800, payloadBytes: Int? = null): Double? {
        if (host.isBlank() || host == "—") return null
        val isV6 = host.contains(':')
        val size = (payloadBytes ?: defaultPayload(isV6)).coerceIn(16, 65_507)
        shellPing(host, size)?.let { return it }
        return reachablePing(host, timeoutMs)
    }

    fun pingOnce(context: Context, host: String, timeoutMs: Int = 800): Double? {
        val isV6 = host.contains(':')
        return pingOnce(host, timeoutMs, payloadForMtu(activeMtu(context), isV6))
    }

    data class FloodResult(
        val sent: Int,
        val received: Int,
        val rtts: List<Double>,
        val elapsedMs: Long,
    ) {
        val lost: Int get() = (sent - received).coerceAtLeast(0)
        val pps: Double
            get() = if (elapsedMs <= 0L) 0.0 else sent * 1_000.0 / elapsedMs

        /** RTT list padded with nulls for lost probes (for charts). */
        fun slots(): List<Double?> =
            rtts.map { it as Double? } + List(lost) { null }
    }

    /**
     * Maximal ICMP load for [durationMs] (default 10 s): try flood (`-f`), else
     * [workers] parallel `ping -w … -i 0.2` streams. Aggregates tx/rx for loss.
     */
    fun pingFlood(
        host: String,
        durationMs: Long = 10_000L,
        workers: Int = 16,
        payloadBytes: Int? = null,
    ): FloodResult {
        if (host.isBlank() || host == "—") {
            return FloodResult(0, 0, emptyList(), durationMs)
        }
        val isV6 = host.contains(':')
        val size = (payloadBytes ?: defaultPayload(isV6)).coerceIn(16, 65_507)
        shellFlood(host, durationMs, size)?.let { return it }
        return parallelDeadlineFlood(host, durationMs, workers.coerceIn(1, 32), size)
    }

    /** Active network MTU from LinkProperties (fallback 1500). */
    fun activeMtu(context: Context): Int {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return DEFAULT_MTU
        val network = cm.activeNetwork ?: return DEFAULT_MTU
        val mtu = cm.getLinkProperties(network)?.mtu ?: 0
        return if (mtu >= 576) mtu else DEFAULT_MTU
    }

    /**
     * ICMP echo payload so the IP packet fills [mtu] without fragmentation:
     * `-s (mtu - IP - ICMP)`.
     */
    fun payloadForMtu(mtu: Int, ipv6: Boolean): Int {
        val overhead = if (ipv6) V6_OVERHEAD else V4_OVERHEAD
        return (mtu - overhead).coerceIn(16, 65_507)
    }

    private fun defaultPayload(ipv6: Boolean): Int = payloadForMtu(DEFAULT_MTU, ipv6)

    private fun shellPing(host: String, payloadBytes: Int): Double? {
        val isV6 = host.contains(':')
        val size = payloadBytes.toString()
        val variants = if (isV6) {
            listOf(
                arrayOf("ping6", "-c", "1", "-W", "1", "-s", size, host),
                arrayOf("ping", "-c", "1", "-W", "1", "-s", size, host),
            )
        } else {
            listOf(
                arrayOf("ping", "-c", "1", "-W", "1", "-s", size, host),
                arrayOf("ping", "-c", "1", "-w", "1", "-s", size, host),
            )
        }
        for (cmd in variants) {
            try {
                val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    continue
                }
                val match = TIME_RE.find(out) ?: continue
                return match.groupValues[1].toDoubleOrNull()
            } catch (_: Exception) {
                // try next
            }
        }
        return null
    }

    /** Single-process flood if the binary allows `-f` / deadline. */
    private fun shellFlood(host: String, durationMs: Long, payloadBytes: Int): FloodResult? {
        val isV6 = host.contains(':')
        val size = payloadBytes.toString()
        val deadlineSec = ((durationMs + 999) / 1_000L).toInt().coerceAtLeast(1).toString()
        val bases = if (isV6) listOf("ping6", "ping") else listOf("ping")
        val variants = bases.flatMap { bin ->
            listOf(
                arrayOf(bin, "-f", "-w", deadlineSec, "-s", size, host),
                arrayOf(bin, "-f", "-c", "500", "-s", size, host),
            )
        }
        val t0 = System.currentTimeMillis()
        for (cmd in variants) {
            try {
                val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                if (!proc.waitFor(durationMs / 1_000L + 5L, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    continue
                }
                parseFloodSummary(out, System.currentTimeMillis() - t0)?.let { return it }
            } catch (_: Exception) {
                // try next
            }
        }
        return null
    }

    /**
     * Many parallel deadline pings — Android usually floors `-i` at 0.2 s/stream,
     * so pps ≈ workers / 0.2.
     */
    private fun parallelDeadlineFlood(
        host: String,
        durationMs: Long,
        workers: Int,
        payloadBytes: Int,
    ): FloodResult {
        val isV6 = host.contains(':')
        val size = payloadBytes.toString()
        val deadlineSec = ((durationMs + 999) / 1_000L).toInt().coerceAtLeast(1)
        val bin = if (isV6) "ping6" else "ping"
        // Prefer -w deadline; fallback -c for ~duration at 0.2 s.
        val countFallback = (deadlineSec * 5).coerceAtLeast(5)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)
        val t0 = System.currentTimeMillis()
        try {
            val futures = List(workers) {
                pool.submit<FloodResult?> {
                    val cmds = listOf(
                        arrayOf(bin, "-w", deadlineSec.toString(), "-i", "0.2", "-s", size, host),
                        arrayOf(bin, "-c", countFallback.toString(), "-i", "0.2", "-W", "1", "-s", size, host),
                        arrayOf("ping", "-w", deadlineSec.toString(), "-i", "0.2", "-s", size, host),
                    )
                    for (cmd in cmds) {
                        try {
                            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                            val out = proc.inputStream.bufferedReader().use { it.readText() }
                            if (!proc.waitFor(deadlineSec + 5L, TimeUnit.SECONDS)) {
                                proc.destroyForcibly()
                                continue
                            }
                            parseFloodSummary(out, durationMs)?.let { return@submit it }
                        } catch (_: Exception) {
                            // next cmd
                        }
                    }
                    null
                }
            }
            var sent = 0
            var recv = 0
            val rtts = ArrayList<Double>()
            for (f in futures) {
                val part = runCatching {
                    f.get(deadlineSec + 8L, TimeUnit.SECONDS)
                }.getOrNull() ?: continue
                sent += part.sent
                recv += part.received
                rtts.addAll(part.rtts)
            }
            val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            return FloodResult(sent = sent, received = recv, rtts = rtts, elapsedMs = elapsed)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun parseFloodSummary(out: String, elapsedMs: Long): FloodResult? {
        val tx = TX_RE.find(out)?.groupValues?.get(1)?.toIntOrNull()
        val rx = RX_RE.find(out)?.groupValues?.get(1)?.toIntOrNull()
        val times = TIME_RE.findAll(out).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        if (tx == null && times.isEmpty()) return null
        val sent = tx ?: times.size
        val received = rx ?: times.size
        return FloodResult(
            sent = sent,
            received = received.coerceAtMost(sent),
            rtts = times,
            elapsedMs = elapsedMs.coerceAtLeast(1L),
        )
    }

    private fun reachablePing(host: String, timeoutMs: Int): Double? {
        return try {
            val addr = InetAddress.getByName(host)
            val start = System.nanoTime()
            val ok = addr.isReachable(timeoutMs)
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            if (ok) elapsed else null
        } catch (_: Exception) {
            null
        }
    }

    fun formatRtt(rttMs: Double?): String =
        when {
            rttMs == null -> "—"
            rttMs < 10 -> String.format("%.1f ms", rttMs)
            else -> "${rttMs.roundToLong()} ms"
        }

    /** Packet loss over a rolling window: lost / attempted. */
    fun formatLoss(lost: Int, attempted: Int): String {
        if (attempted <= 0) return "—"
        val pct = lost * 100.0 / attempted
        return when {
            pct == 0.0 -> "0%"
            pct < 10.0 -> String.format("%.1f%%", pct)
            else -> "${pct.roundToLong()}%"
        }
    }

    /** Packets per second over a rolling window (attempted / elapsed). */
    fun formatPps(pps: Double): String =
        when {
            pps <= 0.0 -> "—"
            pps < 10.0 -> String.format("%.1f", pps)
            else -> pps.roundToLong().toString()
        }

    fun pps(attempted: Int, firstTMs: Long, lastTMs: Long): Double {
        if (attempted <= 0) return 0.0
        val elapsedSec = (lastTMs - firstTMs).coerceAtLeast(1L) / 1_000.0
        return attempted / elapsedSec
    }

    private val TIME_RE = Regex("""(?i)time[=<]\s*([\d.]+)\s*ms""")
    private val TX_RE = Regex("""(?i)(\d+)\s+packets?\s+transmitted""")
    private val RX_RE = Regex("""(?i)(\d+)\s+(?:packets?\s+)?received""")
}
