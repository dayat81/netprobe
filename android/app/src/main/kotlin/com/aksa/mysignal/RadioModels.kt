package com.aksa.mysignal

import org.json.JSONArray
import org.json.JSONObject

/** Identifier the collector accepts as `{value, status}`; value is only set when status is available. */
data class Secret(val value: String?, val status: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("value", if (status == "available" && value != null) value else JSONObject.NULL)
        .put("status", status)

    companion object {
        val ABSENT = Secret(null, "absent")
    }
}

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val release: String,
    val androidId: Secret,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("manufacturer", manufacturer)
        .put("model", model)
        .put("sdk_int", sdkInt)
        .put("release", release)
        .put("android_id", androidId.toJson())
}

/** One aggregated component carrier from PhysicalChannelConfig. */
data class ComponentCarrier(
    val rat: String,
    val status: String,
    val channel: Int?,
    val band: Int?,
    val bandwidthKhz: Int?,
    val pci: Int?,
) {
    val isPrimary: Boolean get() = status == STATUS_PRIMARY
    val isSecondary: Boolean get() = status == STATUS_SECONDARY

    fun toJson(): JSONObject = JSONObject()
        .put("rat", rat)
        .put("status", status)
        .put("channel", channel.orNull())
        .put("band", band.orNull())
        .put("bandwidth_khz", bandwidthKhz.orNull())
        .put("pci", pci.orNull())

    fun shortLine(): String =
        "$status $rat ch=${channel ?: "?"} b=${band ?: "?"} bw=${bandwidthKhz?.let { it / 1000 } ?: "?"}MHz pci=${pci ?: "?"}"

    companion object {
        const val STATUS_PRIMARY = "primary"
        const val STATUS_SECONDARY = "secondary"
        const val STATUS_UNKNOWN = "unknown"
    }
}

data class ServingSignal(
    val rat: String,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val level: Int?,
    val dbm: Int?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("rat", rat)
        .put("rsrp", rsrp.orNull())
        .put("rsrq", rsrq.orNull())
        .put("sinr", sinr.orNull())
        .put("level", level.orNull())
        .put("dbm", dbm.orNull())
}

data class RadioCell(
    val registered: Boolean,
    val rat: String,
    val mcc: String = "",
    val mnc: String = "",
    val ci: String = "",
    val pci: Int? = null,
    val tac: Int? = null,
    val earfcn: Int? = null,
    val nrarfcn: Int? = null,
    val uarfcn: Int? = null,
    val arfcn: Int? = null,
    val bands: List<Int> = emptyList(),
    val bandwidthKhz: Int? = null,
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
    val rssi: Int? = null,
    val cqi: Int? = null,
    val timingAdvance: Int? = null,
    val ssRsrp: Int? = null,
    val ssRsrq: Int? = null,
    val ssSinr: Int? = null,
    val csiRsrp: Int? = null,
    val csiRsrq: Int? = null,
    val csiSinr: Int? = null,
    val dbm: Int? = null,
    val asu: Int? = null,
    val level: Int? = null,
    val subId: Int? = null,
    val simSlot: Int? = null,
    val carrier: String = "",
    /** Local only. Not uploaded; secondary NR serving is often isRegistered=false. */
    val connection: Int = CONNECTION_NONE,
) {
    val role: String get() = if (registered) "serving" else "neighbor"
    val chartServing: Boolean
        get() = registered || connection == CONNECTION_PRIMARY || connection == CONNECTION_SECONDARY
    val channel: Int? get() = earfcn ?: nrarfcn ?: uarfcn ?: arfcn

    fun toJson(): JSONObject = JSONObject()
        .put("registered", registered)
        .put("role", role)
        .put("rat", rat)
        .put("mcc", mcc)
        .put("mnc", mnc)
        .put("ci", ci)
        .put("pci", pci.orNull())
        .put("tac", tac.orNull())
        .put("earfcn", earfcn.orNull())
        .put("nrarfcn", nrarfcn.orNull())
        .put("uarfcn", uarfcn.orNull())
        .put("arfcn", arfcn.orNull())
        .put("bands", JSONArray(bands.take(8)))
        .put("bandwidth_khz", bandwidthKhz.orNull())
        .put("rsrp", rsrp.orNull())
        .put("rsrq", rsrq.orNull())
        .put("sinr", sinr.orNull())
        .put("rssi", rssi.orNull())
        .put("cqi", cqi.orNull())
        .put("timing_advance", timingAdvance.orNull())
        .put("ss_rsrp", ssRsrp.orNull())
        .put("ss_rsrq", ssRsrq.orNull())
        .put("ss_sinr", ssSinr.orNull())
        .put("csi_rsrp", csiRsrp.orNull())
        .put("csi_rsrq", csiRsrq.orNull())
        .put("csi_sinr", csiSinr.orNull())
        .put("dbm", dbm.orNull())
        .put("asu", asu.orNull())
        .put("level", level.orNull())
        .put("sub_id", subId.orNull())
        .put("sim_slot", simSlot.orNull())
        .put("carrier", carrier)
        .put("channel", channel.orNull())

    companion object {
        const val CONNECTION_NONE = 0
        const val CONNECTION_PRIMARY = 1
        const val CONNECTION_SECONDARY = 2
    }

    fun shortLine(): String {
        val ch = when (rat) {
            "LTE" -> "EARFCN=${earfcn ?: "?"}"
            "NR" -> "NRARFCN=${nrarfcn ?: "?"}"
            "WCDMA" -> "UARFCN=${uarfcn ?: "?"}"
            "GSM" -> "ARFCN=${arfcn ?: "?"}"
            else -> ""
        }
        val sig = when {
            ssRsrp != null -> "ssRSRP=$ssRsrp ssSINR=${ssSinr ?: "?"}"
            rsrp != null -> "RSRP=$rsrp RSRQ=${rsrq ?: "?"} SINR=${sinr ?: "?"}"
            dbm != null -> "dBm=$dbm"
            else -> ""
        }
        val plmn = if (mcc.isNotEmpty() && mnc.isNotEmpty()) " $mcc-$mnc" else ""
        return "$rat$plmn PCI=${pci ?: "?"} $ch $sig".trim()
    }
}

data class SubscriptionRadio(
    val slot: Int,
    val subId: Int?,
    val carrier: String,
    val mcc: String,
    val mnc: String,
    val rat: String,
    val networkOperator: String,
    val simOperator: String,
    val dataState: String,
    val serviceState: String,
    val isDefaultData: Boolean,
    val nrState: Int?,
    val nrStateSource: String,
    val nrFrequencyRange: Int?,
    val networkType: Int,
    val voiceNetworkType: Int,
    val overrideNetworkType: Int?,
    val ca: Boolean?,
    val caSource: String,
    val endcAvailable: Boolean?,
    val channel: Int?,
    val servingSignal: ServingSignal?,
    val carriers: List<ComponentCarrier>,
    val imei: Secret = Secret.ABSENT,
    val imsi: Secret = Secret.ABSENT,
    val msisdn: Secret = Secret.ABSENT,
    val iccid: Secret = Secret.ABSENT,
    val apn: String = "",
    val apnName: String = "",
    val apnTypes: String = "",
    val apnProtocol: String = "",
    val cellBandwidthKhz: List<Int> = emptyList(),
) {
    val plmn: String get() = if (mcc.isNotEmpty() && mnc.isNotEmpty()) "$mcc-$mnc" else ""

    val verdict: NrVerdict get() = NrVerdict.evaluate(overrideNetworkType, nrState, networkType)

    fun toJson(): JSONObject = JSONObject()
        .put("slot", slot)
        .put("sub_id", subId.orNull())
        .put("carrier", carrier)
        .put("mcc", mcc)
        .put("mnc", mnc)
        .put("rat", rat)
        .put("network_operator", networkOperator)
        .put("sim_operator", simOperator)
        .put("data_state", dataState)
        .put("service_state", serviceState)
        .put("nr_state", nrState.orNull())
        .put("nr_frequency_range", nrFrequencyRange.orNull())
        .put("network_type", networkType)
        .put("override_network_type", overrideNetworkType.orNull())
        .put("ca", ca ?: JSONObject.NULL)
        .put("endc_available", endcAvailable ?: JSONObject.NULL)
        .put("channel", channel.orNull())
        .put("serving_signal", servingSignal?.toJson() ?: JSONObject.NULL)
        .put("carriers", JSONArray().also { arr -> carriers.take(MAX_CARRIERS).forEach { arr.put(it.toJson()) } })
        .put("imei", imei.toJson())
        .put("imsi", imsi.toJson())
        .put("msisdn", msisdn.toJson())
        .put("iccid", iccid.toJson())
        .put("apn", apn)
        .put("apn_name", apnName)
        .put("apn_types", apnTypes)
        .put("apn_protocol", apnProtocol)

    companion object {
        const val MAX_CARRIERS = 8
    }
}

/** Preferred APN row from the telephony carriers table. */
data class PreferApn(
    val apn: String = "",
    val name: String = "",
    val types: String = "",
    val protocol: String = "",
    val numeric: String = "",
    val subId: Int? = null,
)

data class NetworkInfo(
    val transport: String,
    val connected: Boolean,
    val roaming: Boolean,
    val downstreamKbps: Int,
    val upstreamKbps: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("transport", transport)
        .put("connected", connected)
        .put("roaming", roaming)
        .put("downstream_kbps", downstreamKbps)
        .put("upstream_kbps", upstreamKbps)

    companion object {
        val NONE = NetworkInfo("none", false, false, 0, 0)
    }
}

data class WifiNeighbor(
    val ssid: String = "",
    val bssid: String = "",
    val rssiDbm: Int? = null,
    val frequencyMhz: Int? = null,
    val channel: Int? = null,
    val channelWidthMhz: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ssid", ssid)
        .put("bssid", bssid)
        .put("rssi_dbm", rssiDbm.orNull())
        .put("frequency_mhz", frequencyMhz.orNull())
        .put("channel", channel.orNull())
        .put("channel_width_mhz", channelWidthMhz.orNull())
}

data class WifiCongestion(
    val scanAgeMs: Long? = null,
    val neighbors: Int = 0,
    val sameChannel: Int = 0,
    val adjacentChannel: Int = 0,
    val strongestNeighborRssiDbm: Int? = null,
    val score: String = "unknown",
    val neighborsTop: List<WifiNeighbor> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("scan_age_ms", scanAgeMs ?: JSONObject.NULL)
        .put("neighbors", neighbors)
        .put("same_channel", sameChannel)
        .put("adjacent_channel", adjacentChannel)
        .put("strongest_neighbor_rssi_dbm", strongestNeighborRssiDbm.orNull())
        .put("score", score)
        .put(
            "neighbors_top",
            JSONArray().also { arr -> neighborsTop.take(MAX_NEIGHBORS).forEach { arr.put(it.toJson()) } },
        )

    companion object {
        const val MAX_NEIGHBORS = 8
    }
}

data class WifiSignal(
    val status: String,
    val rssiDbm: Int? = null,
    val frequencyMhz: Int? = null,
    val channel: Int? = null,
    val linkSpeedMbps: Int? = null,
    val ssid: String = "",
    val bssid: String = "",
    val standard: String = "",
    val congestion: WifiCongestion? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("status", status)
        .put("rssi_dbm", rssiDbm.orNull())
        .put("frequency_mhz", frequencyMhz.orNull())
        .put("channel", channel.orNull())
        .put("link_speed_mbps", linkSpeedMbps.orNull())
        .put("ssid", ssid)
        .put("bssid", bssid)
        .put("standard", standard)
        .put("congestion", congestion?.toJson() ?: JSONObject.NULL)

    companion object {
        val DISABLED = WifiSignal("disabled")
        val NOT_ASSOCIATED = WifiSignal("not_associated")
    }
}

data class LocationInfo(
    val status: String,
    val provider: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Double? = null,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
    val bearing: Double? = null,
    val tsMs: Long? = null,
) {
    fun toJson(): JSONObject {
        val available = status == "available" && latitude != null && longitude != null
        return JSONObject()
            .put("status", if (available || status != "available") status else "absent")
            .put("provider", provider)
            .put("latitude", if (available) latitude else JSONObject.NULL)
            .put("longitude", if (available) longitude else JSONObject.NULL)
            .put("accuracy_m", accuracyM ?: JSONObject.NULL)
            .put("altitude_m", altitudeM ?: JSONObject.NULL)
            .put("speed_mps", speedMps ?: JSONObject.NULL)
            .put("bearing", bearing ?: JSONObject.NULL)
            .put("ts_ms", tsMs ?: JSONObject.NULL)
    }
}

data class RadioSnapshot(
    val eventId: String,
    val tsMs: Long,
    val device: DeviceInfo,
    val subscriptions: List<SubscriptionRadio>,
    val cells: List<RadioCell>,
    val network: NetworkInfo,
    val location: LocationInfo,
    val wifi: WifiSignal,
    val error: String? = null,
) {
    /** Subscription carrying mobile data; the widget and NR verdict follow this SIM. */
    val primary: SubscriptionRadio?
        get() = subscriptions.firstOrNull { it.isDefaultData }
            ?: subscriptions.firstOrNull { it.dataState == "CONNECTED" }
            ?: subscriptions.firstOrNull()

    fun toMeasureJson(reason: String = "interval"): JSONObject = JSONObject()
        .put("event_id", eventId)
        .put("ts_ms", tsMs)
        .put("reason", reason)
        .put("device", device.toJson())
        .put("subscriptions", JSONArray().also { arr -> subscriptions.take(8).forEach { arr.put(it.toJson()) } })
        .put("cells", JSONArray().also { arr -> cells.take(MAX_CELLS).forEach { arr.put(it.toJson()) } })
        .put("network", network.toJson())
        .put("location", location.toJson())
        .put("wifi", wifi.toJson())

    /** Legacy NR-path view used by the widget, notification and `/v1/nr-status`. */
    fun toNrSnapshot(): NrSnapshot {
        val sub = primary
        val cellsForSub = cells.filter { sub == null || it.subId == null || it.subId == sub.subId }
        return NrSnapshot(
            tsMs = tsMs,
            overrideNetworkType = sub?.overrideNetworkType,
            displayNetworkType = sub?.networkType,
            nrState = sub?.nrState,
            nrFrequencyRange = sub?.nrFrequencyRange,
            dataNetworkType = sub?.networkType,
            voiceNetworkType = sub?.voiceNetworkType,
            cells = cellsForSub,
            error = error,
        )
    }

    companion object {
        const val MAX_CELLS = 32
    }
}

/** NR-path view of the data subscription; derived from [RadioSnapshot]. */
data class NrSnapshot(
    val tsMs: Long,
    val overrideNetworkType: Int?,
    val displayNetworkType: Int?,
    val nrState: Int?,
    val nrFrequencyRange: Int?,
    val dataNetworkType: Int?,
    val voiceNetworkType: Int?,
    val cells: List<RadioCell>,
    val error: String? = null,
) {
    val verdict: NrVerdict = NrVerdict.evaluate(overrideNetworkType, nrState, dataNetworkType)
}

internal fun Int?.orNull(): Any = this ?: JSONObject.NULL
