package com.aksa.magnon

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedtestMathTest {
    @Test
    fun medianOddAndEven() {
        assertEquals(12L, SpeedtestMath.medianMs(listOf(20, 12, 8)))
        assertEquals(15L, SpeedtestMath.medianMs(listOf(10, 20)))
        assertEquals(0L, SpeedtestMath.medianMs(emptyList()))
    }

    @Test
    fun idlePingIsTheLowestSample() {
        val settled = listOf(164L, 129L, 124L, 188L, 120L, 168L, 115L, 129L, 115L, 123L, 184L, 114L)
        assertEquals(114L, SpeedtestMath.idlePingMs(settled))
        assertEquals(0L, SpeedtestMath.idlePingMs(emptyList()))
    }

    @Test
    fun jitterIsMeanAbsDelta() {
        assertEquals(8L, SpeedtestMath.jitterMs(listOf(10, 20, 15)))
        assertEquals(0L, SpeedtestMath.jitterMs(listOf(8)))
    }

    @Test
    fun mbpsFromBytesAndElapsed() {
        assertEquals(8.0, SpeedtestMath.mbps(1_000_000, 1_000), 0.0001)
        assertEquals(0.0, SpeedtestMath.mbps(100, 0), 0.0)
    }

    @Test
    fun trimmedMeanDropsOutliersWhenEnoughSamples() {
        val samples = (1..10).map { it * 10.0 } // 10..100
        // Ookla: drop lowest 30% (3) + highest 10% (1) → 40..90, mean = 65
        assertEquals(65.0, SpeedtestMath.trimmedMeanMbps(samples), 0.0001)
    }

    @Test
    fun trimmedMeanFallsBackToMedianWhenFewSamples() {
        assertEquals(20.0, SpeedtestMath.trimmedMeanMbps(listOf(10.0, 20.0, 100.0)), 0.0001)
        assertEquals(0.0, SpeedtestMath.trimmedMeanMbps(emptyList()), 0.0)
    }

    @Test
    fun stableMbpsDropsGapZerosTowardPlateau() {
        // Plateau ~42 with HTTP gap zeros — must not drag to teens.
        val samples = listOf(
            0.0, 0.5, 41.0, 42.0, 43.0, 40.0, 44.0, 42.5, 41.5, 0.0,
            43.0, 42.0, 1.0, 40.5, 41.8,
        )
        val score = SpeedtestMath.stableMbps(samples)
        assertEquals(42.0, score, 2.5)
    }

    @Test
    fun stableMbpsSurvivesMajorityZeroUploadGaps() {
        // Production 2.5.60: p50=0 when >half of 500ms windows are idle stalls.
        val samples = listOf(
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            80.0, 95.0, 110.0, 100.0, 88.0, 105.0, 92.0, 98.0,
        )
        val score = SpeedtestMath.stableMbps(samples, lowFrac = 0.30)
        org.junit.Assert.assertTrue("score=$score", score in 85.0..110.0)
    }

    @Test
    fun ooklaUploadAveragesFastestHalf() {
        val samples = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0)
        // Fastest half: 50..80 → mean 65
        assertEquals(65.0, SpeedtestMath.ooklaUploadMbps(samples), 0.0001)
    }

    @Test
    fun stableMbpsUnchangedWhenSamplesAreSimilar() {
        val samples = List(12) { 36.0 + (it % 3) * 0.5 }
        val score = SpeedtestMath.stableMbps(samples)
        assertEquals(36.5, score, 1.0)
    }

    @Test
    fun ooklaServerUrls() {
        val base = "http://20.205.188.36:8080/speedtest/"
        assertEquals("http://20.205.188.36:8080/speedtest/hello", SpeedtestEngine.helloUrl(base))
        assertEquals(
            "http://20.205.188.36:8080/speedtest/download?size=2048",
            SpeedtestEngine.downloadUrl(base, 2048),
        )
        assertEquals("http://20.205.188.36:8080/speedtest/upload", SpeedtestEngine.uploadUrl(base))
    }

    @Test
    fun ooklaStyleGaugeFractionIsPiecewise() {
        assertEquals(0f, SpeedtestGaugeView.fractionForMbps(0f), 0.001f)
        // Midpoint between 50 and 100 on the 9-slot Ookla 1000 Mbps scale → slot 3.5 / 8
        assertEquals(3.5f / 8f, SpeedtestGaugeView.fractionForMbps(75f), 0.001f)
        assertEquals(1f, SpeedtestGaugeView.fractionForMbps(1000f), 0.001f)
        assertEquals(1f, SpeedtestGaugeView.fractionForMbps(2000f), 0.001f)
    }
}
