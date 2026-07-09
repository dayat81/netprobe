package com.telcoagent.udpclient

object CsvLogFormat {
    const val HEADER =
        "timestamp,lat,lon,imsi,imei,device_id,operator,tech,tac,site_id,cell_id,arfcn,rsrp,rsrq,snr," +
            "nr_pci,nr_gnb,nr_cell_id,nr_arfcn,nr_rsrp,nr_rsrq,nr_snr," +
            "local_ip,dns_servers,network_type,udp_latency_ms,udp_loss_pct,udp_jitter_ms," +
            "udp_uplink_ms,udp_downlink_ms,udp_uplink_loss_pct,udp_downlink_loss_pct"

    fun formatRow(snapshot: CellInfoSnapshot, extras: LogSessionExtras?): String {
        val dc = snapshot.dcNr
        return listOf(
            snapshot.updatedAt,
            csvCoord(snapshot.latitude),
            csvCoord(snapshot.longitude),
            csv(snapshot.imsi),
            csv(snapshot.imei),
            csv(snapshot.deviceId),
            csv(snapshot.operator),
            snapshot.tech,
            csv(snapshot.tac),
            csv(snapshot.enb),
            csv(snapshot.cellId),
            csv(snapshot.arfcn),
            csv(snapshot.rsrp),
            csv(snapshot.rsrq),
            csv(snapshot.snr),
            csv(dc?.pci),
            csv(dc?.gnb),
            csv(dc?.cellId),
            csv(dc?.arfcn),
            csv(dc?.rsrp),
            csv(dc?.rsrq),
            csv(dc?.snr),
            csv(extras?.localIp),
            csv(extras?.dnsServers),
            csv(extras?.networkType),
            csvFloat(extras?.udpLatencyMs),
            csvFloat(extras?.udpLossPct),
            csvFloat(extras?.udpJitterMs),
            csvFloat(extras?.udpUplinkMs),
            csvFloat(extras?.udpDownlinkMs),
            csvFloat(extras?.udpUplinkLossPct),
            csvFloat(extras?.udpDownlinkLossPct),
        ).joinToString(",")
    }

    fun csvCoord(value: Double?): String {
        return value?.let { "%.6f".format(it) } ?: ""
    }

    fun csvFloat(value: Double?): String {
        return value?.let { "%.2f".format(it) } ?: ""
    }

    fun csv(value: String?): String {
        if (value.isNullOrBlank() || value == "—") return ""
        return if (value.contains(',') || value.contains('"')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
