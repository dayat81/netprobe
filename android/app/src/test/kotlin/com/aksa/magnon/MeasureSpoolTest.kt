package com.aksa.magnon

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeasureSpoolTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun peekReturnsOldestFirstWithinLimits() {
        val spool = MeasureSpool(temp.newFolder("spool"))
        for (i in 0 until 5) spool.enqueue("""{"n":$i}""", nowMs = 1_000L + i)
        val batch = spool.peek(maxItems = 3, maxBytes = 1_000)
        assertEquals(listOf("""{"n":0}""", """{"n":1}""", """{"n":2}"""), batch.map { it.readText() })
        spool.remove(batch)
        assertEquals(2, spool.count())
    }

    @Test
    fun oversizedSingleFileIsStillReturned() {
        val spool = MeasureSpool(temp.newFolder("spool"))
        spool.enqueue("x".repeat(500), nowMs = 1L)
        spool.enqueue("y", nowMs = 2L)
        assertEquals(1, spool.peek(maxItems = 12, maxBytes = 10).size)
    }

    @Test
    fun trimDropsOldestBeyondFileCap() {
        val spool = MeasureSpool(temp.newFolder("spool"), maxFiles = 3, ttlMs = Long.MAX_VALUE / 2)
        for (i in 0 until 6) spool.enqueue("""{"n":$i}""", nowMs = 10_000L + i)
        val left = spool.peek(maxItems = 12, maxBytes = 10_000).map { it.readText() }
        assertEquals(listOf("""{"n":3}""", """{"n":4}""", """{"n":5}"""), left)
    }
}
