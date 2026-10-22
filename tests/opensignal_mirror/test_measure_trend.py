"""Trend panel: one series per SIM slot + serving-ARFCN band."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "opensignal_mirror"))

import measure_dashboard as dashboard  # noqa: E402


def _row(t, slot, carrier, arfcn, rsrp, sinr=5, samples=10):
    return {
        "t": t,
        "sim_slot": slot,
        "carrier_name": carrier,
        "rsrp_avg": rsrp,
        "sinr_avg": sinr,
        "arfcn_mode": arfcn,
        "samples": samples,
        "top_count": samples,
    }


def test_trend_groups_per_sim_slot_with_handover_points():
    rows = [
        _row(1000, 0, "INDOSAT", 1625, -99),
        _row(1030, 0, "INDOSAT", 200, -100),
        _row(1060, 0, "INDOSAT", 200, -100),
        _row(1090, 0, "INDOSAT", 1475, -94),
        _row(1000, 1, "XL", 1313, -71),
        _row(1030, 1, "XL", 1313, -72),
        _row(1060, 1, "XL", 325, -70),
    ]
    original = dashboard.rows
    dashboard.rows = lambda query: rows
    try:
        result = dashboard.trend(15, "")
    finally:
        dashboard.rows = original

    assert result["bucket_s"] == 30
    # fixed order: slot 1 on top, slot 0 below
    assert [slot["label"] for slot in result["slots"]] == ["slot 1", "slot 0"]
    assert result["slots"][0]["carrier"] == "XL"
    assert result["slots"][1]["carrier"] == "INDOSAT"
    assert result["keys"] == [200, 325, 1313, 1475, 1625]
    assert len(result["points"]) == 7
    handovers = [(p["t"], p["slot"], p["arfcn"]) for p in result["points"] if p["slot"] == 0]
    assert handovers == [(1000, 0, 1625), (1030, 0, 200), (1060, 0, 200), (1090, 0, 1475)]


def test_trend_query_filters_measure_source_and_serving_cells():
    queries = []

    def fake_rows(query):
        queries.append(query)
        return []

    original = dashboard.rows
    dashboard.rows = fake_rows
    try:
        result = dashboard.trend(60, "dev1")
    finally:
        dashboard.rows = original

    query = queries[0]
    assert "source = 'mirror'" in query
    assert "serving = 1" in query
    assert "GROUP BY t, sim_slot" in query
    assert "device_id = 'dev1'" in query
    assert "INTERVAL 60 SECOND" in query
    assert result == {"bucket_s": 60, "slots": [], "keys": [], "points": []}


def test_trend_drops_arfcn_outside_the_limit():
    rows = [
        _row(1000 + index * 30, 0, "INDOSAT", index, -100 + index, samples=10 * (index + 1))
        for index in range(12)
    ]
    original = dashboard.rows
    dashboard.rows = lambda query: rows
    try:
        result = dashboard.trend(15, "")
    finally:
        dashboard.rows = original

    assert len(result["keys"]) == 8  # default limit
    assert result["keys"] == sorted(result["keys"]), "ARFCN order must be stable for colour mapping"
    assert result["keys"] == [4, 5, 6, 7, 8, 9, 10, 11]  # top-8 by samples, then ascending
    assert all(point["arfcn"] in result["keys"] for point in result["points"])


def test_trend_handles_slot_without_carrier():
    rows = [_row(1000, None, "", 200, -97)]
    original = dashboard.rows
    dashboard.rows = lambda query: rows
    try:
        result = dashboard.trend(15, "")
    finally:
        dashboard.rows = original

    assert result["slots"] == [{"label": "slot ?", "slot": None, "carrier": None, "segments": [
        {"arfcn": 200, "start": 1000, "end": 1000, "duration_s": 30, "buckets": 1, "samples": 10, "rsrp": -97.0}
    ]}]
    assert result["points"][0]["carrier"] is None


def test_trend_segments_form_a_dwell_timeline_per_slot():
    rows = [
        _row(1000, 0, "INDOSAT", 1625, -99),
        _row(1030, 0, "INDOSAT", 200, -100),
        _row(1060, 0, "INDOSAT", 200, -100),
        _row(1090, 0, "INDOSAT", 1475, -96),
        _row(1120, 0, "INDOSAT", 1475, -94),
    ]
    original = dashboard.rows
    dashboard.rows = lambda query: rows
    try:
        result = dashboard.trend(15, "")
    finally:
        dashboard.rows = original

    segments = result["slots"][0]["segments"]
    assert [(s["arfcn"], s["start"], s["end"], s["duration_s"]) for s in segments] == [
        (1625, 1000, 1000, 30),
        (200, 1030, 1060, 60),
        (1475, 1090, 1120, 60),
    ]
    assert [s["buckets"] for s in segments] == [1, 2, 2]
    assert segments[1]["rsrp"] == -100.0


def test_trend_segments_fold_single_bucket_blip_between_same_arfcn():
    rows = [
        _row(1000, 0, "INDOSAT", 200, -99),
        _row(1030, 0, "INDOSAT", 200, -99),
        _row(1060, 0, "INDOSAT", 1475, -96),  # 3-second glitch
        _row(1090, 0, "INDOSAT", 200, -99),
        _row(1120, 0, "INDOSAT", 200, -99),
    ]
    original = dashboard.rows
    dashboard.rows = lambda query: rows
    try:
        result = dashboard.trend(15, "")
    finally:
        dashboard.rows = original

    segments = result["slots"][0]["segments"]
    assert len(segments) == 1, "a one-bucket blip must not create a duplicate ARFCN segment"
    assert segments[0]["arfcn"] == 200
    assert segments[0]["buckets"] == 5
    assert segments[0]["start"] == 1000
    assert segments[0]["end"] == 1120
    assert segments[0]["duration_s"] == 150


def test_trend_segments_keep_real_handovers_and_ping_pong():
    rows = [
        _row(1000, 0, "INDOSAT", 1475, -96),
        _row(1030, 0, "INDOSAT", 1475, -96),
        _row(1060, 0, "INDOSAT", 200, -100),
        _row(1090, 0, "INDOSAT", 200, -100),
        _row(1120, 0, "INDOSAT", 200, -100),
        _row(1150, 0, "INDOSAT", 1475, -96),
        _row(1180, 0, "INDOSAT", 1475, -96),
    ]
    original = dashboard.rows
    dashboard.rows = lambda query: rows
    try:
        result = dashboard.trend(15, "")
    finally:
        dashboard.rows = original

    segments = result["slots"][0]["segments"]
    assert [s["arfcn"] for s in segments] == [1475, 200, 1475]
    assert [s["buckets"] for s in segments] == [2, 3, 2]
    assert segments[1]["duration_s"] == 90


def test_trend_for_24h_uses_minute_buckets():
    original = dashboard.rows
    dashboard.rows = lambda query: [_row(1000, 0, "XL", 325, -70, samples=25)]
    try:
        result = dashboard.trend(1440, "")
    finally:
        dashboard.rows = original
    assert result["bucket_s"] == 60
