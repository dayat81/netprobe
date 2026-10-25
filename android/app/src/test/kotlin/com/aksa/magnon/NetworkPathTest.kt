package com.aksa.magnon

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPathTest {
    @Test
    fun canonicalNamesWifiAndCell() {
        assertEquals("wifi", NetworkPath.canonical("Wi-Fi"))
        assertEquals("wifi", NetworkPath.canonical("wifi"))
        assertEquals("cell", NetworkPath.canonical("cellular"))
        assertEquals("cell", NetworkPath.canonical("CELL"))
        assertEquals("", NetworkPath.canonical("unknown"))
        assertEquals("", NetworkPath.canonical(""))
    }

    @Test
    fun labelIsUppercase() {
        assertEquals("WIFI", NetworkPath.label("wifi"))
        assertEquals("CELL", NetworkPath.label("cellular"))
        assertEquals("", NetworkPath.label("ethernet"))
    }
}
