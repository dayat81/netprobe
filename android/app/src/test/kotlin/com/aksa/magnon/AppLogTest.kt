package com.aksa.magnon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test
    fun ringDropsTheOldestAndClipsEachLine() {
        AppLog.clear()
        repeat(AppLog.CAP + 10) { index ->
            AppLog.note("info", "T", "m$index " + "x".repeat(300) + "\nmore")
        }
        val lines = AppLog.recent(AppLog.CAP + 10)
        assertEquals(AppLog.CAP, lines.size)
        assertTrue(lines.first().message.startsWith("m10 "))
        assertTrue(lines.last().message.length <= AppLog.LINE)
        assertFalse(lines.last().message.contains('\n'))
        AppLog.clear()
    }
}
