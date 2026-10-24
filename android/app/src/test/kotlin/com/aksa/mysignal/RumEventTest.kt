package com.aksa.mysignal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RumEventTest {
    @Test
    fun sessionOmitsTraceAndClipsTheMessage() {
        val event = RumEvent.build(
            id = "rum-event-1",
            tsMs = 10,
            kind = "session",
            level = "info",
            code = "session",
            message = "x".repeat(600),
            appVersion = "2.5.87",
            versionCode = 113,
            sdk = 35,
            model = "Pixel",
            manufacturer = "Google",
            stack = "must not be stored",
            logs = listOf(AppLog.Line(10, "info", "T", "hidden")),
        )
        assertFalse(event.has("stack"))
        assertFalse(event.has("logs"))
        assertEquals(RumEvent.MESSAGE, event.getString("message").length)
        assertEquals(113, event.getInt("version_code"))
    }

    @Test
    fun crashKeepsStackAndLogLines() {
        val event = RumEvent.build(
            id = "rum-event-2",
            tsMs = 11,
            kind = "crash",
            level = "error",
            code = "IllegalState",
            message = "boom",
            appVersion = "2.5.87",
            versionCode = 113,
            sdk = 35,
            model = "Pixel",
            manufacturer = "Google",
            stack = "java.lang.IllegalState\nat Main.run",
            logs = listOf(AppLog.Line(11, "warn", "Speed", "worker failed")),
        )
        assertTrue(event.getString("stack").contains("\n"))
        assertEquals(1, event.getJSONArray("logs").length())
        assertEquals("worker failed", event.getJSONArray("logs").getJSONObject(0).getString("message"))
    }
}
