package com.aksa.magnon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceFillTest {
    @Test
    fun coordinateRowNeedsANameUntilOneArrives() {
        assertEquals("—", PlaceFill.label("", "", "", ""))
        assertTrue(PlaceFill.pending("", "", "", "", -6.2, 106.8))
        assertEquals("Menteng · Jakarta Pusat", PlaceFill.label("", "Menteng", "Jakarta Pusat", ""))
        assertFalse(PlaceFill.pending("", "Menteng", "", "", -6.2, 106.8))
        assertFalse(PlaceFill.pending("", "", "", "", null, null))
    }
}
