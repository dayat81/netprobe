"""Bounded HTTP collectors for OpenSignal flow metadata and debug events.

The public interfaces accept only authenticated POSTs. Health, metrics, and
recent-event reads are limited to loopback. Request bodies are flow metadata
or sanitized diagnostics; packet payloads are rejected by schema.
"""

from __future__ import annotations

import hashlib
import hmac
import ipaddress
import json
import os
import secrets
import sqlite3
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
from typing import Mapping
from urllib.parse import parse_qs, urlsplit

from opensignal_mirror.schema import correlation_ids, validate_measurement_envelope
from opensignal_mirror.telemetry import ClickHouseTelemetry

FLOW_PATH = "/v1/flows"
DEBUG_PATH = "/v1/debug"
MEASURE_PATH = "/v1/measurements"
NR_STATUS_PATH = "/v1/nr-status"
OOKLA_PATH = "/v1/ookla"
TIMELINE_PATH = "/v1/timeline"
RUM_PATH = "/v1/rum"
LOGIN_PATH = "/login"
READ_PATHS = {"/", "/dashboard", "/api/live", "/api/measurements"}
ADMIN_PATHS = {"/health", "/metrics", "/v1/recent"}
SESSION_TTL = 12 * 3600
MAX_FLOWS = 200
MAX_DEBUG_EVENTS = 50
MAX_RUM_EVENTS = 8
MAX_RUM_LOGS = 40
RUM_KINDS = {"session", "heartbeat", "crash", "nonfatal", "exit"}
RUM_LEVELS = {"info", "warn", "error"}
RUM_REQUIRED = {
    "event_id",
    "ts_ms",
    "kind",
    "level",
    "code",
    "message",
    "app_version",
    "version_code",
    "sdk",
    "model",
    "manufacturer",
}
RUM_OPTIONAL = {
    "network",
    "rat",
    "foreground",
    "cold_start_ms",
    "queue",
    "upload_code",
    "speed",
    "abi",
    "stack",
    "logs",
}
MAX_MESSAGE = 240
MAX_CODE = 64
ID_MIN = 8
ID_MAX = 80
NONCE_MIN = 16
NONCE_MAX = 128
MAX_COUNTER = 2**53
MAX_NR_SERVING = 8
MAX_NR_NEIGHBORS = 12
NR_PATH_STATUSES = {"advertised_only", "data_path", "lte_only", "unknown"}
NR_VERDICTS = {"LTE_ONLY", "NSA_ADVERTISED", "NSA_CONNECTED", "NR_SA", "UNKNOWN"}
TIMELINE_KINDS = {"app_open", "plmn_change", "test_start", "test_complete", "test_fail"}
TIMELINE_APPS = {"opensignal", "ookla"}
MAX_TIMELINE_EVENTS = 32
TIMELINE_EVENT_FIELDS = {
    "event_id",
    "ts_ms",
    "session_id",
    "kind",
    "test_kind",
    "run_id",
    "result_id",
    "plmn",
    "prev_plmn",
    "duration_ms",
    "detail",
}
NR_CELL_FIELDS = {
    "rat",
    "role",
    "registered",
    "mcc",
    "mnc",
    "pci",
    "tac",
    "ci",
    "earfcn",
    "nrarfcn",
    "bandwidth_khz",
    "bands",
    "rsrp",
    "rsrq",
    "sinr",
    "rssi",
    "ss_rsrp",
    "ss_rsrq",
    "ss_sinr",
}
NR_RATS = {"LTE", "NR", "WCDMA", "GSM", "CDMA", "TDSCDMA", "UNKNOWN"}


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def is_loopback(addr: str) -> bool:
    try:
        return ipaddress.ip_address(addr).is_loopback
    except ValueError:
        return False


def is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _ident_ok(value: str) -> bool:
    try:
        _ident(value, NONCE_MIN, NONCE_MAX)
    except ValueError:
        return False
    return True


def _same(left: str, right: str) -> bool:
    if not isinstance(left, str) or not isinstance(right, str) or len(left) != len(right) or not left:
        return False
    return hmac.compare_digest(left, right)


def _ident(value: object, minimum: int, maximum: int) -> str:
    if not isinstance(value, str) or not minimum <= len(value) <= maximum:
        raise ValueError("invalid identifier length")
    if any(ch not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-" for ch in value):
        raise ValueError("invalid identifier")
    return value


def _counter(value: object) -> int:
    if not is_int(value) or value < 0 or value > MAX_COUNTER:
        raise ValueError("invalid counter")
    return value


def _port(value: object) -> int:
    if not is_int(value) or value < 0 or value > 65535:
        raise ValueError("invalid port")
    return value


def _ip(value: object) -> str:
    if not isinstance(value, str) or len(value) > 64:
        raise ValueError("invalid ip")
    try:
        return str(ipaddress.ip_address(value))
    except ValueError as exc:
        raise ValueError("invalid ip") from exc


def _text(value: object, limit: int) -> str:
    if not isinstance(value, str) or not value or len(value) > limit:
        raise ValueError("invalid text")
    if any(ord(ch) < 32 or ord(ch) == 127 for ch in value):
        raise ValueError("invalid text")
    return value


def validate_flows(body: object) -> dict:
    if not isinstance(body, dict):
        raise ValueError("body must be an object")
    allowed = {"schema_version", "device_id", "batch_id", "sent_at_ms", "flows"}
    if set(body) != allowed:
        raise ValueError("unexpected flow fields")
    if body["schema_version"] != 1:
        raise ValueError("unsupported schema")
    device_id = _ident(body["device_id"], ID_MIN, 64)
    batch_id = _ident(body["batch_id"], ID_MIN, ID_MAX)
    if not is_int(body["sent_at_ms"]) or body["sent_at_ms"] < 0:
        raise ValueError("invalid timestamp")
    flows = body["flows"]
    if not isinstance(flows, list) or len(flows) > MAX_FLOWS:
        raise ValueError("invalid flow list")
    clean = []
    flow_fields = {
        "event_id",
        "proto",
        "family",
        "local_port",
        "remote_ip",
        "remote_port",
        "tx_bytes",
        "rx_bytes",
        "tx_packets",
        "rx_packets",
        "first_seen_ms",
        "last_seen_ms",
        "state",
    }
    for item in flows:
        if not isinstance(item, dict) or set(item) != flow_fields:
            raise ValueError("unexpected flow item fields")
        if item["proto"] not in ("tcp", "udp"):
            raise ValueError("invalid proto")
        if item["family"] not in ("ipv4", "ipv6"):
            raise ValueError("invalid family")
        if item["state"] not in ("open", "closed", "error"):
            raise ValueError("invalid state")
        for stamp in ("first_seen_ms", "last_seen_ms"):
            if not is_int(item[stamp]) or item[stamp] < 0:
                raise ValueError("invalid flow timestamp")
        clean.append(
            {
                "event_id": _ident(item["event_id"], ID_MIN, ID_MAX),
                "proto": item["proto"],
                "family": item["family"],
                "local_port": _port(item["local_port"]),
                "remote_ip": _ip(item["remote_ip"]),
                "remote_port": _port(item["remote_port"]),
                "tx_bytes": _counter(item["tx_bytes"]),
                "rx_bytes": _counter(item["rx_bytes"]),
                "tx_packets": _counter(item["tx_packets"]),
                "rx_packets": _counter(item["rx_packets"]),
                "first_seen_ms": item["first_seen_ms"],
                "last_seen_ms": item["last_seen_ms"],
                "state": item["state"],
            }
        )
    return {
        "schema_version": 1,
        "device_id": device_id,
        "batch_id": batch_id,
        "sent_at_ms": body["sent_at_ms"],
        "flows": clean,
    }


def validate_debug(body: object) -> dict:
    if not isinstance(body, dict):
        raise ValueError("body must be an object")
    if set(body) != {"schema_version", "device_id", "events"}:
        raise ValueError("unexpected debug fields")
    if body["schema_version"] != 1:
        raise ValueError("unsupported schema")
    events = body["events"]
    if not isinstance(events, list) or not 1 <= len(events) <= MAX_DEBUG_EVENTS:
        raise ValueError("invalid debug list")
    clean = []
    fields = {"event_id", "ts_ms", "level", "code", "message"}
    for item in events:
        if not isinstance(item, dict) or set(item) != fields:
            raise ValueError("unexpected debug item fields")
        if item["level"] not in ("info", "warn", "error"):
            raise ValueError("invalid level")
        if not is_int(item["ts_ms"]) or item["ts_ms"] < 0:
            raise ValueError("invalid debug timestamp")
        clean.append(
            {
                "event_id": _ident(item["event_id"], ID_MIN, ID_MAX),
                "ts_ms": item["ts_ms"],
                "level": item["level"],
                "code": _text(item["code"], MAX_CODE),
                "message": _text(item["message"], MAX_MESSAGE),
            }
        )
    return {
        "schema_version": 1,
        "device_id": _ident(body["device_id"], ID_MIN, 64),
        "events": clean,
    }


def _rum_clip(value: object, limit: int, newlines: bool = False) -> str:
    if not isinstance(value, str):
        raise ValueError("invalid text")
    kept = []
    for ch in value:
        code = ord(ch)
        if ch == "\n" and newlines:
            kept.append(ch)
        elif ch == "\r":
            continue
        elif code < 32 or code == 127:
            kept.append(" ")
        else:
            kept.append(ch)
    text = "".join(kept).strip()
    if len(text) > limit:
        text = text[:limit].rstrip()
    return text or "-"


def _rum_int(item: dict, key: str, low: int, high: int) -> int | None:
    if key not in item:
        return None
    value = item[key]
    if not is_int(value) or value < low or value > high:
        raise ValueError("invalid rum number")
    return value


def _rum_logs(value: object) -> list[dict]:
    if not isinstance(value, list) or len(value) > MAX_RUM_LOGS:
        raise ValueError("invalid rum logs")
    logs = []
    fields = {"ts_ms", "level", "tag", "message"}
    for item in value:
        if not isinstance(item, dict) or set(item) != fields:
            raise ValueError("unexpected rum log fields")
        if item["level"] not in RUM_LEVELS:
            raise ValueError("invalid rum log level")
        if not is_int(item["ts_ms"]) or item["ts_ms"] < 0:
            raise ValueError("invalid rum log time")
        logs.append(
            {
                "ts_ms": item["ts_ms"],
                "level": item["level"],
                "tag": _rum_clip(item["tag"], 40),
                "message": _rum_clip(item["message"], 180),
            }
        )
    return logs


def validate_rum(body: object) -> dict:
    if not isinstance(body, dict):
        raise ValueError("body must be an object")
    if set(body) != {"schema_version", "device_id", "batch_id", "sent_at_ms", "events"}:
        raise ValueError("unexpected rum fields")
    if body["schema_version"] != 1:
        raise ValueError("unsupported schema")
    if not is_int(body["sent_at_ms"]) or body["sent_at_ms"] < 0:
        raise ValueError("invalid timestamp")
    events = body["events"]
    if not isinstance(events, list) or not 1 <= len(events) <= MAX_RUM_EVENTS:
        raise ValueError("invalid rum list")
    clean = []
    for item in events:
        if not isinstance(item, dict):
            raise ValueError("invalid rum event")
        extra = set(item) - RUM_REQUIRED - RUM_OPTIONAL
        if extra or not RUM_REQUIRED <= set(item):
            raise ValueError("unexpected rum event fields")
        kind = item["kind"]
        if kind not in RUM_KINDS:
            raise ValueError("invalid rum kind")
        if item["level"] not in RUM_LEVELS:
            raise ValueError("invalid rum level")
        if not is_int(item["ts_ms"]) or item["ts_ms"] < 0:
            raise ValueError("invalid rum timestamp")
        if kind not in ("crash", "nonfatal") and ("stack" in item or "logs" in item):
            raise ValueError("unexpected rum trace")
        event = {
            "event_id": _ident(item["event_id"], ID_MIN, ID_MAX),
            "ts_ms": item["ts_ms"],
            "kind": kind,
            "level": item["level"],
            "code": _rum_clip(item["code"], 64),
            "message": _rum_clip(item["message"], 512),
            "app_version": _rum_clip(item["app_version"], 32),
            "version_code": _rum_int(item, "version_code", 1, 1_000_000),
            "sdk": _rum_int(item, "sdk", 1, 100),
            "model": _rum_clip(item["model"], 64),
            "manufacturer": _rum_clip(item["manufacturer"], 64),
        }
        for key, limit in (
            ("network", 32),
            ("rat", 16),
            ("speed", 120),
            ("abi", 32),
        ):
            if key in item:
                event[key] = _rum_clip(item[key], limit)
        if "foreground" in item:
            if not isinstance(item["foreground"], bool):
                raise ValueError("invalid rum foreground")
            event["foreground"] = item["foreground"]
        cold = _rum_int(item, "cold_start_ms", 0, 600_000)
        if cold is not None:
            event["cold_start_ms"] = cold
        queue = _rum_int(item, "queue", 0, 100_000)
        if queue is not None:
            event["queue"] = queue
        upload_code = _rum_int(item, "upload_code", -1, 999)
        if upload_code is not None:
            event["upload_code"] = upload_code
        if "stack" in item:
            event["stack"] = _rum_clip(item["stack"], 8192, newlines=True)
        if "logs" in item:
            event["logs"] = _rum_logs(item["logs"])
        clean.append(event)
    return {
        "schema_version": 1,
        "device_id": _ident(body["device_id"], ID_MIN, 64),
        "batch_id": _ident(body["batch_id"], ID_MIN, ID_MAX),
        "sent_at_ms": body["sent_at_ms"],
        "events": clean,
    }


def _opt_plmn_part(value: object, limit: int) -> str | None:
    if value is None or value == "":
        return None
    text = _text(value, limit)
    if not text.isdigit():
        raise ValueError("invalid plmn part")
    return text


def _opt_cell_int(value: object, low: int, high: int) -> int | None:
    if value is None:
        return None
    if not is_int(value) or value < low or value > high:
        raise ValueError("invalid cell number")
    return value


def _opt_cell_long(value: object, low: int, high: int) -> int | None:
    if value is None:
        return None
    if not is_int(value) or value < low or value > high:
        raise ValueError("invalid cell id")
    return value


def _validate_nr_cell(item: object) -> dict:
    if not isinstance(item, dict) or not item:
        raise ValueError("invalid cell")
    extra = set(item) - NR_CELL_FIELDS
    if extra:
        raise ValueError("unexpected cell fields")
    rat = item.get("rat", "UNKNOWN")
    if rat not in NR_RATS:
        raise ValueError("invalid cell rat")
    role = item.get("role")
    if role is not None and role not in ("serving", "neighbor"):
        raise ValueError("invalid cell role")
    registered = item.get("registered")
    if registered is not None and not isinstance(registered, bool):
        raise ValueError("invalid registered")
    bands = item.get("bands")
    clean_bands: list[int] = []
    if bands is not None:
        if not isinstance(bands, list) or len(bands) > 16:
            raise ValueError("invalid bands")
        for band in bands:
            if not is_int(band) or band < 0 or band > 1024:
                raise ValueError("invalid band")
            clean_bands.append(band)
    clean = {"rat": rat}
    if role is not None:
        clean["role"] = role
    if registered is not None:
        clean["registered"] = registered
    mcc = _opt_plmn_part(item.get("mcc"), 3)
    mnc = _opt_plmn_part(item.get("mnc"), 3)
    if mcc is not None:
        clean["mcc"] = mcc
    if mnc is not None:
        clean["mnc"] = mnc
    for key, low, high in (
        ("pci", 0, 1007),
        ("tac", 0, 16_777_215),
        ("earfcn", 0, 10_000_000),
        ("nrarfcn", 0, 3_279_165),
        ("bandwidth_khz", 0, 400_000),
        ("rsrp", -200, 0),
        ("rsrq", -43, 20),
        ("sinr", -30, 50),
        ("rssi", -200, 0),
        ("ss_rsrp", -200, 0),
        ("ss_rsrq", -43, 20),
        ("ss_sinr", -30, 50),
    ):
        number = _opt_cell_int(item.get(key), low, high)
        if number is not None:
            clean[key] = number
    ci = _opt_cell_long(item.get("ci"), 0, 2**53)
    if ci is not None:
        clean["ci"] = ci
    if clean_bands:
        clean["bands"] = clean_bands
    return clean


def validate_nr_status(body: object) -> dict:
    if not isinstance(body, dict):
        raise ValueError("body must be an object")
    required = {
        "schema_version",
        "device_id",
        "event_id",
        "sent_at_ms",
        "path_status",
        "verdict",
        "plmn",
        "mcc",
        "mnc",
        "override_network_type",
        "override_label",
        "nr_state",
        "nr_state_label",
        "data_network_type",
        "data_label",
        "display_network_type",
        "nr_frequency_range",
        "serving_cells",
        "neighbor_cells",
    }
    if set(body) != required:
        raise ValueError("unexpected nr-status fields")
    if body["schema_version"] != 1:
        raise ValueError("unsupported schema")
    path_status = body["path_status"]
    if path_status not in NR_PATH_STATUSES:
        raise ValueError("invalid path_status")
    verdict = body["verdict"]
    if verdict not in NR_VERDICTS:
        raise ValueError("invalid verdict")
    if not is_int(body["sent_at_ms"]) or body["sent_at_ms"] < 0:
        raise ValueError("invalid timestamp")
    for key in ("override_network_type", "nr_state", "data_network_type", "display_network_type", "nr_frequency_range"):
        if body[key] is not None and (not is_int(body[key]) or body[key] < 0 or body[key] > 255):
            raise ValueError("invalid telephony code")
    serving = body["serving_cells"]
    neighbors = body["neighbor_cells"]
    if not isinstance(serving, list) or len(serving) > MAX_NR_SERVING:
        raise ValueError("invalid serving_cells")
    if not isinstance(neighbors, list) or len(neighbors) > MAX_NR_NEIGHBORS:
        raise ValueError("invalid neighbor_cells")
    plmn = body["plmn"]
    if plmn is not None and plmn != "":
        plmn = _text(plmn, 12)
        if not plmn.isdigit():
            raise ValueError("invalid plmn")
    else:
        plmn = None
    return {
        "schema_version": 1,
        "device_id": _ident(body["device_id"], ID_MIN, 64),
        "event_id": _ident(body["event_id"], ID_MIN, ID_MAX),
        "sent_at_ms": body["sent_at_ms"],
        "ts_ms": body["sent_at_ms"],
        "path_status": path_status,
        "verdict": verdict,
        "plmn": plmn,
        "mcc": _opt_plmn_part(body["mcc"], 3),
        "mnc": _opt_plmn_part(body["mnc"], 3),
        "override_network_type": body["override_network_type"],
        "override_label": _text(body["override_label"], 32) if body["override_label"] else "n/a",
        "nr_state": body["nr_state"],
        "nr_state_label": _text(body["nr_state_label"], 32) if body["nr_state_label"] else "n/a",
        "data_network_type": body["data_network_type"],
        "data_label": _text(body["data_label"], 32) if body["data_label"] else "n/a",
        "display_network_type": body["display_network_type"],
        "nr_frequency_range": body["nr_frequency_range"],
        "serving_cells": [_validate_nr_cell(item) for item in serving],
        "neighbor_cells": [_validate_nr_cell(item) for item in neighbors],
        "test_type": path_status,
    }


def _opt_finite_number(value: object, low: float, high: float) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("invalid number")
    number = float(value)
    if number != number or number < low or number > high:  # NaN check
        raise ValueError("number out of range")
    return number


OOKLA_RADIO_KEYS = {
    "serving_cells",
    "neighbor_cells",
    "data_network_type",
    "plmn",
    "mcc",
    "mnc",
}


def validate_ookla_result(body: object) -> dict:
    if not isinstance(body, dict):
        raise ValueError("body must be an object")
    test_kind = body.get("test_kind")
    if test_kind is None or test_kind == "speed":
        return _validate_ookla_speed(body)
    if test_kind == "video":
        return _validate_ookla_video(body)
    if test_kind == "radio":
        return _validate_ookla_radio(body)
    raise ValueError("unsupported ookla test_kind")


def _ookla_raw(body: dict) -> str:
    raw = body.get("raw")
    if raw is None:
        raw = ""
    if not isinstance(raw, str):
        raise ValueError("invalid raw")
    if len(raw) > 8192:
        raw = raw[:8192]
    return raw


def _ookla_cell_lists(body: dict) -> tuple[list, list]:
    serving = body.get("serving_cells", [])
    neighbors = body.get("neighbor_cells", [])
    if serving is None:
        serving = []
    if neighbors is None:
        neighbors = []
    if not isinstance(serving, list) or len(serving) > 32:
        raise ValueError("invalid serving_cells")
    if not isinstance(neighbors, list) or len(neighbors) > 64:
        raise ValueError("invalid neighbor_cells")
    return (
        [_validate_nr_cell(item) for item in serving],
        [_validate_nr_cell(item) for item in neighbors],
    )


def _ookla_radio_meta(body: dict) -> dict:
    out: dict = {}
    for key in ("data_network_type", "plmn", "mcc", "mnc"):
        if key not in body or body.get(key) is None:
            out[key] = None
            continue
        limit = 64 if key == "data_network_type" else (6 if key == "plmn" else 3)
        out[key] = _text(body.get(key), limit) if body.get(key) else None
    return out


def _validate_ookla_speed(body: dict) -> dict:
    required = {
        "schema_version",
        "device_id",
        "event_id",
        "sent_at_ms",
        "result_id",
        "download_mbps",
        "upload_mbps",
        "ping_ms",
        "jitter_ms",
        "loss",
        "server_id",
        "server_name",
        "server_location",
        "isp_name",
        "network_type",
        "latitude",
        "longitude",
        "raw",
    }
    # Accept legacy bodies (no test_kind) and explicit test_kind=speed.
    # Radio keys optional for backward compatibility with older lab APKs.
    keys = set(body)
    if "test_kind" in keys:
        if body.get("test_kind") not in (None, "speed"):
            raise ValueError("unexpected ookla fields")
        keys = keys - {"test_kind"}
    keys = keys - OOKLA_RADIO_KEYS
    if keys != required:
        raise ValueError("unexpected ookla fields")
    if body["schema_version"] != 1:
        raise ValueError("unsupported schema")
    if not is_int(body["sent_at_ms"]) or body["sent_at_ms"] < 0:
        raise ValueError("invalid timestamp")
    raw = _ookla_raw(body)
    serving, neighbors = _ookla_cell_lists(body)
    radio = _ookla_radio_meta(body)
    return {
        "schema_version": 1,
        "test_kind": "speed",
        "device_id": _ident(body["device_id"], ID_MIN, 64),
        "event_id": _ident(body["event_id"], ID_MIN, ID_MAX),
        "sent_at_ms": body["sent_at_ms"],
        "ts_ms": body["sent_at_ms"],
        "result_id": _text(body["result_id"], 128) if body["result_id"] else "",
        "download_mbps": _opt_finite_number(body["download_mbps"], 0.0, 1e6),
        "upload_mbps": _opt_finite_number(body["upload_mbps"], 0.0, 1e6),
        "ping_ms": _opt_finite_number(body["ping_ms"], 0.0, 1e6),
        "jitter_ms": _opt_finite_number(body["jitter_ms"], 0.0, 1e6),
        "loss": _opt_finite_number(body["loss"], 0.0, 1.0),
        "server_id": _text(body["server_id"], 64) if body["server_id"] else None,
        "server_name": _text(body["server_name"], 128) if body["server_name"] else None,
        "server_location": _text(body["server_location"], 128) if body["server_location"] else None,
        "isp_name": _text(body["isp_name"], 128) if body["isp_name"] else None,
        "network_type": _text(body["network_type"], 64) if body["network_type"] else None,
        "latitude": _opt_finite_number(body["latitude"], -90.0, 90.0),
        "longitude": _opt_finite_number(body["longitude"], -180.0, 180.0),
        "raw": raw,
        "serving_cells": serving,
        "neighbor_cells": neighbors,
        **radio,
    }


def _validate_ookla_video(body: dict) -> dict:
    required = {
        "schema_version",
        "test_kind",
        "device_id",
        "event_id",
        "sent_at_ms",
        "result_id",
        "load_time_ms",
        "max_resolution",
        "buffering_pct",
        "provider",
        "network_type",
        "latitude",
        "longitude",
        "raw",
    }
    keys = set(body) - OOKLA_RADIO_KEYS
    if keys != required:
        raise ValueError("unexpected ookla video fields")
    if body["schema_version"] != 1:
        raise ValueError("unsupported schema")
    if body.get("test_kind") != "video":
        raise ValueError("unsupported ookla test_kind")
    if not is_int(body["sent_at_ms"]) or body["sent_at_ms"] < 0:
        raise ValueError("invalid timestamp")
    raw = _ookla_raw(body)
    serving, neighbors = _ookla_cell_lists(body)
    radio = _ookla_radio_meta(body)
    return {
        "schema_version": 1,
        "test_kind": "video",
        "device_id": _ident(body["device_id"], ID_MIN, 64),
        "event_id": _ident(body["event_id"], ID_MIN, ID_MAX),
        "sent_at_ms": body["sent_at_ms"],
        "ts_ms": body["sent_at_ms"],
        "result_id": _text(body["result_id"], 128) if body["result_id"] else "",
        "load_time_ms": _opt_finite_number(body["load_time_ms"], 0.0, 1e9),
        "max_resolution": _text(body["max_resolution"], 64) if body["max_resolution"] else None,
        "buffering_pct": _opt_finite_number(body["buffering_pct"], 0.0, 1e6),
        "provider": _text(body["provider"], 128) if body["provider"] else None,
        "network_type": _text(body["network_type"], 64) if body["network_type"] else None,
        "latitude": _opt_finite_number(body["latitude"], -90.0, 90.0),
        "longitude": _opt_finite_number(body["longitude"], -180.0, 180.0),
        "raw": raw,
        "serving_cells": serving,
        "neighbor_cells": neighbors,
        **radio,
    }


def _validate_ookla_radio(body: dict) -> dict:
    required = {
        "schema_version",
        "test_kind",
        "device_id",
        "event_id",
        "sent_at_ms",
        "result_id",
        "network_type",
        "latitude",
        "longitude",
        "raw",
        "serving_cells",
        "neighbor_cells",
        "data_network_type",
        "plmn",
        "mcc",
        "mnc",
    }
    if set(body) != required:
        raise ValueError("unexpected ookla radio fields")
    if body["schema_version"] != 1:
        raise ValueError("unsupported schema")
    if body.get("test_kind") != "radio":
        raise ValueError("unsupported ookla test_kind")
    if not is_int(body["sent_at_ms"]) or body["sent_at_ms"] < 0:
        raise ValueError("invalid timestamp")
    raw = _ookla_raw(body)
    serving, neighbors = _ookla_cell_lists(body)
    radio = _ookla_radio_meta(body)
    return {
        "schema_version": 1,
        "test_kind": "radio",
        "device_id": _ident(body["device_id"], ID_MIN, 64),
        "event_id": _ident(body["event_id"], ID_MIN, ID_MAX),
        "sent_at_ms": body["sent_at_ms"],
        "ts_ms": body["sent_at_ms"],
        "result_id": _text(body["result_id"], 128) if body["result_id"] else "",
        "network_type": _text(body["network_type"], 64) if body["network_type"] else None,
        "latitude": _opt_finite_number(body["latitude"], -90.0, 90.0),
        "longitude": _opt_finite_number(body["longitude"], -180.0, 180.0),
        "raw": raw,
        "serving_cells": serving,
        "neighbor_cells": neighbors,
        **radio,
    }


def _timeline_plmn(value: object) -> str | None:
    if value is None or value == "":
        return None
    if not isinstance(value, str) or not 5 <= len(value) <= 6 or not value.isdigit():
        raise ValueError("invalid timeline plmn")
    return value


def _timeline_event(item: object) -> dict:
    if not isinstance(item, dict) or set(item) != TIMELINE_EVENT_FIELDS:
        raise ValueError("unexpected timeline event fields")
    kind = item["kind"]
    if kind not in TIMELINE_KINDS:
        raise ValueError("invalid timeline kind")
    if not is_int(item["ts_ms"]) or item["ts_ms"] < 0:
        raise ValueError("invalid timestamp")
    duration = item["duration_ms"]
    if duration is not None and (not is_int(duration) or duration < 0 or duration > 7 * 24 * 3600 * 1000):
        raise ValueError("invalid duration")
    detail = item["detail"]
    if detail is None:
        detail = ""
    else:
        detail = _text(detail, 200)
    return {
        "event_id": _ident(item["event_id"], ID_MIN, ID_MAX),
        "ts_ms": item["ts_ms"],
        "session_id": _ident(item["session_id"], ID_MIN, ID_MAX),
        "kind": kind,
        "test_kind": _text(item["test_kind"], 16) if item["test_kind"] else "",
        "run_id": _text(item["run_id"], ID_MAX) if item["run_id"] else "",
        "result_id": _text(item["result_id"], 128) if item["result_id"] else "",
        "plmn": _timeline_plmn(item["plmn"]),
        "prev_plmn": _timeline_plmn(item["prev_plmn"]),
        "duration_ms": duration,
        "detail": detail,
    }


def validate_timeline(body: object) -> dict:
    if not isinstance(body, dict):
        raise ValueError("body must be an object")
    if set(body) != {"schema_version", "device_id", "app", "events"}:
        raise ValueError("unexpected timeline fields")
    if body["schema_version"] != 1:
        raise ValueError("unsupported schema")
    app = body["app"]
    if app not in TIMELINE_APPS:
        raise ValueError("invalid timeline app")
    events = body["events"]
    if not isinstance(events, list) or not events or len(events) > MAX_TIMELINE_EVENTS:
        raise ValueError("invalid timeline events")
    return {
        "schema_version": 1,
        "device_id": _ident(body["device_id"], ID_MIN, 64),
        "app": app,
        "events": [_timeline_event(item) for item in events],
    }


def canonical(method: str, path: str, timestamp: str, nonce: str, body: bytes) -> bytes:
    digest = hashlib.sha256(body).hexdigest()
    return f"{method}\n{path}\n{timestamp}\n{nonce}\n{digest}".encode("ascii")


def sign(key: bytes, method: str, path: str, timestamp: str, nonce: str, body: bytes) -> str:
    return hmac.new(key, canonical(method, path, timestamp, nonce, body), hashlib.sha256).hexdigest()


def load_secrets(path: str) -> dict[str, str]:
    values = {}
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            name, value = line.split("=", 1)
            values[name.strip()] = value.strip()
    return values


def load_key(path: str) -> tuple[str, bytes]:
    values = load_secrets(path)
    key_id = values["key_id"]
    key = bytes.fromhex(values["key_hex"])
    if not key or len(key) < 16:
        raise ValueError("HMAC key is too short")
    return key_id, key


def load_measure_key(path: str) -> bytes:
    key = bytes.fromhex(load_secrets(path)["measure_key_hex"])
    if len(key) != 32:
        raise ValueError("measurement key must be 32 bytes")
    return key


def parse_allowlist(text: str) -> list[ipaddress.IPv4Network | ipaddress.IPv6Network]:
    networks = []
    for raw in text.split(","):
        item = raw.strip()
        if not item or item.startswith("#"):
            continue
        if "/" not in item:
            item += "/128" if ":" in item else "/32"
        networks.append(ipaddress.ip_network(item, strict=False))
    return networks


def ip_allowed(addr: str, networks: list[ipaddress.IPv4Network | ipaddress.IPv6Network]) -> bool:
    try:
        parsed = ipaddress.ip_address(addr)
    except ValueError:
        return False
    if isinstance(parsed, ipaddress.IPv6Address) and parsed.ipv4_mapped:
        parsed = parsed.ipv4_mapped
    return any(parsed in network for network in networks)


def client_ip(
    peer: str,
    headers: Mapping[str, str] | object,
    trusted_proxies: list[ipaddress.IPv4Network | ipaddress.IPv6Network],
) -> str:
    """Resolve the client address, trusting proxy headers only from trusted peers.

    Prefer ``X-Real-IP`` (set by nginx to ``$remote_addr``). Fall back to the
    leftmost ``X-Forwarded-For`` hop. Untrusted peers never override via headers.
    """
    if not ip_allowed(peer, trusted_proxies):
        return peer
    getter = getattr(headers, "get", None)
    if not callable(getter):
        return peer
    real = (getter("X-Real-IP") or "").strip()
    if not real:
        forwarded = (getter("X-Forwarded-For") or "").strip()
        if forwarded:
            real = forwarded.split(",")[0].strip()
    if not real:
        return peer
    try:
        parsed = ipaddress.ip_address(real)
    except ValueError:
        return peer
    if isinstance(parsed, ipaddress.IPv6Address) and parsed.ipv4_mapped:
        return str(parsed.ipv4_mapped)
    return str(parsed)


class RateLimitError(ValueError):
    pass


class RateLimiter:
    def __init__(self, per_minute: int) -> None:
        self.per_minute = per_minute
        self._lock = threading.Lock()
        self._hits: dict[str, list[float]] = {}

    def allow(self, key: str, now: float | None = None) -> bool:
        moment = time.monotonic() if now is None else now
        with self._lock:
            hits = [item for item in self._hits.get(key, []) if moment - item < 60]
            if len(hits) >= self.per_minute:
                self._hits[key] = hits
                return False
            hits.append(moment)
            self._hits[key] = hits
            return True


class Store:
    def __init__(self, path: str, ttl_seconds: int, max_bytes: int) -> None:
        self.path = path
        self.ttl_seconds = ttl_seconds
        self.max_bytes = max_bytes
        self.lock = threading.Lock()
        parent = os.path.dirname(path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with self._connect() as conn:
            conn.executescript(
                """
                PRAGMA journal_mode=WAL;
                PRAGMA synchronous=NORMAL;
                CREATE TABLE IF NOT EXISTS events (
                    event_id TEXT PRIMARY KEY,
                    role TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    received_at INTEGER NOT NULL,
                    source_ip TEXT,
                    remote_ip TEXT,
                    remote_port INTEGER,
                    proto TEXT,
                    payload TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS nonces (
                    nonce TEXT PRIMARY KEY,
                    seen_at INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_events_received ON events(received_at);
                """
            )
            self._migrate(conn)

    def _migrate(self, conn: sqlite3.Connection) -> None:
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(events)")}
        for name, declaration in (
            ("ts_ms", "INTEGER"),
            ("rat", "TEXT"),
            ("imei", "TEXT"),
            ("imsi", "TEXT"),
            ("test_type", "TEXT"),
        ):
            if name in columns:
                continue
            try:
                conn.execute("ALTER TABLE events ADD COLUMN %s %s" % (name, declaration))
            except sqlite3.OperationalError as exc:
                if "duplicate column" not in str(exc).lower():
                    raise
        conn.execute("CREATE INDEX IF NOT EXISTS idx_events_imei ON events(imei)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_events_imsi ON events(imsi)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_events_rat ON events(rat)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_events_test_type ON events(test_type)")

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=5)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA busy_timeout=5000")
        return conn

    def nonce_seen(self, nonce: str, now: int) -> bool:
        with self.lock, self._connect() as conn:
            row = conn.execute("SELECT nonce FROM nonces WHERE nonce = ?", (nonce,)).fetchone()
            if row:
                return True
            conn.execute("INSERT INTO nonces(nonce, seen_at) VALUES(?, ?)", (nonce, now))
            return False

    def insert_events(self, role: str, device_id: str, source_ip: str, events: list[dict]) -> tuple[int, int]:
        accepted = 0
        duplicate = 0
        now = int(time.time())
        with self.lock, self._connect() as conn:
            for event in events:
                remote_ip = event.get("remote_ip")
                remote_port = event.get("remote_port")
                proto = event.get("proto")
                imei = imsi = rat = test_type = None
                ts_ms = event.get("ts_ms")
                if role == "measurements":
                    imei, imsi, rat = correlation_ids(event)
                elif role == "tests":
                    test_type = event.get("test_type")
                elif role == "nr_status":
                    test_type = event.get("path_status") or event.get("test_type")
                    serving = event.get("serving_cells") if isinstance(event.get("serving_cells"), list) else []
                    if serving and isinstance(serving[0], dict):
                        rat = serving[0].get("rat")
                elif role == "ookla":
                    test_type = "OOKLA"
                elif role == "timeline":
                    test_type = event.get("kind")
                elif role == "rum":
                    test_type = event.get("kind")
                elif role == "os_results":
                    if event.get("stage") == "sdk_json":
                        job = str(event.get("fields", {}).get("job_type") or "sdk_json")
                        test_type = job[:64]
                    else:
                        test_type = event.get("stage")
                cursor = conn.execute(
                    """
                    INSERT OR IGNORE INTO events(
                        event_id, role, device_id, received_at, source_ip,
                        remote_ip, remote_port, proto, payload, ts_ms, rat,
                        imei, imsi, test_type
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        event["event_id"],
                        role,
                        device_id,
                        now,
                        source_ip,
                        remote_ip,
                        remote_port,
                        proto,
                        json.dumps(_with_quality(event), separators=(",", ":"), sort_keys=True),
                        ts_ms,
                        rat,
                        imei,
                        imsi,
                        test_type,
                    ),
                )
                if cursor.rowcount:
                    accepted += 1
                else:
                    duplicate += 1
        return accepted, duplicate

    def purge(self, now: int | None = None) -> int:
        moment = int(time.time()) if now is None else now
        cutoff = moment - self.ttl_seconds
        deleted = 0
        with self.lock:
            with self._connect() as conn:
                deleted = conn.execute("DELETE FROM events WHERE received_at < ?", (cutoff,)).rowcount
                conn.execute("DELETE FROM nonces WHERE seen_at < ?", (cutoff,))
                if self._size_unlocked() > self.max_bytes:
                    deleted += conn.execute(
                        """
                        DELETE FROM events WHERE event_id IN (
                            SELECT event_id FROM events ORDER BY received_at ASC LIMIT 1000
                        )
                        """
                    ).rowcount
            try:
                with self._connect() as conn:
                    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            except sqlite3.OperationalError:
                pass
        return deleted

    def _size_unlocked(self) -> int:
        total = 0
        for suffix in ("", "-wal", "-shm"):
            candidate = self.path + suffix
            if os.path.exists(candidate):
                total += os.path.getsize(candidate)
        return total

    def counts(self) -> dict[str, int]:
        with self.lock, self._connect() as conn:
            rows = conn.execute("SELECT role, COUNT(*) AS n FROM events GROUP BY role").fetchall()
        result = {
            "flows": 0,
            "debug": 0,
            "measurements": 0,
            "tests": 0,
            "os_results": 0,
            "nr_status": 0,
            "rum": 0,
        }
        for row in rows:
            result[row["role"]] = row["n"]
        result["bytes"] = self._size_unlocked()
        return result

    def recent(self, role: str, limit: int) -> list[dict]:
        with self.lock, self._connect() as conn:
            rows = conn.execute(
                """
                SELECT payload, source_ip, received_at FROM events
                WHERE role = ? ORDER BY received_at DESC LIMIT ?
                """,
                (role, limit),
            ).fetchall()
        return [
            {
                "received_at": row["received_at"],
                "source_ip": row["source_ip"],
                "event": json.loads(row["payload"]),
            }
            for row in rows
        ]


class Config:
    def __init__(self, env: Mapping[str, str]) -> None:
        self.role = env.get("OSM_ROLE", "flows")
        if self.role not in ("flows", "debug"):
            raise ValueError("OSM_ROLE must be flows or debug")
        self.bind = env.get("OSM_BIND", "0.0.0.0")
        self.port = int(env["OSM_PORT"])
        self.db_path = env.get("OSM_DB", os.path.join("var", "collector.sqlite"))
        self.ttl_seconds = int(env.get("OSM_TTL_SECONDS", str(72 * 3600)))
        self.max_db_bytes = int(env.get("OSM_MAX_DB_BYTES", str(512 * 1024 * 1024)))
        self.max_body = int(env.get("OSM_MAX_BODY", "262144" if self.role == "flows" else "65536"))
        # Per real client IP after trusted-proxy resolution; ~3s MEASURE + other posts.
        self.rate_per_minute = int(env.get("OSM_RATE_PER_MINUTE", "180"))
        self.skew_seconds = int(env.get("OSM_SKEW_SECONDS", "300"))
        self.key_id, self.key = load_key(env["OSM_KEY_FILE"])
        self.measure_key = load_measure_key(env["OSM_KEY_FILE"]) if self.role == "flows" else b""
        self.dash_user = env.get("OSM_DASH_USER", "")
        self.dash_pass_sha256 = env.get("OSM_DASH_PASS_SHA256", "").lower()
        self.allowlist_file = env.get("OSM_ALLOWLIST_FILE", "")
        self.allowlist = parse_allowlist(env.get("OSM_ALLOWLIST", "127.0.0.1/32,::1/128"))
        self.trusted_proxies = parse_allowlist(
            env.get("OSM_TRUSTED_PROXIES", "127.0.0.1/32,::1/128")
        )
        self.path = FLOW_PATH if self.role == "flows" else DEBUG_PATH
        if env.get("OSM_STORE", "sqlite") == "firestore":
            from opensignal_mirror.firestore_store import FirestoreStore

            self.store = FirestoreStore(self.ttl_seconds)
        else:
            self.store = Store(self.db_path, self.ttl_seconds, self.max_db_bytes)
        self.limiter = RateLimiter(self.rate_per_minute)
        self.started = int(time.time())
        self.magnon = None
        if env.get("MAGNON_PORTAL") == "1":
            from opensignal_mirror.magnon import build_portal

            self.magnon = build_portal(env)
        clickhouse = env.get("OSM_CLICKHOUSE_URL", "").strip()
        self.telemetry = ClickHouseTelemetry(clickhouse) if clickhouse else None


def display_ip(value: str) -> str:
    try:
        parsed = ipaddress.ip_address(value)
    except ValueError:
        return value
    if isinstance(parsed, ipaddress.IPv6Address) and parsed.ipv4_mapped:
        return str(parsed.ipv4_mapped)
    return str(parsed)


def live_snapshot(store: Store, limit: int = 400) -> dict:
    flows = store.recent("flows", limit)
    debug = store.recent("debug", 20)
    grouped: dict[tuple, dict] = {}
    for row in flows:
        event = row["event"]
        remote = display_ip(str(event.get("remote_ip", "")))
        key = (row["source_ip"], event.get("proto"), remote, event.get("remote_port"))
        current = grouped.get(key)
        if current is None or event.get("last_seen_ms", 0) >= current["last_seen_ms"]:
            grouped[key] = {
                "source_ip": row["source_ip"],
                "received_at": row["received_at"],
                "proto": event.get("proto"),
                "remote_ip": remote,
                "remote_port": event.get("remote_port"),
                "tx_bytes": event.get("tx_bytes", 0),
                "rx_bytes": event.get("rx_bytes", 0),
                "tx_packets": event.get("tx_packets", 0),
                "rx_packets": event.get("rx_packets", 0),
                "state": event.get("state"),
                "first_seen_ms": event.get("first_seen_ms", 0),
                "last_seen_ms": event.get("last_seen_ms", 0),
            }
    ordered = sorted(grouped.values(), key=lambda item: item["last_seen_ms"], reverse=True)
    measurements = latest_measurements(store)
    tests = _event_rows(store, "tests", 30)
    original = _event_rows(store, "os_results", 40)
    rum = store.recent("rum", 30)
    return {
        "now": int(time.time()),
        "flows": ordered[:150],
        "measurements": measurements,
        "tests": tests,
        "os_results": original,
        "debug": [
            {
                "received_at": row["received_at"],
                "source_ip": row["source_ip"],
                "level": row["event"].get("level"),
                "code": row["event"].get("code"),
                "message": row["event"].get("message"),
                "ts_ms": row["event"].get("ts_ms", 0),
            }
            for row in debug
        ],
        "rum": [
            {
                "received_at": row["received_at"],
                "device_id": row.get("device_id") or row["event"].get("device_id"),
                "event": row["event"],
            }
            for row in rum
        ],
        "totals": {
            "flows": len(ordered),
            "up": sum(item["tx_bytes"] for item in ordered),
            "down": sum(item["rx_bytes"] for item in ordered),
            "sources": len({item["source_ip"] for item in ordered}),
            "measurements": len(measurements),
            "tests": len(tests),
            "os_results": len(original),
        },
    }


def _with_quality(event: dict) -> dict:
    from opensignal_mirror.session_gate import current_quality

    stamped = dict(event)
    stamped.setdefault("quality", current_quality())
    return stamped


def _event_rows(store: Store, role: str, limit: int) -> list[dict]:
    if getattr(store, "backend", "") == "firestore":
        return store.recent(role, limit)
    with store.lock, store._connect() as conn:
        rows = conn.execute(
            """
            SELECT payload, source_ip, received_at, device_id, ts_ms, rat, imei, imsi, test_type
            FROM events WHERE role = ?
            ORDER BY COALESCE(ts_ms, received_at * 1000) DESC LIMIT ?
            """,
            (role, limit),
        ).fetchall()
    return [
        {
            "received_at": row["received_at"],
            "source_ip": row["source_ip"],
            "device_id": row["device_id"],
            "ts_ms": row["ts_ms"],
            "rat": row["rat"],
            "imei": row["imei"] or "",
            "imsi": row["imsi"] or "",
            "test_type": row["test_type"] or "",
            "event": json.loads(row["payload"]),
        }
        for row in rows
    ]


def _like_token(value: str) -> str:
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def search_measurements(store: Store, query: str, limit: int = 50) -> list[dict]:
    if getattr(store, "backend", "") == "firestore":
        return store.search(query, limit)
    if not re_fullmatch_query(query):
        raise ValueError("invalid query")
    token = _like_token(query)
    with store.lock, store._connect() as conn:
        rows = conn.execute(
            """
            SELECT payload, source_ip, received_at, device_id, ts_ms, rat, imei, imsi, test_type, role
            FROM events
            WHERE role IN ('measurements', 'tests', 'os_results')
              AND (
                device_id = ?
                OR ',' || COALESCE(imei, '') || ',' LIKE '%,' || ? || ',%' ESCAPE '\\'
                OR ',' || COALESCE(imsi, '') || ',' LIKE '%,' || ? || ',%' ESCAPE '\\'
              )
            ORDER BY COALESCE(ts_ms, received_at * 1000) DESC LIMIT ?
            """,
            (query, token, token, limit),
        ).fetchall()
    return [
        {
            "role": row["role"],
            "received_at": row["received_at"],
            "source_ip": row["source_ip"],
            "device_id": row["device_id"],
            "ts_ms": row["ts_ms"],
            "rat": row["rat"] or "",
            "imei": row["imei"] or "",
            "imsi": row["imsi"] or "",
            "test_type": row["test_type"] or "",
            "event": json.loads(row["payload"]),
        }
        for row in rows
    ]


def re_fullmatch_query(query: str) -> bool:
    if not isinstance(query, str) or not 6 <= len(query) <= 64:
        return False
    return all(ch in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_+-" for ch in query)


def latest_measurements(store: Store) -> list[dict]:
    rows = _event_rows(store, "measurements", 80)
    chosen: dict[str, dict] = {}
    for row in rows:
        if row["device_id"] not in chosen:
            chosen[row["device_id"]] = row
    return list(chosen.values())[:20]


def dashboard_html() -> bytes:
    path = os.path.join(os.path.dirname(__file__), "dashboard.html")
    with open(path, "rb") as handle:
        return handle.read()


def login_html() -> bytes:
    return """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>OpenSignal sign in</title>
<style>
body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #10141a; color: #e7eef7; font: 16px/1.4 Segoe UI, sans-serif; }
form { width: min(360px, calc(100% - 32px)); background: #1a212b; border: 1px solid #2c3644; border-radius: 12px; padding: 24px; }
h1 { margin: 0 0 8px; font-size: 24px; } p { margin: 0 0 16px; color: #93a0b3; }
label { display: block; margin: 12px 0 6px; } input { width: 100%; box-sizing: border-box; padding: 10px; border-radius: 8px; border: 1px solid #2c3644; background: #10141a; color: inherit; }
button { margin-top: 16px; width: 100%; padding: 10px; border: 0; border-radius: 8px; background: #85c1e9; color: #10202c; font-weight: 700; }
#error { min-height: 1.2em; color: #f5b7b1; }
</style></head><body>
<form id="login"><h1>OpenSignal</h1><p>Sign in to view radio measurements.</p>
<label>Username <input name="username" autocomplete="username" required></label>
<label>Password <input name="password" type="password" autocomplete="current-password" required></label>
<div id="error"></div><button type="submit">Sign in</button></form>
<script>
document.querySelector("#login").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = new FormData(event.target);
  const response = await fetch("/login", { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: data.get("username"), password: data.get("password") }) });
  if (response.ok) location.href = "/";
  else document.querySelector("#error").textContent = "Sign-in failed";
});
</script></body></html>""".encode("utf-8")


def make_handler(config: Config):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self) -> None:  # noqa: N802
            parsed_url = urlsplit(self.path)
            path = parsed_url.path
            if config.magnon is not None and path == "/":
                from opensignal_mirror.magnon import home_html

                self._send_bytes(200, home_html(), "text/html; charset=utf-8")
                return
            if config.magnon is not None and (
                path == "/magnon" or path.startswith("/api/magnon") or path.startswith("/v1/magnon")
            ):
                self._magnon("GET")
                return
            if path in ("/", "/dashboard"):
                if self._read_allowed(page=True):
                    self._send_bytes(200, dashboard_html(), "text/html; charset=utf-8")
                return
            if path == "/api/live":
                if self._read_allowed(page=False):
                    self._send(200, live_snapshot(config.store))
                return
            if path == "/api/measurements":
                if not self._read_allowed(page=False):
                    return
                try:
                    query = parse_qs(parsed_url.query).get("q", [""])[0]
                    self._send(200, {"events": search_measurements(config.store, query)})
                except ValueError as exc:
                    self._send(400, {"error": str(exc)})
                return
            if path == "/api/radio":
                if not self._read_allowed(page=False):
                    return
                if not config.telemetry or not config.telemetry.ready:
                    self._send(503, {"error": "telemetry_unavailable"})
                    return
                params = parse_qs(parsed_url.query)
                try:
                    minutes = int(params.get("minutes", ["60"])[0])
                    limit = int(params.get("limit", ["200"])[0])
                except ValueError:
                    self._send(400, {"error": "invalid range"})
                    return
                self._send(200, {"cells": config.telemetry.radio_by_arfcn(minutes, limit)})
                return
            if path not in ADMIN_PATHS or not is_loopback(self.client_address[0]):
                self._send(404, {"error": "not_found"})
                return
            if path == "/health":
                self._send(200, {"status": "ok", "role": config.role})
            elif path == "/metrics":
                counts = config.store.counts()
                counts.update({"role": config.role, "uptime_seconds": int(time.time()) - config.started})
                self._send(200, counts)
            else:
                role = "flows" if config.role == "flows" else "debug"
                self._send(200, {"events": config.store.recent(role, 50)})

        def do_POST(self) -> None:  # noqa: N802
            path = urlsplit(self.path).path
            if config.magnon is not None and (
                path == "/v1/auth/google" or path.startswith("/v1/magnon") or path.startswith("/api/magnon")
            ):
                self._magnon("POST")
                return
            if path == LOGIN_PATH:
                self._login()
                return
            if path == MEASURE_PATH:
                if config.role != "flows":
                    self._send(404, {"error": "not_found"})
                    return
                self._ingest(MEASURE_PATH, self._accept_measurements)
                return
            if path == NR_STATUS_PATH:
                if config.role != "flows":
                    self._send(404, {"error": "not_found"})
                    return
                self._ingest(NR_STATUS_PATH, self._accept_nr_status)
                return
            if path == OOKLA_PATH:
                if config.role != "flows":
                    self._send(404, {"error": "not_found"})
                    return
                self._ingest(OOKLA_PATH, self._accept_ookla)
                return
            if path == TIMELINE_PATH:
                if config.role != "flows":
                    self._send(404, {"error": "not_found"})
                    return
                self._ingest(TIMELINE_PATH, self._accept_timeline)
                return
            if path == RUM_PATH:
                if config.role != "flows":
                    self._send(404, {"error": "not_found"})
                    return
                self._ingest(RUM_PATH, self._accept_rum)
                return
            if path != config.path:
                self._send(404, {"error": "not_found"})
                return
            self._ingest(path, self._accept_role)

        def _magnon(self, method: str) -> None:
            from opensignal_mirror.magnon import handle, portal_html, token_from_headers

            parsed = urlsplit(self.path)
            if method == "GET" and parsed.path == "/magnon":
                self._send_bytes(200, portal_html(config.magnon.client_id), "text/html; charset=utf-8")
                return
            body: dict = {}
            if method == "POST":
                try:
                    length = int(self.headers.get("Content-Length", ""))
                except ValueError:
                    self._send(411, {"error": "length_required"})
                    return
                if length < 2 or length > 8192:
                    self._send(413, {"error": "body_too_large"})
                    return
                raw = self.rfile.read(length)
                try:
                    body = json.loads(raw.decode("utf-8"))
                except (json.JSONDecodeError, UnicodeError):
                    self._send(400, {"error": "invalid_json"})
                    return
                if not isinstance(body, dict):
                    self._send(400, {"error": "invalid_json"})
                    return
            else:
                body = {key: values[0] for key, values in parse_qs(parsed.query).items() if values}
            status, payload, cookie = handle(
                config.magnon,
                method,
                parsed.path,
                token_from_headers(self.headers),
                body,
                self._session_user() is not None,
            )
            if cookie:
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header(
                    "Set-Cookie",
                    "magnon_session=%s; HttpOnly; SameSite=Lax; Path=/; Max-Age=%s" % (cookie, 30 * 24 * 3600),
                )
                raw_body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
                self.send_header("Content-Length", str(len(raw_body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(raw_body)
                return
            self._send(status, payload)

        def _magnon_tests(self, parsed: dict) -> None:
            if config.magnon is None:
                return
            try:
                config.magnon.note_tests(
                    parsed.get("device_id", ""),
                    parsed.get("tests") or [],
                    parsed.get("snapshots") or [],
                )
            except Exception as exc:
                sys.stderr.write("magnon points failed: %s\n" % exc)

        def _magnon_radio(self, parsed: dict) -> None:
            if config.magnon is None:
                return
            try:
                config.magnon.award(parsed.get("device_id", ""), str(parsed.get("event_id") or ""), "radio", parsed)
            except Exception as exc:
                sys.stderr.write("magnon radio points failed: %s\n" % exc)

        def _client_ip(self) -> str:
            return client_ip(self.client_address[0], self.headers, config.trusted_proxies)

        def _accept_role(self, body: dict) -> tuple[int, int]:
            parsed = validate_flows(body) if config.role == "flows" else validate_debug(body)
            events = parsed["flows"] if config.role == "flows" else parsed["events"]
            return config.store.insert_events(config.role, parsed["device_id"], self._client_ip(), events)

        def _accept_rum(self, body: dict) -> tuple[int, int]:
            parsed = validate_rum(body)
            events = parsed["events"]
            for event in events:
                event["device_id"] = parsed["device_id"]
            return config.store.insert_events("rum", parsed["device_id"], self._client_ip(), events)

        def _accept_nr_status(self, body: dict) -> tuple[int, int]:
            parsed = validate_nr_status(body)
            source = self._client_ip()
            accepted, duplicate = config.store.insert_events(
                "nr_status", parsed["device_id"], source, [parsed]
            )
            self._magnon_radio(parsed)
            if config.telemetry:
                try:
                    config.telemetry.ensure_schema()
                    config.telemetry.store_mysignal(parsed["device_id"], source, parsed)
                except Exception as exc:
                    sys.stderr.write("clickhouse mysignal insert failed: %s\n" % exc)
            return accepted, duplicate

        def _accept_ookla(self, body: dict) -> tuple[int, int]:
            parsed = validate_ookla_result(body)
            source = self._client_ip()
            accepted, duplicate = config.store.insert_events(
                "ookla", parsed["device_id"], source, [parsed]
            )
            if config.telemetry:
                try:
                    config.telemetry.ensure_schema()
                    config.telemetry.store_ookla(parsed["device_id"], source, parsed)
                except Exception as exc:
                    sys.stderr.write("clickhouse ookla insert failed: %s\n" % exc)
            return accepted, duplicate

        def _accept_timeline(self, body: dict) -> tuple[int, int]:
            parsed = validate_timeline(body)
            source = self._client_ip()
            accepted, duplicate = config.store.insert_events(
                "timeline", parsed["device_id"], source, parsed["events"]
            )
            if config.telemetry:
                try:
                    config.telemetry.ensure_schema()
                    config.telemetry.store_timeline(
                        parsed["device_id"],
                        source,
                        parsed["app"],
                        parsed["events"],
                    )
                except Exception as exc:
                    sys.stderr.write("clickhouse timeline insert failed: %s\n" % exc)
            return accepted, duplicate

        def _accept_measurements(self, body: dict) -> tuple[int, int]:
            parsed = validate_measurement_envelope(body, config.measure_key)
            source = self._client_ip()
            accepted, duplicate = config.store.insert_events(
                "measurements", parsed["device_id"], source, parsed["snapshots"]
            )
            test_accepted, test_duplicate = config.store.insert_events(
                "tests", parsed["device_id"], source, parsed["tests"]
            )
            result_accepted, result_duplicate = config.store.insert_events(
                "os_results", parsed["device_id"], source, parsed["os_results"]
            )
            self._magnon_tests(parsed)
            if config.telemetry:
                try:
                    config.telemetry.ensure_schema()
                    config.telemetry.store(parsed["device_id"], source, parsed["os_results"])
                    config.telemetry.store_measurements(
                        parsed["device_id"], source, parsed["snapshots"]
                    )
                except Exception as exc:
                    sys.stderr.write("clickhouse insert failed: %s\n" % exc)
            return accepted + test_accepted + result_accepted, duplicate + test_duplicate + result_duplicate

        def _ingest(self, path: str, accept) -> None:
            try:
                length = int(self.headers.get("Content-Length", ""))
            except ValueError:
                self._send(411, {"error": "length_required"})
                return
            if length < 2 or length > config.max_body:
                self._send(413, {"error": "body_too_large"})
                return
            raw = self.rfile.read(length)
            if len(raw) != length:
                self._send(400, {"error": "short_body"})
                return
            try:
                self._authenticate(path, raw)
                from opensignal_mirror.session_gate import bind_request

                bind_request(self.headers)
                parsed_body = json.loads(raw.decode("utf-8"))
                accepted, duplicate = accept(parsed_body)
            except PermissionError as exc:
                self._send(401, {"error": str(exc)})
                return
            except RateLimitError as exc:
                self._send(429, {"error": str(exc)})
                return
            except ValueError as exc:
                sys.stderr.write("rejected %s\n" % exc)
                self._send(400, {"error": str(exc)})
                return
            except json.JSONDecodeError:
                self._send(400, {"error": "invalid_json"})
                return
            self._send(200, {"status": "ok", "accepted": accepted, "duplicate": duplicate})

        def _networks(self) -> list:
            if config.allowlist_file and os.path.exists(config.allowlist_file):
                try:
                    with open(config.allowlist_file, "r", encoding="utf-8") as handle:
                        parsed = parse_allowlist(handle.read().replace("\n", ","))
                    if parsed:
                        return parsed
                except OSError:
                    pass
            return config.allowlist

        def _read_allowed(self, page: bool) -> bool:
            source = self._client_ip()
            if not ip_allowed(source, self._networks()):
                self._send(403, {"error": "ip_forbidden", "client": source})
                return False
            if self._session_user() is None:
                if page:
                    self._send_bytes(200, login_html(), "text/html; charset=utf-8")
                else:
                    self._send(401, {"error": "login_required"})
                return False
            return True

        def _session_user(self) -> str | None:
            token = ""
            for part in self.headers.get("Cookie", "").split(";"):
                part = part.strip()
                if part.startswith("osm_session="):
                    token = part.split("=", 1)[1]
            pieces = token.split(".")
            if len(pieces) != 4:
                return None
            user, stamp, nonce, signature = pieces
            if user != config.dash_user or not stamp.isdigit() or not _ident_ok(nonce):
                return None
            if abs(int(time.time()) - int(stamp)) > SESSION_TTL:
                return None
            message = "%s.%s.%s" % (user, stamp, nonce)
            expected = hmac.new(config.key, message.encode("ascii"), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expected, signature):
                return None
            return user

        def _login(self) -> None:
            source = self._client_ip()
            if not ip_allowed(source, self._networks()):
                self._send(403, {"error": "ip_forbidden", "client": source})
                return
            try:
                length = int(self.headers.get("Content-Length", ""))
            except ValueError:
                self._send(411, {"error": "length_required"})
                return
            if length < 2 or length > 2048:
                self._send(413, {"error": "body_too_large"})
                return
            raw = self.rfile.read(length)
            try:
                if not config.limiter.allow("login:" + source):
                    raise RateLimitError("rate_limited")
                body = json.loads(raw.decode("utf-8"))
                username = body.get("username", "")
                password = body.get("password", "")
                digest = hashlib.sha256(password.encode("utf-8")).hexdigest() if isinstance(password, str) else ""
                if not isinstance(username, str) or not _same(username, config.dash_user) or not _same(digest, config.dash_pass_sha256):
                    raise PermissionError("login_failed")
            except PermissionError as exc:
                self._send(401, {"error": str(exc)})
                return
            except RateLimitError as exc:
                self._send(429, {"error": str(exc)})
                return
            except (json.JSONDecodeError, UnicodeError, ValueError):
                self._send(400, {"error": "invalid_json"})
                return
            stamp = str(int(time.time()))
            nonce = secrets.token_hex(8)
            message = "%s.%s.%s" % (config.dash_user, stamp, nonce)
            signature = hmac.new(config.key, message.encode("ascii"), hashlib.sha256).hexdigest()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header(
                "Set-Cookie",
                "osm_session=%s; HttpOnly; SameSite=Strict; Path=/; Max-Age=%s" % (message + "." + signature, SESSION_TTL),
            )
            raw_body = b'{"status":"ok"}'
            self.send_header("Content-Length", str(len(raw_body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(raw_body)

        def _authenticate(self, path: str, raw: bytes) -> None:
            key_id = self.headers.get("X-Osm-Key-Id", "")
            timestamp = self.headers.get("X-Osm-Timestamp", "")
            nonce = self.headers.get("X-Osm-Nonce", "")
            signature = self.headers.get("X-Osm-Signature", "")
            if key_id != config.key_id:
                raise PermissionError("unknown_key")
            if not timestamp.isdigit():
                raise PermissionError("invalid_timestamp")
            if abs(int(time.time()) - int(timestamp)) > config.skew_seconds:
                raise PermissionError("timestamp_skew")
            try:
                _ident(nonce, NONCE_MIN, NONCE_MAX)
            except ValueError as exc:
                raise PermissionError("invalid_nonce") from exc
            expected = sign(config.key, "POST", path, timestamp, nonce, raw)
            if not hmac.compare_digest(expected, signature.lower()):
                raise PermissionError("bad_signature")
            source = self._client_ip()
            if not config.limiter.allow(source + ":" + key_id):
                raise RateLimitError("rate_limited")
            if config.store.nonce_seen(nonce, int(time.time())):
                raise PermissionError("replayed_nonce")

        def _send(self, status: int, payload: dict) -> None:
            self._send_bytes(status, json.dumps(payload, separators=(",", ":")).encode("utf-8"), "application/json")

        def _send_bytes(self, status: int, raw: bytes, content_type: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, fmt: str, *args) -> None:
            sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    return Handler


def build_server(env: Mapping[str, str] | None = None) -> tuple[Config, ThreadingHTTPServer]:
    config = Config(os.environ if env is None else env)
    server = ThreadingHTTPServer((config.bind, config.port), make_handler(config))
    return config, server


def _purge_loop(config: Config, stop: threading.Event) -> None:
    while not stop.wait(60):
        try:
            config.store.purge()
        except sqlite3.Error as exc:
            sys.stderr.write("purge failed: %s\n" % exc)


def _clickhouse_loop(config: Config, stop: threading.Event) -> None:
    telemetry = config.telemetry
    if telemetry is None:
        return
    while not stop.is_set():
        try:
            if not telemetry.ready:
                telemetry.ensure_schema()
                count = telemetry.backfill(config.store)
                telemetry.optimize()
                sys.stderr.write("clickhouse backfill %s\n" % count)
            else:
                telemetry.sync_recent(config.store, 300)
        except Exception as exc:
            sys.stderr.write("clickhouse sync failed: %s\n" % exc)
        stop.wait(60 if telemetry.ready else 15)


def main() -> None:
    config, server = build_server()
    stop = threading.Event()
    thread = threading.Thread(target=_purge_loop, args=(config, stop), daemon=True)
    thread.start()
    if config.telemetry:
        threading.Thread(target=_clickhouse_loop, args=(config, stop), daemon=True).start()
    print("opensignal-mirror %s listening on %s:%s" % (config.role, config.bind, server.server_address[1]), flush=True)
    try:
        server.serve_forever()
    finally:
        stop.set()
        server.server_close()
