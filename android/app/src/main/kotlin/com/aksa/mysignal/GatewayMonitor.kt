package com.aksa.mysignal

import android.content.Context
import android.util.Log

/**
 * Shared gateway IP + RTT samples (IPv4 and IPv6) for MEASURE ticks and the Speed UI.
 *
 * Traceroute IP resolve is cached ([MIN_RESOLVE_MS]); ICMP ping runs on every
 * [sample] so the Speed tab can measure packet loss at ~1 Hz.
 * Ping payload fills the active-network MTU (IP packet size == MTU).
 */
object GatewayMonitor {
    private const val TAG = "MySignalGw"
    private const val MIN_RESOLVE_MS = 2_500L

    data class Sample(
        val ipv4: String? = null,
        val ipv4RttMs: Double? = null,
        val ipv6: String? = null,
        val ipv6RttMs: Double? = null,
        val mtu: Int = 1500,
    )

    @Volatile
    var last: Sample = Sample()
        private set

    @Volatile
    private var lastResolveMs = 0L

    @Volatile
    private var cachedV4: String? = null

    @Volatile
    private var cachedV6: String? = null

    private val lock = Any()

    /** Resolve IPs if stale, then ping both. Safe from sampler / UI threads. */
    fun sample(context: Context, forceResolve: Boolean = false): Sample {
        synchronized(lock) {
            val app = context.applicationContext
            val now = System.currentTimeMillis()
            if (forceResolve || lastResolveMs == 0L || now - lastResolveMs >= MIN_RESOLVE_MS) {
                cachedV4 = ClientNetworkInfo.gatewayIp(app).takeIf { it.isNotBlank() && it != "—" }
                cachedV6 = ClientNetworkInfo.gatewayIpv6(app).takeIf { it.isNotBlank() && it != "—" }
                lastResolveMs = now
            }
            val mtu = GatewayPinger.activeMtu(app)
            val v4 = cachedV4
            val v6 = cachedV6
            val v4Payload = GatewayPinger.payloadForMtu(mtu, ipv6 = false)
            val v6Payload = GatewayPinger.payloadForMtu(mtu, ipv6 = true)
            val v4Rtt = if (v4 != null) GatewayPinger.pingOnce(v4, payloadBytes = v4Payload) else null
            val v6Rtt = if (v6 != null) GatewayPinger.pingOnce(v6, payloadBytes = v6Payload) else null
            last = Sample(
                ipv4 = v4,
                ipv4RttMs = v4Rtt,
                ipv6 = v6,
                ipv6RttMs = v6Rtt,
                mtu = mtu,
            )
            Log.i(
                TAG,
                "measure mtu=$mtu s4=$v4Payload s6=$v6Payload " +
                    "gw4=${v4 ?: "—"} rtt4=${GatewayPinger.formatRtt(v4Rtt)} " +
                    "gw6=${v6 ?: "—"} rtt6=${GatewayPinger.formatRtt(v6Rtt)}",
            )
            return last
        }
    }
}
