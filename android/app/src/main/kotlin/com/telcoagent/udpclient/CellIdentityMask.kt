package com.telcoagent.udpclient

import android.telephony.CellInfo

object CellIdentityMask {
    private const val MASKED_GNB = 65535
    private const val MASKED_NR_CELL = 4095
    private const val MASKED_LTE_ENB = 65535
    private const val MASKED_NCI = 268435455L

    fun isUnavailableNci(nci: Long): Boolean {
        if (nci == CellInfo.UNAVAILABLE.toLong()) return true
        if (nci == MASKED_NCI) return true
        if (nci / 4096 == MASKED_GNB.toLong() && nci % 4096 == MASKED_NR_CELL.toLong()) return true
        return false
    }

    fun formatGnb(nci: Long): String {
        if (isUnavailableNci(nci)) return "—"
        val gnb = (nci / 4096).toInt()
        if (gnb == MASKED_GNB || gnb <= 0) return "—"
        return gnb.toString()
    }

    fun formatNrCellId(nci: Long): String {
        if (isUnavailableNci(nci)) return "—"
        val cellId = (nci % 4096).toInt()
        if (cellId == MASKED_NR_CELL) return "—"
        return cellId.toString()
    }

    fun formatLteEnb(ci: Int): String {
        if (ci == CellInfo.UNAVAILABLE) return "—"
        val enb = ci / 256
        if (enb == MASKED_LTE_ENB || enb <= 0) return "—"
        return enb.toString()
    }

    fun formatLteCellId(ci: Int): String {
        if (ci == CellInfo.UNAVAILABLE) return "—"
        if (ci / 256 == MASKED_LTE_ENB) return "—"
        return (ci % 256).toString()
    }

    fun formatInt(value: Int): String {
        if (value == CellInfo.UNAVAILABLE) return "—"
        if (value == MASKED_GNB || value == MASKED_NR_CELL) return "—"
        return value.toString()
    }

    fun formatPci(value: Int): String {
        if (value == CellInfo.UNAVAILABLE) return "—"
        if (value < 0 || value > 1007) return "—"
        return value.toString()
    }
}
