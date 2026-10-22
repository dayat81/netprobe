"""Tests for the OTel interpretation brief and client (no network needed)."""

from __future__ import annotations

import io
import json
import sys
import urllib.error
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "opensignal_mirror"))

import measure_otel  # noqa: E402


def payload() -> dict:
    return {
        "now": 1792555200,
        "minutes": 15,
        "devices": [
            {"model": "Xiaomi 24094RAD4G", "sdk_int": 36, "snapshots": 288,
             "empty_snapshots": 0, "cells_avg": 10.4},
        ],
        "sims": [
            {"slot": 1, "sub_id": 3, "carrier": "XL", "plmn": "510-11", "rat": "LTE",
             "serving_arfcn": 325, "serving_pci": 484, "rsrp": -69, "rsrq": -8, "sinr": 14,
             "nr_state": 1, "service_state": "IN_SERVICE", "data_state": "CONNECTED", "samples": 288},
            {"slot": 0, "sub_id": 4, "carrier": "INDOSAT", "plmn": "510-01", "rat": "LTE",
             "serving_arfcn": 200, "serving_pci": 409, "rsrp": -97, "rsrq": -18, "sinr": -5,
             "nr_state": 1, "service_state": "IN_SERVICE", "data_state": "CONNECTED", "samples": 288},
        ],
        "cells": [
            {"rat": "LTE", "arfcn": 200, "pci": 409, "sim_slot": 0, "sub_id": 4,
             "carrier": "INDOSAT", "serving_samples": 170, "samples": 282,
             "serving_share": 0.59, "rsrp": -97.5, "rsrp_min": -100, "rsrp_max": -90,
             "rsrq": -18, "sinr": -5, "last_seen": 1792555199},
            {"rat": "LTE", "arfcn": 1475, "pci": 409, "sim_slot": 0, "sub_id": 4,
             "carrier": "INDOSAT", "serving_samples": 97, "samples": 280,
             "serving_share": 0.337, "rsrp": -94.9, "rsrp_min": -99, "rsrp_max": -92,
             "rsrq": -19, "sinr": -4.5, "last_seen": 1792555199},
            {"rat": "LTE", "arfcn": 325, "pci": 310, "sim_slot": None, "sub_id": None,
             "carrier": None, "serving_samples": 0, "samples": 288, "serving_share": 0.0,
             "rsrp": -82, "rsrp_min": -84, "rsrp_max": -80, "rsrq": -14, "sinr": 4,
             "last_seen": 1792555199},
        ],
        "serving": {
            "snapshots": 576,
            "per_snapshot_max": 1,
            "per_snapshot_avg": 1.0,
            "parallel_carriers": False,
            "by_slot": [
                {"slot": 1, "carrier": "XL", "serving_samples": 288, "pci_changes": 0,
                 "items": [{"arfcn": 325, "pci": 484, "sample_share": 1.0, "samples": 288,
                            "rsrp": -69.3}]},
                {"slot": 0, "carrier": "INDOSAT", "serving_samples": 288, "pci_changes": 1,
                 "items": [
                     {"arfcn": 200, "pci": 409, "sample_share": 0.59, "samples": 170, "rsrp": -97.5},
                     {"arfcn": 1475, "pci": 409, "sample_share": 0.337, "samples": 97, "rsrp": -94.9},
                     {"arfcn": 1475, "pci": 394, "sample_share": 0.003, "samples": 1, "rsrp": -94.1},
                 ]},
            ],
        },
        "trend": {
            "bucket_s": 30,
            "slots": [
                {"label": "slot 1", "slot": 1, "carrier": "XL",
                 "segments": [{"arfcn": 325, "start": 1792554300, "end": 1792555199,
                               "duration_s": 900, "samples": 288, "rsrp": -69.3}]},
                {"label": "slot 0", "slot": 0, "carrier": "INDOSAT",
                 "segments": [
                     {"arfcn": 1475, "start": 1792554300, "end": 1792554780,
                      "duration_s": 480, "samples": 160, "rsrp": -95.0},
                     {"arfcn": 200, "start": 1792554810, "end": 1792555050,
                      "duration_s": 240, "samples": 80, "rsrp": -97.0},
                     {"arfcn": 1475, "start": 1792555080, "end": 1792555199,
                      "duration_s": 120, "samples": 48, "rsrp": -95.5},
                 ]},
            ],
        },
        "nr": {
            "states": {"3": 0, "2": 0, "1": 576, "0": 0},
            "cells": [{"arfcn": 630000, "pci": 484, "sim_slot": 1, "carrier": "XL",
                       "rsrp": -84, "sinr": 9}],
        },
        "transport": {"transport": "wifi", "connected": True},
    }


def test_brief_is_compact_and_numeric():
    brief = measure_otel.build_brief(payload())
    assert brief["window"]["minutes"] == 15
    assert brief["window"]["timezone"] == "WIB (UTC+7)"
    assert brief["serving"]["max_serving_cells_per_snapshot"] == 1
    assert brief["serving"]["parallel_carriers_detected"] is False
    indosat = next(item for item in brief["serving"]["per_sim"] if item["sim"].startswith("slot 0"))
    assert [share["arfcn"] for share in indosat["shares"]] == [200, 1475, 1475]
    assert [share["share_pct"] for share in indosat["shares"]] == [59.0, 33.7, 0.3]
    assert indosat["same_arfcn_pci_changes"] == 1
    sims = {item["sim"]: item for item in brief["sims"]}
    assert sims["slot 0 · sub 4 · INDOSAT"]["plmn"] == "510-01"
    assert brief["nr"]["state_samples"] == {"1": 576}
    assert len(brief["nr"]["cells"]) == 1
    # neighbours never enter the brief, they are not what the window is about
    assert all(cell["arfcn"] != 325 or cell["pci"] != 310 for cell in brief["serving_cells"])


def test_brief_records_handovers_with_times():
    brief = measure_otel.build_brief(payload())
    mobility = brief["mobility"]
    assert mobility["total_arfcn_changes"] == 2
    indosat = next(item for item in mobility["per_sim"] if item["sim"].startswith("slot 0"))
    assert indosat["dwell_blocks"] == 3
    assert [item["to_arfcn"] for item in indosat["transitions"]] == [200, 1475]
    assert indosat["transitions"][0]["from_arfcn"] == 1475
    assert indosat["transitions"][0]["at"].endswith("WIB")
    xl = next(item for item in mobility["per_sim"] if item["sim"].startswith("slot 1"))
    assert xl["arfcn_changes"] == 0


def test_facts_summarise_the_window():
    facts = measure_otel.facts(measure_otel.build_brief(payload()))
    assert "2 SIM" in facts
    assert "576 snapshot" in facts
    assert "1 serving/SIM" in facts
    assert "2 pindah ARFCN" in facts
    assert any(fact.startswith("nr_state") for fact in facts)


def test_brief_caps_cell_rows():
    data = payload()
    data["serving"]["by_slot"][0]["items"] = [
        {"arfcn": 300 + index, "pci": index, "sample_share": 0.01, "samples": 1, "rsrp": -90}
        for index in range(20)
    ]
    brief = measure_otel.build_brief(data)
    assert len(brief["serving"]["per_sim"][0]["shares"]) == measure_otel.MAX_CELLS


def test_load_api_key_prefers_env(monkeypatch):
    monkeypatch.setenv("FEATHERLESS_API_KEY", "sk-test")
    assert measure_otel.load_api_key() == "sk-test"


def test_load_api_key_reads_env_file(monkeypatch, tmp_path):
    monkeypatch.delenv("FEATHERLESS_API_KEY", raising=False)
    env_file = tmp_path / ".env"
    env_file.write_text('OTHER=1\nFEATHERLESS_API_KEY="sk-file"\n', encoding="utf-8")
    monkeypatch.setattr(measure_otel, "ENV_FILES", (env_file,))
    assert measure_otel.load_api_key() == "sk-file"


def test_load_api_key_reports_missing(monkeypatch, tmp_path):
    monkeypatch.delenv("FEATHERLESS_API_KEY", raising=False)
    monkeypatch.setattr(measure_otel, "ENV_FILES", (tmp_path / "absent.env",))
    with pytest.raises(RuntimeError, match="FEATHERLESS_API_KEY"):
        measure_otel.load_api_key()


class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


def test_interpret_posts_brief_to_otel(monkeypatch):
    captured = {}

    def fake_urlopen(request, timeout=None):
        captured["body"] = json.loads(request.data.decode("utf-8"))
        captured["auth"] = request.headers.get("Authorization")
        captured["timeout"] = timeout
        return FakeResponse(json.dumps({
            "model": measure_otel.MODEL,
            "choices": [{"message": {"content": "**Ringkasan** — ok"}, "finish_reason": "stop"}],
            "usage": {"total_tokens": 900},
        }).encode("utf-8"))

    monkeypatch.setattr(measure_otel.urllib.request, "urlopen", fake_urlopen)
    result = measure_otel.interpret(measure_otel.build_brief(payload()), api_key="sk-test")

    assert result["text"] == "**Ringkasan** — ok"
    assert result["usage"]["total_tokens"] == 900
    assert captured["auth"] == "Bearer sk-test"
    assert captured["body"]["model"] == measure_otel.MODEL
    assert captured["body"]["messages"][0]["role"] == "system"
    brief = json.loads(captured["body"]["messages"][1]["content"])
    assert brief["serving"]["max_serving_cells_per_snapshot"] == 1


def test_interpret_surfaces_http_error(monkeypatch):
    def fake_urlopen(request, timeout=None):
        raise urllib.error.HTTPError(request.full_url, 401, "Unauthorized", {}, io.BytesIO(b"bad key"))

    monkeypatch.setattr(measure_otel.urllib.request, "urlopen", fake_urlopen)
    with pytest.raises(RuntimeError, match="401"):
        measure_otel.interpret(measure_otel.build_brief(payload()), api_key="sk-test")


def test_interpret_rejects_empty_answer(monkeypatch):
    def fake_urlopen(request, timeout=None):
        return FakeResponse(json.dumps({"choices": [{"message": {"content": "  "}}]}).encode("utf-8"))

    monkeypatch.setattr(measure_otel.urllib.request, "urlopen", fake_urlopen)
    with pytest.raises(RuntimeError, match="tidak mengembalikan teks"):
        measure_otel.interpret(measure_otel.build_brief(payload()), api_key="sk-test")
