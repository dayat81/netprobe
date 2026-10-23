package com.aksa.mysignal

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/** ICMP / reachability RTT to the default gateway. */
object GatewayPinger {
    data class Sample(val tMs: Long, val rttMs: Double?)

    fun pingOnce(host: String, timeoutMs: Int = 1_200): Double? {
        if (host.isBlank() || host == "—") return null
        shellPing(host)?.let { return it }
        return reachablePing(host, timeoutMs)
    }

    private fun shellPing(host: String): Double? {
        val isV6 = host.contains(':')
        val variants = if (isV6) {
            listOf(
                arrayOf("ping6", "-c", "1", "-W", "1", host),
                arrayOf("ping", "-c", "1", "-W", "1", host),
            )
        } else {
            listOf(
                arrayOf("ping", "-c", "1", "-W", "1", host),
                arrayOf("ping", "-c", "1", "-w", "1", host),
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

    private val TIME_RE = Regex("""(?i)time[=<]\s*([\d.]+)\s*ms""")
}
