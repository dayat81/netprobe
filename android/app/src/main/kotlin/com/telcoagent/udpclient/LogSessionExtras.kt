package com.telcoagent.udpclient

data class LogSessionExtras(
    val localIp: String? = null,
    val dnsServers: String? = null,
    val networkType: String? = null,
    val udpLatencyMs: Double? = null,
    val udpLossPct: Double? = null,
    val udpJitterMs: Double? = null,
    val udpUplinkMs: Double? = null,
    val udpDownlinkMs: Double? = null,
    val udpUplinkLossPct: Double? = null,
    val udpDownlinkLossPct: Double? = null,
)
