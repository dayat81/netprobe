package com.aksa.mysignal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioRulesTest {
    private fun cc(rat: String, status: String, channel: Int? = null) =
        ComponentCarrier(rat, status, channel, null, null, null)

    @Test
    fun parsesNrStateLongestNameFirst() {
        assertEquals(2, RadioRules.parseNrState("x nrState=NOT_RESTRICTED y"))
        assertEquals(1, RadioRules.parseNrState("nrState=RESTRICTED"))
        assertEquals(3, RadioRules.parseNrState("nrState=CONNECTED, nrFrequencyRange=MID"))
        assertEquals(0, RadioRules.parseNrState("nrState=NONE"))
        assertEquals(3, RadioRules.parseNrState("nrState=3"))
        assertNull(RadioRules.parseNrState("nrState=9"))
        assertNull(RadioRules.parseNrState("no field"))
        assertNull(RadioRules.parseNrState(null))
    }

    @Test
    fun parsesRegistrationFlags() {
        val text = "DataSpecificRegistrationInfo :{ isNrAvailable = true isEnDcAvailable = true " +
            "mIsUsingCarrierAggregation = false }"
        assertEquals(true, RadioRules.parseEndcAvailable(text))
        assertEquals(true, RadioRules.parseEndcAvailable("CellInfoLte:{isEndcAvailable=true}"))
        assertEquals(false, RadioRules.parseCarrierAggregation(text))
        assertEquals(true, RadioRules.parseCarrierAggregation("isUsingCarrierAggregation=true"))
        assertNull(RadioRules.parseCarrierAggregation("nothing here"))
        assertNull(RadioRules.parseEndcAvailable(""))
    }

    @Test
    fun reflectedNrStateWinsOverRedactedText() {
        val carriers = listOf(cc("LTE", "primary", 1475))
        val derived = RadioRules.selectNrState(3, null, carriers, RadioRules.NETWORK_TYPE_LTE)
        assertEquals(3, derived.value)
        assertEquals("reflection", derived.source)
        assertNull(RadioRules.selectNrState(null, null, emptyList(), RadioRules.NETWORK_TYPE_LTE).value)
    }

    @Test
    fun nrSecondaryCarrierMeansConnectedEvenWhenRegistrationLags() {
        val carriers = listOf(cc("LTE", "primary", 1475), cc("NR", "secondary", 531870))
        val derived = RadioRules.selectNrState(2, null, carriers, RadioRules.NETWORK_TYPE_LTE)
        assertEquals(3, derived.value)
        assertEquals("physical_channel", derived.source)
    }

    @Test
    fun serviceStateCaTrueOverridesNestedFalse() {
        val full = "isUsingCarrierAggregation=true nrState=**** " +
            "NetworkRegistrationInfo{ isUsingCarrierAggregation=false }"
        assertEquals(true, RadioRules.parseCarrierAggregation(full))
        val derived = RadioRules.deriveCarrierAggregation(
            RadioRules.NETWORK_TYPE_LTE,
            null,
            RadioRules.parseCarrierAggregation(full),
            false,
            emptyList(),
        )
        assertEquals(true, derived.value)
        assertEquals("service_state", derived.source)
    }

    @Test
    fun carrierAggregationSources() {
        assertEquals(
            "network_type",
            RadioRules.deriveCarrierAggregation(RadioRules.NETWORK_TYPE_LTE_CA, null, null, null, emptyList()).source,
        )
        val lteCa = listOf(cc("LTE", "primary", 1475), cc("LTE", "secondary", 200))
        assertEquals(true, RadioRules.deriveCarrierAggregation(13, null, null, null, lteCa).value)
        val endcOnly = listOf(cc("LTE", "primary", 1475), cc("NR", "secondary", 531870))
        val endc = RadioRules.deriveCarrierAggregation(13, null, false, false, endcOnly)
        assertEquals(false, endc.value)
        assertEquals(true, RadioRules.deriveCarrierAggregation(13, null, null, true, emptyList()).value)
        assertEquals(true, RadioRules.deriveCarrierAggregation(13, RadioRules.OVERRIDE_LTE_CA, null, null, emptyList()).value)
        assertNull(RadioRules.deriveCarrierAggregation(13, null, null, null, emptyList()).value)
    }

    @Test
    fun frequencyRangeFromNrArfcn() {
        assertEquals(2, RadioRules.nrFrequencyRangeFromArfcn(531870))
        assertEquals(1, RadioRules.nrFrequencyRangeFromArfcn(158200))
        assertEquals(3, RadioRules.nrFrequencyRangeFromArfcn(636666))
        assertEquals(4, RadioRules.nrFrequencyRangeFromArfcn(2070015))
        assertNull(RadioRules.nrFrequencyRangeFromArfcn(null))
    }

    @Test
    fun servingSinrFillsOnlyTheCampedCell() {
        val serving = RadioCell(registered = true, rat = "LTE", earfcn = 1850, rsrp = -90, subId = 3)
        val neighbour = RadioCell(registered = false, rat = "LTE", earfcn = 1850, pci = 145, rsrp = -88, subId = 3)
        val filled = RadioRules.fillServingSinr(listOf(serving, neighbour), mapOf((3 to "LTE") to -4))
        assertEquals(-4, filled[0].sinr)
        assertNull(filled[1].sinr)
        val kept = RadioRules.fillServingSinr(listOf(serving.copy(sinr = 7)), mapOf((3 to "LTE") to -4))
        assertEquals(7, kept[0].sinr)
    }

    @Test
    fun sentinelsAndCleanText() {
        assertNull(RadioRules.bounded(Int.MAX_VALUE, -200, 0))
        assertNull(RadioRules.bounded(5, -200, 0))
        assertEquals(-95, RadioRules.bounded(-95, -200, 0))
        assertEquals("", RadioRules.cellId(Int.MAX_VALUE.toLong()))
        assertEquals("12345", RadioRules.cellId(12345))
        assertEquals("INDOSAT", RadioRules.clean(" INDOSAT\u0000 ", 64))
        assertEquals("", RadioRules.clean("null", 64))
        assertEquals("LTE", RadioRules.ratName(19))
        assertEquals("NR", RadioRules.ratName(20))
    }
}
