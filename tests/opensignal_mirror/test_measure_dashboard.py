"""Structural tests for the measurements dashboard API (no ClickHouse needed)."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "opensignal_mirror"))

import measure_dashboard as dashboard  # noqa: E402


def test_cells_query_uses_measure_source_only():
    queries = []

    def fake_rows(query):
        queries.append(query)
        return []

    original = dashboard.rows
    dashboard.rows = fake_rows
    try:
        dashboard.cells(15, "")
    finally:
        dashboard.rows = original

    assert len(queries) == 1
    query = queries[0]
    assert "source = 'mirror'" in query
    assert "opensignal.radio_cells" in query
    assert "GROUP BY device_id, rat, arfcn, pci" in query
    assert "source, " not in query.split("GROUP BY", 1)[1]


def test_cells_maps_attributed_row_and_keeps_unique_keys():
    """ClickHouse already collapses the duplicate rows (GROUP BY ... pci);
    the API mapping must expose the attributed SIM and a unique cell key."""
    grouped = [
        {  # served cell, SIM attribution resolved by argMax
            "device_id": "dev1",
            "rat": "LTE",
            "arfcn": 1313,
            "pci": 493,
            "ci_key": "",
            "slot_last": 1,
            "sub_last": 3,
            "carrier_last": "XL",
            "mcc_last": "510",
            "mnc_last": "11",
            "samples": 270,
            "serving_samples": 270,
            "rsrp_avg": -72.9,
            "rsrp_min": -76,
            "rsrp_max": -69,
            "rsrq_avg": -8.7,
            "sinr_avg": 6.2,
            "last_seen": 1700000100,
        },
        {  # neighbour: no PLMN / SIM attribution
            "device_id": "dev1",
            "rat": "LTE",
            "arfcn": 200,
            "pci": 409,
            "ci_key": "",
            "slot_last": None,
            "sub_last": None,
            "carrier_last": "",
            "mcc_last": None,
            "mnc_last": None,
            "samples": 52,
            "serving_samples": 0,
            "rsrp_avg": -97.0,
            "rsrp_min": -98,
            "rsrp_max": -96,
            "rsrq_avg": None,
            "sinr_avg": None,
            "last_seen": 1700000000,
        },
    ]

    original = dashboard.rows
    dashboard.rows = lambda query: grouped
    try:
        result = dashboard.cells(15, "")
    finally:
        dashboard.rows = original

    keys = [(item["device_id"], item["rat"], item["arfcn"], item["pci"]) for item in result]
    assert len(keys) == len(set(keys)), "one row per physical cell"

    served = next(item for item in result if item["arfcn"] == 1313)
    assert served["sim_slot"] == 1
    assert served["sub_id"] == 3
    assert served["carrier"] == "XL"
    assert served["plmn"] == "510-11"
    assert served["pci"] == 493
    assert served["serving_ratio"] == 1.0

    neighbour = next(item for item in result if item["arfcn"] == 200)
    assert neighbour["serving_samples"] == 0
    assert neighbour["serving_ratio"] == 0.0
    assert neighbour["sim_slot"] is None
    assert neighbour["plmn"] is None


def test_cells_serving_ratio_is_capped():
    row = {
        "device_id": "dev1",
        "rat": "LTE",
        "arfcn": 325,
        "pci": 484,
        "ci_key": "",
        "slot_last": 1,
        "sub_last": 3,
        "carrier_last": "XL",
        "mcc_last": "510",
        "mnc_last": "11",
        "samples": 10,
        "serving_samples": 30,  # cross-window artefact must never exceed 100%
        "rsrp_avg": -67,
        "rsrp_min": -68,
        "rsrp_max": -66,
        "rsrq_avg": -9,
        "sinr_avg": 16,
        "last_seen": 1700000000,
    }
    original = dashboard.rows
    dashboard.rows = lambda query: [row]
    try:
        result = dashboard.cells(15, "")
    finally:
        dashboard.rows = original
    assert result[0]["serving_ratio"] == 1.0


def test_cells_keeps_null_arfcn_rows_separate_by_ci():
    base = {
        "device_id": "dev1",
        "rat": "LTE",
        "arfcn": None,
        "pci": None,
        "slot_last": None,
        "sub_last": None,
        "carrier_last": "",
        "mcc_last": None,
        "mnc_last": None,
        "samples": 10,
        "serving_samples": 0,
        "rsrp_avg": -90,
        "rsrp_min": -91,
        "rsrp_max": -89,
        "rsrq_avg": None,
        "sinr_avg": None,
        "last_seen": 1700000000,
    }
    rows = [dict(base, ci_key="ci-a"), dict(base, ci_key="ci-b")]
    original = dashboard.rows
    dashboard.rows = lambda query: rows
    try:
        result = dashboard.cells(15, "")
    finally:
        dashboard.rows = original
    assert len(result) == 2


def test_serving_summary_reports_time_share_not_parallel_carriers():
    """One SIM can own several serving rows in a 15-min window (handover and
    intra-frequency PCI change). Those rows split ~100% of the time, and every
    snapshot still reports exactly one serving cell."""
    cells = [
        {"sim_slot": 0, "carrier": "INDOSAT", "arfcn": 1475, "pci": 409, "serving_samples": 151,
         "samples": 289, "rsrp": -95},
        {"sim_slot": 0, "carrier": "INDOSAT", "arfcn": 200, "pci": 409, "serving_samples": 126,
         "samples": 289, "rsrp": -97},
        {"sim_slot": 0, "carrier": "INDOSAT", "arfcn": 1475, "pci": 394, "serving_samples": 11,
         "samples": 289, "rsrp": -94},
        {"sim_slot": 1, "carrier": "XL", "arfcn": 1313, "pci": 493, "serving_samples": 289,
         "samples": 289, "rsrp": -69},
        {"sim_slot": None, "carrier": None, "arfcn": 325, "pci": 310, "serving_samples": 0,
         "samples": 289, "rsrp": -82},
    ]
    counts = [{"event_id": "e%d" % i, "sim_slot": slot, "serving_cells": 1}
              for i in range(289) for slot in (0, 1)]

    original = dashboard.rows
    dashboard.rows = lambda query: counts
    try:
        summary = dashboard.serving_summary(15, "", cells)
    finally:
        dashboard.rows = original

    assert summary["per_snapshot_max"] == 1
    assert summary["parallel_carriers"] is False
    assert summary["snapshots"] == len(counts)

    indosat = next(item for item in summary["by_slot"] if item["slot"] == 0)
    shares = sum(item["sample_share"] for item in indosat["items"])
    assert 0.99 <= shares <= 1.01, "serving shares partition the window"
    assert [item["arfcn"] for item in indosat["items"]] == [1475, 200, 1475]
    assert indosat["pci_changes"] == 1, "1475 was served by PCI 409 and PCI 394"
    assert all("slot" in item for item in summary["by_slot"])


def test_serving_summary_flags_real_parallel_carriers():
    """A snapshot with two serving cells (carrier aggregation) must be surfaced."""
    counts = [{"event_id": "e1", "sim_slot": 0, "serving_cells": 2}]
    cells = [{"sim_slot": 0, "carrier": "INDOSAT", "arfcn": 1475, "pci": 409,
              "serving_samples": 1, "samples": 1, "rsrp": -95}]
    original = dashboard.rows
    dashboard.rows = lambda query: counts
    try:
        summary = dashboard.serving_summary(15, "", cells)
    finally:
        dashboard.rows = original
    assert summary["per_snapshot_max"] == 2
    assert summary["parallel_carriers"] is True


def test_serving_summary_survives_empty_window():
    original = dashboard.rows
    dashboard.rows = lambda query: []
    try:
        summary = dashboard.serving_summary(15, "", [])
    finally:
        dashboard.rows = original
    assert summary["per_snapshot_max"] == 0
    assert summary["parallel_carriers"] is False
    assert summary["by_slot"] == []


def test_attach_serving_shares_normalises_brief_cells():
    """A cell seen in only a few snapshots must report its true window share."""
    cells = [
        {"sim_slot": 0, "arfcn": 200, "pci": 409, "serving_ratio": 0.493},
        {"sim_slot": 0, "arfcn": 1625, "pci": 128, "serving_ratio": 0.4},
        {"sim_slot": None, "arfcn": 325, "pci": 310, "serving_ratio": 0.0},
    ]
    summary = {
        "by_slot": [
            {"slot": 0, "items": [
                {"arfcn": 200, "pci": 409, "sample_share": 0.959},
                {"arfcn": 1625, "pci": 128, "sample_share": 0.041},
            ]},
        ]
    }
    dashboard.attach_serving_shares(cells, summary)
    assert cells[0]["serving_share"] == 0.959
    assert cells[1]["serving_share"] == 0.041, "hit-rate must not leak into the window share"
    assert cells[2]["serving_share"] == 0.0


def test_otel_interpretation_is_cached_and_refresh_bypasses_cache(monkeypatch):
    calls = []

    monkeypatch.setattr(dashboard, "cached", lambda minutes, device: {"minutes": minutes, "now": 1700000000})
    monkeypatch.setattr(dashboard.measure_otel, "build_brief", lambda payload: {"window": {"minutes": 15}})
    monkeypatch.setattr(dashboard.measure_otel, "facts", lambda brief: ["2 SIM"])

    def fake_interpret(brief):
        calls.append(brief)
        return {"text": "**Ringkasan** hasil", "model": "otel-test", "usage": {"total_tokens": 12}}

    monkeypatch.setattr(dashboard.measure_otel, "interpret", fake_interpret)
    dashboard._otel_cache.clear()
    try:
        first = dashboard.otel(15, "")
        second = dashboard.otel(15, "")
        forced = dashboard.otel(15, "", force=True)
        other = dashboard.otel(60, "")
    finally:
        dashboard._otel_cache.clear()

    assert first["cached"] is False
    assert second["cached"] is True
    assert forced["cached"] is False
    assert other["cached"] is False
    assert len(calls) == 3, "a 5 s page refresh must reuse one model call"
    assert first["text"].startswith("**Ringkasan**")
    assert first["facts"] == ["2 SIM"]


def test_otel_cache_key_tracks_device_set(monkeypatch):
    """A phone that joins the window must trigger a fresh reading."""
    devices = [{"device_id": "dev-a"}]
    calls = []
    monkeypatch.setattr(
        dashboard, "cached",
        lambda minutes, device: {"minutes": minutes, "now": 1, "devices": list(devices)},
    )
    monkeypatch.setattr(dashboard.measure_otel, "build_brief", lambda payload: {})
    monkeypatch.setattr(dashboard.measure_otel, "facts", lambda brief: [])

    def fake_interpret(brief):
        calls.append(1)
        return {"text": "ok", "model": "m", "usage": {}}

    monkeypatch.setattr(dashboard.measure_otel, "interpret", fake_interpret)
    dashboard._otel_cache.clear()
    try:
        dashboard.otel(15, "")
        dashboard.otel(15, "")
        assert len(calls) == 1
        devices.append({"device_id": "dev-b"})
        result = dashboard.otel(15, "")
    finally:
        dashboard._otel_cache.clear()
    assert len(calls) == 2
    assert result["cached"] is False


def test_otel_reports_missing_key(monkeypatch):
    monkeypatch.setattr(dashboard, "cached", lambda minutes, device: {"minutes": minutes, "now": 1})
    monkeypatch.setattr(dashboard.measure_otel, "build_brief", lambda payload: {})

    def boom(brief):
        raise RuntimeError("FEATHERLESS_API_KEY tidak ditemukan")

    monkeypatch.setattr(dashboard.measure_otel, "interpret", boom)
    dashboard._otel_cache.clear()
    with pytest.raises(RuntimeError, match="FEATHERLESS_API_KEY"):
        dashboard.otel(15, "")
    assert not dashboard._otel_cache, "a failed call must not be cached"


def test_nr_cells_query_is_measure_nr_only():
    queries = []
    original = dashboard.rows

    def fake_rows(query):
        queries.append(query)
        return []

    dashboard.rows = fake_rows
    try:
        dashboard.nr_cells(15, "")
    finally:
        dashboard.rows = original

    query = queries[0]
    assert "source = 'mirror'" in query
    assert "rat = 'NR'" in query
    assert "GROUP BY device_id, rat, arfcn, pci" in query
    assert "ORDER BY serving_samples DESC" in query


def test_attach_nr_owner_uses_the_data_sim():
    nr_rows = [
        {"rat": "NR", "arfcn": 531870, "pci": 875, "sim_slot": None, "sub_id": None,
         "carrier": None, "plmn": None, "serving_samples": 8},
        {"rat": "NR", "arfcn": 531870, "pci": 932, "sim_slot": 0, "sub_id": 4,
         "carrier": "INDOSAT", "plmn": "510-01", "serving_samples": 0},
    ]
    sims = [
        {"slot": 0, "sub_id": 4, "carrier": "INDOSAT", "plmn": "510-01", "data_state": "CONNECTED"},
        {"slot": 1, "sub_id": 3, "carrier": "XL", "plmn": "510-11", "data_state": "DISCONNECTED"},
    ]
    dashboard.attach_nr_owner(nr_rows, sims)
    assert nr_rows[0]["sim_slot"] == 0
    assert nr_rows[0]["carrier"] == "INDOSAT"
    assert nr_rows[0]["sim_source"] == "data_sim"
    assert nr_rows[1]["sim_slot"] == 0, "an already attributed row keeps its owner"
    assert "sim_source" not in nr_rows[1]


def test_attach_nr_owner_stays_neutral_when_ambiguous():
    nr_rows = [{"rat": "NR", "sim_slot": None, "sub_id": None, "serving_samples": 3}]
    sims = [
        {"slot": 0, "sub_id": 4, "data_state": "CONNECTED"},
        {"slot": 1, "sub_id": 3, "data_state": "CONNECTED"},
    ]
    dashboard.attach_nr_owner(nr_rows, sims)
    assert nr_rows[0]["sim_slot"] is None


def test_nr_summary_marks_only_the_sim_that_owns_the_nr_cell():
    nr_rows = [{"sim_slot": 0, "arfcn": 531870, "pci": 875, "serving_samples": 8,
                "rsrp": -84.4, "sinr": 19.4, "samples": 8}]
    sims = [
        {"slot": 0, "sub_id": 4, "carrier": "INDOSAT", "nr_state": 1, "data_state": "CONNECTED"},
        {"slot": 1, "sub_id": 3, "carrier": "XL", "nr_state": 1, "data_state": "DISCONNECTED"},
    ]
    summary = dashboard.nr_summary(nr_rows, sims, {"3": 0, "2": 0, "1": 20, "0": 0})
    assert summary["attached"] is True
    assert summary["serving_cells"] == 1
    by_slot = {item["slot"]: item for item in summary["per_sim"]}
    assert by_slot[0]["attached"] is True
    assert by_slot[0]["cells"][0]["pci"] == 875
    assert by_slot[1]["attached"] is False


def test_plmn_and_window_helpers():
    assert dashboard.plmn("510", "01") == "510-01"
    assert dashboard.plmn("", "01") is None
    assert dashboard.plmn(None, None) is None
    assert dashboard.DEVICE_RE.match("devad34b58e1df62d")
    assert not dashboard.DEVICE_RE.match("dev' OR 1=1")
    assert dashboard.device_filter("dev1") == " AND device_id = 'dev1'"
    assert dashboard.device_filter("bad value!") == ""
