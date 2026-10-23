package com.aksa.mysignal

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioChartLabelsTest {
    @Test
    fun arfcnAndEnbCarryRat() {
        val lte = RadioChartLabels.carrierKey("510-01", "LTE", 1475)
        val nr = RadioChartLabels.carrierKey("510-01", "NR", 531870)
        assertEquals("1475 LTE", RadioChartLabels.carrierLabel(lte, showPlmn = false))
        assertEquals("531870 NR", RadioChartLabels.carrierLabel(nr, showPlmn = false))
        assertEquals("531870 NR 510-01", RadioChartLabels.carrierLabel(nr, showPlmn = true))
        assertEquals("1475 LTE 510-01", RadioChartLabels.carrierLabel(lte, showPlmn = true))
        val lteSite = RadioChartLabels.siteKey("LTE", 2560, "510-01")
        val nrSite = RadioChartLabels.siteKey("NR", 2560, "510-01")
        assertEquals("10 LTE", RadioChartLabels.siteLabel(lteSite, showPlmn = false))
        assertEquals("10 NR", RadioChartLabels.siteLabel(nrSite, showPlmn = false))
        assertEquals("10 NR 510-01", RadioChartLabels.siteLabel(nrSite, showPlmn = true))
        val slot0 = RadioChartLabels.slotId("510-01", 0)
        val slot1 = RadioChartLabels.slotId("510-01", 1)
        assertEquals("510-01 ·1", RadioChartLabels.slotLabel(slot0))
        assertEquals("510-01 ·2", RadioChartLabels.slotLabel(slot1))
        org.junit.Assert.assertNotEquals(slot0, slot1)
        val slotted = RadioChartLabels.carrierKey(slot1, "LTE", 1475)
        assertEquals("1475 LTE 510-01 ·2", RadioChartLabels.carrierLabel(slotted, showPlmn = true))
        org.junit.Assert.assertNotEquals(lteSite, nrSite)
    }
}

class LteBandwidthTest {
    @Test
    fun keepsEveryCarrierWidthInMhz() {
        val text = "mChannelNumber=1475, mCellBandwidths=[10000, 10000, 60000]"
        val khz = RadioRules.cellBandwidthsKhz(text)
        assertEquals(listOf(10000, 10000, 60000), khz)
        assertEquals("10+10+60 MHz", RadioRules.mhzLabel(khz))
        assertEquals("10000+10000+60000 kHz", RadioRules.khzLabel(khz))
        assertEquals(1475, RadioRules.parseChannelNumber(text))
    }
}

class CellMergeTest {
    @Test
    fun nrArfcnFromModemRefreshBeatsEmptyLiveCache() {
        val live = CellMerge.Item("NR|510|01|42", channel = null, serving = true, fresh = false)
        val fresh = CellMerge.Item("NR|510|01|42", channel = 531870, serving = true, fresh = true)
        assertEquals(true, CellMerge.wins(fresh, live))
        assertEquals(false, CellMerge.wins(live, fresh))
    }

    @Test
    fun secondaryNrCountsAsServing() {
        val nr = RadioCell(registered = false, rat = "NR", nrarfcn = 531870, connection = RadioCell.CONNECTION_SECONDARY)
        val neighbor = RadioCell(registered = false, rat = "NR", nrarfcn = 531870)
        assertEquals(true, nr.chartServing)
        assertEquals(false, neighbor.chartServing)
    }
}

class SpeedtestMathTest {
    @Test
    fun medianOddAndEven() {
        assertEquals(12L, SpeedtestMath.medianMs(listOf(20, 12, 8)))
        assertEquals(15L, SpeedtestMath.medianMs(listOf(10, 20)))
        assertEquals(0L, SpeedtestMath.medianMs(emptyList()))
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
        // trim 1 each side → 20..90, mean = 55
        assertEquals(55.0, SpeedtestMath.trimmedMeanMbps(samples), 0.0001)
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
}
