import json
import re
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))

from opensignal_mirror.schema import MAX_SDK_PAYLOAD, _sdk_payload  # noqa: E402
from opensignal_mirror.telemetry import parse_document, repair_json  # noqa: E402

REPORTER = ROOT / "scripts" / "opensignal_mirror" / "java" / "OsmReporter.java"

PRIORITY = [
    "id",
    "task_id",
    "task_name",
    "job_type",
    "data_endpoint",
    "time_of_result",
    "device_sdk_int",
    "device_sdk_int_full",
    "app_version",
    "ANDROID_VRS",
    "SERVICE_STATE",
    "TELEPHONY_DATA",
    "CELLS_INFO",
    "LOCATION_DATA",
    "DISPLAY_INFO_DETECTED",
    "SYSTEM_STATUS",
    "WIFI_STATUS",
]
DROP_ORDER = [
    "WIFI_STATUS",
    "SYSTEM_STATUS",
    "DISPLAY_INFO_DETECTED",
    "LOCATION_DATA",
    "CELLS_INFO",
    "TELEPHONY_DATA",
]
ESSENTIAL = {"id", "task_id", "task_name", "job_type", "data_endpoint", "time_of_result"}


def java_int(name: str) -> int:
    text = REPORTER.read_text(encoding="utf-8")
    match = re.search(r"int\s+%s\s*=\s*(\d+)\s*;" % re.escape(name), text)
    assert match, "constant %s not found" % name
    return int(match.group(1))


def compact(document: dict, limit: int) -> tuple[dict, str]:
    """Python mirror of OsmReporter.compactSdkJson used to pin the trimming rules."""
    ordered = {}
    for key in PRIORITY:
        if key in document:
            ordered[key] = document[key]
    for key in sorted(document):
        if key not in ordered:
            ordered[key] = document[key]
    size = len(json.dumps(ordered, separators=(",", ":")))
    if size <= limit:
        return ordered, ""
    dropped: list[str] = []
    cells = ordered.get("CELLS_INFO")
    if isinstance(cells, str):
        try:
            cells = json.loads(cells)
        except json.JSONDecodeError:
            cells = None
    if isinstance(cells, list) and cells:
        for cap in (8, 4, 2, 0):
            if len(cells) <= cap:
                continue
            cells = cells[:cap]
            ordered["CELLS_INFO"] = cells
            dropped.append("CELLS_INFO:%d" % len(cells))
            if len(json.dumps(ordered, separators=(",", ":"))) <= limit:
                return ordered, ",".join(dropped)
    for key in DROP_ORDER:
        if len(json.dumps(ordered, separators=(",", ":"))) <= limit:
            break
        if key not in ordered or key in ESSENTIAL:
            continue
        ordered.pop(key)
        dropped.append(key)
    for key in [k for k in ordered if k not in ESSENTIAL and k not in PRIORITY]:
        if len(json.dumps(ordered, separators=(",", ":"))) <= limit:
            break
        ordered.pop(key)
        dropped.append(key)
    return ordered, ",".join(dropped)


def core_snapshot(cells: int = 40) -> dict:
    return {
        "id": "res1234",
        "task_id": "task1234",
        "task_name": "core",
        "job_type": "CORE",
        "data_endpoint": "core",
        "time_of_result": 1750000000000,
        "device_sdk_int": 34,
        "SERVICE_STATE": {"network_operator": "51010"},
        "TELEPHONY_DATA": {"sim_operator": "51010", "sim_state": "READY"},
        "CELLS_INFO": [
            {
                "is_serving": index == 0,
                "rat": "lte",
                "rsrp": -90 - index,
                "rsrq": -10,
                "ci": 115244292 + index,
                "pci": 100 + index,
                "earfcn": 1850,
                "padding": "x" * 900,
            }
            for index in range(cells)
        ],
        "LOCATION_DATA": {"latitude": -6.2, "longitude": 106.8, "accuracy": 12},
        "SYSTEM_STATUS": {"battery": 88, "screen_on": True},
        "WIFI_STATUS": {"ssid": "lab", "rssi": -55},
    }


def test_apk_and_collector_limits_stay_in_sync():
    assert java_int("SDK_PAYLOAD_LIMIT") == MAX_SDK_PAYLOAD == 32768


def test_post_body_budget_fits_collector_body_limit():
    batch = java_int("SDK_BATCH")
    budget = java_int("SDK_BODY_BUDGET")
    assert batch * MAX_SDK_PAYLOAD > 262144, "batch of max payloads must not be sent as-is"
    assert budget < 262144


def test_sdk_payload_accepts_legit_size_and_rejects_oversize():
    assert _sdk_payload("y" * 20000) == "y" * 20000
    with pytest.raises(ValueError):
        _sdk_payload("y" * (MAX_SDK_PAYLOAD + 1))


def test_large_core_snapshot_fits_without_losing_telephony_or_wifi():
    snapshot = core_snapshot()
    raw = json.dumps(snapshot, separators=(",", ":"))
    assert len(raw) > MAX_SDK_PAYLOAD, "fixture must actually need trimming"

    trimmed, dropped = compact(snapshot, MAX_SDK_PAYLOAD)
    encoded = json.dumps(trimmed, separators=(",", ":"))
    assert len(encoded) <= MAX_SDK_PAYLOAD
    assert json.loads(encoded) == trimmed
    assert len(trimmed["CELLS_INFO"]) <= 8
    assert trimmed["CELLS_INFO"][0]["is_serving"] is True
    assert "TELEPHONY_DATA" in trimmed
    assert "WIFI_STATUS" in trimmed
    assert dropped.startswith("CELLS_INFO:")


def test_trimming_drops_low_priority_keys_before_core_context():
    snapshot = core_snapshot(cells=6)
    trimmed, dropped = compact(snapshot, 400)
    encoded = json.dumps(trimmed, separators=(",", ":"))
    assert len(encoded) <= 400
    assert json.loads(encoded) == trimmed
    assert ESSENTIAL <= set(trimmed)
    assert "WIFI_STATUS" not in trimmed
    assert "WIFI_STATUS" in dropped
    assert "job_type" in trimmed and "task_id" in trimmed


def test_repair_json_handles_mid_token_cut():
    raw = json.dumps(core_snapshot(cells=3), separators=(",", ":"))
    cut = raw[: len(raw) // 2]
    repaired = json.loads(repair_json(cut))
    assert isinstance(repaired, dict)
    assert repaired["job_type"] == "CORE"


def test_parse_document_flags_repaired_legacy_payload():
    raw = json.dumps(core_snapshot(cells=3), separators=(",", ":"))
    document, truncated, repaired = parse_document(
        {"stage": "sdk_json", "fields": {"payload_truncated": "1"}, "payload": raw[:120]}
    )
    assert truncated is True
    assert repaired is True
    assert document["job_type"] == "CORE"

    document, truncated, repaired = parse_document({"stage": "sdk_json", "fields": {}, "payload": raw})
    assert truncated is False
    assert repaired is False
    assert document["TELEPHONY_DATA"]["sim_operator"] == "51010"


def test_parse_document_without_payload_is_not_marked_repaired():
    document, truncated, repaired = parse_document({"stage": "sdk_json", "fields": {"job_type": "CORE"}})
    assert document["job_type"] == "CORE"
    assert truncated is True
    assert repaired is False
