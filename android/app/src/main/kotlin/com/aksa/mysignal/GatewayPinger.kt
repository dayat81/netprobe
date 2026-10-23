package com.aksa.mysignal

import android.content.Context
import android.net.ConnectivityManager
import java.net.InetAddress
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

    fun pingOnce(host: String, timeoutMs: Int = 1_200, payloadBytes: Int? = null): Double? {
        if (host.isBlank() || host == "—") return null
        val isV6 = host.contains(':')
        val size = (payloadBytes ?: defaultPayload(isV6)).coerceIn(16, 65_507)
        shellPing(host, size)?.let { return it }
        return reachablePing(host, timeoutMs)
    }

    fun pingOnce(context: Context, host: String, timeoutMs: Int = 1_200): Double? {
        val isV6 = host.contains(':')
        return pingOnce(host, timeoutMs, payloadForMtu(activeMtu(context), isV6))
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

    private val TIME_RE = Regex("""(?i)time[=<]\s*([\d.]+)\s*ms""")
}
