package com.aksa.mysignal

import android.content.Context
import android.util.Log

/**
 * Shared gateway IP + RTT samples (IPv4 and IPv6) for MEASURE ticks and the Speed UI.
 *
 * Resolving via traceroute can take >1 s per family on cell; we refresh at most
 * every [MIN_INTERVAL_MS] so the 3 s radio tick stays responsive.
 */
object GatewayMonitor {
    private const val TAG = "MySignalGw"
    private const val MIN_INTERVAL_MS = 2_500L

    data class Sample(
        val ipv4: String? = null,
        val ipv4RttMs: Double? = null,
        val ipv6: String? = null,
        val ipv6RttMs: Double? = null,
    )

    @Volatile
    var last: Sample = Sample()
        private set

    @Volatile
    private var lastSampleMs = 0L

    private val lock = Any()

    /** Latest values, refreshing if stale. Safe to call from the sampler thread. */
    fun sample(context: Context, force: Boolean = false): Sample {
        val now = System.currentTimeMillis()
        if (!force && lastSampleMs > 0L && now - lastSampleMs < MIN_INTERVAL_MS) {
            return last
        }
        synchronized(lock) {
            val again = System.currentTimeMillis()
            if (!force && lastSampleMs > 0L && again - lastSampleMs < MIN_INTERVAL_MS) {
                return last
            }
            val app = context.applicationContext
            val v4 = ClientNetworkInfo.gatewayIp(app).takeIf { it.isNotBlank() && it != "—" }
            val v4Rtt = if (v4 != null) GatewayPinger.pingOnce(v4) else null
            val v6 = ClientNetworkInfo.gatewayIpv6(app).takeIf { it.isNotBlank() && it != "—" }
            val v6Rtt = if (v6 != null) GatewayPinger.pingOnce(v6) else null
            last = Sample(ipv4 = v4, ipv4RttMs = v4Rtt, ipv6 = v6, ipv6RttMs = v6Rtt)
            lastSampleMs = again
            Log.i(
                TAG,
                "measure gw4=${v4 ?: "—"} rtt4=${GatewayPinger.formatRtt(v4Rtt)} " +
                    "gw6=${v6 ?: "—"} rtt6=${GatewayPinger.formatRtt(v6Rtt)}",
            )
            return last
        }
    }
}
