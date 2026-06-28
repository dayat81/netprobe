package com.telcoagent.udpclient

data class NrCellInfo(
    val pci: String = "—",
    val gnb: String = "—",
    val cellId: String = "—",
    val arfcn: String = "—",
    val band: String = "—",
    val rsrp: String = "—",
    val rsrq: String = "—",
    val snr: String = "—",
)

data class CellInfoSnapshot(
    val tech: String = "—",
    val tac: String = "—",
    val enb: String = "—",
    val cellId: String = "—",
    val arfcn: String = "—",
    val band: String = "—",
    val rsrp: String = "—",
    val rsrq: String = "—",
    val snr: String = "—",
    val rsrpDbm: Int? = null,
    val dcNr: NrCellInfo? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val imsi: String? = null,
    val imei: String? = null,
    val deviceId: String? = null,
    val operator: String? = null,
    val updatedAt: String = "—",
    val error: String? = null,
) {
    companion object {
        val empty = CellInfoSnapshot()
    }
}
