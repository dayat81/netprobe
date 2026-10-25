"""Strict measurement and speed-test schemas."""

from __future__ import annotations

import base64
import binascii
import json
import re

ID_MIN = 8
ID_MAX = 80
MAX_COUNTER = 2**53
_IDENT = re.compile(r"[A-Za-z0-9_-]+$")


def is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _ident(value: object, minimum: int, maximum: int) -> str:
    if not isinstance(value, str) or not minimum <= len(value) <= maximum or not _IDENT.fullmatch(value):
        raise ValueError("invalid identifier")
    return value


def _counter(value: object) -> int:
    if not is_int(value) or value < 0 or value > MAX_COUNTER:
        raise ValueError("invalid counter")
    return value


def _text(value: object, limit: int) -> str:
    if not isinstance(value, str) or not value or len(value) > limit:
        raise ValueError("invalid text")
    if any(ord(ch) < 32 or ord(ch) == 127 for ch in value):
        raise ValueError("invalid text")
    return value

STATUSES = {
    "available",
    "permission_denied",
    "platform_restricted",
    "not_supported",
    "absent",
    "error",
}
RATS = {"LTE", "NR", "WCDMA", "GSM", "CDMA", "TDSCDMA", "UNKNOWN"}
REASONS = {"interval", "change", "keepalive"}
TEST_SOURCES = {"opensignal", "network_fallback"}
TEST_TYPES = {"download", "upload", "latency", "combined", "unknown"}
RESULT_SOURCES = {"opensignal", "opensignal_sdk"}
RESULT_STAGES = {"phase_payload", "phase_merged", "final_ui", "sdk_json", "drive_mode"}
MAX_SNAPSHOTS = 12
MAX_TESTS = 8
MAX_OS_RESULTS = 16
MAX_OS_FIELDS = 40
MAX_SDK_PAYLOAD = 32768  # keep in sync with SDK_PAYLOAD_LIMIT in OsmReporter.java
MAX_CELLS = 32
MAX_SUBS = 8
MAX_BANDS = 8
MAX_WIFI_NEIGHBORS = 8
WIFI_STATUSES = {"associated", "not_associated", "disabled"}
WIFI_SCORES = {"low", "medium", "high", "unknown"}
WIFI_STANDARDS = {"", "legacy", "11n", "11ac", "11ax", "11ad", "11be"}
_DIGIT_ID = re.compile(r"\+?[0-9]{6,20}")
_ANDROID_ID = re.compile(r"[0-9a-fA-F]{8,32}")


def _exact(value: object, fields: set[str], label: str) -> dict:
    if not isinstance(value, dict) or set(value) != fields:
        raise ValueError("unexpected %s fields" % label)
    return value


def _shape(value: object, required: set[str], optional: set[str], label: str) -> dict:
    """Accept every required key plus any of the optional ones.

    Used where an older APK must keep working after we start sending extra
    fields: missing optional keys read as None, unknown keys are still rejected.
    """
    if not isinstance(value, dict):
        raise ValueError("unexpected %s fields" % label)
    keys = set(value)
    if not required <= keys or not keys <= required | optional:
        raise ValueError("unexpected %s fields" % label)
    return value


def _opt_int(value: object, low: int, high: int) -> int | None:
    if value is None:
        return None
    if not is_int(value) or value < low or value > high:
        raise ValueError("invalid number")
    return value


def _opt_float(value: object, low: float, high: float) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("invalid number")
    number = float(value)
    if number != number or number < low or number > high:
        raise ValueError("invalid number")
    return number


def _plain(value: object, limit: int) -> str:
    if value is None or value == "":
        return ""
    return _text(value, limit)


def _secret(value: object, kind: str) -> dict:
    item = _exact(value, {"value", "status"}, kind)
    status = item["status"]
    if status not in STATUSES:
        raise ValueError("invalid status")
    if status != "available":
        if item["value"] is not None:
            raise ValueError("invalid secret")
        return {"value": None, "status": status}
    raw = item["value"]
    if not isinstance(raw, str):
        raise ValueError("invalid secret")
    if kind == "android_id":
        if not _ANDROID_ID.fullmatch(raw):
            raise ValueError("invalid android id")
    elif not _DIGIT_ID.fullmatch(raw):
        raise ValueError("invalid identifier")
    return {"value": raw, "status": "available"}


def _plmns(value: object) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list) or len(value) > 8:
        raise ValueError("invalid plmns")
    plmns = []
    for item in value:
        text = _plain(item, 6)
        if not text:
            continue
        if not text.isdigit() or not 5 <= len(text) <= 6:
            raise ValueError("invalid plmn")
        plmns.append(text)
    return plmns


def _cqi_report(value: object) -> list[int]:
    if value is None:
        return []
    if not isinstance(value, list) or len(value) > 8:
        raise ValueError("invalid cqi report")
    report = []
    for item in value:
        number = _opt_int(item, 0, 15)
        if number is None:
            raise ValueError("invalid cqi report")
        report.append(number)
    return report


def _bands(value: object) -> list[int]:
    if not isinstance(value, list) or len(value) > MAX_BANDS:
        raise ValueError("invalid bands")
    bands = []
    for item in value:
        if not is_int(item) or item < 0 or item > 300:
            raise ValueError("invalid band")
        bands.append(item)
    return bands


CELL_FIELDS = frozenset(
    {
        "registered",
        "role",
        "rat",
        "mcc",
        "mnc",
        "ci",
        "pci",
        "tac",
        "earfcn",
        "nrarfcn",
        "uarfcn",
        "arfcn",
        "bands",
        "bandwidth_khz",
        "rsrp",
        "rsrq",
        "sinr",
        "rssi",
        "cqi",
        "timing_advance",
        "ss_rsrp",
        "ss_rsrq",
        "ss_sinr",
        "csi_rsrp",
        "csi_rsrq",
        "csi_sinr",
        "dbm",
        "asu",
        "level",
    }
)
# Added later; old APKs simply omit them.
CELL_OPTIONAL = frozenset({
    "sub_id",
    "sim_slot",
    "carrier",
    "channel",
    "timing_advance_micros",
    "cqi_table_index",
    "csi_cqi_table_index",
    "csi_cqi_report",
    "additional_plmns",
    "operator_alpha_long",
    "operator_alpha_short",
})
SIGNAL_FIELDS = frozenset({"rat", "rsrp", "rsrq", "sinr", "level", "dbm"})


def _signal(value: object) -> dict | None:
    if value is None:
        return None
    item = _exact(value, set(SIGNAL_FIELDS), "serving_signal")
    if item["rat"] not in RATS:
        raise ValueError("invalid rat")
    return {
        "rat": item["rat"],
        "rsrp": _opt_int(item["rsrp"], -200, 0),
        "rsrq": _opt_int(item["rsrq"], -43, 20),
        "sinr": _opt_int(item["sinr"], -30, 50),
        "level": _opt_int(item["level"], 0, 4),
        "dbm": _opt_int(item["dbm"], -200, 0),
    }


def _cell(value: object) -> dict:
    item = _shape(value, set(CELL_FIELDS), set(CELL_OPTIONAL), "cell")
    if not isinstance(item["registered"], bool):
        raise ValueError("invalid cell registration")
    if item["role"] not in ("serving", "neighbor"):
        raise ValueError("invalid cell role")
    if item["rat"] not in RATS:
        raise ValueError("invalid rat")
    ci = _plain(item["ci"], 20)
    if ci and not ci.isdigit():
        raise ValueError("invalid ci")
    return {
        "registered": item["registered"],
        "role": item["role"],
        "rat": item["rat"],
        "mcc": _plain(item["mcc"], 4),
        "mnc": _plain(item["mnc"], 4),
        "ci": ci,
        "pci": _opt_int(item["pci"], 0, 1007),
        "tac": _opt_int(item["tac"], 0, 16777215),
        "earfcn": _opt_int(item["earfcn"], 0, 10000000),
        "nrarfcn": _opt_int(item["nrarfcn"], 0, 3279165),
        "uarfcn": _opt_int(item["uarfcn"], 0, 200000),
        "arfcn": _opt_int(item["arfcn"], 0, 200000),
        "bands": _bands(item["bands"]),
        "bandwidth_khz": _opt_int(item["bandwidth_khz"], 0, 400000),
        "rsrp": _opt_int(item["rsrp"], -200, 0),
        "rsrq": _opt_int(item["rsrq"], -43, 20),
        "sinr": _opt_int(item["sinr"], -30, 50),
        "rssi": _opt_int(item["rssi"], -200, 0),
        "cqi": _opt_int(item["cqi"], 0, 15),
        "cqi_table_index": _opt_int(item.get("cqi_table_index"), 1, 6),
        "timing_advance": _opt_int(item["timing_advance"], 0, 4096),
        "ss_rsrp": _opt_int(item["ss_rsrp"], -200, 0),
        "ss_rsrq": _opt_int(item["ss_rsrq"], -43, 20),
        "timing_advance_micros": _opt_int(item.get("timing_advance_micros"), 0, 2_000_000),
        "ss_sinr": _opt_int(item["ss_sinr"], -30, 50),
        "csi_rsrp": _opt_int(item["csi_rsrp"], -200, 0),
        "csi_rsrq": _opt_int(item["csi_rsrq"], -40, 20),
        "csi_sinr": _opt_int(item["csi_sinr"], -30, 50),
        "csi_cqi_table_index": _opt_int(item.get("csi_cqi_table_index"), 1, 3),
        "csi_cqi_report": _cqi_report(item.get("csi_cqi_report")),
        "additional_plmns": _plmns(item.get("additional_plmns")),
        "operator_alpha_long": _plain(item.get("operator_alpha_long"), 64),
        "operator_alpha_short": _plain(item.get("operator_alpha_short"), 64),
        "dbm": _opt_int(item["dbm"], -200, 0),
        "asu": _opt_int(item["asu"], 0, 200),
        "level": _opt_int(item["level"], 0, 4),
        "sub_id": _opt_int(item.get("sub_id"), -1, 2**31 - 1),
        "sim_slot": _opt_int(item.get("sim_slot"), -1, 16),
        "carrier": _plain(item.get("carrier"), 64),
        "channel": _opt_int(item.get("channel"), 0, 10000000),
    }


CARRIER_FIELDS = frozenset({"rat", "status", "channel", "band", "bandwidth_khz", "pci"})
CARRIER_STATUSES = {"primary", "secondary", "unknown"}
MAX_CARRIERS = 8


def _carriers(value: object) -> list[dict] | None:
    """Component carriers from PhysicalChannelConfig (PCC/SCC list behind CA and EN-DC)."""
    if value is None:
        return None
    if not isinstance(value, list) or len(value) > MAX_CARRIERS:
        raise ValueError("invalid carriers")
    carriers = []
    for raw in value:
        item = _exact(raw, set(CARRIER_FIELDS), "carrier")
        if item["rat"] not in RATS:
            raise ValueError("invalid rat")
        if item["status"] not in CARRIER_STATUSES:
            raise ValueError("invalid carrier status")
        carriers.append(
            {
                "rat": item["rat"],
                "status": item["status"],
                "channel": _opt_int(item["channel"], 0, 3279165),
                "band": _opt_int(item["band"], 1, 300),
                "bandwidth_khz": _opt_int(item["bandwidth_khz"], 1, 400000),
                "pci": _opt_int(item["pci"], 0, 1007),
            }
        )
    return carriers


def _subscription(value: object) -> dict:
    item = _shape(
        value,
        {
            "slot",
            "sub_id",
            "carrier",
            "mcc",
            "mnc",
            "rat",
            "network_operator",
            "sim_operator",
            "data_state",
            "service_state",
            "imei",
            "imsi",
            "msisdn",
            "iccid",
        },
        {
            "serving_signal",
            "nr_state",
            "channel",
            "network_type",
            "ca",
            "nr_frequency_range",
            "endc_available",
            "override_network_type",
            "carriers",
            "apn",
            "apn_name",
            "apn_types",
            "apn_protocol",
        },
        "subscription",
    )
    if item["rat"] not in RATS:
        raise ValueError("invalid rat")
    return {
        "slot": _opt_int(item["slot"], -1, 16),
        "sub_id": _opt_int(item["sub_id"], -1, 2**31 - 1),
        "carrier": _plain(item["carrier"], 64),
        "mcc": _plain(item["mcc"], 4),
        "mnc": _plain(item["mnc"], 4),
        "rat": item["rat"],
        "network_operator": _plain(item["network_operator"], 64),
        "sim_operator": _plain(item["sim_operator"], 64),
        "data_state": _plain(item["data_state"], 32),
        "service_state": _plain(item["service_state"], 32),
        "nr_state": _opt_int(item.get("nr_state"), -1, 3),
        "network_type": _opt_int(item.get("network_type"), 0, 100),
        "ca": item.get("ca") if isinstance(item.get("ca"), bool) else None,
        "nr_frequency_range": _opt_int(item.get("nr_frequency_range"), 0, 4),
        "endc_available": item.get("endc_available") if isinstance(item.get("endc_available"), bool) else None,
        "channel": _opt_int(item.get("channel"), 0, 10000000),
        "override_network_type": _opt_int(item.get("override_network_type"), 0, 10),
        "carriers": _carriers(item.get("carriers")),
        "serving_signal": _signal(item.get("serving_signal")),
        "imei": _secret(item["imei"], "imei"),
        "imsi": _secret(item["imsi"], "imsi"),
        "msisdn": _secret(item["msisdn"], "msisdn"),
        "iccid": _secret(item["iccid"], "iccid"),
        "apn": _plain(item.get("apn"), 64),
        "apn_name": _plain(item.get("apn_name"), 64),
        "apn_types": _plain(item.get("apn_types"), 64),
        "apn_protocol": _plain(item.get("apn_protocol"), 16),
    }


def _wifi_neighbor(value: object) -> dict:
    item = _exact(
        value,
        {"ssid", "bssid", "rssi_dbm", "frequency_mhz", "channel", "channel_width_mhz"},
        "wifi_neighbor",
    )
    return {
        "ssid": _plain(item["ssid"], 64),
        "bssid": _plain(item["bssid"], 32),
        "rssi_dbm": _opt_int(item["rssi_dbm"], -127, 0),
        "frequency_mhz": _opt_int(item["frequency_mhz"], 2400, 7200),
        "channel": _opt_int(item["channel"], 1, 300),
        "channel_width_mhz": _opt_int(item["channel_width_mhz"], 20, 320),
    }


def _wifi_congestion(value: object) -> dict | None:
    if value is None:
        return None
    item = _exact(
        value,
        {
            "scan_age_ms",
            "neighbors",
            "same_channel",
            "adjacent_channel",
            "strongest_neighbor_rssi_dbm",
            "score",
            "neighbors_top",
        },
        "wifi_congestion",
    )
    if item["score"] not in WIFI_SCORES:
        raise ValueError("invalid wifi score")
    top = item["neighbors_top"]
    if not isinstance(top, list) or len(top) > MAX_WIFI_NEIGHBORS:
        raise ValueError("invalid wifi neighbors")
    neighbors = _opt_int(item["neighbors"], 0, 512)
    same = _opt_int(item["same_channel"], 0, 512)
    adjacent = _opt_int(item["adjacent_channel"], 0, 512)
    if neighbors is None or same is None or adjacent is None:
        raise ValueError("invalid wifi congestion counts")
    return {
        "scan_age_ms": _opt_int(item["scan_age_ms"], 0, 2**53),
        "neighbors": neighbors,
        "same_channel": same,
        "adjacent_channel": adjacent,
        "strongest_neighbor_rssi_dbm": _opt_int(item["strongest_neighbor_rssi_dbm"], -127, 0),
        "score": item["score"],
        "neighbors_top": [_wifi_neighbor(entry) for entry in top],
    }


def _wifi(value: object) -> dict:
    item = _exact(
        value,
        {
            "status",
            "rssi_dbm",
            "frequency_mhz",
            "channel",
            "link_speed_mbps",
            "ssid",
            "bssid",
            "standard",
            "congestion",
        },
        "wifi",
    )
    if item["status"] not in WIFI_STATUSES:
        raise ValueError("invalid wifi status")
    standard = item["standard"]
    if not isinstance(standard, str) or standard not in WIFI_STANDARDS:
        raise ValueError("invalid wifi standard")
    return {
        "status": item["status"],
        "rssi_dbm": _opt_int(item["rssi_dbm"], -127, 0),
        "frequency_mhz": _opt_int(item["frequency_mhz"], 2400, 7200),
        "channel": _opt_int(item["channel"], 1, 300),
        "link_speed_mbps": _opt_int(item["link_speed_mbps"], 1, 100000),
        "ssid": _plain(item["ssid"], 64),
        "bssid": _plain(item["bssid"], 32),
        "standard": standard,
        "congestion": _wifi_congestion(item["congestion"]),
    }


def _snapshot(value: object) -> dict:
    item = _shape(
        value,
        {"event_id", "ts_ms", "reason", "device", "subscriptions", "cells", "network", "location"},
        {"wifi"},
        "snapshot",
    )
    if item["reason"] not in REASONS:
        raise ValueError("invalid reason")
    if not is_int(item["ts_ms"]) or item["ts_ms"] < 0:
        raise ValueError("invalid timestamp")
    device = _exact(
        item["device"],
        {"manufacturer", "model", "sdk_int", "release", "android_id"},
        "device",
    )
    if not is_int(device["sdk_int"]) or not 1 <= device["sdk_int"] <= 100:
        raise ValueError("invalid sdk")
    subscriptions = item["subscriptions"]
    cells = item["cells"]
    if not isinstance(subscriptions, list) or len(subscriptions) > MAX_SUBS:
        raise ValueError("invalid subscriptions")
    if not isinstance(cells, list) or len(cells) > MAX_CELLS:
        raise ValueError("invalid cells")
    network = _shape(
        item["network"],
        {"transport", "connected", "roaming", "downstream_kbps", "upstream_kbps"},
        {"gateway_ip", "gateway_rtt_ms", "gateway_ipv6", "gateway_ipv6_rtt_ms"},
        "network",
    )
    if network["transport"] not in ("cellular", "wifi", "ethernet", "other", "none"):
        raise ValueError("invalid transport")
    if not isinstance(network["connected"], bool) or not isinstance(network["roaming"], bool):
        raise ValueError("invalid network flags")
    gateway_ip = network.get("gateway_ip")
    if gateway_ip is not None and not isinstance(gateway_ip, str):
        raise ValueError("invalid gateway_ip")
    if isinstance(gateway_ip, str):
        if len(gateway_ip) > 64 or any(ord(ch) < 32 or ord(ch) == 127 for ch in gateway_ip):
            raise ValueError("invalid gateway_ip")
        if not gateway_ip or gateway_ip == "—":
            gateway_ip = None
    gateway_rtt_ms = _opt_float(network.get("gateway_rtt_ms"), 0, 60_000)
    gateway_ipv6 = network.get("gateway_ipv6")
    if gateway_ipv6 is not None and not isinstance(gateway_ipv6, str):
        raise ValueError("invalid gateway_ipv6")
    if isinstance(gateway_ipv6, str):
        if len(gateway_ipv6) > 64 or any(ord(ch) < 32 or ord(ch) == 127 for ch in gateway_ipv6):
            raise ValueError("invalid gateway_ipv6")
        if not gateway_ipv6 or gateway_ipv6 == "—":
            gateway_ipv6 = None
    gateway_ipv6_rtt_ms = _opt_float(network.get("gateway_ipv6_rtt_ms"), 0, 60_000)
    location = _exact(
        item["location"],
        {
            "status",
            "provider",
            "latitude",
            "longitude",
            "accuracy_m",
            "altitude_m",
            "speed_mps",
            "bearing",
            "ts_ms",
        },
        "location",
    )
    if location["status"] not in STATUSES:
        raise ValueError("invalid location status")
    latitude = _opt_float(location["latitude"], -90, 90)
    longitude = _opt_float(location["longitude"], -180, 180)
    if location["status"] != "available" and (latitude is not None or longitude is not None):
        raise ValueError("location withheld")
    if location["status"] == "available" and (latitude is None or longitude is None):
        raise ValueError("location missing")
    out = {
        "event_id": _ident(item["event_id"], ID_MIN, ID_MAX),
        "ts_ms": item["ts_ms"],
        "reason": item["reason"],
        "device": {
            "manufacturer": _plain(device["manufacturer"], 64),
            "model": _plain(device["model"], 64),
            "sdk_int": device["sdk_int"],
            "release": _plain(device["release"], 32),
            "android_id": _secret(device["android_id"], "android_id"),
        },
        "subscriptions": [_subscription(entry) for entry in subscriptions],
        "cells": [_cell(entry) for entry in cells],
        "network": {
            "transport": network["transport"],
            "connected": network["connected"],
            "roaming": network["roaming"],
            "downstream_kbps": _opt_int(network["downstream_kbps"], 0, 100000000) or 0,
            "upstream_kbps": _opt_int(network["upstream_kbps"], 0, 100000000) or 0,
            **(
                {"gateway_ip": gateway_ip}
                if gateway_ip is not None
                else {}
            ),
            **(
                {"gateway_rtt_ms": gateway_rtt_ms}
                if gateway_rtt_ms is not None
                else {}
            ),
            **(
                {"gateway_ipv6": gateway_ipv6}
                if gateway_ipv6 is not None
                else {}
            ),
            **(
                {"gateway_ipv6_rtt_ms": gateway_ipv6_rtt_ms}
                if gateway_ipv6_rtt_ms is not None
                else {}
            ),
        },
        "location": {
            "status": location["status"],
            "provider": _plain(location["provider"], 32),
            "latitude": latitude,
            "longitude": longitude,
            "accuracy_m": _opt_float(location["accuracy_m"], 0, 1000000),
            "altitude_m": _opt_float(location["altitude_m"], -1000, 20000),
            "speed_mps": _opt_float(location["speed_mps"], 0, 300),
            "bearing": _opt_float(location["bearing"], 0, 360),
            "ts_ms": _opt_int(location["ts_ms"], 0, 2**53),
        },
    }
    if "wifi" in item:
        out["wifi"] = _wifi(item["wifi"])
    return out


def _hop(value: object) -> dict:
    item = _shape(value, {"ttl"}, {"host", "rtt_ms"}, "hop")
    ttl = item["ttl"]
    if not is_int(ttl) or ttl < 1 or ttl > 64:
        raise ValueError("invalid hop")
    out = {"ttl": ttl}
    if "host" in item and item["host"] is not None:
        host = _plain(item["host"], 64)
        if host:
            out["host"] = host
    if "rtt_ms" in item:
        rtt = _opt_float(item["rtt_ms"], 0, 60_000)
        if rtt is not None:
            out["rtt_ms"] = rtt
    return out


def _trace(value: object) -> dict:
    item = _shape(value, set(), {"ipv4", "ipv6"}, "trace")
    out = {}
    for family in ("ipv4", "ipv6"):
        hops = item.get(family)
        if hops is None:
            continue
        if not isinstance(hops, list) or len(hops) > 32:
            raise ValueError("invalid trace")
        parsed = [_hop(hop) for hop in hops]
        if parsed:
            out[family] = parsed
    if not out:
        raise ValueError("invalid trace")
    return out


def _test_result(value: object) -> dict:
    item = _shape(
        value,
        {
            "event_id",
            "ts_ms",
            "source",
            "test_type",
            "download_bps",
            "upload_bps",
            "latency_ms",
            "jitter_ms",
            "loss_pct",
            "server",
            "summary",
        },
        {"trace", "latitude", "longitude"},
        "test",
    )
    if item["source"] not in TEST_SOURCES:
        raise ValueError("invalid test source")
    if item["test_type"] not in TEST_TYPES:
        raise ValueError("invalid test type")
    if not is_int(item["ts_ms"]) or item["ts_ms"] < 0:
        raise ValueError("invalid timestamp")
    download = item["download_bps"]
    upload = item["upload_bps"]
    result = {
        "event_id": _ident(item["event_id"], ID_MIN, ID_MAX),
        "ts_ms": item["ts_ms"],
        "source": item["source"],
        "test_type": item["test_type"],
        "download_bps": None if download is None else _counter(download),
        "upload_bps": None if upload is None else _counter(upload),
        "latency_ms": _opt_int(item["latency_ms"], 0, 600000),
        "jitter_ms": _opt_int(item["jitter_ms"], 0, 600000),
        "loss_pct": _opt_float(item["loss_pct"], 0, 100),
        "server": _plain(item["server"], 120),
        "summary": _text(item["summary"], 240),
    }
    if "trace" in item and item["trace"] is not None:
        result["trace"] = _trace(item["trace"])
    if "latitude" in item or "longitude" in item:
        latitude = _opt_float(item.get("latitude"), -90, 90)
        longitude = _opt_float(item.get("longitude"), -180, 180)
        if latitude is None or longitude is None:
            raise ValueError("location missing")
        result["latitude"] = latitude
        result["longitude"] = longitude
    return result


def _field_text(value: object) -> str:
    if not isinstance(value, str) or len(value) > 240:
        raise ValueError("invalid field")
    if any(ord(ch) < 32 or ord(ch) == 127 for ch in value):
        raise ValueError("invalid field")
    return value


def _os_fields(value: object) -> dict:
    if not isinstance(value, dict) or len(value) > MAX_OS_FIELDS:
        raise ValueError("invalid result fields")
    clean = {}
    for key, item in value.items():
        if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", key):
            raise ValueError("invalid field name")
        clean[key] = _field_text(item)
    return clean


def _sdk_payload(value: object) -> str:
    if not isinstance(value, str) or not value or len(value) > MAX_SDK_PAYLOAD:
        raise ValueError("invalid sdk payload")
    if any(ord(ch) < 32 or ord(ch) == 127 for ch in value):
        raise ValueError("invalid sdk payload")
    return value


def _os_result(value: object) -> dict:
    if not isinstance(value, dict):
        raise ValueError("unexpected os_result fields")
    keys = set(value)
    base = {"event_id", "ts_ms", "class_name", "source", "stage", "fields"}
    if keys == base:
        payload = None
    elif keys == base | {"payload"}:
        payload = value["payload"]
    else:
        raise ValueError("unexpected os_result fields")
    if value["source"] not in RESULT_SOURCES:
        raise ValueError("invalid result source")
    if value["stage"] not in RESULT_STAGES:
        raise ValueError("invalid result stage")
    if not is_int(value["ts_ms"]) or value["ts_ms"] < 0:
        raise ValueError("invalid timestamp")
    if value["stage"] == "sdk_json":
        if payload is None:
            raise ValueError("missing sdk payload")
        payload = _sdk_payload(payload)
    elif payload is not None:
        raise ValueError("unexpected sdk payload")
    result = {
        "event_id": _ident(value["event_id"], ID_MIN, ID_MAX),
        "ts_ms": value["ts_ms"],
        "class_name": _text(value["class_name"], 120),
        "source": value["source"],
        "stage": value["stage"],
        "fields": _os_fields(value["fields"]),
    }
    if payload is not None:
        result["payload"] = payload
    return result


def validate_measurement_plain(body: object) -> dict:
    if not isinstance(body, dict):
        raise ValueError("unexpected measurement fields")
    allowed = {"snapshots", "tests"}
    if "os_results" in body:
        allowed.add("os_results")
    if set(body) != allowed:
        raise ValueError("unexpected measurement fields")
    snapshots = body["snapshots"]
    tests = body["tests"]
    results = body.get("os_results", [])
    if not isinstance(snapshots, list) or not isinstance(tests, list) or not isinstance(results, list):
        raise ValueError("invalid measurement lists")
    if len(snapshots) > MAX_SNAPSHOTS or len(tests) > MAX_TESTS or len(results) > MAX_OS_RESULTS:
        raise ValueError("measurement batch too large")
    if not snapshots and not tests and not results:
        raise ValueError("empty measurement batch")
    return {
        "snapshots": [_snapshot(entry) for entry in snapshots],
        "tests": [_test_result(entry) for entry in tests],
        "os_results": [_os_result(entry) for entry in results],
    }


def measurement_aad(device_id: str, batch_id: str, sent_at_ms: int) -> bytes:
    return f"{device_id}\n{batch_id}\n{sent_at_ms}".encode("utf-8")


def validate_measurement_envelope(body: object, key: bytes) -> dict:
    from opensignal_mirror.aead import decrypt

    item = _exact(
        body,
        {"schema_version", "device_id", "batch_id", "sent_at_ms", "alg", "iv_b64", "ciphertext_b64"},
        "envelope",
    )
    if item["schema_version"] != 1:
        raise ValueError("unsupported schema")
    if item["alg"] != "AES-256-GCM":
        raise ValueError("unsupported algorithm")
    device_id = _ident(item["device_id"], ID_MIN, 64)
    batch_id = _ident(item["batch_id"], ID_MIN, ID_MAX)
    if not is_int(item["sent_at_ms"]) or item["sent_at_ms"] < 0:
        raise ValueError("invalid timestamp")
    try:
        iv = base64.b64decode(item["iv_b64"], validate=True)
        ciphertext = base64.b64decode(item["ciphertext_b64"], validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError("invalid ciphertext") from exc
    if len(ciphertext) > 480000:
        raise ValueError("ciphertext too large")
    plaintext = decrypt(key, iv, measurement_aad(device_id, batch_id, item["sent_at_ms"]), ciphertext)
    try:
        decoded = json.loads(plaintext.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError("invalid measurement plaintext") from exc
    parsed = validate_measurement_plain(decoded)
    parsed.update(
        {
            "schema_version": 1,
            "device_id": device_id,
            "batch_id": batch_id,
            "sent_at_ms": item["sent_at_ms"],
        }
    )
    return parsed


def correlation_ids(snapshot: dict) -> tuple[str, str, str]:
    imeis = []
    imsis = []
    rat = ""
    for sub in snapshot.get("subscriptions", []):
        if sub["imei"]["status"] == "available":
            imeis.append(sub["imei"]["value"])
        if sub["imsi"]["status"] == "available":
            imsis.append(sub["imsi"]["value"])
        if not rat and sub.get("rat") and sub["rat"] != "UNKNOWN":
            rat = sub["rat"]
    for cell in snapshot.get("cells", []):
        if cell["role"] == "serving":
            rat = cell["rat"]
            break
    return ",".join(imeis)[:128], ",".join(imsis)[:128], rat[:16]
