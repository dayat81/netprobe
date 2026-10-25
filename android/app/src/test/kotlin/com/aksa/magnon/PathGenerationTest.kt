package com.aksa.magnon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathGenerationTest {
    @Test
    fun newerPathGenerationDropsTheOlderHello() {
        val generation = PathGeneration()
        val wifi = generation.next()
        val cell = generation.next()
        assertFalse(generation.accept(wifi))
        assertTrue(generation.accept(cell))
    }
}
