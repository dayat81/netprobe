"""Parse OpenSignal SDK JSON and store scalar paths in ClickHouse."""

from __future__ import annotations

import json
import re
import urllib.parse
import urllib.request
from typing import Iterator

MAX_VALUES = 2000
MAX_STRING = 4000
MAX_PAYLOAD = 200000

# One row per cell, from the SDK snapshot (CORE) or our own 3 s mirror (MEASURE).
# JSONExtractInt returns 0 for a missing key, hence the nullIf(...,0) guards:
# without them a neighbour's placeholder SINR of 0 would drag every average down.
RADIO_CELLS_VIEW = """
CREATE OR REPLACE VIEW opensignal.radio_cells AS
SELECT
    e.received_at,
    e.device_id,
    e.event_id,
    e.job_type,
    if(e.job_type = 'CORE', 'sdk', 'mirror') AS source,
    upper(if(JSONExtractString(c, 'rat') != '', JSONExtractString(c, 'rat'), JSONExtractString(c, 'type'))) AS rat,
    if(JSONType(c, 'channel') != 'Null',
       nullIf(JSONExtractInt(c, 'channel'), 0),
       coalesce(nullIf(JSONExtractInt(c, 'earfcn'), 0), nullIf(JSONExtractInt(c, 'erfcn'), 0),
                nullIf(JSONExtractInt(c, 'nrarfcn'), 0), nullIf(JSONExtractInt(c, 'nr_arfcn'), 0),
                nullIf(JSONExtractInt(c, 'uarfcn'), 0), nullIf(JSONExtractInt(c, 'arfcn'), 0))) AS arfcn,
    JSONExtractString(c, 'ci') AS ci,
    nullIf(JSONExtractInt(c, 'pci'), 0) AS pci,
    nullIf(JSONExtractString(c, 'mcc'), '') AS mcc,
    nullIf(JSONExtractString(c, 'mnc'), '') AS mnc,
    if(JSONExtractBool(c, 'registered') OR JSONExtractString(c, 'role') = 'serving', 1, 0) AS serving,
    coalesce(nullIf(JSONExtractInt(c, 'rsrp'), 0), nullIf(JSONExtractInt(c, 'ss_rsrp'), 0)) AS rsrp,
    coalesce(nullIf(JSONExtractInt(c, 'rsrq'), 0), nullIf(JSONExtractInt(c, 'ss_rsrq'), 0)) AS rsrq,
    coalesce(nullIf(JSONExtractInt(c, 'sinr'), 0), nullIf(JSONExtractInt(c, 'rssnr'), 0),
             nullIf(JSONExtractInt(c, 'ss_sinr'), 0)) AS sinr,
    if(JSONType(c, 'sub_id') = 'Null', NULL, JSONExtractInt(c, 'sub_id')) AS sub_id,
    if(JSONType(c, 'sim_slot') = 'Null', NULL, JSONExtractInt(c, 'sim_slot')) AS sim_slot,
    nullIf(JSONExtractString(c, 'carrier'), '') AS carrier
FROM opensignal.telemetry_events AS e
ARRAY JOIN JSONExtractArrayRaw(e.payload, 'CELLS_INFO') AS c
WHERE e.job_type IN ('CORE', 'MEASURE')
"""

SCHEMA = (
    "CREATE DATABASE IF NOT EXISTS opensignal",
    """
    CREATE TABLE IF NOT EXISTS opensignal.telemetry_events (
        event_id String,
        received_at DateTime,
        device_id String,
        source_ip String,
        ts_ms UInt64,
        job_type LowCardinality(String),
        task_name String,
        task_id String,
        data_endpoint String,
        time_of_result UInt64,
        truncated UInt8,
        payload String
    ) ENGINE = ReplacingMergeTree
    ORDER BY event_id
    """,
    """
    CREATE TABLE IF NOT EXISTS opensignal.telemetry_values (
        event_id String,
        received_at DateTime,
        job_type LowCardinality(String),
        path String,
        value_type LowCardinality(String),
        value_str String,
        value_num Nullable(Float64)
    ) ENGINE = ReplacingMergeTree
    ORDER BY (event_id, path)
    """,
    RADIO_CELLS_VIEW,
)


def repair_json(text: str) -> str:
    """Close a JSON document that was cut off at the end."""
    out: list[str] = []
    stack: list[str] = []
    in_string = False
    escape = False
    for ch in text:
        if in_string:
            out.append(ch)
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch in "{[":
            stack.append(ch)
        elif ch in "}]" and stack:
            stack.pop()
        out.append(ch)
    if in_string:
        out.append('\\"' if escape else '"')
    repaired = "".join(out)
    previous = None
    while repaired != previous:
        previous = repaired
        repaired = re.sub(r",\s*$", "", repaired)
        repaired = re.sub(r":\s*$", ":null", repaired)
        repaired = re.sub(r',\s*"(?:\\.|[^"\\])*"\s*$', "", repaired)
    while stack:
        repaired += "}" if stack.pop() == "{" else "]"
    return repaired


def unwrap(value: object) -> object:
    if isinstance(value, str):
        stripped = value.strip()
        if (stripped.startswith("{") and stripped.endswith("}")) or (stripped.startswith("[") and stripped.endswith("]")):
            try:
                return unwrap(json.loads(stripped))
            except json.JSONDecodeError:
                return value
        return value
    if isinstance(value, dict):
        return {str(key): unwrap(item) for key, item in value.items()}
    if isinstance(value, list):
        return [unwrap(item) for item in value]
    return value


def parse_document(event: dict) -> tuple[dict, bool, bool]:
    """Return (document, truncated, repaired).

    ``repaired`` is True only when the stored payload was not valid JSON and
    ``repair_json`` had to close it (legacy APKs that sliced at 8192 bytes).
    """
    payload = event.get("payload") if isinstance(event.get("payload"), str) else ""
    truncated = str(event.get("fields", {}).get("payload_truncated", "")) == "1"
    if payload:
        try:
            parsed = json.loads(payload)
            if isinstance(parsed, dict):
                return unwrap(parsed), truncated, False
        except json.JSONDecodeError:
            try:
                parsed = json.loads(repair_json(payload))
                if isinstance(parsed, dict):
                    return unwrap(parsed), True, True
            except json.JSONDecodeError:
                pass
    fields = event.get("fields") if isinstance(event.get("fields"), dict) else {}
    return unwrap(fields), True, False


def _flatten(value: object, prefix: str) -> Iterator[tuple[str, object]]:
    if isinstance(value, dict):
        for key, item in value.items():
            path = "%s.%s" % (prefix, key) if prefix else str(key)
            if len(path) <= 240:
                yield from _flatten(item, path)
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from _flatten(item, "%s.%s" % (prefix, index))
    elif prefix:
        yield prefix, value


def scalar_rows(document: dict) -> list[dict]:
    rows = []
    for path, value in _flatten(document, ""):
        if value is None or isinstance(value, (dict, list)):
            continue
        if isinstance(value, bool):
            kind = "bool"
            number = 1.0 if value else 0.0
            text = "true" if value else "false"
        elif isinstance(value, (int, float)) and not isinstance(value, bool):
            kind = "number"
            number = float(value)
            text = str(value)
        else:
            kind = "string"
            number = None
            text = str(value).replace("\u0000", "")
            if len(text) > MAX_STRING:
                text = text[:MAX_STRING]
        rows.append({"path": path, "value_type": kind, "value_str": text, "value_num": number})
        if len(rows) >= MAX_VALUES:
            break
    return rows


def _text(value: object, limit: int) -> str:
    if value is None:
        return ""
    return str(value)[:limit]


def _u64(value: object) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        try:
            value = int(str(value))
        except (TypeError, ValueError):
            return 0
    number = int(value)
    if number < 0 or number > 2**64 - 1:
        return 0
    return number


def event_row(event: dict, document: dict, truncated: bool, received_at: int, device_id: str, source_ip: str) -> dict:
    fields = event.get("fields") if isinstance(event.get("fields"), dict) else {}
    payload = json.dumps(document, separators=(",", ":"), ensure_ascii=False)
    if len(payload) > MAX_PAYLOAD:
        payload = payload[:MAX_PAYLOAD]
        truncated = True
    return {
        "event_id": _text(event.get("event_id"), 80),
        "received_at": max(int(received_at), 0),
        "device_id": _text(device_id, 64),
        "source_ip": _text(source_ip, 64),
        "ts_ms": _u64(event.get("ts_ms")),
        "job_type": _text(document.get("job_type") or fields.get("job_type") or "sdk_json", 64),
        "task_name": _text(document.get("task_name") or fields.get("task_name"), 120),
        "task_id": _text(document.get("task_id") or fields.get("task_id"), 40),
        "data_endpoint": _text(document.get("data_endpoint") or fields.get("data_endpoint"), 160),
        "time_of_result": _u64(document.get("time_of_result") or fields.get("time_of_result")),
        "truncated": 1 if truncated else 0,
        "payload": payload,
    }


def _active_subscription(subscriptions: list) -> dict:
    """Prefer the subscription that actually carries data."""
    fallback: dict = {}
    for item in subscriptions:
        if not isinstance(item, dict):
            continue
        if not fallback:
            fallback = item
        if str(item.get("data_state") or "").upper() == "CONNECTED":
            return item
    return fallback


def _cell_channel(cell: dict) -> int | None:
    """Single ARFCN field regardless of RAT, so queries need not know the key name."""
    for key in ("earfcn", "nrarfcn", "uarfcn", "arfcn"):
        value = cell.get(key)
        if isinstance(value, int) and not isinstance(value, bool) and value > 0:
            return value
    return None


def _measure_cells(cells: list) -> list[dict]:
    """Mirror cells with an explicit serving flag plus the normalised channel."""
    out = []
    for cell in cells[:32]:
        if not isinstance(cell, dict):
            continue
        item = dict(cell)
        item["serving"] = item.get("role") == "serving"
        if not item.get("channel"):
            channel = _cell_channel(item)
            if channel is not None:
                item["channel"] = channel
        out.append(item)
    return out


def _public_subscription(item: dict) -> dict:
    """Drop the secret identifiers; keep the radio/carrier context."""
    return {
        "slot": item.get("slot"),
        "sub_id": item.get("sub_id"),
        "carrier": item.get("carrier"),
        "mcc": item.get("mcc"),
        "mnc": item.get("mnc"),
        "rat": item.get("rat"),
        "network_operator": item.get("network_operator"),
        "sim_operator": item.get("sim_operator"),
        "data_state": item.get("data_state"),
        "service_state": item.get("service_state"),
        "nr_state": item.get("nr_state"),
        "network_type": item.get("network_type"),
        "ca": item.get("ca"),
        "nr_frequency_range": item.get("nr_frequency_range"),
        "endc_available": item.get("endc_available"),
        "channel": item.get("channel"),
        "override_network_type": item.get("override_network_type"),
        "carriers": item.get("carriers"),
        "serving_signal": item.get("serving_signal"),
        "apn": item.get("apn"),
        "apn_name": item.get("apn_name"),
        "apn_types": item.get("apn_types"),
        "apn_protocol": item.get("apn_protocol"),
    }


def _public_device(item: object) -> dict:
    """Keep the hardware label; never persist the android_id."""
    device = item if isinstance(item, dict) else {}
    return {
        "manufacturer": _text(device.get("manufacturer"), 64) or None,
        "model": _text(device.get("model"), 64) or None,
        "sdk_int": device.get("sdk_int"),
        "release": _text(device.get("release"), 32) or None,
    }


class ClickHouseTelemetry:
    def __init__(self, url: str) -> None:
        self.url = url.rstrip("/")
        self.ready = False

    def _sql(self, query: str, body: bytes | None = None) -> str:
        request = urllib.request.Request(
            self.url + "/?query=" + urllib.parse.quote(query),
            data=body,
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=8) as response:
            return response.read().decode("utf-8")

    def ensure_schema(self) -> None:
        if self.ready:
            return
        for statement in SCHEMA:
            self._sql(statement)
        self.ready = True

    def _collect(self, device_id: str, source_ip: str, events: list[dict], received_at: int) -> tuple[list[dict], list[dict]]:
        event_rows = []
        value_rows = []
        for event in events:
            stage = event.get("stage")
            if stage not in ("sdk_json", "drive_mode"):
                continue
            if stage == "drive_mode":
                fields = event.get("fields") if isinstance(event.get("fields"), dict) else {}
                document = {"job_type": "DRIVE_MODE", "task_name": str(fields.get("stage") or "drive")}
                document.update(fields)
                truncated = False
            else:
                document, truncated, repaired = parse_document(event)
                if repaired:
                    document = dict(document)
                    document["payload_repaired"] = True
            header = event_row(event, document, truncated, received_at, device_id, source_ip)
            event_rows.append(header)
            for item in scalar_rows(document):
                value_rows.append(
                    {
                        "event_id": header["event_id"],
                        "received_at": header["received_at"],
                        "job_type": header["job_type"],
                        **item,
                    }
                )
        return event_rows, value_rows

    def store(self, device_id: str, source_ip: str, events: list[dict], received_at: int | None = None) -> int:
        import time

        moment = int(time.time()) if received_at is None else int(received_at)
        event_rows, value_rows = self._collect(device_id, source_ip, events, moment)
        return self._write(event_rows, value_rows)

    def store_ookla(
        self,
        device_id: str,
        source_ip: str,
        event: dict,
        received_at: int | None = None,
    ) -> int:
        import time

        moment = int(time.time()) if received_at is None else int(received_at)
        test_kind = event.get("test_kind") or "speed"
        serving = event.get("serving_cells") if isinstance(event.get("serving_cells"), list) else []
        neighbors = event.get("neighbor_cells") if isinstance(event.get("neighbor_cells"), list) else []
        cells = list(serving) + list(neighbors)
        radio_meta = {
            "data_network_type": event.get("data_network_type"),
            "plmn": event.get("plmn"),
            "mcc": event.get("mcc"),
            "mnc": event.get("mnc"),
            "serving_cells": serving,
            "neighbor_cells": neighbors,
        }
        if test_kind == "video":
            document = {
                "job_type": "OOKLA",
                "task_name": "video",
                "result_id": event.get("result_id"),
                "network_type": event.get("network_type"),
                "plmn": event.get("plmn"),
                "mcc": event.get("mcc"),
                "mnc": event.get("mnc"),
                "LOCATION_DATA": {
                    "latitude": event.get("latitude"),
                    "longitude": event.get("longitude"),
                },
                "CELLS_INFO": cells,
                "ookla": {
                    "test_kind": "video",
                    "result_id": event.get("result_id"),
                    "load_time_ms": event.get("load_time_ms"),
                    "max_resolution": event.get("max_resolution"),
                    "buffering_pct": event.get("buffering_pct"),
                    "provider": event.get("provider"),
                    "network_type": event.get("network_type"),
                    "raw": _text(event.get("raw"), 2048),
                    "radio": radio_meta,
                    "video": {
                        "load_time_ms": event.get("load_time_ms"),
                        "max_resolution": event.get("max_resolution"),
                        "buffering_pct": event.get("buffering_pct"),
                        "provider": event.get("provider"),
                    },
                },
            }
        elif test_kind == "radio":
            document = {
                "job_type": "OOKLA",
                "task_name": "radio",
                "result_id": event.get("result_id"),
                "network_type": event.get("network_type"),
                "plmn": event.get("plmn"),
                "mcc": event.get("mcc"),
                "mnc": event.get("mnc"),
                "LOCATION_DATA": {
                    "latitude": event.get("latitude"),
                    "longitude": event.get("longitude"),
                },
                "CELLS_INFO": cells,
                "ookla": {
                    "test_kind": "radio",
                    "result_id": event.get("result_id"),
                    "network_type": event.get("network_type"),
                    "raw": _text(event.get("raw"), 2048),
                    "radio": radio_meta,
                },
            }
        else:
            document = {
                "job_type": "OOKLA",
                "task_name": "speedtest",
                "result_id": event.get("result_id"),
                "download_mbps": event.get("download_mbps"),
                "upload_mbps": event.get("upload_mbps"),
                "ping_ms": event.get("ping_ms"),
                "jitter_ms": event.get("jitter_ms"),
                "loss": event.get("loss"),
                "server_id": event.get("server_id"),
                "server_name": event.get("server_name"),
                "server_location": event.get("server_location"),
                "isp_name": event.get("isp_name"),
                "network_type": event.get("network_type"),
                "plmn": event.get("plmn"),
                "mcc": event.get("mcc"),
                "mnc": event.get("mnc"),
                "LOCATION_DATA": {
                    "latitude": event.get("latitude"),
                    "longitude": event.get("longitude"),
                },
                "CELLS_INFO": cells,
                "ookla": {
                    "test_kind": "speed",
                    "result_id": event.get("result_id"),
                    "download_mbps": event.get("download_mbps"),
                    "upload_mbps": event.get("upload_mbps"),
                    "ping_ms": event.get("ping_ms"),
                    "jitter_ms": event.get("jitter_ms"),
                    "loss": event.get("loss"),
                    "server_id": event.get("server_id"),
                    "server_name": event.get("server_name"),
                    "server_location": event.get("server_location"),
                    "isp_name": event.get("isp_name"),
                    "network_type": event.get("network_type"),
                    "raw": _text(event.get("raw"), 2048),
                    "radio": radio_meta,
                },
            }
        header = event_row(
            {
                "event_id": event.get("event_id"),
                "ts_ms": event.get("sent_at_ms") or event.get("ts_ms"),
            },
            document,
            False,
            moment,
            device_id,
            source_ip,
        )
        value_rows = [
            {
                "event_id": header["event_id"],
                "received_at": header["received_at"],
                "job_type": header["job_type"],
                **item,
            }
            for item in scalar_rows(document)
        ]
        return self._write([header], value_rows)

    def store_mysignal(
        self,
        device_id: str,
        source_ip: str,
        event: dict,
        received_at: int | None = None,
    ) -> int:
        import time

        moment = int(time.time()) if received_at is None else int(received_at)
        path_status = _text(event.get("path_status"), 64)
        document = {
            "job_type": "MYSIGNAL",
            "task_name": path_status or "nr_status",
            "mysignal": {
                "path_status": event.get("path_status"),
                "verdict": event.get("verdict"),
                "plmn": event.get("plmn"),
                "mcc": event.get("mcc"),
                "mnc": event.get("mnc"),
                "override_network_type": event.get("override_network_type"),
                "override_label": event.get("override_label"),
                "nr_state": event.get("nr_state"),
                "nr_state_label": event.get("nr_state_label"),
                "data_network_type": event.get("data_network_type"),
                "data_label": event.get("data_label"),
                "display_network_type": event.get("display_network_type"),
                "nr_frequency_range": event.get("nr_frequency_range"),
                "serving": event.get("serving_cells") or [],
                "neighbor": event.get("neighbor_cells") or [],
            },
        }
        header = event_row(
            {
                "event_id": event.get("event_id"),
                "ts_ms": event.get("sent_at_ms") or event.get("ts_ms"),
            },
            document,
            False,
            moment,
            device_id,
            source_ip,
        )
        value_rows = [
            {
                "event_id": header["event_id"],
                "received_at": header["received_at"],
                "job_type": header["job_type"],
                **item,
            }
            for item in scalar_rows(document)
        ]
        return self._write([header], value_rows)

    def store_timeline(
        self,
        device_id: str,
        source_ip: str,
        app: str,
        events: list[dict],
        received_at: int | None = None,
    ) -> int:
        import time

        moment = int(time.time()) if received_at is None else int(received_at)
        event_rows = []
        value_rows = []
        for event in events:
            document = {
                "job_type": "TIMELINE",
                "task_name": event.get("kind") or "unknown",
                "timeline": {
                    "app": app,
                    "kind": event.get("kind"),
                    "session_id": event.get("session_id"),
                    "test_kind": event.get("test_kind"),
                    "run_id": event.get("run_id"),
                    "result_id": event.get("result_id"),
                    "plmn": event.get("plmn"),
                    "prev_plmn": event.get("prev_plmn"),
                    "duration_ms": event.get("duration_ms"),
                    "detail": _text(event.get("detail"), 200),
                },
            }
            header = event_row(
                {"event_id": event.get("event_id"), "ts_ms": event.get("ts_ms")},
                document,
                False,
                moment,
                device_id,
                source_ip,
            )
            event_rows.append(header)
            for item in scalar_rows(document):
                value_rows.append(
                    {
                        "event_id": header["event_id"],
                        "received_at": header["received_at"],
                        "job_type": header["job_type"],
                        **item,
                    }
                )
        return self._write(event_rows, value_rows)

    def store_measurements(
        self,
        device_id: str,
        source_ip: str,
        snapshots: list[dict],
        received_at: int | None = None,
    ) -> int:
        """Flatten TelephonyManager snapshots (role=measurements) into ClickHouse as MEASURE."""
        import time

        moment = int(time.time()) if received_at is None else int(received_at)
        event_rows = []
        value_rows = []
        for snapshot in snapshots:
            if not isinstance(snapshot, dict):
                continue
            cells = snapshot.get("cells") if isinstance(snapshot.get("cells"), list) else []
            serving = [cell for cell in cells if isinstance(cell, dict) and cell.get("role") == "serving"]
            subscriptions = snapshot.get("subscriptions") if isinstance(snapshot.get("subscriptions"), list) else []
            sub = _active_subscription(subscriptions)
            mcc = sub.get("mcc") or ""
            mnc = sub.get("mnc") or ""
            plmn = (str(mcc) + str(mnc)) if mcc and mnc else None
            serving0 = serving[0] if serving else {}
            document = {
                "job_type": "MEASURE",
                "task_name": _text(snapshot.get("reason"), 32) or "interval",
                "reason": snapshot.get("reason"),
                "CELLS_INFO": _measure_cells(cells),
                "SUBSCRIPTIONS": [_public_subscription(item) for item in subscriptions if isinstance(item, dict)],
                "SERVICE_STATE": {
                    "operator_alpha_long": sub.get("carrier"),
                    "operator_numeric": plmn,
                    "mcc": mcc or None,
                    "mnc": mnc or None,
                    "network_operator": sub.get("network_operator"),
                    "rat": sub.get("rat"),
                    "nr_state": sub.get("nr_state"),
                    "network_type": sub.get("network_type"),
                    "ca": sub.get("ca"),
                    "endc_available": sub.get("endc_available"),
                },
                "LOCATION_DATA": snapshot.get("location"),
                "NETWORK": snapshot.get("network"),
                "WIFI": snapshot.get("wifi"),
                "DEVICE": _public_device(snapshot.get("device")),
                "MEASURE": {
                    "cells": len(cells),
                    "serving": len(serving),
                    "rat": serving0.get("rat"),
                    "pci": serving0.get("pci"),
                    "ci": serving0.get("ci"),
                    "rsrp": serving0.get("rsrp"),
                    "arfcn": serving0.get("channel") or _cell_channel(serving0),
                    "sim_slot": serving0.get("sim_slot"),
                    "sub_id": serving0.get("sub_id"),
                    "nr_state": sub.get("nr_state"),
                    "network_type": sub.get("network_type"),
                    "ca": sub.get("ca"),
                    "nr_frequency_range": sub.get("nr_frequency_range"),
                    "endc_available": sub.get("endc_available"),
                    "override_network_type": sub.get("override_network_type"),
                    "carriers": len(sub.get("carriers") or []),
                },
            }
            header = event_row(
                {"event_id": snapshot.get("event_id"), "ts_ms": snapshot.get("ts_ms")},
                document,
                False,
                int(snapshot.get("ts_ms") / 1000) if snapshot.get("ts_ms") else moment,
                device_id,
                source_ip,
            )
            event_rows.append(header)
            for item in scalar_rows(document):
                value_rows.append(
                    {
                        "event_id": header["event_id"],
                        "received_at": header["received_at"],
                        "job_type": header["job_type"],
                        **item,
                    }
                )
        return self._write(event_rows, value_rows)

    def optimize(self) -> None:
        self._sql("OPTIMIZE TABLE opensignal.telemetry_events FINAL")
        self._sql("OPTIMIZE TABLE opensignal.telemetry_values FINAL")

    def radio_by_arfcn(self, minutes: int = 60, limit: int = 200) -> list[dict]:
        """Average RSRP/RSRQ/SINR per ARFCN, split by source and SIM."""
        minutes = max(1, min(int(minutes), 1440))
        limit = max(1, min(int(limit), 500))
        query = (
            "SELECT source, rat, arfcn, mcc, mnc, sub_id, sim_slot, carrier,"
            " count() AS samples, sum(serving) AS serving_samples,"
            " round(avg(rsrp), 1) AS rsrp, round(avg(rsrq), 1) AS rsrq, round(avg(sinr), 1) AS sinr"
            " FROM opensignal.radio_cells"
            " WHERE received_at >= now() - INTERVAL %d MINUTE"
            " GROUP BY source, rat, arfcn, mcc, mnc, sub_id, sim_slot, carrier"
            " ORDER BY samples DESC LIMIT %d FORMAT JSON" % (minutes, limit)
        )
        raw = self._sql(query)
        try:
            return json.loads(raw).get("data", [])
        except json.JSONDecodeError:
            return []

    def _insert(self, table: str, rows: list[dict]) -> None:
        for offset in range(0, len(rows), 200):
            body = "\n".join(json.dumps(row, separators=(",", ":"), ensure_ascii=False) for row in rows[offset : offset + 200]).encode("utf-8")
            self._sql("INSERT INTO %s FORMAT JSONEachRow" % table, body)

    def _write(self, event_rows: list[dict], value_rows: list[dict]) -> int:
        if not event_rows:
            return 0
        self._insert("opensignal.telemetry_events", event_rows)
        if value_rows:
            self._insert("opensignal.telemetry_values", value_rows)
        return len(event_rows)

    def backfill_rows(self, rows: list[tuple]) -> int:
        event_rows = []
        value_rows = []
        for payload, source_ip, received_at, device_id in rows:
            event = json.loads(payload)
            events, values = self._collect(device_id or "", source_ip or "", [event], int(received_at))
            event_rows.extend(events)
            value_rows.extend(values)
        return self._write(event_rows, value_rows)

    def _role_rows(self, store, role: str, cutoff: int | None) -> list[tuple]:
        with store.lock, store._connect() as conn:
            if cutoff is None:
                return conn.execute(
                    "SELECT payload, source_ip, received_at, device_id FROM events WHERE role = ?",
                    (role,),
                ).fetchall()
            return conn.execute(
                """
                SELECT payload, source_ip, received_at, device_id FROM events
                WHERE role = ? AND received_at >= ?
                """,
                (role, cutoff),
            ).fetchall()

    def _replay_measurements(self, rows: list[tuple]) -> int:
        written = 0
        by_device: dict[tuple[str, str], list[dict]] = {}
        for payload, source_ip, received_at, device_id in rows:
            snapshot = json.loads(payload)
            key = (device_id or "", source_ip or "")
            by_device.setdefault(key, []).append(snapshot)
        for (device_id, source_ip), snapshots in by_device.items():
            written += self.store_measurements(device_id, source_ip, snapshots)
        return written

    def _replay_ookla(self, rows: list[tuple]) -> int:
        written = 0
        for payload, source_ip, received_at, device_id in rows:
            written += self.store_ookla(
                device_id or "", source_ip or "", json.loads(payload), int(received_at)
            )
        return written

    def _replay_mysignal(self, rows: list[tuple]) -> int:
        written = 0
        for payload, source_ip, received_at, device_id in rows:
            written += self.store_mysignal(
                device_id or "", source_ip or "", json.loads(payload), int(received_at)
            )
        return written

    def _replay_timeline(self, rows: list[tuple]) -> int:
        written = 0
        by_device: dict[tuple[str, str, str], list[dict]] = {}
        received: dict[tuple[str, str, str], int] = {}
        for payload, source_ip, received_at, device_id in rows:
            event = json.loads(payload)
            app = event.get("app") if isinstance(event.get("app"), str) else "opensignal"
            if app not in ("opensignal", "ookla"):
                app = "opensignal"
            key = (device_id or "", source_ip or "", app)
            by_device.setdefault(key, []).append(event)
            received[key] = max(received.get(key, 0), int(received_at))
        for (device_id, source_ip, app), events in by_device.items():
            written += self.store_timeline(
                device_id, source_ip, app, events, received[(device_id, source_ip, app)]
            )
        return written

    def sync_ingest_roles(self, store, cutoff: int | None = None) -> int:
        """Replay SQLite ingest roles into ClickHouse (idempotent by event_id)."""
        self.ensure_schema()
        with store.lock, store._connect() as conn:
            if cutoff is None:
                os_rows = conn.execute(
                    """
                    SELECT payload, source_ip, received_at, device_id FROM events
                    WHERE role = 'os_results'
                      AND (payload LIKE '%"stage":"sdk_json"%' OR payload LIKE '%"stage":"drive_mode"%')
                    """
                ).fetchall()
            else:
                os_rows = conn.execute(
                    """
                    SELECT payload, source_ip, received_at, device_id FROM events
                    WHERE role = 'os_results' AND received_at >= ?
                      AND (payload LIKE '%"stage":"sdk_json"%' OR payload LIKE '%"stage":"drive_mode"%')
                    """,
                    (cutoff,),
                ).fetchall()
        written = self.backfill_rows(os_rows)
        written += self._replay_measurements(self._role_rows(store, "measurements", cutoff))
        written += self._replay_ookla(self._role_rows(store, "ookla", cutoff))
        written += self._replay_mysignal(self._role_rows(store, "nr_status", cutoff))
        written += self._replay_timeline(self._role_rows(store, "timeline", cutoff))
        return written

    def backfill(self, store) -> int:
        return self.sync_ingest_roles(store, cutoff=None)

    def sync_recent(self, store, seconds: int) -> int:
        import time

        cutoff = int(time.time()) - seconds
        return self.sync_ingest_roles(store, cutoff=cutoff)
