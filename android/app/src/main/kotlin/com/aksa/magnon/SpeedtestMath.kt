package com.aksa.magnon

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

    /** Ookla reports the lowest unloaded ping, not the mean. */
    fun idlePingMs(samples: List<Long>): Long {
        if (samples.isEmpty()) return 0L
        return samples.min()
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
     * Classic Ookla download trim: drop lowest 30% and highest 10%, average the rest.
     * Falls back to median when n < 10.
     */
    fun trimmedMeanMbps(
        samples: List<Double>,
        lowFrac: Double = 0.30,
        highFrac: Double = 0.10,
    ): Double {
        if (samples.isEmpty()) return 0.0
        val sorted = samples.sorted()
        if (sorted.size < 10) {
            return medianDouble(sorted)
        }
        val low = (sorted.size * lowFrac).toInt().coerceAtLeast(1)
        val high = (sorted.size * highFrac).toInt().coerceAtLeast(1)
        val end = sorted.size - high
        if (end <= low) return medianDouble(sorted)
        val kept = sorted.subList(low, end)
        if (kept.isEmpty()) return sorted[sorted.size / 2]
        return kept.sum() / kept.size
    }

    /**
     * Download score: ignore near-idle gaps, then Ookla 30%/10% trim.
     */
    fun stableMbps(
        samples: List<Double>,
        lowFrac: Double = 0.30,
        highFrac: Double = 0.10,
    ): Double {
        if (samples.isEmpty()) return 0.0
        val positive = samples.filter { it > 0.0 }
        val base = if (positive.size >= 5) positive else samples
        val sorted = base.sorted()
        val median = medianDouble(sorted)
        if (median <= 0.0) return 0.0
        val floor = median * 0.20
        val active = sorted.filter { it >= floor }
        val pool = if (active.size >= 5) active else sorted
        return trimmedMeanMbps(pool, lowFrac = lowFrac, highFrac = highFrac)
    }

    /**
     * Ookla upload: sort chunk speeds, average the fastest half
     * (https://www.speedtest.net/about/knowledge/faq — upload methodology).
     */
    fun ooklaUploadMbps(samples: List<Double>): Double {
        val positive = samples.filter { it > 0.0 }.sorted()
        if (positive.isEmpty()) return 0.0
        if (positive.size < 4) return medianDouble(positive)
        val start = positive.size / 2
        val fastest = positive.subList(start, positive.size)
        return fastest.sum() / fastest.size
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
