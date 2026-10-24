package com.aksa.mysignal

/**
 * Download control matching Ookla `libandroidsharedsuite`:
 * `ConnectionScaling` (enabled, factor, maxConnections) and
 * `StableStop.recordBandwidth` / `isBandwidthStable`.
 */
class ConnectionScaler(
    val enabled: Boolean = true,
    val initial: Int = 4,
    val factor: Int = 2,
    val maxConnections: Int = 16,
) {
    fun grow(current: Int): Int {
        if (!enabled) return current
        if (current < initial) return initial
        if (current >= maxConnections) return maxConnections
        return (current + factor).coerceAtMost(maxConnections)
    }
}

/** Last few bandwidth samples are a plateau, so the stage can stop early. */
class BandwidthStableStop(
    private val samplesNeeded: Int = 4,
    private val relativeBand: Double = 0.12,
) {
    private val samples = ArrayDeque<Double>()

    fun record(mbps: Double) {
        if (mbps <= 0.5) return
        samples.addLast(mbps)
        while (samples.size > samplesNeeded) samples.removeFirst()
    }

    fun isStable(): Boolean {
        if (samples.size < samplesNeeded) return false
        val sorted = samples.sorted()
        val median = SpeedtestMath.medianDouble(sorted)
        if (median <= 0.0) return false
        val spread = sorted.last() - sorted.first()
        return spread <= median * relativeBand
    }
}
