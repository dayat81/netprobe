package com.aksa.magnon

/**
 * Shared field dictionary aligned with check_nr_device.py / OpenSignal CORE labels.
 * Keep decode tables identical across APK UI and CLI companion.
 */
object NrLabels {
    val NR_STATE = mapOf(
        0 to "NONE",
        1 to "RESTRICTED",
        2 to "NOT_RESTRICTED",
        3 to "CONNECTED",
    )

    val NR_FREQ = mapOf(
        0 to "UNKNOWN",
        1 to "LOW",
        2 to "MID",
        3 to "HIGH",
        4 to "MMWAVE",
    )

    /** TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_* */
    val OVERRIDE = mapOf(
        0 to "NONE",
        1 to "LTE_CA",
        2 to "LTE_ADVANCED_PRO",
        3 to "NR_NSA",
        4 to "NR_NSA_MMWAVE",
        5 to "NR_ADVANCED",
    )

    /** TelephonyManager.NETWORK_TYPE_* (subset used for data/voice RAT) */
    val NETWORK_TYPE = mapOf(
        0 to "UNKNOWN",
        1 to "GPRS",
        2 to "EDGE",
        3 to "UMTS",
        4 to "CDMA",
        5 to "EVDO_0",
        6 to "EVDO_A",
        7 to "1xRTT",
        8 to "HSDPA",
        9 to "HSUPA",
        10 to "HSPA",
        11 to "IDEN",
        12 to "EVDO_B",
        13 to "LTE",
        14 to "EHRPD",
        15 to "HSPAP",
        16 to "GSM",
        17 to "TD_SCDMA",
        18 to "IWLAN",
        19 to "LTE_CA",
        20 to "NR",
    )

    const val NR_STATE_CONNECTED = 3
    const val OVERRIDE_NR_NSA = 3
    const val OVERRIDE_NR_NSA_MMWAVE = 4
    const val OVERRIDE_NR_ADVANCED = 5
    const val NETWORK_TYPE_NR = 20
    const val NETWORK_TYPE_LTE = 13

    fun nrState(code: Int?): String =
        code?.let { NR_STATE[it] ?: "UNKNOWN($it)" } ?: "n/a"

    fun nrFreq(code: Int?): String =
        code?.let { NR_FREQ[it] ?: "UNKNOWN($it)" } ?: "n/a"

    fun override(code: Int?): String =
        code?.let { OVERRIDE[it] ?: "UNKNOWN($it)" } ?: "n/a"

    fun networkType(code: Int?): String =
        code?.let { NETWORK_TYPE[it] ?: "TYPE_$it" } ?: "n/a"
}
