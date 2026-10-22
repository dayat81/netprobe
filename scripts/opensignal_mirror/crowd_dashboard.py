#!/usr/bin/env python3
"""Crowdsourced MySignal view: radio MEASURE rows plus speed and video tests.

  GET /crowd/       page
  GET /crowd/api    JSON ?start=YYYY-MM-DD&end=YYYY-MM-DD  (WIB, inclusive)
"""

from __future__ import annotations

import hashlib
import hmac
import html
import json
import math
import os
import pathlib
import re
import secrets
import sqlite3
import time
from datetime import datetime, timedelta, timezone
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CLICKHOUSE = os.environ.get("CLICKHOUSE_URL", "http://127.0.0.1:8123")
OSM_DB = os.environ.get("OSM_DB", "/var/lib/opensignal-mirror/collector.sqlite")
COMPLAINT_DIR = pathlib.Path(os.environ.get("COMPLAINT_DIR", "/var/lib/opensignal-mirror/complaints"))
HMAC_FILE = os.environ.get("OSM_HMAC", "/etc/opensignal-mirror/hmac.key")
PUBLIC_BASE = os.environ.get("PUBLIC_BASE", "https://netprobe.xyz").rstrip("/")
HERE = pathlib.Path(__file__).resolve().parent
PAGE = HERE / "crowd_dashboard.html"
WIB = timezone(timedelta(hours=7))
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
MAX_DAYS = 31


def ch(query: str) -> list[dict]:
    if not query.rstrip().endswith("FORMAT JSONEachRow"):
        query = query.rstrip().rstrip(";") + " FORMAT JSONEachRow"
    request = urllib.request.Request(CLICKHOUSE, data=query.encode("utf-8"), method="POST")
    with urllib.request.urlopen(request, timeout=20) as response:
        text = response.read().decode("utf-8")
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def period(start: str, end: str) -> tuple[int, int, str, str]:
    today = datetime.now(WIB).date()
    if not DATE_RE.match(start or ""):
        start = today.isoformat()
    if not DATE_RE.match(end or ""):
        end = start
    start_day = datetime.strptime(start, "%Y-%m-%d").date()
    end_day = datetime.strptime(end, "%Y-%m-%d").date()
    if end_day < start_day:
        start_day, end_day = end_day, start_day
    if (end_day - start_day).days > MAX_DAYS:
        start_day = end_day - timedelta(days=MAX_DAYS)
    t0 = datetime(start_day.year, start_day.month, start_day.day, tzinfo=WIB)
    t1 = datetime(end_day.year, end_day.month, end_day.day, tzinfo=WIB) + timedelta(days=1)
    return int(t0.timestamp()), int(t1.timestamp()), start_day.isoformat(), end_day.isoformat()


def radio(t0: int, t1: int) -> list[dict]:
    query = (
        "SELECT device_id,"
        " JSONExtractString(payload, 'DEVICE', 'model') AS model,"
        " JSONExtractString(payload, 'SERVICE_STATE', 'operator_alpha_long') AS carrier,"
        " JSONExtractString(payload, 'SERVICE_STATE', 'mcc') AS mcc,"
        " JSONExtractString(payload, 'SERVICE_STATE', 'mnc') AS mnc,"
        " JSONExtractString(payload, 'MEASURE', 'rat') AS rat,"
        " JSONExtractInt(payload, 'MEASURE', 'arfcn') AS arfcn,"
        " JSONExtractInt(payload, 'MEASURE', 'rsrp') AS rsrp,"
        " JSONExtractRaw(payload, 'CELLS_INFO') AS cells_json,"
        " JSONExtractRaw(payload, 'SUBSCRIPTIONS') AS subs_json,"
        " JSONExtractInt(payload, 'CELLS_INFO', 1, 'bandwidth_khz') AS bandwidth_khz,"
        " JSONExtractInt(payload, 'SERVICE_STATE', 'nr_state') AS nr_state,"
        " JSONExtractString(payload, 'SERVICE_STATE', 'ca') AS ca,"
        " JSONExtractFloat(payload, 'LOCATION_DATA', 'latitude') AS lat,"
        " JSONExtractFloat(payload, 'LOCATION_DATA', 'longitude') AS lon,"
        " JSONExtractString(payload, 'LOCATION_DATA', 'status') AS loc_status,"
        " toUnixTimestamp(received_at) AS ts"
        " FROM opensignal.telemetry_events"
        " WHERE job_type = 'MEASURE'"
        " AND received_at >= toDateTime(%d) AND received_at < toDateTime(%d)"
        " ORDER BY received_at DESC LIMIT 8000" % (t0, t1)
    )
    rows = ch(query)
    for row in rows:
        mcc = str(row.get("mcc") or "")
        mnc = str(row.get("mnc") or "")
        row["plmn"] = "%s-%s" % (mcc, mnc) if mcc and mnc else ""
        if row.get("rsrp") == 0:
            row["rsrp"] = None
        _apply_cells(row)
        if row.get("bandwidth_khz") == 0:
            row["bandwidth_khz"] = None
        if row.get("arfcn") == 0:
            row["arfcn"] = None
        lat = row.get("lat")
        lon = row.get("lon")
        located = row.get("loc_status") == "available" and _coord(lat, -90, 90) and _coord(lon, -180, 180)
        row["lat"] = lat if located else None
        row["lon"] = lon if located else None
        row.pop("loc_status", None)
    return rows


def _num(value):
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _channel(cell: dict):
    for key in ("channel", "nrarfcn", "earfcn", "arfcn"):
        value = _num(cell.get(key))
        if value is not None and value > 0:
            return int(value)
    return None


def _cell_plmn(cell: dict) -> str:
    mcc = str(cell.get("mcc") or "")
    mnc = str(cell.get("mnc") or "")
    return "%s-%s" % (mcc, mnc) if mcc and mnc else ""


def _pick_cell(cells: list, rat: str, plmn: str) -> dict | None:
    matched = [cell for cell in cells if str(cell.get("rat") or "").upper() == rat]
    if plmn:
        owned = [cell for cell in matched if _cell_plmn(cell) == plmn]
        unlabeled = [cell for cell in matched if not _cell_plmn(cell)]
        matched = owned or unlabeled
    serving = [cell for cell in matched if cell.get("serving") or cell.get("role") == "serving"]
    chosen = serving or matched
    if not chosen:
        return None
    chosen.sort(key=lambda cell: _num(cell.get("sinr")) is not None, reverse=True)
    return chosen[0]


def _json_list(raw) -> list:
    if not raw or raw == "null":
        return []
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return []
    return parsed if isinstance(parsed, list) else []


def _serving_sinr(subs: list, rat: str):
    """SignalStrength SINR. CellInfo rssnr is unavailable on some Qualcomm phones."""
    for sub in subs:
        if not isinstance(sub, dict):
            continue
        signal = sub.get("serving_signal")
        if not isinstance(signal, dict):
            continue
        if str(signal.get("rat") or "").upper() != rat:
            continue
        value = _num(signal.get("sinr"))
        if value is not None:
            return value
    return None


def _apply_cells(row: dict) -> None:
    raw = row.pop("cells_json", None)
    subs = _json_list(row.pop("subs_json", None))
    cells = [cell for cell in _json_list(raw) if isinstance(cell, dict)]
    for rat, prefix in (("LTE", "lte"), ("NR", "nr")):
        cell = _pick_cell(cells, rat, str(row.get("plmn") or ""))
        cell_sinr = _num(cell.get("sinr")) if cell else None
        row[prefix + "_arfcn"] = _channel(cell) if cell else None
        row[prefix + "_snr"] = cell_sinr if cell_sinr is not None else _serving_sinr(subs, rat)
        row[prefix + "_rsrp"] = _num(cell.get("rsrp")) if cell else None


def _mode(values: list):
    counts: dict = {}
    for value in values:
        if value is None:
            continue
        counts[value] = counts.get(value, 0) + 1
    if not counts:
        return None
    return max(counts, key=counts.get)


def _coord(value, low: float, high: float) -> bool:
    return isinstance(value, (int, float)) and low <= value <= high and not (value == 0)


RES_HEIGHT = {"360p": 360, "720p": 720, "1080p": 1080}
RES_RE = re.compile(r"res=(\d+p)")


def lab_tests(t0: int, t1: int) -> tuple[list[dict], list[dict], str]:
    """Split uploaded tests into speed rows and video rows."""
    path = pathlib.Path(OSM_DB)
    if not path.is_file():
        return [], [], "sqlite missing"
    try:
        conn = sqlite3.connect("file:%s?mode=ro" % path, uri=True, timeout=5)
    except sqlite3.Error as exc:
        return [], [], str(exc)
    try:
        found = conn.execute(
            "SELECT device_id, received_at, payload FROM events"
            " WHERE role = 'tests' AND received_at >= ? AND received_at < ? AND payload LIKE '%mysignal%'"
            " ORDER BY received_at DESC LIMIT 500",
            (t0, t1),
        ).fetchall()
    except sqlite3.Error as exc:
        return [], [], str(exc)
    finally:
        conn.close()
    speeds = []
    videos = []
    for device_id, received_at, payload in found:
        try:
            item = json.loads(payload)
        except json.JSONDecodeError:
            continue
        summary = str(item.get("summary") or "")
        base = {
            "device_id": device_id,
            "ts": item.get("ts_ms") or received_at * 1000,
            "server": item.get("server") or "",
        }
        if summary.startswith("mysignal-video"):
            match = RES_RE.search(summary)
            videos.append(
                {
                    **base,
                    "load_ms": item.get("latency_ms"),
                    "buffering_pct": item.get("loss_pct"),
                    "resolution": match.group(1) if match else "",
                }
            )
            continue
        if summary != "mysignal":
            continue
        down = item.get("download_bps")
        up = item.get("upload_bps")
        speeds.append(
            {
                **base,
                "ping_ms": item.get("latency_ms"),
                "jitter_ms": item.get("jitter_ms"),
                "download_mbps": None if down is None else round(down / 1_000_000, 2),
                "upload_mbps": None if up is None else round(up / 1_000_000, 2),
            }
        )
    return speeds, videos, ""


RADIUS_M = 150.0
OPS = {
    "indosat": {"label": "Indosat", "color": "#f97316", "badge": "op-indosat"},
    "xl": {"label": "XL", "color": "#3b82f6", "badge": "op-xl"},
    "telkomsel": {"label": "Telkomsel", "color": "#e11d48", "badge": "op-tsel"},
}


def _hav_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * radius * math.asin(min(1.0, math.sqrt(h)))


def _median(values: list) -> float | None:
    nums = sorted(value for value in values if isinstance(value, (int, float)))
    if not nums:
        return None
    mid = len(nums) // 2
    if len(nums) % 2:
        return float(nums[mid])
    return (nums[mid - 1] + nums[mid]) / 2.0


def _operator(row: dict) -> str | None:
    plmn = str(row.get("plmn") or "")
    carrier = str(row.get("carrier") or "").upper()
    if plmn == "510-01" or "INDOSAT" in carrier or carrier in {"ISAT", "IOH"}:
        return "indosat"
    if plmn == "510-11" or carrier.startswith("XL"):
        return "xl"
    if plmn == "510-10" or "TELKOMSEL" in carrier or carrier in {"TSEL"}:
        return "telkomsel"
    return None


def _cluster(points: list[dict], radius_m: float) -> list[list[dict]]:
    """Group samples whose coordinates fall in the same radius_m cell."""
    groups: dict[tuple[int, int], list[dict]] = {}
    lat_step = radius_m / 111_320.0
    for point in points:
        lon_scale = max(0.2, math.cos(math.radians(point["lat"])))
        lon_step = radius_m / (111_320.0 * lon_scale)
        key = (round(point["lat"] / lat_step), round(point["lon"] / lon_step))
        groups.setdefault(key, []).append(point)
    return list(groups.values())


def _nr_value(row: dict) -> int | None:
    try:
        return int(row.get("nr_state"))
    except (TypeError, ValueError):
        return None


def _radio_stat(rows: list[dict]) -> dict | None:
    focused = list(rows)
    if not focused:
        return None
    lte_rows = [row for row in focused if row.get("lte_snr") is not None or row.get("lte_arfcn")]
    nr_rows = [row for row in focused if row.get("nr_snr") is not None or row.get("nr_arfcn")]
    latest_lte = max(lte_rows, key=lambda row: row.get("ts") or 0) if lte_rows else None
    latest_nr = max(nr_rows, key=lambda row: row.get("ts") or 0) if nr_rows else None
    lte_snr = None if latest_lte is None else latest_lte.get("lte_snr")
    nr_snr = None if latest_nr is None else latest_nr.get("nr_snr")
    score = nr_snr if nr_snr is not None else lte_snr
    if score is None:
        return None
    n2 = sum(1 for row in focused if _nr_value(row) == 2)
    n3 = sum(1 for row in focused if _nr_value(row) == 3)
    return {
        "n": len(focused),
        "n2": n2,
        "n3": n3,
        "lte_arfcn": None if latest_lte is None else latest_lte.get("lte_arfcn"),
        "nr_arfcn": None if latest_nr is None else latest_nr.get("nr_arfcn"),
        "lte_snr": None if lte_snr is None else round(lte_snr, 1),
        "nr_snr": None if nr_snr is None else round(nr_snr, 1),
        "lte_ts": None if latest_lte is None else latest_lte.get("ts"),
        "nr_ts": None if latest_nr is None else latest_nr.get("ts"),
        "ts": max(
            (latest_lte.get("ts") if latest_lte else 0) or 0,
            (latest_nr.get("ts") if latest_nr else 0) or 0,
        ) or None,
        "score": round(score, 1),
    }


def _video_stat(rows: list[dict]) -> dict | None:
    if not rows:
        return None
    latest = max(rows, key=lambda row: row.get("ts") or 0)
    resolution = str(latest.get("resolution") or "")
    height = RES_HEIGHT.get(resolution)
    if height is None:
        return None
    buffering = latest.get("buffering_pct")
    load = latest.get("load_ms")
    penalty = 0.0 if buffering is None else float(buffering) / 100.0
    return {
        "n": len(rows),
        "load_ms": None if load is None else int(round(float(load))),
        "resolution": resolution,
        "buffering_pct": None if buffering is None else round(float(buffering), 1),
        "ts": latest.get("ts"),
        "score": height - penalty,
    }


def _speed_stat(rows: list[dict]) -> dict | None:
    if not rows:
        return None
    latest = max(rows, key=lambda row: row.get("ts") or 0)
    download = latest.get("download_mbps")
    upload = latest.get("upload_mbps")
    ping = latest.get("ping_ms")
    return {
        "n": len(rows),
        "dl": None if download is None else round(download, 2),
        "ul": None if upload is None else round(upload, 2),
        "ping": None if ping is None else round(ping, 0),
        "ts": latest.get("ts"),
        "score": None if download is None else round(min(10.0, download / 3.0), 1),
    }


def _rank(stats: dict[str, dict | None], score_key: str) -> list[dict]:
    ranked = []
    for key, stat in stats.items():
        if not stat or stat.get(score_key) is None:
            continue
        meta = OPS[key]
        ranked.append({
            "op": meta["label"],
            "key": key,
            "badgeClass": meta["badge"],
            "color": meta["color"],
            "score": stat[score_key],
            **stat,
        })
    ranked.sort(key=lambda item: (-item["score"], -item["n"]))
    for index, item in enumerate(ranked, start=1):
        item["rank"] = index
    return ranked


def _attach_tests(tests: list[dict], points: list[dict]) -> None:
    by_device: dict[str, list[dict]] = {}
    for point in points:
        by_device.setdefault(point["device_id"], []).append(point)
    for test in tests:
        nearest = None
        gap = None
        ts = test["ts"] / 1000.0 if test["ts"] and test["ts"] > 10**12 else test["ts"]
        for point in by_device.get(test["device_id"], []):
            if point.get("lat") is None or point.get("ts") is None:
                continue
            delta = abs(point["ts"] - (ts or 0))
            if delta <= 900 and (gap is None or delta < gap):
                nearest = point
                gap = delta
        if nearest is None:
            continue
        test["lat"] = nearest["lat"]
        test["lon"] = nearest["lon"]
        test["plmn"] = nearest.get("plmn")
        test["carrier"] = nearest.get("carrier")
        test["cluster"] = nearest.get("cluster")


def _keep_owned_nr(rows: list[dict]) -> None:
    """NR cells have no MCC/MNC. Give each ARFCN to the PLMN that reports it most."""
    counts: dict[int, dict[str, int]] = {}
    for row in rows:
        plmn = row.get("plmn")
        arfcn = row.get("nr_arfcn")
        if not plmn or not arfcn:
            continue
        counts.setdefault(arfcn, {})
        counts[arfcn][plmn] = counts[arfcn].get(plmn, 0) + 1
    owner = {}
    for arfcn, by_plmn in counts.items():
        winner = max(by_plmn, key=by_plmn.get)
        if by_plmn[winner] >= sum(by_plmn.values()) * 0.6:
            owner[arfcn] = winner
    for row in rows:
        arfcn = row.get("nr_arfcn")
        if arfcn and owner.get(arfcn) != row.get("plmn"):
            row["nr_arfcn"] = None
            row["nr_snr"] = None


def pois_from(radio_rows: list[dict], tests: list[dict], videos: list[dict], radius_m: float) -> list[dict]:
    _keep_owned_nr(radio_rows)
    located = [row for row in radio_rows if row.get("lat") is not None and row.get("lon") is not None]
    groups = _cluster(located, radius_m)
    for index, group in enumerate(groups):
        for row in group:
            row["cluster"] = index
    _attach_tests(tests, located)
    _attach_tests(videos, located)
    pois = []
    for index, group in enumerate(groups):
        by_op: dict[str, list[dict]] = {key: [] for key in OPS}
        for row in group:
            op = _operator(row)
            if op:
                by_op[op].append(row)
        radio_stats = {key: _radio_stat(rows) for key, rows in by_op.items()}
        speed_rows: dict[str, list[dict]] = {key: [] for key in OPS}
        for test in tests:
            if test.get("cluster") != index:
                continue
            op = _operator(test)
            if op:
                speed_rows[op].append(test)
        speed_stats = {key: _speed_stat(rows) for key, rows in speed_rows.items()}
        video_rows: dict[str, list[dict]] = {key: [] for key in OPS}
        for test in videos:
            if test.get("cluster") != index:
                continue
            op = _operator(test)
            if op:
                video_rows[op].append(test)
        video_stats = {key: _video_stat(rows) for key, rows in video_rows.items()}
        radio_rank = _rank(radio_stats, "score")
        speed_rank = _rank(speed_stats, "score")
        video_rank = _rank(video_stats, "score")
        winner = radio_rank[0] if radio_rank else None
        lat = sum(row["lat"] for row in group) / len(group)
        lon = sum(row["lon"] for row in group) / len(group)
        latest = max(row.get("ts") or 0 for row in group)
        pois.append({
            "id": index + 1,
            "name": "POI %.3f, %.3f" % (lat, lon),
            "lat": round(lat, 6),
            "lng": round(lon, 6),
            "n": len(group),
            "last_ts": latest,
            "best": winner["op"] if winner else "—",
            "badgeClass": winner["badgeClass"] if winner else "op-other",
            "color": winner["color"] if winner else "#9ca3af",
            "score": winner["score"] if winner else None,
            "best_speed": speed_rank[0]["op"] if speed_rank else "—",
            "speed_score": speed_rank[0]["score"] if speed_rank else None,
            "speed_badge": speed_rank[0]["badgeClass"] if speed_rank else "op-other",
            "speed_color": speed_rank[0]["color"] if speed_rank else "#9ca3af",
            "best_video": video_rank[0]["op"] if video_rank else "—",
            "video_score": video_rank[0]["score"] if video_rank else None,
            "video_badge": video_rank[0]["badgeClass"] if video_rank else "op-other",
            "video_color": video_rank[0]["color"] if video_rank else "#9ca3af",
            "radio": radio_stats,
            "speed": speed_stats,
            "video": video_stats,
            "ranking": {"radio": radio_rank, "speed": speed_rank, "video": video_rank},
        })
    pois.sort(key=lambda item: (-(item["score"] or -1), -item["n"]))
    for index, poi in enumerate(pois, start=1):
        poi["rank_radio"] = index
    by_speed = sorted(pois, key=lambda item: (-(item["speed_score"] if item["speed_score"] is not None else -1), -item["n"]))
    for index, poi in enumerate(by_speed, start=1):
        poi["rank_speed"] = index if poi["speed_score"] is not None else None
    by_video = sorted(pois, key=lambda item: (-(item["video_score"] if item["video_score"] is not None else -1), -item["n"]))
    for index, poi in enumerate(by_video, start=1):
        poi["rank_video"] = index if poi["video_score"] is not None else None
    return pois


def crowd(start: str, end: str, radius_m: float) -> dict:
    t0, t1, start_day, end_day = period(start, end)
    radio_rows = radio(t0, t1)
    tests, videos, note = lab_tests(t0, t1)
    places = pois_from(radio_rows, tests, videos, radius_m)
    devices = {row["device_id"] for row in radio_rows} | {row["device_id"] for row in tests} | {row["device_id"] for row in videos}
    return {
        "start": start_day,
        "end": end_day,
        "radius_m": radius_m,
        "devices": len(devices),
        "radio_count": len(radio_rows),
        "speedtest_count": len(tests),
        "video_count": len(videos),
        "poi_count": len(places),
        "pois": places,
        "note": note,
    }


def _hmac_secrets() -> tuple[str, bytes]:
    values = {}
    with open(HMAC_FILE, "r", encoding="utf-8") as handle:
        for line in handle:
            if "=" not in line:
                continue
            key, value = line.strip().split("=", 1)
            values[key] = value
    key_hex = values.get("key_hex") or ""
    return values.get("key_id") or "", bytes.fromhex(key_hex)


def _verify_hmac(path: str, body: bytes, headers) -> None:
    key_id, key = _hmac_secrets()
    if not key_id or not key:
        raise PermissionError("hmac_unconfigured")
    sent_id = headers.get("X-Osm-Key-Id", "")
    timestamp = headers.get("X-Osm-Timestamp", "")
    nonce = headers.get("X-Osm-Nonce", "")
    signature = headers.get("X-Osm-Signature", "")
    if sent_id != key_id or not timestamp.isdigit() or not nonce:
        raise PermissionError("bad_headers")
    if abs(int(time.time()) - int(timestamp)) > 300:
        raise PermissionError("timestamp_skew")
    digest = hashlib.sha256(body).hexdigest()
    message = "POST\n%s\n%s\n%s\n%s" % (path, timestamp, nonce, digest)
    expected = hmac.new(key, message.encode("ascii"), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signature.lower()):
        raise PermissionError("bad_signature")


def _clip(value, limit: int) -> str:
    return str(value or "").replace("\x00", "")[:limit]


def _bounded(value, low: float, high: float):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number != number or number < low or number > high:
        return None
    return number


def _complaint_brief(raw: dict) -> dict:
    speed = raw.get("speed") if isinstance(raw.get("speed"), dict) else {}
    video = raw.get("video") if isinstance(raw.get("video"), dict) else {}
    return {
        "transport": _clip(raw.get("transport"), 16),
        "plmn": _clip(raw.get("plmn"), 16),
        "slot": _clip(raw.get("slot"), 24),
        "carrier": _clip(raw.get("carrier"), 64),
        "mention": _clip(raw.get("mention"), 32),
        "radio": _clip(raw.get("radio"), 240),
        "location": _clip(raw.get("location"), 48),
        "when": _clip(raw.get("when"), 32),
        "speed": {
            "download_mbps": _bounded(speed.get("download_mbps"), 0, 10000),
            "upload_mbps": _bounded(speed.get("upload_mbps"), 0, 10000),
            "ping_ms": _bounded(speed.get("ping_ms"), 0, 600000),
            "transport": _clip(speed.get("transport"), 16),
        },
        "video": {
            "load_ms": _bounded(video.get("load_ms"), 0, 600000),
            "resolution": _clip(video.get("resolution"), 16),
            "buffering_pct": _bounded(video.get("buffering_pct"), 0, 100),
            "transport": _clip(video.get("transport"), 16),
        },
    }


def _local_block(brief: dict) -> str:
    speed = brief["speed"]["transport"] == "cellular" and brief["speed"]["download_mbps"] is not None
    video = brief["video"]["transport"] == "cellular" and brief["video"]["resolution"]
    if not speed and not video:
        return "Belum ada tes seluler. Jalankan speed atau video lewat data seluler. Wi-Fi boleh dipakai hanya untuk mengirim."
    return ""


def create_complaint(raw: dict) -> dict:
    brief = _complaint_brief(raw)
    blocked = _local_block(brief)
    if blocked:
        return {"eligible": False, "sentence": "", "summary": blocked, "url": ""}
    import measure_otel
    judged = measure_otel.judge_complaint(brief)
    complaint_id = secrets.token_hex(8)
    record = {
        "id": complaint_id,
        "created": int(time.time()),
        "brief": brief,
        "eligible": bool(judged["eligible"]),
        "sentence": judged["sentence"],
        "summary": judged["summary"],
        "model": judged.get("model") or "",
    }
    COMPLAINT_DIR.mkdir(parents=True, exist_ok=True)
    path = COMPLAINT_DIR / ("%s.json" % complaint_id)
    path.write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
    url = "%s/crowd/c/%s" % (PUBLIC_BASE, complaint_id)
    return {
        "eligible": record["eligible"],
        "sentence": record["sentence"],
        "summary": record["summary"],
        "url": url,
    }


def complaint_page(complaint_id: str) -> bytes | None:
    if not re.fullmatch(r"[0-9a-f]{16}", complaint_id):
        return None
    path = COMPLAINT_DIR / ("%s.json" % complaint_id)
    if not path.is_file():
        return None
    record = json.loads(path.read_text(encoding="utf-8"))
    brief = record.get("brief") or {}
    speed = brief.get("speed") or {}
    video = brief.get("video") or {}
    rows = [
        ("Transport", brief.get("transport")),
        ("Operator", "%s %s" % (brief.get("carrier") or "", brief.get("slot") or "")),
        ("Radio", brief.get("radio")),
        ("Waktu", brief.get("when")),
        ("Lokasi", brief.get("location")),
        ("Download", speed.get("download_mbps")),
        ("Upload", speed.get("upload_mbps")),
        ("Ping", speed.get("ping_ms")),
        ("Transport tes speed", speed.get("transport")),
        ("Resolusi", video.get("resolution")),
        ("Load", video.get("load_ms")),
        ("Buffering", video.get("buffering_pct")),
        ("Transport tes video", video.get("transport")),
    ]
    body = "".join(
        "<tr><th>%s</th><td>%s</td></tr>" % (html.escape(label), html.escape("" if value is None else str(value)))
        for label, value in rows
    )
    page = """<!DOCTYPE html>
<html lang="id"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Detail komplain</title>
<style>
body{font-family:Inter,Segoe UI,sans-serif;margin:0;background:#f5f7fa;color:#1a1a2e}
main{max-width:720px;margin:24px auto;background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px}
h1{font-size:20px} p{line-height:1.5} table{width:100%%;border-collapse:collapse;font-size:14px}
th,td{text-align:left;padding:8px;border-top:1px solid #f3f4f6;vertical-align:top} th{width:180px;color:#6b7280;font-weight:600}
</style></head><body><main>
<h1>%s</h1>
<p>%s</p>
<table>%s</table>
</main></body></html>""" % (
        html.escape("Memenuhi syarat komplain" if record.get("eligible") else "Tidak memenuhi syarat"),
        html.escape(record.get("summary") or "").replace("\n", "<br>"),
        body,
    )
    return page.encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path.rstrip("/") or "/"
        if path in ("/crowd", "/crowd/index.html"):
            body = PAGE.read_bytes()
            self._send(200, body, "text/html; charset=utf-8")
            return
        if path == "/crowd/api":
            params = urllib.parse.parse_qs(parsed.query)
            start = (params.get("start", [""])[0] or "").strip()
            end = (params.get("end", [""])[0] or "").strip()
            try:
                radius_m = int(params.get("radius", ["10"])[0])
            except ValueError:
                radius_m = 10
            if radius_m not in (10, 25, 50, 150):
                radius_m = 10
            try:
                self._json(crowd(start, end, radius_m))
            except Exception as exc:  # noqa: BLE001
                self._json({"error": str(exc)[:300]}, 503)
            return
        if path == "/health":
            self._json({"status": "ok"})
            return
        if path.startswith("/crowd/c/"):
            page = complaint_page(path.rsplit("/", 1)[-1])
            if page is None:
                self._json({"error": "not_found"}, 404)
                return
            self._send(200, page, "text/html; charset=utf-8")
            return
        self._json({"error": "not_found"}, 404)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path.rstrip("/") or "/"
        length = int(self.headers.get("Content-Length", "0") or "0")
        if path != "/crowd/complaint" or length <= 0 or length > 12000:
            self._json({"error": "not_found"}, 404)
            return
        body = self.rfile.read(length)
        try:
            _verify_hmac(path, body, self.headers)
            raw = json.loads(body.decode("utf-8"))
            if not isinstance(raw, dict):
                raise ValueError("body")
            self._json(create_complaint(raw))
        except PermissionError as exc:
            self._json({"error": str(exc)}, 401)
        except Exception as exc:  # noqa: BLE001
            self._json({"error": str(exc)[:300]}, 503)

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
    server = ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("CROWD_PORT", "8773"))), Handler)
    print("crowd dashboard on http://127.0.0.1:%s/crowd/" % server.server_address[1], flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
