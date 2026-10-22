#!/usr/bin/env python3
"""Dashboard measurements OpenSignal (MEASURE + CORE) — device / PLMN / ARFCN / RSRP-SINR / NR.

  GET /             -> halaman dashboard (measure_dashboard.html)
  GET /api/measure  -> JSON {window, devices[], sims[], cells[], nr{}, trend[], meta{}}
      ?minutes=N    (default 15, max 1440)
      ?device=ID    (opsional, default semua device)
  GET /api/interpret -> JSON {model, text, facts[], brief{}, generated_at}
      ?minutes=N&device=ID, ?refresh=1 untuk membaca ulang window (default cache 10 min)

Dijalankan oleh systemd: opensignal-measure-dashboard.service (127.0.0.1:8772),
di-proxy nginx lewat /meas/.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import measure_otel

CLICKHOUSE = os.environ.get("CLICKHOUSE_URL", "http://127.0.0.1:8123")
HERE = pathlib.Path(__file__).resolve().parent
PAGE = HERE / "measure_dashboard.html"
DEVICE_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
CACHE_TTL = 2.0
# The interpretation costs an LLM call per window; refreshing the page every 5 s
# must keep reusing it until the window has moved enough to be worth re-reading.
OTEL_TTL = 600.0
_cache: dict[str, tuple[float, dict]] = {}
_otel_cache: dict[str, tuple[float, dict]] = {}
_otel_lock = threading.Lock()


def ch(query: str) -> str:
    if not query.rstrip().endswith("FORMAT JSONEachRow"):
        query = query.rstrip().rstrip(";") + " FORMAT JSONEachRow"
    request = urllib.request.Request(CLICKHOUSE, data=query.encode("utf-8"), method="POST")
    with urllib.request.urlopen(request, timeout=15) as response:
        return response.read().decode("utf-8")


def rows(query: str) -> list[dict]:
    return [json.loads(line) for line in ch(query).splitlines() if line.strip()]


def num(value):
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        return value
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def plmn(mcc, mnc):
    mcc = "" if mcc is None else str(mcc).strip()
    mnc = "" if mnc is None else str(mnc).strip()
    return "%s-%s" % (mcc, mnc) if mcc and mnc else None


def window(minutes: int) -> str:
    return "now() - INTERVAL %d MINUTE" % minutes


def device_filter(device: str) -> str:
    if not device or not DEVICE_RE.match(device):
        return ""
    return " AND device_id = '%s'" % device


def devices(minutes: int, device: str) -> list[dict]:
    where = window(minutes) + device_filter(device)
    snaps = rows(
        "SELECT device_id, count() AS snapshots,"
        " round(avg(JSONExtractInt(payload, 'MEASURE', 'cells')), 1) AS cells_avg,"
        " countIf(JSONExtractInt(payload, 'MEASURE', 'cells') = 0) AS empty_snapshots,"
        " toUnixTimestamp(max(received_at)) AS last_seen"
        " FROM opensignal.telemetry_events"
        " WHERE job_type = 'MEASURE' AND received_at >= %s"
        " GROUP BY device_id ORDER BY snapshots DESC LIMIT 20" % where
    )
    labels = {
        row["device_id"]: row
        for row in rows(
            "SELECT device_id,"
            " anyLast(JSONExtractString(payload, 'DEVICE', 'manufacturer')) AS manufacturer,"
            " anyLast(JSONExtractString(payload, 'DEVICE', 'model')) AS model,"
            " anyLast(JSONExtractString(payload, 'DEVICE', 'release')) AS release,"
            " anyLast(JSONExtractString(payload, 'DEVICE', 'sdk_int')) AS sdk_int"
            " FROM opensignal.telemetry_events"
            " WHERE job_type = 'MEASURE' AND received_at >= now() - INTERVAL 48 HOUR"
            " GROUP BY device_id"
        )
    }
    radio = {
        row["device_id"]: row
        for row in rows(
            "SELECT device_id, uniqExact(arfcn) AS arfcns, uniqExact(plmn_tuple) AS plmns"
            " FROM ("
            "   SELECT device_id, arfcn, concat(ifNull(mcc, ''), '-', ifNull(mnc, '')) AS plmn_tuple"
            "   FROM opensignal.radio_cells WHERE source = 'mirror' AND received_at >= %s"
            " ) GROUP BY device_id" % where
        )
    }
    out = []
    for row in snaps:
        device_id = row["device_id"]
        label = labels.get(device_id) or {}
        model = " ".join(
            part for part in (str(label.get("manufacturer") or "").strip(), str(label.get("model") or "").strip()) if part
        )
        extra = radio.get(device_id) or {}
        out.append(
            {
                "device_id": device_id,
                "model": model or None,
                "release": label.get("release") or None,
                "sdk_int": num(label.get("sdk_int")),
                "snapshots": num(row.get("snapshots")) or 0,
                "cells_avg": num(row.get("cells_avg")),
                "empty_snapshots": num(row.get("empty_snapshots")) or 0,
                "last_seen": num(row.get("last_seen")),
                "arfcns": num(extra.get("arfcns")),
                "plmns": num(extra.get("plmns")),
            }
        )
    return out


def sims(minutes: int, device: str) -> list[dict]:
    where = window(minutes) + device_filter(device)
    inner = (
        "SELECT e.device_id AS device_id, toUnixTimestamp(e.received_at) AS t,"
        " JSONExtractInt(s, 'sub_id') AS sub_id, JSONExtractInt(s, 'slot') AS slot,"
        " JSONExtractString(s, 'mcc') AS mcc, JSONExtractString(s, 'mnc') AS mnc,"
        " JSONExtractString(s, 'carrier') AS carrier, JSONExtractString(s, 'rat') AS rat,"
        " JSONExtractString(s, 'data_state') AS data_state,"
        " JSONExtractString(s, 'service_state') AS service_state,"
        " JSONExtractInt(s, 'nr_state') AS nr_state, JSONExtractInt(s, 'channel') AS channel,"
        " JSONExtractInt(s, 'network_type') AS network_type,"
        " JSONExtractBool(s, 'ca') AS ca, JSONExtractBool(s, 'endc_available') AS endc_available,"
        " JSONExtractInt(s, 'serving_signal', 'rsrp') AS rsrp,"
        " JSONExtractInt(s, 'serving_signal', 'sinr') AS sinr,"
        " JSONExtractInt(s, 'serving_signal', 'rsrq') AS rsrq"
        " FROM opensignal.telemetry_events AS e"
        " ARRAY JOIN JSONExtractArrayRaw(e.payload, 'SUBSCRIPTIONS') AS s"
        " WHERE e.job_type = 'MEASURE' AND e.received_at >= %s" % where
    )
    data = rows(
        "SELECT device_id, sub_id, slot,"
        " argMax(mcc, t) AS mcc_last, argMax(mnc, t) AS mnc_last, argMax(carrier, t) AS carrier_last,"
        " argMax(rat, t) AS rat_last, argMax(data_state, t) AS data_state_last,"
        " argMax(service_state, t) AS service_state_last,"
        " argMax(nr_state, t) AS nr_state_last, argMax(channel, t) AS channel_last,"
        " argMax(network_type, t) AS network_type_last,"
        " argMax(ca, t) AS ca_last, argMax(endc_available, t) AS endc_last,"
        " argMax(rsrp, t) AS rsrp_last, argMax(sinr, t) AS sinr_last, argMax(rsrq, t) AS rsrq_last,"
        " count() AS samples, max(t) AS last_seen,"
        " countIf(nr_state = 3) AS nr3, countIf(nr_state = 2) AS nr2, countIf(nr_state = 1) AS nr1,"
        " countIf(service_state = 'IN_SERVICE') AS in_service,"
        " countIf(data_state = 'CONNECTED') AS connected"
        " FROM (%s) GROUP BY device_id, sub_id, slot"
        " ORDER BY device_id, slot" % inner
    )
    out = []
    for row in data:
        rsrp = num(row.get("rsrp_last")) or None
        rsrq = num(row.get("rsrq_last")) or None
        sinr = num(row.get("sinr_last")) or None
        if rsrp is None:  # 0 == "not reported" in the APK snapshot
            rsrq = sinr = None
        out.append(
            {
                "device_id": row.get("device_id"),
                "sub_id": num(row.get("sub_id")),
                "slot": num(row.get("slot")),
                "carrier": (row.get("carrier_last") or "").strip() or None,
                "plmn": plmn(row.get("mcc_last"), row.get("mnc_last")),
                "rat": row.get("rat_last") or None,
                "data_state": row.get("data_state_last") or None,
                "service_state": row.get("service_state_last") or None,
                "nr_state": num(row.get("nr_state_last")),
                "network_type": num(row.get("network_type_last")) or None,
                "ca": bool(row.get("ca_last")),
                "endc_available": bool(row.get("endc_last")),
                "channel": (num(row.get("channel_last")) or None),
                "rsrp": rsrp,
                "sinr": sinr,
                "rsrq": rsrq,
                "samples": num(row.get("samples")) or 0,
                "last_seen": num(row.get("last_seen")),
                "nr3": num(row.get("nr3")) or 0,
                "nr2": num(row.get("nr2")) or 0,
                "nr1": num(row.get("nr1")) or 0,
                "in_service": num(row.get("in_service")) or 0,
                "connected": num(row.get("connected")) or 0,
            }
        )
    return out


def cells(minutes: int, device: str, limit: int = 400) -> list[dict]:
    """One row per physical cell from the MEASURE (mirror) stream only.

    Only ``source = 'mirror'`` samples are used: the sdk/CORE rows duplicate the
    same cells with a different cadence and must not be mixed in. ``radio_cells``
    still reports one cell twice (with and without the SIM attribution), so the
    per-source groups are merged below into a single physical row keyed by
    device x RAT x ARFCN x PCI.
    """
    where = window(minutes) + device_filter(device)
    data = rows(
        "SELECT device_id, rat, arfcn, pci,"
        " count() AS samples, sum(serving) AS serving_samples,"
        " round(avg(rsrp), 1) AS rsrp_avg, min(rsrp) AS rsrp_min, max(rsrp) AS rsrp_max,"
        " round(avg(rsrq), 1) AS rsrq_avg, round(avg(sinr), 1) AS sinr_avg,"
        " argMax(sim_slot, tuple(sim_slot IS NOT NULL, received_at)) AS slot_last,"
        " argMax(sub_id, tuple(sim_slot IS NOT NULL, received_at)) AS sub_last,"
        " argMax(carrier, tuple(ifNull(carrier, '') != '', received_at)) AS carrier_last,"
        " argMax(mcc, tuple(ifNull(mcc, '') != '', received_at)) AS mcc_last,"
        " argMax(mnc, tuple(ifNull(mnc, '') != '', received_at)) AS mnc_last,"
        " toUnixTimestamp(max(received_at)) AS last_seen,"
        " if(arfcn IS NULL OR pci IS NULL, ci, '') AS ci_key"
        " FROM opensignal.radio_cells WHERE source = 'mirror' AND received_at >= %s"
        " GROUP BY device_id, rat, arfcn, pci, ci_key"
        " ORDER BY samples DESC LIMIT %d" % (where, limit)
    )
    out = []
    for row in data:
        samples = num(row.get("samples")) or 0
        serving = num(row.get("serving_samples")) or 0
        out.append(
            {
                "device_id": row.get("device_id"),
                "sim_slot": num(row.get("slot_last")),
                "sub_id": num(row.get("sub_last")),
                "carrier": (row.get("carrier_last") or "").strip() or None,
                "plmn": plmn(row.get("mcc_last"), row.get("mnc_last")),
                "rat": row.get("rat") or None,
                "arfcn": num(row.get("arfcn")),
                "pci": num(row.get("pci")),
                "samples": samples,
                "serving_samples": serving,
                "serving_ratio": min(1.0, round(serving / samples, 3)) if samples else 0.0,
                "rsrp": num(row.get("rsrp_avg")),
                "rsrp_min": num(row.get("rsrp_min")),
                "rsrp_max": num(row.get("rsrp_max")),
                "rsrq": num(row.get("rsrq_avg")),
                "sinr": num(row.get("sinr_avg")),
                "last_seen": num(row.get("last_seen")),
            }
        )
    out.sort(key=lambda item: (item["rat"] != "NR", -(item["serving_samples"] or 0), -(item["samples"] or 0)))
    return out


def nr_cells(minutes: int, device: str, limit: int = 40) -> list[dict]:
    """NR cells from the MEASURE stream, queried on their own.

    NR is rare (a handful of samples) and ``cells()`` caps its result by sample
    count, so an NR cell would silently fall out of a busy window. This runs a
    dedicated query so the NR panel always reflects what the modem reported.
    """
    where = window(minutes) + device_filter(device)
    data = rows(
        "SELECT device_id, rat, arfcn, pci,"
        " count() AS samples, sum(serving) AS serving_samples,"
        " round(avg(rsrp), 1) AS rsrp_avg, min(rsrp) AS rsrp_min, max(rsrp) AS rsrp_max,"
        " round(avg(rsrq), 1) AS rsrq_avg, round(avg(sinr), 1) AS sinr_avg,"
        " argMax(sim_slot, tuple(sim_slot IS NOT NULL, received_at)) AS slot_last,"
        " argMax(sub_id, tuple(sim_slot IS NOT NULL, received_at)) AS sub_last,"
        " argMax(carrier, tuple(ifNull(carrier, '') != '', received_at)) AS carrier_last,"
        " argMax(mcc, tuple(ifNull(mcc, '') != '', received_at)) AS mcc_last,"
        " argMax(mnc, tuple(ifNull(mnc, '') != '', received_at)) AS mnc_last,"
        " toUnixTimestamp(max(received_at)) AS last_seen"
        " FROM opensignal.radio_cells"
        " WHERE source = 'mirror' AND rat = 'NR' AND received_at >= %s"
        " GROUP BY device_id, rat, arfcn, pci"
        " ORDER BY serving_samples DESC, samples DESC LIMIT %d" % (where, limit)
    )
    out = []
    for row in data:
        samples = num(row.get("samples")) or 0
        serving = num(row.get("serving_samples")) or 0
        out.append(
            {
                "device_id": row.get("device_id"),
                "sim_slot": num(row.get("slot_last")),
                "sub_id": num(row.get("sub_last")),
                "carrier": (row.get("carrier_last") or "").strip() or None,
                "plmn": plmn(row.get("mcc_last"), row.get("mnc_last")),
                "rat": "NR",
                "arfcn": num(row.get("arfcn")),
                "pci": num(row.get("pci")),
                "samples": samples,
                "serving_samples": serving,
                "serving_ratio": min(1.0, round(serving / samples, 3)) if samples else 0.0,
                "rsrp": num(row.get("rsrp_avg")),
                "rsrp_min": num(row.get("rsrp_min")),
                "rsrp_max": num(row.get("rsrp_max")),
                "rsrq": num(row.get("rsrq_avg")),
                "sinr": num(row.get("sinr_avg")),
                "last_seen": num(row.get("last_seen")),
            }
        )
    return out


def attach_nr_owner(nr_list: list[dict], sims_list: list[dict]) -> None:
    """Give NR cells the SIM that owns them.

    ``CellIdentityNr`` frequently omits MCC/MNC, so the APK cannot match a PLMN
    and the row arrives unattributed. The registered NR carrier belongs to the
    SIM with the data connection (EN-DC anchor or SA), so fall back to the single
    CONNECTED subscription; anything ambiguous stays unattributed.
    """
    connected = [sim for sim in sims_list if (sim.get("data_state") or "").upper() == "CONNECTED"]
    if len(connected) != 1:
        return
    owner = connected[0]
    for cell in nr_list:
        if cell.get("sim_slot") is not None or cell.get("sub_id") is not None:
            continue
        cell["sim_slot"] = owner.get("slot")
        cell["sub_id"] = owner.get("sub_id")
        cell["carrier"] = cell.get("carrier") or owner.get("carrier")
        cell["plmn"] = cell.get("plmn") or owner.get("plmn")
        cell["sim_source"] = "data_sim"


def nr_summary(nr_list: list[dict], sims_list: list[dict], states: dict) -> dict:
    """What the modem reported about NR in this window, per SIM."""
    serving = [cell for cell in nr_list if cell.get("serving_samples")]
    per_sim = []
    for sim in sims_list:
        mine = [cell for cell in serving if cell.get("sim_slot") == sim.get("slot")]
        per_sim.append(
            {
                "slot": sim.get("slot"),
                "sub_id": sim.get("sub_id"),
                "carrier": sim.get("carrier"),
                "nr_state": sim.get("nr_state"),
                "data_state": sim.get("data_state"),
                "attached": bool(mine) or sim.get("nr_state") == 3,
                "cells": [
                    {
                        "arfcn": cell.get("arfcn"),
                        "pci": cell.get("pci"),
                        "rsrp": cell.get("rsrp"),
                        "sinr": cell.get("sinr"),
                        "samples": cell.get("samples"),
                    }
                    for cell in mine
                ],
            }
        )
    return {
        "attached": bool(serving),
        "state_samples": states,
        "serving_cells": len(serving),
        "per_sim": per_sim,
    }


def nr_timeline(minutes: int, device: str) -> list[dict]:
    where = window(minutes) + device_filter(device)
    data = rows(
        "SELECT device_id, toUnixTimestamp(e.received_at) AS t,"
        " JSONExtractInt(s, 'sub_id') AS sub_id, JSONExtractInt(s, 'slot') AS slot,"
        " JSONExtractInt(s, 'nr_state') AS nr_state, JSONExtractInt(s, 'channel') AS channel"
        " FROM opensignal.telemetry_events AS e"
        " ARRAY JOIN JSONExtractArrayRaw(e.payload, 'SUBSCRIPTIONS') AS s"
        " WHERE e.job_type = 'MEASURE' AND e.received_at >= %s"
        " ORDER BY t DESC LIMIT 400" % where
    )
    data.reverse()
    return [
        {
            "t": num(row.get("t")),
            "sub_id": num(row.get("sub_id")),
            "slot": num(row.get("slot")),
            "nr_state": num(row.get("nr_state")),
            "channel": num(row.get("channel")),
        }
        for row in data
    ]


def trend(minutes: int, device: str, limit: int = 8) -> dict:
    """Serving-cell RSRP over time, one series per SIM slot.

    A phone has one serving cell per SIM, so plotting per ARFCN splits a single
    radio into fragments whenever it hands over (1625 -> 200 -> 1475) and the
    near-equal dBm values end up overlapping. Each bucket therefore carries the
    SIM slot, the dominant serving ARFCN and the RSRP, which lets the page draw
    one line per SIM plus a colour band of the ARFCN actually in use.
    """
    where = window(minutes) + device_filter(device)
    bucket = 30 if minutes <= 30 else 60
    data = rows(
        "SELECT t, sim_slot, argMax(arfcn, cnt) AS arfcn_mode, any(carrier_name) AS carrier_name,"
        " round(sum(rsrp_avg * cnt) / greatest(sum(cnt), 1), 1) AS rsrp_avg,"
        " round(avg(sinr_avg), 1) AS sinr_avg, sum(cnt) AS samples, argMax(cnt, cnt) AS top_count"
        " FROM ("
        "   SELECT toUnixTimestamp(toStartOfInterval(received_at, INTERVAL %d SECOND)) AS t,"
        "   sim_slot, arfcn, any(carrier) AS carrier_name,"
        "   round(avg(rsrp), 1) AS rsrp_avg, round(avg(sinr), 1) AS sinr_avg, count() AS cnt"
        "   FROM opensignal.radio_cells"
        "   WHERE source = 'mirror' AND serving = 1 AND received_at >= %s"
        "   GROUP BY t, sim_slot, arfcn"
        " ) GROUP BY t, sim_slot ORDER BY t ASC LIMIT 4000" % (bucket, where)
    )
    points = [
        {
            "t": num(row.get("t")),
            "slot": num(row.get("sim_slot")),
            "carrier": (row.get("carrier_name") or "").strip() or None,
            "arfcn": num(row.get("arfcn_mode")),
            "rsrp": num(row.get("rsrp_avg")),
            "sinr": num(row.get("sinr_avg")),
            "samples": num(row.get("samples")) or 0,
            "top": num(row.get("top_count")) or 0,
        }
        for row in data
    ]
    slots = []
    for point in points:
        label = "slot ?" if point["slot"] is None else "slot %d" % point["slot"]
        if all(item["label"] != label for item in slots):
            slots.append({"label": label, "slot": point["slot"], "carrier": point["carrier"]})
        elif not slots[[item["label"] for item in slots].index(label)]["carrier"] and point["carrier"]:
            slots[[item["label"] for item in slots].index(label)]["carrier"] = point["carrier"]
    # Fixed band order: highest slot on top (slot 1 above slot 0) so a refresh
    # never swaps the rows around, and the unknown slot always sinks last.
    slots.sort(key=lambda item: (item["slot"] is None, -(item["slot"] or 0)))
    counts: dict[int, int] = {}
    for point in points:
        if point["arfcn"] is not None:
            counts[point["arfcn"]] = counts.get(point["arfcn"], 0) + point["samples"]
    top = [key for key, _ in sorted(counts.items(), key=lambda item: -item[1])][:limit]
    # Colours are assigned from the ARFCN list, so keep it numerically sorted:
    # otherwise an ARFCN changing rank between refreshes repaints every series.
    keys = sorted(top)
    for slot in slots:
        slot["segments"] = serving_segments(
            [point for point in points if point["slot"] == slot["slot"]], bucket
        )
    return {
        "bucket_s": bucket,
        "slots": slots,
        "keys": keys,
        "points": [point for point in points if point["arfcn"] in keys],
    }


def serving_segments(points: list[dict], bucket: int) -> list[dict]:
    """Collapse bucket points into an ARFCN timeline, one entry per dwell.

    Consecutive buckets on the same ARFCN become one segment. A single 3-second
    blip that a bucket-level ``argMax`` would surface as its own segment is
    folded back into the surrounding dwell when both neighbours agree, so the
    band shows real handovers instead of flicker.
    """
    ordered = sorted((point for point in points if point["arfcn"] is not None), key=lambda point: point["t"])
    segments: list[dict] = []
    for point in ordered:
        if segments and segments[-1]["arfcn"] == point["arfcn"]:
            last = segments[-1]
            last["end"] = point["t"]
            last["buckets"] += 1
            last["samples"] += point["samples"]
            last["rsrps"].append(point["rsrp"])
        else:
            segments.append(
                {
                    "arfcn": point["arfcn"],
                    "start": point["t"],
                    "end": point["t"],
                    "buckets": 1,
                    "samples": point["samples"],
                    "rsrps": [point["rsrp"]],
                }
            )
    changed = True
    while changed and len(segments) >= 3:
        changed = False
        for index in range(1, len(segments) - 1):
            current = segments[index]
            previous = segments[index - 1]
            following = segments[index + 1]
            if current["buckets"] > 1 or previous["arfcn"] != following["arfcn"]:
                continue
            previous["end"] = following["end"]
            previous["buckets"] += current["buckets"] + following["buckets"]
            previous["samples"] += current["samples"] + following["samples"]
            previous["rsrps"].extend(current["rsrps"] + following["rsrps"])
            segments = segments[: index] + segments[index + 2:]
            changed = True
            break
    out = []
    for segment in segments:
        values = [value for value in segment["rsrps"] if value is not None]
        out.append(
            {
                "arfcn": segment["arfcn"],
                "start": segment["start"],
                "end": segment["end"],
                "duration_s": (segment["end"] - segment["start"]) + bucket,
                "buckets": segment["buckets"],
                "samples": segment["samples"],
                "rsrp": round(sum(values) / len(values), 1) if values else None,
            }
        )
    return out


def transport(minutes: int, device: str) -> dict:
    where = window(minutes) + device_filter(device)
    data = rows(
        "SELECT anyLast(JSONExtractString(payload, 'NETWORK', 'transport')) AS transport,"
        " anyLast(JSONExtractBool(payload, 'NETWORK', 'connected')) AS connected,"
        " anyLast(JSONExtractBool(payload, 'NETWORK', 'roaming')) AS roaming,"
        " anyLast(JSONExtractInt(payload, 'MEASURE', 'nr_state')) AS nr_state"
        " FROM opensignal.telemetry_events"
        " WHERE job_type = 'MEASURE' AND received_at >= %s" % where
    )
    row = data[0] if data else {}
    return {
        "transport": row.get("transport") or None,
        "connected": bool(row.get("connected")),
        "roaming": bool(row.get("roaming")),
        "nr_state": num(row.get("nr_state")),
    }


def serving_summary(minutes: int, device: str, cells_list: list[dict]) -> dict:
    """Prove whether the SIMs report parallel serving carriers or time-sharing.

    ``radio_cells`` rows aggregate a whole window, so one SIM legitimately owns
    several rows: the serving cell moves (handover) or changes PCI on the same
    ARFCN. Counting the serving flags per snapshot tells us the truth, and the
    per-slot shares show that those rows partition the window instead of
    running in parallel (LTE carrier aggregation would show count > 1).
    """
    where = window(minutes) + device_filter(device)
    data = rows(
        "SELECT event_id, sim_slot, count() AS serving_cells"
        " FROM opensignal.radio_cells"
        " WHERE source = 'mirror' AND serving = 1 AND received_at >= %s"
        " GROUP BY event_id, sim_slot" % where
    )
    counts = [int(num(row.get("serving_cells")) or 0) for row in data]
    shares: dict[int, dict] = {}
    for cell in cells_list:
        slot = cell["sim_slot"]
        if slot is None or not cell["serving_samples"]:
            continue
        bucket = shares.setdefault(slot, {"slot": slot, "carrier": cell["carrier"], "items": [], "serving_samples": 0})
        bucket["serving_samples"] += cell["serving_samples"]
        if not bucket["carrier"] and cell["carrier"]:
            bucket["carrier"] = cell["carrier"]
    for cell in cells_list:
        slot = cell["sim_slot"]
        if slot is None or slot not in shares or not cell["serving_samples"]:
            continue
        total = shares[slot]["serving_samples"] or 1
        shares[slot]["items"].append(
            {
                "arfcn": cell["arfcn"],
                "pci": cell["pci"],
                "sample_share": min(1.0, round(cell["serving_samples"] / total, 3)),
                "samples": cell["serving_samples"],
                "rsrp": cell["rsrp"],
            }
        )
    for bucket in shares.values():
        bucket["items"].sort(key=lambda item: -item["samples"])
        # extra (arfcn, pci) pairs beyond one per ARFCN = intra-frequency PCI changes
        bucket["pci_changes"] = max(
            0, len(bucket["items"]) - len({item["arfcn"] for item in bucket["items"]})
        )
    return {
        "snapshots": len(counts),
        "per_snapshot_max": max(counts) if counts else 0,
        "per_snapshot_avg": round(sum(counts) / len(counts), 2) if counts else 0.0,
        "parallel_carriers": bool(counts) and max(counts) > 1,
        "by_slot": sorted(shares.values(), key=lambda item: -item["slot"]),
    }


def attach_serving_shares(cells_list: list[dict], serving_info: dict) -> None:
    """Normalise each cell's serving share against its SIM's window samples.

    ``serving_ratio`` is a hit-rate (serving samples / times the cell was seen),
    which overstates cells that only appeared briefly. The dashboard shows the
    window share, i.e. the value that adds up to ~100% per SIM.
    """
    shares = {
        (slot["slot"], item["arfcn"], item["pci"]): item["sample_share"]
        for slot in serving_info["by_slot"]
        for item in slot["items"]
    }
    for cell in cells_list:
        cell["serving_share"] = shares.get(
            (cell["sim_slot"], cell["arfcn"], cell["pci"]), cell["serving_ratio"] or 0.0
        )


def build(minutes: int, device: str) -> dict:
    started = time.time()
    devices_list = devices(minutes, device)
    sims_list = sims(minutes, device)
    cells_list = cells(minutes, device)
    by_cell = {}
    for cell in cells_list:
        if cell["arfcn"] is None:
            continue
        by_cell.setdefault((cell["device_id"], cell["sim_slot"], cell["arfcn"]), cell)
    for sim in sims_list:  # serving_signal is 0 when the modem omits it: reuse the cell row
        cell = by_cell.get((sim["device_id"], sim["slot"], sim["channel"]))
        if cell:
            for field in ("rsrp", "rsrq", "sinr"):
                if sim[field] is None and cell[field] is not None:
                    sim[field] = cell[field]
                    sim["signal_from"] = "cell"
    serving_slot = {}
    for cell in cells_list:  # serving cell per SIM slot, independent of the subscription channel
        if cell["sim_slot"] is None or not cell["serving_samples"]:
            continue
        key = (cell["device_id"], cell["sim_slot"])
        rank = (cell["last_seen"] or 0, cell["serving_samples"] or 0)
        current = serving_slot.get(key)
        if current is None or rank > current[0]:
            serving_slot[key] = (rank, cell)
    for sim in sims_list:
        picked = serving_slot.get((sim["device_id"], sim["slot"]))
        cell = picked[1] if picked else None
        sim["serving_arfcn"] = cell["arfcn"] if cell else None
        sim["serving_pci"] = cell["pci"] if cell else None
        sim["cell_rsrp"] = cell["rsrp"] if cell else None
        sim["cell_rsrq"] = cell["rsrq"] if cell else None
        sim["cell_sinr"] = cell["sinr"] if cell else None
    serving_info = serving_summary(minutes, device, cells_list)
    # A cell that is only briefly visible (e.g. 15 snapshots) must not look like a
    # 40%-of-window serving cell: normalise the share against the SIM's window.
    attach_serving_shares(cells_list, serving_info)
    nr_states = {"3": 0, "2": 0, "1": 0, "0": 0}
    for item in sims_list:
        nr_states[str(int(item["nr_state"] or 0))] = nr_states.get(str(int(item["nr_state"] or 0)), 0) + int(item["samples"])
    # NR carries far fewer samples than LTE, so it is queried on its own and merged
    # back: the NR panel and the Cells table must show it even in a busy window.
    nr_list = nr_cells(minutes, device)
    attach_nr_owner(nr_list, sims_list)
    known = {(cell["device_id"], cell["rat"], cell["arfcn"], cell["pci"]) for cell in cells_list}
    for cell in nr_list:
        if (cell["device_id"], cell["rat"], cell["arfcn"], cell["pci"]) not in known:
            cells_list.append(cell)
    nr_list.sort(key=lambda cell: (-(cell["serving_samples"] or 0), -(cell["samples"] or 0)))
    for cell in nr_list:
        cell["serving_share"] = 1.0 if cell["serving_samples"] else 0.0
    nr_info = nr_summary(nr_list, sims_list, nr_states)
    for sim in sims_list:  # what the SIM's NR side looked like in this window
        entry = next((item for item in nr_info["per_sim"] if item["slot"] == sim.get("slot")), None)
        sim["nr"] = entry
    return {
        "now": int(time.time()),
        "minutes": minutes,
        "device": device or None,
        "elapsed_ms": int((time.time() - started) * 1000),
        "devices": devices_list,
        "sims": sims_list,
        "cells": cells_list,
        "nr": {
            "states": nr_states,
            "cells": nr_list,
            "timeline": nr_timeline(minutes, device),
            "summary": nr_info,
        },
        "trend": trend(minutes, device),
        "serving": serving_info,
        "transport": transport(minutes, device),
    }


def cached(minutes: int, device: str) -> dict:
    key = "%d|%s" % (minutes, device)
    now = time.time()
    hit = _cache.get(key)
    if hit and now - hit[0] < CACHE_TTL:
        return hit[1]
    payload = build(minutes, device)
    _cache[key] = (now, payload)
    if len(_cache) > 32:
        for stale in sorted(_cache, key=lambda item: _cache[item][0])[:16]:
            _cache.pop(stale, None)
    return payload


def otel(minutes: int, device: str, force: bool = False) -> dict:
    """Interpret the same window the page shows, cached per window setting.

    The measurements payload is rebuilt every 2 s but an interpretation is only
    worth a new LLM call when the window itself has moved, so results are kept
    for ``OTEL_TTL`` unless the page explicitly asks to re-read it. The cache
    key includes the device set: a phone that starts reporting mid-window must
    not inherit an interpretation written for the previous set.
    """
    payload = cached(minutes, device)
    devices = sorted(str(item.get("device_id") or "") for item in payload.get("devices") or [])
    fingerprint = hashlib.sha1(",".join(devices).encode("utf-8")).hexdigest()[:8]
    key = "%d|%s|%s" % (minutes, device, fingerprint)
    if not force:
        hit = _otel_cache.get(key)
        if hit and time.time() - hit[0] < OTEL_TTL:
            return dict(hit[1], cached=True)
    with _otel_lock:
        if not force:  # another request may have filled the cache while we waited
            hit = _otel_cache.get(key)
            if hit and time.time() - hit[0] < OTEL_TTL:
                return dict(hit[1], cached=True)
        started = time.time()
        brief = measure_otel.build_brief(payload)
        answer = measure_otel.interpret(brief)
        result = {
            "model": answer["model"],
            "generated_at": int(time.time()),
            "minutes": minutes,
            "device": device or None,
            "elapsed_ms": int((time.time() - started) * 1000),
            "facts": measure_otel.facts(brief),
            "brief": brief,
            "text": answer["text"],
            "usage": answer.get("usage") or {},
        }
        _otel_cache[key] = (time.time(), result)
        if len(_otel_cache) > 32:
            for stale in sorted(_otel_cache, key=lambda item: _otel_cache[item][0])[:16]:
                _otel_cache.pop(stale, None)
        return dict(result, cached=False)


def page() -> bytes:
    return PAGE.read_bytes()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "osm-measure-dashboard"

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path.rstrip("/") or "/"
        if path in ("/", "/index.html"):
            self._send(200, page(), "text/html; charset=utf-8")
            return
        if path == "/health":
            self._json({"status": "ok"})
            return
        if path == "/api/measure":
            params = urllib.parse.parse_qs(parsed.query)
            try:
                minutes = int(params.get("minutes", ["15"])[0])
            except ValueError:
                self._json({"error": "invalid minutes"}, 400)
                return
            minutes = max(1, min(minutes, 1440))
            device = (params.get("device", [""])[0] or "").strip()
            try:
                self._json(cached(minutes, device))
            except Exception as error:  # noqa: BLE001 - surface the reason to the page
                self._json({"error": str(error)[:300]}, 503)
            return
        if path == "/api/interpret":
            params = urllib.parse.parse_qs(parsed.query)
            try:
                minutes = int(params.get("minutes", ["15"])[0])
            except ValueError:
                self._json({"error": "invalid minutes"}, 400)
                return
            minutes = max(1, min(minutes, 1440))
            device = (params.get("device", [""])[0] or "").strip()
            force = (params.get("refresh", ["0"])[0] or "").lower() in ("1", "true", "yes")
            try:
                self._json(otel(minutes, device, force))
            except Exception as error:  # noqa: BLE001 - the panel shows the reason
                self._json(
                    {"error": str(error)[:300], "minutes": minutes, "device": device or None},
                    503,
                )
            return
        self._json({"error": "not_found"}, 404)

    def _json(self, payload: dict, status: int = 200) -> None:
        self._send(status, json.dumps(payload, separators=(",", ":")).encode("utf-8"), "application/json")

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args) -> None:
        print("%s - %s" % (self.address_string(), fmt % args), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8772)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print("measure dashboard on http://%s:%d" % (args.host, args.port), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
