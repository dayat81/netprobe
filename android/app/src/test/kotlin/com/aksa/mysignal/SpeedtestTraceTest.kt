package com.aksa.mysignal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedtestTraceTest {
    @Test
    fun traceKeepsStarHopAndDropsEmptyFamily() {
        val json = SpeedtestReport.traceJson(
            listOf(
                NativeTraceroute.Hop(1, "10.1.1.1", 4.2),
                NativeTraceroute.Hop(2, null, null),
            ),
            emptyList(),
        )
        val v4 = json!!.getJSONArray("ipv4")
        assertEquals(2, v4.length())
        assertEquals("10.1.1.1", v4.getJSONObject(0).getString("host"))
        assertEquals(4.2, v4.getJSONObject(0).getDouble("rtt_ms"), 0.01)
        assertFalse(v4.getJSONObject(1).has("host"))
        assertFalse(json.has("ipv6"))
    }

    @Test
    fun traceOmitsWhenBothPathsAreEmpty() {
        assertNull(SpeedtestReport.traceJson(emptyList(), emptyList()))
    }
}
