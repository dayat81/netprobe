package com.aksa.mysignal

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoBufferTest {
    @Test
    fun startupWaitIsNotRebuffer() {
        assertEquals(0L, rebufferMillis(bufferStart = 0L, bufferEnd = 990L, firstFrameAt = 990L))
        assertEquals(0L, rebufferMillis(bufferStart = 0L, bufferEnd = 500L, firstFrameAt = 0L))
    }

    @Test
    fun rebufferAfterTheFirstFrameCounts() {
        assertEquals(400L, rebufferMillis(bufferStart = 1_000L, bufferEnd = 1_400L, firstFrameAt = 900L))
        assertEquals(200L, rebufferMillis(bufferStart = 800L, bufferEnd = 1_200L, firstFrameAt = 1_000L))
    }
}
