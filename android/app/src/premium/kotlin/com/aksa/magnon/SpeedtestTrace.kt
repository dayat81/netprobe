package com.aksa.magnon

import org.json.JSONArray
import org.json.JSONObject

/** Hop list for a premium traceroute. Lite uploads do not carry this. */
object SpeedtestTrace {
    fun traceJson(
        hops4: List<NativeTraceroute.Hop>,
        hops6: List<NativeTraceroute.Hop>,
    ): JSONObject? {
        val v4 = hopArray(hops4)
        val v6 = hopArray(hops6)
        if (v4.length() == 0 && v6.length() == 0) return null
        val trace = JSONObject()
        if (v4.length() > 0) trace.put("ipv4", v4)
        if (v6.length() > 0) trace.put("ipv6", v6)
        return trace
    }

    private fun hopArray(hops: List<NativeTraceroute.Hop>): JSONArray {
        val array = JSONArray()
        hops.take(30).forEach { hop ->
            val item = JSONObject().put("ttl", hop.ttl.coerceIn(1, 64))
            hop.host?.trim()?.takeIf { it.isNotEmpty() }?.let { item.put("host", it.take(64)) }
            hop.rttMs?.takeIf { it.isFinite() && it >= 0.0 }?.let { item.put("rtt_ms", it) }
            array.put(item)
        }
        return array
    }
}
