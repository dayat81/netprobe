package com.telcoagent.udpclient

import android.telephony.CellInfo

object RadioBandMapper {
    private val LTE_BANDS = listOf(
        Triple(0, 599, "B1"),
        Triple(600, 1199, "B2"),
        Triple(1200, 1949, "B3"),
        Triple(1950, 2399, "B4"),
        Triple(2400, 2649, "B5"),
        Triple(2650, 2749, "B7"),
        Triple(2750, 3799, "B8"),
        Triple(9210, 9659, "B28"),
        Triple(38650, 39649, "B40"),
        Triple(39650, 41589, "B41"),
    )

    fun lteBand(earfcn: Int): String? {
        if (earfcn == CellInfo.UNAVAILABLE) return null
        for ((start, end, band) in LTE_BANDS) {
            if (earfcn in start..end) return band
        }
        return null
    }

    fun nrBand(nrarfcn: Int): String {
        if (nrarfcn == CellInfo.UNAVAILABLE) return "—"
        val freqMhz = nrFrequencyMhz(nrarfcn)
        return when {
            freqMhz in 2110.0..2170.0 -> "n1"
            freqMhz in 1805.0..1880.0 -> "n3"
            freqMhz in 758.0..803.0 -> "n28"
            freqMhz in 2300.0..2400.0 -> "n40"
            freqMhz in 2496.0..2690.0 -> "n41"
            freqMhz in 3300.0..3800.0 -> "n78"
            freqMhz in 3800.0..4200.0 -> "n79"
            else -> "%.0f MHz".format(freqMhz)
        }
    }

    fun lteBandLabel(earfcn: Int): String = lteBand(earfcn) ?: "—"

    fun nrBandLabel(nrarfcn: Int): String {
        if (nrarfcn == CellInfo.UNAVAILABLE) return "—"
        return nrBand(nrarfcn)
    }

    fun bandLabel(arfcn: String, tech: String): String {
        if (arfcn == "—" || arfcn.isBlank()) return "—"
        val value = arfcn.toIntOrNull() ?: return "—"
        return when {
            tech == "NR" -> nrBandLabel(value)
            tech.contains("LTE") -> lteBandLabel(value)
            else -> lteBand(value) ?: nrBandLabel(value)
        }
    }

    private fun nrFrequencyMhz(nrarfcn: Int): Double {
        return if (nrarfcn < 600000) {
            nrarfcn * 0.005
        } else {
            3000.0 + 0.015 * (nrarfcn - 600000)
        }
    }
}
