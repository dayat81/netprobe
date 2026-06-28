package com.telcoagent.udpclient

data class RoundMetrics(
    val roundNo: Int,
    var sent: Int = 0,
    var received: Int = 0,
    var replyRx: Int = 0,
    var serverRx: Int? = null,
    var serverTx: Int? = null,
    var maxServerRxSeen: Int = 0,
    var maxServerTxSeen: Int = 0,
    val latenciesMs: MutableList<Double> = mutableListOf(),
    val uplinkMs: MutableList<Double> = mutableListOf(),
    val downlinkMs: MutableList<Double> = mutableListOf(),
    val rttMs: MutableList<Double> = mutableListOf(),
    var skewRejected: Int = 0,
    var clockOffsetMs: Double? = null,
) {
    val lost: Int get() = sent - received
    val lossPct: Double get() = if (sent == 0) 0.0 else lost.toDouble() / sent * 100.0

    private fun resolvedServerRx(): Int? {
        serverRx?.let { return it }
        return maxServerRxSeen.takeIf { it > 0 }
    }

    private fun resolvedServerTx(): Int? {
        serverTx?.let { return it }
        return maxServerTxSeen.takeIf { it > 0 }
    }

    val uplinkLossPct: Double?
        get() {
            if (sent == 0) return null
            val rx = resolvedServerRx() ?: return null
            return UdpProbeProtocol.uplinkLossPct(sent, rx)
        }

    val downlinkLossPct: Double?
        get() {
            val tx = resolvedServerTx() ?: return null
            return UdpProbeProtocol.downlinkLossPct(tx, replyRx)
        }
    val avgLatencyMs: Double? get() = latenciesMs.takeIf { it.isNotEmpty() }?.average()
    val minLatencyMs: Double? get() = latenciesMs.minOrNull()
    val maxLatencyMs: Double? get() = latenciesMs.maxOrNull()
    val jitterMs: Double? get() = computeJitter(latenciesMs)
    val avgUplinkMs: Double? get() = uplinkMs.takeIf { it.isNotEmpty() }?.average()
    val minUplinkMs: Double? get() = uplinkMs.minOrNull()
    val maxUplinkMs: Double? get() = uplinkMs.maxOrNull()
    val avgDownlinkMs: Double? get() = downlinkMs.takeIf { it.isNotEmpty() }?.average()
    val minDownlinkMs: Double? get() = downlinkMs.minOrNull()
    val maxDownlinkMs: Double? get() = downlinkMs.maxOrNull()
    val avgRttMs: Double? get() = rttMs.takeIf { it.isNotEmpty() }?.average()

    fun recordReplyMatch() {
        replyRx += 1
    }

    fun applySummary(serverRx: Int, serverTx: Int) {
        this.serverRx = serverRx
        this.serverTx = serverTx
    }

    fun noteServerCounts(serverRx: Int, serverTx: Int) {
        maxServerRxSeen = maxOf(maxServerRxSeen, serverRx)
        maxServerTxSeen = maxOf(maxServerTxSeen, serverTx)
    }

    fun recordReply(rtt: Double) {
        received += 1
        latenciesMs.add(rtt)
        this.rttMs.add(rtt)
    }

    fun recordProbe(lat: UdpProbeProtocol.ProbeLatency) {
        received += 1
        uplinkMs.add(lat.uplinkMs)
        downlinkMs.add(lat.downlinkMs)
        rttMs.add(lat.rttMs)
        latenciesMs.add(lat.rttMs)
    }

    fun formatSummary(): String {
        return buildString {
            append("round $roundNo metrics: ")
            append("sent=$sent received=$received lost=$lost loss=${"%.1f".format(lossPct)}% ")
            append("uplink avg=${fmtMs(avgUplinkMs)} downlink avg=${fmtMs(avgDownlinkMs)} ")
            append("rtt avg=${fmtMs(avgRttMs)} jitter=${fmtMs(jitterMs)}")
        }
    }
}

data class OverallMetrics(
    val rounds: List<RoundMetrics>,
) {
    val sent: Int get() = rounds.sumOf { it.sent }
    val received: Int get() = rounds.sumOf { it.received }
    val lost: Int get() = sent - received
    val lossPct: Double get() = if (sent == 0) 0.0 else lost.toDouble() / sent * 100.0
    val replyRx: Int get() = rounds.sumOf { it.replyRx }
    val uplinkLossPct: Double?
        get() {
            if (sent == 0) return null
            var totalServerRx = 0
            var hasData = false
            for (round in rounds) {
                val rx = round.serverRx ?: round.maxServerRxSeen.takeIf { it > 0 }
                if (rx != null) {
                    totalServerRx += rx
                    hasData = true
                }
            }
            if (!hasData) return null
            return UdpProbeProtocol.uplinkLossPct(sent, totalServerRx)
        }
    val downlinkLossPct: Double?
        get() {
            var totalServerTx = 0
            var hasData = false
            for (round in rounds) {
                val tx = round.serverTx ?: round.maxServerTxSeen.takeIf { it > 0 }
                if (tx != null) {
                    totalServerTx += tx
                    hasData = true
                }
            }
            if (!hasData || totalServerTx == 0) return null
            return UdpProbeProtocol.downlinkLossPct(totalServerTx, replyRx)
        }
    val latenciesMs: List<Double> get() = rounds.flatMap { it.latenciesMs }
    val uplinkMs: List<Double> get() = rounds.flatMap { it.uplinkMs }
    val downlinkMs: List<Double> get() = rounds.flatMap { it.downlinkMs }
    val avgLatencyMs: Double? get() = latenciesMs.takeIf { it.isNotEmpty() }?.average()
    val minLatencyMs: Double? get() = latenciesMs.minOrNull()
    val maxLatencyMs: Double? get() = latenciesMs.maxOrNull()
    val jitterMs: Double? get() = computeJitter(latenciesMs)
    val avgUplinkMs: Double? get() = uplinkMs.takeIf { it.isNotEmpty() }?.average()
    val avgDownlinkMs: Double? get() = downlinkMs.takeIf { it.isNotEmpty() }?.average()
    val avgRttMs: Double? get() = rttMs.takeIf { it.isNotEmpty() }?.average()
    val rttMs: List<Double> get() = rounds.flatMap { it.rttMs }
    val perfectRounds: Int get() = rounds.count { it.lost == 0 }

    fun formatSummary(): String {
        return buildString {
            append("overall metrics: ")
            append("sent=$sent received=$received lost=$lost loss=${"%.1f".format(lossPct)}% ")
            append("uplink avg=${fmtMs(avgUplinkMs)} downlink avg=${fmtMs(avgDownlinkMs)} ")
            append("rtt avg=${fmtMs(avgRttMs)} jitter=${fmtMs(jitterMs)}\n")
            append("finished: $perfectRounds/${rounds.size} rounds with zero loss")
        }
    }
}

fun computeJitter(latenciesMs: List<Double>): Double? {
    if (latenciesMs.size < 2) return null
    val diffs = latenciesMs.zipWithNext { prev, next -> kotlin.math.abs(next - prev) }
    return diffs.average()
}

fun fmtMs(value: Double?): String {
    return if (value == null) "n/a" else "${"%.2f".format(value)}ms"
}
