package com.aksa.mysignal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Key sets mirror server/opensignal_mirror/schema.py; the generated payload is also written to
 * build/contract/ so the build host can run it through the real collector validator.
 */
class MeasureContractTest {
    private val snapshotKeys = setOf("event_id", "ts_ms", "reason", "device", "subscriptions", "cells", "network", "location", "wifi")
    private val deviceKeys = setOf("manufacturer", "model", "sdk_int", "release", "android_id")
    private val wifiKeys = setOf(
        "status", "rssi_dbm", "frequency_mhz", "channel", "link_speed_mbps",
        "ssid", "bssid", "standard", "congestion",
    )
    private val congestionKeys = setOf(
        "scan_age_ms", "neighbors", "same_channel", "adjacent_channel",
        "strongest_neighbor_rssi_dbm", "score", "neighbors_top",
    )
    private val subRequired = setOf(
        "slot", "sub_id", "carrier", "mcc", "mnc", "rat", "network_operator", "sim_operator",
        "data_state", "service_state", "imei", "imsi", "msisdn", "iccid",
    )
    private val subOptional = setOf(
        "serving_signal", "nr_state", "channel", "network_type", "ca", "nr_frequency_range",
        "endc_available", "override_network_type", "carriers",
        "apn", "apn_name", "apn_types", "apn_protocol",
    )
    private val cellRequired = setOf(
        "registered", "role", "rat", "mcc", "mnc", "ci", "pci", "tac", "earfcn", "nrarfcn", "uarfcn",
        "arfcn", "bands", "bandwidth_khz", "rsrp", "rsrq", "sinr", "rssi", "cqi", "timing_advance",
        "ss_rsrp", "ss_rsrq", "ss_sinr", "csi_rsrp", "csi_rsrq", "csi_sinr", "dbm", "asu", "level",
    )
    private val cellOptional = setOf("sub_id", "sim_slot", "carrier", "channel")

    private fun sample(): RadioSnapshot {
        val carriers = listOf(
            ComponentCarrier("LTE", ComponentCarrier.STATUS_PRIMARY, 1475, 3, 20_000, 409),
            ComponentCarrier("LTE", ComponentCarrier.STATUS_SECONDARY, 200, 1, 10_000, 409),
            ComponentCarrier("NR", ComponentCarrier.STATUS_SECONDARY, 531_870, 41, 100_000, 875),
        )
        val data = SubscriptionRadio(
            slot = 0, subId = 4, carrier = "INDOSAT", mcc = "510", mnc = "01", rat = "LTE",
            networkOperator = "INDOSAT", simOperator = "INDOSAT", dataState = "CONNECTED",
            serviceState = "IN_SERVICE", isDefaultData = true, nrState = 3, nrStateSource = "physical_channel",
            nrFrequencyRange = 2, networkType = 13, voiceNetworkType = 13, overrideNetworkType = 3,
            ca = true, caSource = "physical_channel", endcAvailable = true, channel = 1475,
            servingSignal = ServingSignal("LTE", -95, -19, 4, 2, -95), carriers = carriers,
            imei = Secret("864335074984928", "available"),
            apn = "ggsb02", apnName = "ggsb02", apnTypes = "default,mms", apnProtocol = "IPV4V6",
        )
        val voice = data.copy(
            slot = 1, subId = 5, carrier = "TSEL", mnc = "10", isDefaultData = false, dataState = "DISCONNECTED",
            nrState = null, nrStateSource = "unavailable", ca = null, endcAvailable = null, carriers = emptyList(),
            servingSignal = null, overrideNetworkType = null,
        )
        val cells = listOf(
            RadioCell(
                registered = true, rat = "LTE", mcc = "510", mnc = "01", ci = "12345678", pci = 409, tac = 289,
                earfcn = 1475, bands = listOf(3), bandwidthKhz = 20_000, rsrp = -95, rsrq = -19, sinr = 4,
                rssi = -60, cqi = 9, timingAdvance = 4, dbm = -95, asu = 45, level = 2,
                subId = 4, simSlot = 0, carrier = "INDOSAT",
            ),
            RadioCell(
                registered = true, rat = "NR", ci = "", pci = 875, nrarfcn = 531_870, bands = listOf(41),
                rsrp = -85, sinr = 20, ssRsrp = -85, ssRsrq = -11, ssSinr = 20, subId = 4, simSlot = 0,
            ),
            RadioCell(registered = false, rat = "LTE", pci = 394, earfcn = 1475, rsrp = -99, subId = 4, simSlot = 0),
        )
        val wifi = WifiSignal(
            status = "associated",
            rssiDbm = -58,
            frequencyMhz = 5180,
            channel = 36,
            linkSpeedMbps = 866,
            ssid = "HomeAP",
            bssid = "aa:bb:cc:dd:ee:ff",
            standard = "11ac",
            congestion = WifiCongestion(
                scanAgeMs = 4_200,
                neighbors = 3,
                sameChannel = 1,
                adjacentChannel = 0,
                strongestNeighborRssiDbm = -72,
                score = "low",
                neighborsTop = listOf(
                    WifiNeighbor("OtherAP", "11:22:33:44:55:66", -72, 5180, 36, 80),
                ),
            ),
        )
        return RadioSnapshot(
            eventId = "mys0123456789abcdef",
            tsMs = 1_761_000_000_000,
            device = DeviceInfo("Xiaomi", "Phone", 34, "14", Secret("0123456789abcdef", "available")),
            subscriptions = listOf(data, voice),
            cells = cells,
            network = NetworkInfo("cellular", true, false, 50_000, 10_000),
            location = LocationInfo("available", "fused", -6.2, 106.8, 12.0, null, 0.0, null, 1_761_000_000_000),
            wifi = wifi,
        )
    }

    private fun keys(obj: JSONObject): Set<String> = obj.keys().asSequence().toSet()

    @Test
    fun snapshotMatchesCollectorShape() {
        val json = sample().toMeasureJson()
        assertEquals(snapshotKeys, keys(json))
        assertEquals("interval", json.getString("reason"))
        assertEquals(deviceKeys, keys(json.getJSONObject("device")))

        val subs = json.getJSONArray("subscriptions")
        assertEquals(2, subs.length())
        for (i in 0 until subs.length()) {
            val sub = subs.getJSONObject(i)
            val k = keys(sub)
            assertTrue("missing ${subRequired - k}", k.containsAll(subRequired))
            assertTrue("unexpected ${k - subRequired - subOptional}", (k - subRequired).all { it in subOptional })
            for (secret in listOf("imei", "imsi", "msisdn", "iccid")) {
                assertEquals(setOf("value", "status"), keys(sub.getJSONObject(secret)))
            }
        }
        val first = subs.getJSONObject(0)
        assertEquals(3, first.getInt("nr_state"))
        assertEquals(true, first.getBoolean("ca"))
        assertEquals(3, first.getJSONArray("carriers").length())
        assertEquals("ggsb02", first.getString("apn"))
        assertEquals("available", first.getJSONObject("imei").getString("status"))
        assertEquals("864335074984928", first.getJSONObject("imei").getString("value"))
        assertTrue(subs.getJSONObject(1).isNull("nr_state"))

        val cells = json.getJSONArray("cells")
        for (i in 0 until cells.length()) {
            val cell = cells.getJSONObject(i)
            val k = keys(cell)
            assertTrue("missing ${cellRequired - k}", k.containsAll(cellRequired))
            assertTrue("unexpected ${k - cellRequired - cellOptional}", (k - cellRequired).all { it in cellOptional })
            val ci = cell.getString("ci")
            assertTrue(ci.isEmpty() || ci.all { it.isDigit() })
        }
        assertEquals(531_870, cells.getJSONObject(1).getInt("channel"))

        val wifi = json.getJSONObject("wifi")
        assertEquals(wifiKeys, keys(wifi))
        assertEquals("associated", wifi.getString("status"))
        assertEquals(-58, wifi.getInt("rssi_dbm"))
        val congestion = wifi.getJSONObject("congestion")
        assertEquals(congestionKeys, keys(congestion))
        assertEquals("low", congestion.getString("score"))
        assertEquals(1, congestion.getJSONArray("neighbors_top").length())
    }

    @Test
    fun nrViewFollowsDataSubscription() {
        val nr = sample().toNrSnapshot()
        assertEquals(NrVerdictLabel.NSA_CONNECTED, nr.verdict.label)
        assertEquals(3, nr.cells.size)
    }

    @Test
    fun writesContractPayloadForCollectorValidation() {
        val withheld = sample().copy(
            eventId = "mys00000000000000ff",
            location = LocationInfo("permission_denied"),
            subscriptions = emptyList(),
            cells = emptyList(),
        )
        val plain = JSONObject()
            .put("snapshots", JSONArray().put(sample().toMeasureJson()).put(withheld.toMeasureJson()))
            .put("tests", JSONArray())
            .put("os_results", JSONArray())
        val out = File("build/contract/mysignal_measurement.json")
        out.parentFile?.mkdirs()
        out.writeText(plain.toString(2))
        assertTrue(out.length() > 0)
    }
}
