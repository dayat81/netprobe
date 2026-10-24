package com.aksa.mysignal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OoklaSuiteTest {
    @Test
    fun scalingAddsFactorUntilMax() {
        val scaler = ConnectionScaler(initial = 4, factor = 2, maxConnections = 8)
        assertEquals(4, scaler.grow(0))
        assertEquals(6, scaler.grow(4))
        assertEquals(8, scaler.grow(6))
        assertEquals(8, scaler.grow(8))
    }

    @Test
    fun scalingStaysPutWhenDisabled() {
        val scaler = ConnectionScaler(enabled = false, initial = 4, factor = 2, maxConnections = 16)
        assertEquals(4, scaler.grow(4))
    }

    @Test
    fun stableStopNeedsAPlateau() {
        val stop = BandwidthStableStop()
        listOf(40.0, 42.0, 41.0).forEach(stop::record)
        assertFalse(stop.isStable())
        stop.record(43.0)
        assertTrue(stop.isStable())
    }

    @Test
    fun climbingBandwidthIsNotStable() {
        val stop = BandwidthStableStop()
        listOf(10.0, 20.0, 30.0, 40.0).forEach(stop::record)
        assertFalse(stop.isStable())
    }
}
