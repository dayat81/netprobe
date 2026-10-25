package com.aksa.magnon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestGateTest {
    @Test
    fun aRoundWaitsForAProviderAndAGpsPlace() {
        assertFalse(TestGate.ready("", "Gondangdia", "Menteng", "", ""))
        assertFalse(TestGate.ready("—", "Gondangdia", "Menteng", "", ""))
        assertFalse(TestGate.ready("…", "", "", "", ""))
        assertFalse(TestGate.ready("Telkomsel", "", "", "", ""))
        assertTrue(TestGate.ready("Telkomsel", "", "Menteng", "", ""))
    }
}
