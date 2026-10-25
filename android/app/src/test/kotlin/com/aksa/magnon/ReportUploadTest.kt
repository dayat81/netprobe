package com.aksa.magnon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportUploadTest {
    @Test
    fun onlyPremiumUploadsAFinishedTestImmediately() {
        assertFalse(ReportUpload.immediate(false))
        assertTrue(ReportUpload.immediate(true))
    }
}
