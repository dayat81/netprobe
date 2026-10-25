package com.aksa.magnon

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

