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

    /**
     * Ookla-style trimmed mean over post-ramp window samples.
     * Drops lowest/highest 10% (at least one each side when n ≥ 10); falls back to median when n < 10.
     */
    fun trimmedMeanMbps(samples: List<Double>): Double {
        if (samples.isEmpty()) return 0.0
        val sorted = samples.sorted()
        if (sorted.size < 10) {
            return medianDouble(sorted)
        }
        val trim = (sorted.size * 0.10).toInt().coerceAtLeast(1)
        val kept = sorted.subList(trim, sorted.size - trim)
        if (kept.isEmpty()) return sorted[sorted.size / 2]
        return kept.sum() / kept.size
    }

    /**
     * Stable goodput: drop idle/gap samples below 20% of median, then 20% trimmed mean.
     * Aimed at plateau scores (Ookla-like) instead of HTTP chunk-gap drag.
     */
    fun stableMbps(samples: List<Double>): Double {
        if (samples.isEmpty()) return 0.0
        val sorted = samples.sorted()
        val median = medianDouble(sorted)
        if (median <= 0.0) return 0.0
        val floor = median * 0.20
        val active = sorted.filter { it >= floor }
        val pool = if (active.isEmpty()) sorted else active
        if (pool.size < 5) return medianDouble(pool)
        if (pool.size < 10) return medianDouble(pool)
        val trim = (pool.size * 0.20).toInt().coerceAtLeast(1)
        val end = pool.size - trim
        if (end <= trim) return medianDouble(pool)
        val kept = pool.subList(trim, end)
        if (kept.isEmpty()) return medianDouble(pool)
        return kept.sum() / kept.size
    }

    fun medianDouble(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 0.0
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    fun formatMbps(value: Double): String =
        if (value >= 100.0) "%.0f".format(value) else "%.1f".format(value)

    fun formatMs(value: Long): String = value.toString()
}
