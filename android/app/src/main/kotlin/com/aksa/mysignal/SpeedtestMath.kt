package com.aksa.mysignal

import kotlin.math.abs
import kotlin.math.roundToLong

/** Ping/jitter/Mbps math matching Ookla-style result fields. */
object SpeedtestMath {
    fun medianMs(samples: List<Long>): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            ((sorted[mid - 1] + sorted[mid]) / 2.0).roundToLong()
        } else {
            sorted[mid]
        }
    }

    fun jitterMs(samples: List<Long>): Long {
        if (samples.size < 2) return 0L
        var total = 0.0
        for (i in 1 until samples.size) {
            total += abs(samples[i] - samples[i - 1]).toDouble()
        }
        return (total / (samples.size - 1)).roundToLong()
    }

    fun mbps(bytes: Long, elapsedMs: Long): Double {
        if (bytes <= 0L || elapsedMs <= 0L) return 0.0
        return (bytes * 8.0) / (elapsedMs * 1_000.0)
    }

    fun formatMbps(value: Double): String =
        if (value >= 100.0) "%.0f".format(value) else "%.1f".format(value)

    fun formatMs(value: Long): String = value.toString()
}
