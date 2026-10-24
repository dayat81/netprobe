import base64
import hashlib
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))

from opensignal_mirror import collector  # noqa: E402
from opensignal_mirror import schema  # noqa: E402
from opensignal_mirror.aead import encrypt  # noqa: E402
from opensignal_mirror.collector import sign  # noqa: E402
from opensignal_mirror.schema import MAX_SDK_PAYLOAD, measurement_aad  # noqa: E402
from opensignal_mirror.telemetry import (  # noqa: E402
    RADIO_CELLS_VIEW,
    SCHEMA,
    ClickHouseTelemetry,
    parse_document,
    repair_json,
    scalar_rows,
)

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "patch_opensignal",
    ROOT / "scripts" / "opensignal_mirror" / "patch_opensignal.py",
)
patcher = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(patcher)


def flow_body():
    return {
        "schema_version": 1,
        "device_id": "device1234",
        "batch_id": "batch12345",
        "sent_at_ms": 1,
        "flows": [
            {
                "event_id": "event12345",
                "proto": "tcp",
                "family": "ipv4",
                "local_port": 40000,
                "remote_ip": "203.0.113.10",
                "remote_port": 443,
                "tx_bytes": 10,
                "rx_bytes": 20,
                "tx_packets": 1,
                "rx_packets": 1,
                "first_seen_ms": 1,
                "last_seen_ms": 2,
                "state": "open",
            }
        ],
    }


class Server:
    def __init__(self, tmp_path: Path, rate: int = 120, max_body: int = 4096, allowlist: str = "127.0.0.1/32,::1/128"):
        key = tmp_path / "hmac.key"
        key.write_text("key_id=lab\nkey_hex=%s\nmeasure_key_hex=%s\n" % ("ab" * 32, "cd" * 32), encoding="utf-8")
        env = {
            "OSM_ROLE": "flows",
            "OSM_BIND": "127.0.0.1",
            "OSM_PORT": "0",
            "OSM_DB": str(tmp_path / "collector.sqlite"),
            "OSM_KEY_FILE": str(key),
            "OSM_TTL_SECONDS": "3600",
            "OSM_MAX_DB_BYTES": "1048576",
            "OSM_RATE_PER_MINUTE": str(rate),
            "OSM_SKEW_SECONDS": "300",
            "OSM_MAX_BODY": str(max_body),
            "OSM_DASH_USER": "osm",
            "OSM_DASH_PASS_SHA256": hashlib.sha256(b"lab-pass").hexdigest(),
            "OSM_ALLOWLIST": allowlist,
        }
        self.config, self.httpd = collector.build_server(env)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.httpd.server_address[1]
        self.key = self.config.key

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def request(self, path, body, nonce="nonce-value-123456", timestamp=None, signature=None, key_id="lab"):
        raw = json.dumps(body).encode("utf-8")
        moment = str(int(time.time()) if timestamp is None else timestamp)
        headers = {
            "Content-Type": "application/json",
            "X-Osm-Key-Id": key_id,
            "X-Osm-Timestamp": moment,
            "X-Osm-Nonce": nonce,
            "X-Osm-Signature": signature if signature is not None else sign(self.key, "POST", path, moment, nonce, raw),
        }
        request = urllib.request.Request("http://127.0.0.1:%s%s" % (self.port, path), data=raw, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            return exc.code, json.loads(exc.read().decode("utf-8"))


@pytest.fixture()
def server(tmp_path):
    item = Server(tmp_path)
    yield item
    item.close()


def login(server, password="lab-pass"):
    raw = json.dumps({"username": "osm", "password": password}).encode("utf-8")
    request = urllib.request.Request(
        "http://127.0.0.1:%s/login" % server.port,
        data=raw,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        return response.headers["Set-Cookie"].split(";", 1)[0]


def authed(server, path):
    request = urllib.request.Request("http://127.0.0.1:%s%s" % (server.port, path), headers={"Cookie": login(server)})
    with urllib.request.urlopen(request, timeout=5) as response:
        return response.status, response.read()


def test_dashboard_requires_login_and_shows_live_flows(server):
    status, _payload = server.request("/v1/flows", flow_body(), nonce="nonce-value-000001")
    assert status == 200
    with urllib.request.urlopen("http://127.0.0.1:%s/" % server.port) as response:
        page = response.read().decode("utf-8")
    assert "Sign in" in page
    assert "203.0.113.10" not in page
    status, raw = authed(server, "/")
    assert status == 200
    page = raw.decode("utf-8")
    assert "OpenSignal measurements" in page
    assert "App health" in page
    status, raw = authed(server, "/api/live")
    live = json.loads(raw.decode("utf-8"))
    assert live["flows"][0]["remote_ip"] == "203.0.113.10"
    assert live["totals"]["flows"] == 1


def test_accepts_signed_flow_and_deduplicates(server):
    status, payload = server.request("/v1/flows", flow_body(), nonce="nonce-value-000001")
    assert status == 200
    assert payload["accepted"] == 1
    status, payload = server.request("/v1/flows", flow_body(), nonce="nonce-value-000002")
    assert status == 200
    assert payload["duplicate"] == 1
    with urllib.request.urlopen("http://127.0.0.1:%s/v1/recent" % server.port) as response:
        recent = json.loads(response.read().decode("utf-8"))
    assert recent["events"][0]["event"]["remote_port"] == 443


def test_rejects_bad_signature_replay_and_schema(server):
    status, payload = server.request("/v1/flows", flow_body(), signature="00" * 32, nonce="nonce-value-000010")
    assert status == 401
    assert payload["error"] == "bad_signature"
    status, _ = server.request("/v1/flows", flow_body(), nonce="nonce-value-000011")
    assert status == 200
    status, payload = server.request("/v1/flows", flow_body(), nonce="nonce-value-000011")
    assert status == 401
    assert payload["error"] == "replayed_nonce"
    body = flow_body()
    body["flows"][0]["proto"] = "icmp"
    status, _ = server.request("/v1/flows", body, nonce="nonce-value-000012")
    assert status == 400


def test_rate_limit(tmp_path):
    server = Server(tmp_path, rate=1)
    try:
        status, _ = server.request("/v1/flows", flow_body(), nonce="nonce-value-000021")
        assert status == 200
        status, payload = server.request("/v1/flows", flow_body(), nonce="nonce-value-000022")
        assert status == 429
        assert payload["error"] == "rate_limited"
    finally:
        server.close()


def test_admin_route_is_not_public_helper():
    assert collector.is_loopback("127.0.0.1")
    assert not collector.is_loopback("203.0.113.5")


def test_ttl_purge_removes_events(tmp_path):
    key = tmp_path / "hmac.key"
    key.write_text("key_id=lab\nkey_hex=%s\n" % ("cd" * 32), encoding="utf-8")
    store = collector.Store(str(tmp_path / "db.sqlite"), ttl_seconds=10, max_bytes=100)
    store.insert_events("flows", "device1234", "127.0.0.1", flow_body()["flows"])
    assert store.purge(now=int(time.time()) + 30) >= 1
    assert store.counts()["flows"] == 0


def test_manifest_and_smali_injection():
    manifest = '<manifest package="com.opensignal.mobile"><application android:name=".App"></application></manifest>'
    updated, existing = patcher.ensure_network_security(manifest, ["20.205.188.36"])
    assert existing is None
    updated = patcher.ensure_provider(updated, "com.opensignal.mobile")
    assert "com.opensignal.mirror.OsmBootstrap" in updated
    assert patcher.application_class(updated, "com.opensignal.mobile") == "com.opensignal.mobile.App"
    smali = ".method public onCreate()V\n    .locals 1\n    return-void\n.end method\n"
    injected = patcher.inject_application_smali(smali)
    assert "OsmBootstrap;->ensure" in injected
    blocking = ".method public onCreate()V\n    .locals 1\n    GET_SIGNATURES\n    System;->exit\n    return-void\n.end method\n"
    assert patcher.application_blocks_resign(blocking)
    assert "20.205.188.36" in patcher.network_security_xml(["20.205.188.36"])
    assert patcher.ensure_uncompressed_so("doNotCompress:\n- arsc\n").count("- so") == 1
    config = json.loads(patcher.gadget_config("lab", "ab", "203.0.113.5", "10.0.2.2", 8000, 8001, "cd" * 32))
    assert config["interaction"]["parameters"]["measure_key_hex"] == "cd" * 32


def secret(status, value=None):
    return {"value": value, "status": status}


def measurement_plain():
    return {
        "snapshots": [
            {
                "event_id": "measure12345",
                "ts_ms": 10,
                "reason": "interval",
                "device": {
                    "manufacturer": "Lab",
                    "model": "Phone",
                    "sdk_int": 34,
                    "release": "14",
                    "android_id": secret("available", "0123456789abcdef"),
                },
                "subscriptions": [
                    {
                        "slot": 0,
                        "sub_id": 1,
                        "carrier": "Telco",
                        "mcc": "510",
                        "mnc": "10",
                        "rat": "LTE",
                        "network_operator": "Telco",
                        "sim_operator": "Telco",
                        "data_state": "CONNECTED",
                        "service_state": "IN_SERVICE",
                        "imei": secret("available", "490154203237518"),
                        "imsi": secret("platform_restricted"),
                        "msisdn": secret("permission_denied"),
                        "iccid": secret("absent"),
                    }
                ],
                "cells": [
                    {
                        "registered": True,
                        "role": "serving",
                        "rat": "LTE",
                        "mcc": "510",
                        "mnc": "10",
                        "ci": "12345",
                        "pci": 20,
                        "tac": 30,
                        "earfcn": 1500,
                        "nrarfcn": None,
                        "uarfcn": None,
                        "arfcn": None,
                        "bands": [3],
                        "bandwidth_khz": 20000,
                        "rsrp": -95,
                        "rsrq": -10,
                        "sinr": 8,
                        "rssi": -70,
                        "cqi": 10,
                        "timing_advance": 4,
                        "ss_rsrp": None,
                        "ss_rsrq": None,
                        "ss_sinr": None,
                        "csi_rsrp": None,
                        "csi_rsrq": None,
                        "csi_sinr": None,
                        "dbm": -95,
                        "asu": 45,
                        "level": 3,
                    }
                ],
                "network": {
                    "transport": "cellular",
                    "connected": True,
                    "roaming": False,
                    "downstream_kbps": 10000,
                    "upstream_kbps": 5000,
                },
                "location": {
                    "status": "permission_denied",
                    "provider": "",
                    "latitude": None,
                    "longitude": None,
                    "accuracy_m": None,
                    "altitude_m": None,
                    "speed_mps": None,
                    "bearing": None,
                    "ts_ms": None,
                },
            }
        ],
        "tests": [
            {
                "event_id": "test123456",
                "ts_ms": 11,
                "source": "opensignal",
                "test_type": "combined",
                "download_bps": 20000000,
                "upload_bps": 8000000,
                "latency_ms": 18,
                "jitter_ms": 2,
                "loss_pct": 0,
                "server": "speed.example",
                "summary": "download=20 upload=8 latency=18",
            }
        ],
    }


def measurement_envelope(server, plain, sent=1):
    device = "device1234"
    batch = "batch12345"
    iv = os.urandom(12)
    ciphertext = encrypt(
        server.config.measure_key,
        iv,
        measurement_aad(device, batch, sent),
        json.dumps(plain, separators=(",", ":")).encode("utf-8"),
    )
    return {
        "schema_version": 1,
        "device_id": device,
        "batch_id": batch,
        "sent_at_ms": sent,
        "alg": "AES-256-GCM",
        "iv_b64": base64.b64encode(iv).decode("ascii"),
        "ciphertext_b64": base64.b64encode(ciphertext).decode("ascii"),
    }


def test_encrypted_measurement_round_trip_and_search(tmp_path):
    server = Server(tmp_path, max_body=65536)
    try:
        body = measurement_envelope(server, measurement_plain())
        status, payload = server.request("/v1/measurements", body, nonce="nonce-value-000031")
        assert status == 200
        assert payload["accepted"] == 2
        status, payload = server.request("/v1/measurements", body, nonce="nonce-value-000032")
        assert status == 200
        assert payload["duplicate"] == 2
        _status, raw = authed(server, "/api/live")
        live = json.loads(raw.decode("utf-8"))
        assert live["measurements"][0]["imei"] == "490154203237518"
        assert live["measurements"][0]["event"]["subscriptions"][0]["imsi"]["status"] == "platform_restricted"
        assert live["tests"][0]["event"]["download_bps"] == 20000000
        _status, raw = authed(server, "/api/measurements?q=490154203237518")
        found = json.loads(raw.decode("utf-8"))
        assert found["events"][0]["imei"] == "490154203237518"
        with urllib.request.urlopen("http://127.0.0.1:%s/" % server.port) as response:
            assert "490154203237518" not in response.read().decode("utf-8")
    finally:
        server.close()


def test_mirror_cell_new_fields_accepted_and_serving_signal(tmp_path):
    server = Server(tmp_path, max_body=65536)
    try:
        plain = measurement_plain()
        snapshot = plain["snapshots"][0]
        primary = snapshot["subscriptions"][0]
        primary["serving_signal"] = {
            "rat": "LTE",
            "rsrp": -95,
            "rsrq": -10,
            "sinr": 8,
            "level": 3,
            "dbm": -95,
        }
        secondary = dict(primary)
        secondary.update({"slot": 1, "sub_id": 2, "carrier": "Other", "mnc": "11", "data_state": "DISCONNECTED"})
        secondary.pop("serving_signal")
        snapshot["subscriptions"] = [primary, secondary]
        primary["nr_state"] = 2
        primary["channel"] = 1625
        primary["network_type"] = 19
        primary["ca"] = True
        primary["endc_available"] = False
        primary["nr_frequency_range"] = 1
        cell = snapshot["cells"][0]
        cell.update({"channel": 1500, "sub_id": 1, "sim_slot": 0, "carrier": "Telco"})
        body = measurement_envelope(server, plain, sent=3)
        status, payload = server.request("/v1/measurements", body, nonce="nonce-value-000071")
        assert status == 200
        assert payload["accepted"] == 2
        _status, raw = authed(server, "/api/live")
        live = json.loads(raw.decode("utf-8"))
        subs = live["measurements"][0]["event"]["subscriptions"]
        assert len(subs) == 2
        assert subs[1]["carrier"] == "Other"
        assert subs[0]["serving_signal"]["sinr"] == 8
        assert subs[0]["nr_state"] == 2
        assert subs[0]["ca"] is True
        assert subs[0]["network_type"] == 19
        assert subs[0]["endc_available"] is False
        assert subs[0]["channel"] == 1625
        assert live["measurements"][0]["event"]["cells"][0]["channel"] == 1500
    finally:
        server.close()


def test_mirror_cell_rejects_invalid_sim_attribution(tmp_path):
    for field, value in (("sub_id", 2**31), ("sim_slot", 17), ("channel", 10000001)):
        plain = measurement_plain()
        plain["snapshots"][0]["cells"][0][field] = value
        with pytest.raises(ValueError):
            schema.validate_measurement_plain(plain)


def test_measure_document_keeps_subscriptions_without_secrets():
    snapshot = measurement_plain()["snapshots"][0]
    snapshot["cells"][0].update({"sub_id": 1, "sim_slot": 0, "carrier": "Telco"})
    snapshot["subscriptions"][0]["serving_signal"] = {
        "rat": "LTE",
        "rsrp": -95,
        "rsrq": -10,
        "sinr": 8,
        "level": 3,
        "dbm": -95,
    }
    snapshot["subscriptions"][0]["nr_state"] = 3
    snapshot["subscriptions"][0]["network_type"] = 19
    snapshot["subscriptions"][0]["ca"] = True
    snapshot["subscriptions"][0]["nr_frequency_range"] = 2
    snapshot["subscriptions"][0]["endc_available"] = True
    snapshot["subscriptions"][0]["channel"] = 1625
    sink = ClickHouseTelemetry("http://127.0.0.1:1")
    captured = {}

    def fake_write(event_rows, value_rows):
        captured["events"] = event_rows
        return len(event_rows)

    sink._write = fake_write
    sink.store_measurements("device1234", "127.0.0.1", [snapshot])
    document = json.loads(captured["events"][0]["payload"])
    subs = document["SUBSCRIPTIONS"]
    assert len(subs) == 1
    assert subs[0]["sub_id"] == 1
    assert subs[0]["serving_signal"]["rsrp"] == -95
    assert subs[0]["nr_state"] == 3
    assert subs[0]["network_type"] == 19
    assert subs[0]["ca"] is True
    assert subs[0]["nr_frequency_range"] == 2
    assert subs[0]["endc_available"] is True
    assert subs[0]["channel"] == 1625
    for hidden in ("imei", "imsi", "msisdn", "iccid"):
        assert hidden not in subs[0]
    assert document["CELLS_INFO"][0]["serving"] is True
    assert document["CELLS_INFO"][0]["channel"] == 1500
    assert document["MEASURE"]["arfcn"] == 1500
    assert document["MEASURE"]["sub_id"] == 1
    assert document["MEASURE"]["nr_state"] == 3
    assert document["MEASURE"]["ca"] is True
    assert document["MEASURE"]["network_type"] == 19
    assert document["SERVICE_STATE"]["nr_state"] == 3
    assert document["SERVICE_STATE"]["ca"] is True
    assert document["SERVICE_STATE"]["operator_numeric"] == "51010"


def mysignal_carriers():
    return [
        {"rat": "LTE", "status": "primary", "channel": 1475, "band": 3, "bandwidth_khz": 20000, "pci": 409},
        {"rat": "NR", "status": "secondary", "channel": 531870, "band": 41, "bandwidth_khz": 100000, "pci": 875},
    ]


def test_subscription_accepts_component_carriers_and_override():
    plain = measurement_plain()
    sub = plain["snapshots"][0]["subscriptions"][0]
    sub.update({"override_network_type": 3, "carriers": mysignal_carriers(), "nr_state": 3})
    parsed = schema.validate_measurement_plain(plain)
    clean = parsed["snapshots"][0]["subscriptions"][0]
    assert clean["override_network_type"] == 3
    assert clean["carriers"][1] == mysignal_carriers()[1]
    legacy = schema.validate_measurement_plain(measurement_plain())
    assert legacy["snapshots"][0]["subscriptions"][0]["carriers"] is None


@pytest.mark.parametrize(
    "patch",
    [
        {"status": "anchor"},
        {"rat": "5G"},
        {"pci": 2000},
        {"extra": 1},
    ],
)
def test_subscription_rejects_invalid_carrier(patch):
    plain = measurement_plain()
    carrier = dict(mysignal_carriers()[0], **patch)
    plain["snapshots"][0]["subscriptions"][0]["carriers"] = [carrier]
    with pytest.raises(ValueError):
        schema.validate_measurement_plain(plain)


def test_measure_document_keeps_component_carriers():
    snapshot = measurement_plain()["snapshots"][0]
    snapshot["subscriptions"][0].update({"override_network_type": 3, "carriers": mysignal_carriers()})
    sink = ClickHouseTelemetry("http://127.0.0.1:1")
    captured = {}

    def fake_write(event_rows, value_rows):
        captured["events"] = event_rows
        return len(event_rows)

    sink._write = fake_write
    sink.store_measurements("device1234", "127.0.0.1", [snapshot])
    document = json.loads(captured["events"][0]["payload"])
    assert document["SUBSCRIPTIONS"][0]["carriers"][1]["channel"] == 531870
    assert document["SUBSCRIPTIONS"][0]["override_network_type"] == 3
    assert document["MEASURE"]["carriers"] == 2


def wifi_payload():
    return {
        "status": "associated",
        "rssi_dbm": -58,
        "frequency_mhz": 5180,
        "channel": 36,
        "link_speed_mbps": 866,
        "ssid": "HomeAP",
        "bssid": "aa:bb:cc:dd:ee:ff",
        "standard": "11ac",
        "congestion": {
            "scan_age_ms": 4200,
            "neighbors": 3,
            "same_channel": 1,
            "adjacent_channel": 0,
            "strongest_neighbor_rssi_dbm": -72,
            "score": "low",
            "neighbors_top": [
                {
                    "ssid": "OtherAP",
                    "bssid": "11:22:33:44:55:66",
                    "rssi_dbm": -72,
                    "frequency_mhz": 5180,
                    "channel": 36,
                    "channel_width_mhz": 80,
                }
            ],
        },
    }


def test_subscription_accepts_optional_apn():
    plain = measurement_plain()
    plain["snapshots"][0]["subscriptions"][0].update(
        {
            "apn": "ggsb02",
            "apn_name": "ggsb02",
            "apn_types": "default,mms,supl",
            "apn_protocol": "IPV4V6",
        }
    )
    parsed = schema.validate_measurement_plain(plain)
    sub = parsed["snapshots"][0]["subscriptions"][0]
    assert sub["apn"] == "ggsb02"
    assert sub["apn_protocol"] == "IPV4V6"
    legacy = schema.validate_measurement_plain(measurement_plain())
    assert legacy["snapshots"][0]["subscriptions"][0]["apn"] == ""


def test_snapshot_accepts_optional_wifi_and_keeps_legacy():
    legacy = schema.validate_measurement_plain(measurement_plain())
    assert "wifi" not in legacy["snapshots"][0]
    plain = measurement_plain()
    plain["snapshots"][0]["wifi"] = wifi_payload()
    parsed = schema.validate_measurement_plain(plain)
    wifi = parsed["snapshots"][0]["wifi"]
    assert wifi["status"] == "associated"
    assert wifi["congestion"]["score"] == "low"
    assert wifi["congestion"]["neighbors_top"][0]["channel"] == 36


def test_snapshot_rejects_invalid_wifi():
    plain = measurement_plain()
    plain["snapshots"][0]["wifi"] = dict(wifi_payload(), status="linked")
    with pytest.raises(ValueError):
        schema.validate_measurement_plain(plain)


def test_measure_document_keeps_wifi():
    snapshot = measurement_plain()["snapshots"][0]
    snapshot["wifi"] = wifi_payload()
    sink = ClickHouseTelemetry("http://127.0.0.1:1")
    captured = {}

    def fake_write(event_rows, value_rows):
        captured["events"] = event_rows
        return len(event_rows)

    sink._write = fake_write
    sink.store_measurements("device1234", "127.0.0.1", [snapshot])
    document = json.loads(captured["events"][0]["payload"])
    assert document["WIFI"]["rssi_dbm"] == -58
    assert document["WIFI"]["congestion"]["same_channel"] == 1


def test_measure_document_service_state_follows_connected_subscription():
    snapshot = measurement_plain()["snapshots"][0]
    primary = snapshot["subscriptions"][0]
    connected = dict(primary)
    connected.update({"slot": 1, "sub_id": 2, "carrier": "Second", "mcc": "510", "mnc": "89"})
    primary["data_state"] = "DISCONNECTED"
    snapshot["subscriptions"] = [primary, connected]
    sink = ClickHouseTelemetry("http://127.0.0.1:1")
    captured = {}

    def fake_write(event_rows, value_rows):
        captured["events"] = event_rows
        return len(event_rows)

    sink._write = fake_write
    sink.store_measurements("device1234", "127.0.0.1", [snapshot])
    document = json.loads(captured["events"][0]["payload"])
    assert document["SERVICE_STATE"]["operator_numeric"] == "51089"
    assert document["SERVICE_STATE"]["operator_alpha_long"] == "Second"


def test_measure_document_keeps_device_label_without_android_id():
    snapshot = measurement_plain()["snapshots"][0]
    snapshot["device"] = {
        "manufacturer": "Xiaomi",
        "model": "24094RAD4G",
        "sdk_int": 36,
        "release": "16",
        "android_id": "cafebabedeadbeef",
    }
    sink = ClickHouseTelemetry("http://127.0.0.1:1")
    captured = {}

    def fake_write(event_rows, value_rows):
        captured["events"] = event_rows
        return len(event_rows)

    sink._write = fake_write
    sink.store_measurements("device1234", "127.0.0.1", [snapshot])
    document = json.loads(captured["events"][0]["payload"])
    assert document["DEVICE"] == {
        "manufacturer": "Xiaomi",
        "model": "24094RAD4G",
        "sdk_int": 36,
        "release": "16",
    }
    assert "android_id" not in json.dumps(document)


def test_radio_cells_view_normalises_both_sources():
    assert RADIO_CELLS_VIEW in SCHEMA
    for key in ("nr_arfcn", "nrarfcn", "erfcn", "earfcn", "uarfcn", "rssnr", "ss_rsrp", "sub_id", "sim_slot"):
        assert "'%s'" % key in RADIO_CELLS_VIEW
    assert "JSONType(c, 'sub_id') = 'Null'" in RADIO_CELLS_VIEW
    assert "job_type IN ('CORE', 'MEASURE')" in RADIO_CELLS_VIEW


def test_radio_by_arfcn_parses_clickhouse_json():
    sink = ClickHouseTelemetry("http://127.0.0.1:1")
    seen = {}

    def fake_sql(query, body=None):
        seen["query"] = query
        return '{"data":[{"source":"mirror","rat":"LTE","arfcn":1500,"samples":3}]}'

    sink._sql = fake_sql
    rows = sink.radio_by_arfcn(90, 50)
    assert rows[0]["arfcn"] == 1500
    assert "INTERVAL 90 MINUTE" in seen["query"]
    assert "LIMIT 50" in seen["query"]


def test_measurement_rejects_plaintext_tampering_and_bad_identifier(tmp_path):
    server = Server(tmp_path, max_body=65536)
    try:
        status, payload = server.request("/v1/measurements", measurement_plain(), nonce="nonce-value-000041")
        assert status == 400
        body = measurement_envelope(server, measurement_plain(), sent=2)
        raw = bytearray(base64.b64decode(body["ciphertext_b64"]))
        raw[-1] ^= 1
        body["ciphertext_b64"] = base64.b64encode(raw).decode("ascii")
        status, payload = server.request("/v1/measurements", body, nonce="nonce-value-000042")
        assert status == 400
        assert payload["error"] == "decrypt_failed"
        plain = measurement_plain()
        plain["snapshots"][0]["subscriptions"][0]["imei"] = secret("available", None)
        status, _payload = server.request(
            "/v1/measurements", measurement_envelope(server, plain, sent=3), nonce="nonce-value-000043"
        )
        assert status == 400
    finally:
        server.close()


def original_result():
    return {
        "snapshots": [],
        "tests": [],
        "os_results": [
            {
                "event_id": "osr123456789",
                "ts_ms": 20,
                "class_name": "com.staircase3.opensignal.goldstar.speedtest.result.SpeedTestResult",
                "source": "opensignal",
                "stage": "final_ui",
                "fields": {"download_speed": "20000000", "upload_speed": "8000000", "latency_ms": "18", "phase": ""},
            }
        ],
    }


def test_original_results_round_trip_and_reject_bad_fields(tmp_path):
    server = Server(tmp_path, max_body=65536)
    try:
        body = measurement_envelope(server, original_result(), sent=4)
        status, payload = server.request("/v1/measurements", body, nonce="nonce-value-000051")
        assert status == 200
        assert payload["accepted"] == 1
        _status, raw = authed(server, "/api/live")
        live = json.loads(raw.decode("utf-8"))
        assert live["os_results"][0]["event"]["stage"] == "final_ui"
        assert live["os_results"][0]["event"]["fields"]["download_speed"] == "20000000"
        plain = original_result()
        plain["os_results"][0]["fields"]["bad key"] = "1"
        status, _payload = server.request(
            "/v1/measurements", measurement_envelope(server, plain, sent=5), nonce="nonce-value-000052"
        )
        assert status == 400
        plain = original_result()
        plain["extra"] = []
        status, _payload = server.request(
            "/v1/measurements", measurement_envelope(server, plain, sent=6), nonce="nonce-value-000053"
        )
        assert status == 400
    finally:
        server.close()


PHASE_SMALI = """\
.method public static final a(Lhl0;Ljava/lang/String;Ljava/lang/String;)Lcom/staircase3/opensignal/goldstar/speedtest/result/SpeedTestResult;
    .locals 1

    const/4 v0, 0x0

    return-object v0
.end method
"""

FINAL_SMALI = """\
.method public final b(Llm5;Lcom/staircase3/opensignal/goldstar/speedtest/result/SpeedTestResult;J)V
    .locals 1

    iput-object v0, p2, Lcom/staircase3/opensignal/goldstar/speedtest/result/SpeedTestResult;->F:Ldv3;

    return-void
.end method
"""


def test_original_hook_injection_and_fail_closed(tmp_path):
    phase = patcher.transform_phase_method(PHASE_SMALI)
    assert phase.count("capturePayload") == 1
    assert phase.count("capturePhase") == 1
    final = patcher.transform_final_method(FINAL_SMALI)
    assert final.count("captureFinal") == 1
    decoded = tmp_path / "decoded"
    phase_path = decoded / "smali" / "defpackage" / "hl0.smali"
    final_path = decoded / "smali" / "com" / "staircase3" / "opensignal" / "viewcontrollers" / "Listener.smali"
    phase_path.parent.mkdir(parents=True)
    final_path.parent.mkdir(parents=True)
    phase_path.write_text(".class Ldefpackage/hl0;\n" + PHASE_SMALI, encoding="utf-8")
    final_path.write_text(".class Llistener;\n" + FINAL_SMALI, encoding="utf-8")
    sdk_path = decoded / "smali" / "lq2.smali"
    sdk_path.write_text(".class Llq2;\n" + SDK_SMALI, encoding="utf-8")
    counts = patcher.inject_original_hooks(decoded)
    assert counts == {"phase_methods": 1, "final_methods": 1, "sdk_methods": 1}
    assert "capturePhase" in phase_path.read_text(encoding="utf-8")
    hooked = sdk_path.read_text(encoding="utf-8")
    assert hooked.count("captureSdkJson") == 1
    assert hooked.index("captureSdkJson") < hooked.index("return-object")
    counts = patcher.inject_original_hooks(decoded)
    assert counts["sdk_methods"] == 1
    assert sdk_path.read_text(encoding="utf-8").count("captureSdkJson") == 1
    missing = tmp_path / "empty"
    (missing / "smali").mkdir(parents=True)
    (missing / "smali" / "none.smali").write_text(".class Lnone;\n", encoding="utf-8")
    with pytest.raises(SystemExit):
        patcher.inject_original_hooks(missing)


SDK_SMALI = """\
.method public final h()Ljava/lang/String;
    .locals 2

    const-string v0, "job_type"

    const-string v1, "time_of_result"

    const-string v1, "data_endpoint"

    invoke-interface {p0, v0}, Llq2;->g(Lorg/json/JSONObject;)V

    invoke-virtual {v0}, Lorg/json/JSONObject;->toString()Ljava/lang/String;

    move-result-object v0

    return-object v0
.end method
"""


def sdk_result():
    payload = json.dumps(
        {
            "job_type": "DOWNLOAD_SPEED",
            "task_name": "speed",
            "task_id": 17,
            "download_speed": 39768,
            "download_host": "flores.nmbdn.com",
            "video_bitrate": 4500,
            "rsrp": -93,
        },
        separators=(",", ":"),
    )
    return {
        "snapshots": [],
        "tests": [],
        "os_results": [
            {
                "event_id": "osrsdk123456",
                "ts_ms": 21,
                "class_name": "opensignal.sdk.DOWNLOAD_SPEED",
                "source": "opensignal_sdk",
                "stage": "sdk_json",
                "fields": {
                    "job_type": "DOWNLOAD_SPEED",
                    "task_name": "speed",
                    "download_speed": "39768",
                    "download_host": "flores.nmbdn.com",
                    "video_bitrate": "4500",
                    "rsrp": "-93",
                },
                "payload": payload,
            }
        ],
    }


def test_sdk_json_round_trip_limits_and_dashboard(tmp_path):
    server = Server(tmp_path, max_body=65536)
    try:
        status, payload = server.request(
            "/v1/measurements", measurement_envelope(server, sdk_result(), sent=7), nonce="nonce-value-000061"
        )
        assert status == 200
        assert payload["accepted"] == 1
        _status, raw = authed(server, "/api/live")
        live = json.loads(raw.decode("utf-8"))
        event = live["os_results"][0]
        assert event["test_type"] == "DOWNLOAD_SPEED"
        assert event["event"]["fields"]["video_bitrate"] == "4500"
        assert event["event"]["fields"]["rsrp"] == "-93"
        assert event["event"]["payload"].startswith('{"job_type":"DOWNLOAD_SPEED"')
        _status, page = authed(server, "/")
        text = page.decode("utf-8")
        assert "SDK telemetry" in text
        assert "added by OpenSignal's servers" in text
        assert "Copy JSON" in text
        assert "Filter JSON keys" in text
        missing = sdk_result()
        del missing["os_results"][0]["payload"]
        status, _payload = server.request(
            "/v1/measurements", measurement_envelope(server, missing, sent=8), nonce="nonce-value-000062"
        )
        assert status == 400
        extra = original_result()
        extra["os_results"][0]["payload"] = "{}"
        status, _payload = server.request(
            "/v1/measurements", measurement_envelope(server, extra, sent=9), nonce="nonce-value-000063"
        )
        assert status == 400
        huge = sdk_result()
        huge["os_results"][0]["payload"] = "x" * (MAX_SDK_PAYLOAD + 1)
        huge["os_results"][0]["event_id"] = "osrsdk123457"
        status, _payload = server.request(
            "/v1/measurements", measurement_envelope(server, huge, sent=10), nonce="nonce-value-000064"
        )
        assert status == 400
        dirty = sdk_result()
        dirty["os_results"][0]["payload"] = "{\"job_type\":\"VIDEO\"}\n"
        dirty["os_results"][0]["event_id"] = "osrsdk123458"
        status, _payload = server.request(
            "/v1/measurements", measurement_envelope(server, dirty, sent=11), nonce="nonce-value-000065"
        )
        assert status == 400
    finally:
        server.close()


def test_sdk_json_parses_nested_and_truncated_documents():
    payload = json.dumps(
        {
            "job_type": "CORE",
            "task_name": "core",
            "CELLS_INFO": json.dumps([{"rat": "lte", "rsrp": -93, "ci": 115244292}]),
            "download_speed": 34766,
        },
        separators=(",", ":"),
    )
    document, truncated, repaired = parse_document(
        {"stage": "sdk_json", "fields": {"payload_truncated": "1"}, "payload": payload[:80]}
    )
    assert truncated is True
    assert repaired is True
    assert document["job_type"] == "CORE"
    complete, truncated, repaired = parse_document({"stage": "sdk_json", "fields": {}, "payload": payload})
    assert truncated is False
    assert repaired is False
    assert complete["CELLS_INFO"][0]["rsrp"] == -93
    paths = {row["path"]: row for row in scalar_rows(complete)}
    assert paths["CELLS_INFO.0.rsrp"]["value_num"] == -93
    assert paths["CELLS_INFO.0.rat"]["value_str"] == "lte"
    assert paths["download_speed"]["value_num"] == 34766
    repaired = json.loads(repair_json(payload[:40]))
    assert repaired["job_type"] == "CORE"


def test_sdk_json_hook_fail_closed(tmp_path):
    decoded = tmp_path / "decoded"
    phase_path = decoded / "smali" / "defpackage" / "hl0.smali"
    final_path = decoded / "smali" / "listener.smali"
    sdk_path = decoded / "smali" / "lq2.smali"
    phase_path.parent.mkdir(parents=True)
    phase_path.write_text(".class Ldefpackage/hl0;\n" + PHASE_SMALI, encoding="utf-8")
    final_path.write_text(".class Llistener;\n" + FINAL_SMALI, encoding="utf-8")
    sdk_path.write_text(".class Llq2;\n" + SDK_SMALI, encoding="utf-8")
    duplicate = decoded / "smali_classes2" / "lq2b.smali"
    duplicate.parent.mkdir()
    duplicate.write_text(".class Llq2b;\n" + SDK_SMALI, encoding="utf-8")
    with pytest.raises(SystemExit, match="SDK JSON"):
        patcher.inject_original_hooks(decoded)
    duplicate.unlink()
    sdk_path.write_text(SDK_SMALI.replace("return-object v0\n.end method", "return-object v0\n\n    return-object v0\n.end method"), encoding="utf-8")
    with pytest.raises(SystemExit, match="return count"):
        patcher.inject_original_hooks(decoded)
    sdk_path.unlink()
    with pytest.raises(SystemExit, match="SDK JSON"):
        patcher.inject_original_hooks(decoded)


def test_dashboard_allowlist_blocks_other_networks(tmp_path):
    server = Server(tmp_path, allowlist="203.0.113.9/32")
    try:
        with pytest.raises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen("http://127.0.0.1:%s/" % server.port, timeout=5)
        assert caught.value.code == 403
    finally:
        server.close()


def nr_status_body():
    return {
        "schema_version": 1,
        "device_id": "mysignal01",
        "event_id": "evt-nr-status-1",
        "sent_at_ms": 1_700_000_000_000,
        "path_status": "advertised_only",
        "verdict": "NSA_ADVERTISED",
        "plmn": "51010",
        "mcc": "510",
        "mnc": "10",
        "override_network_type": 3,
        "override_label": "NR_NSA",
        "nr_state": 0,
        "nr_state_label": "NONE",
        "data_network_type": 13,
        "data_label": "LTE",
        "display_network_type": 13,
        "nr_frequency_range": 0,
        "serving_cells": [
            {
                "rat": "LTE",
                "role": "serving",
                "registered": True,
                "mcc": "510",
                "mnc": "10",
                "pci": 101,
                "tac": 12345,
                "earfcn": 1850,
                "rsrp": -95,
            }
        ],
        "neighbor_cells": [
            {
                "rat": "LTE",
                "role": "neighbor",
                "registered": False,
                "pci": 202,
                "earfcn": 1850,
                "rsrp": -110,
            }
        ],
    }


def test_accepts_signed_nr_status(server):
    status, payload = server.request("/v1/nr-status", nr_status_body(), nonce="nonce-value-nr-0001")
    assert status == 200, payload
    assert payload["accepted"] == 1
    recent = server.config.store.recent("nr_status", 5)
    assert recent[0]["event"]["path_status"] == "advertised_only"
    assert recent[0]["event"]["serving_cells"][0]["pci"] == 101
    counts = server.config.store.counts()
    assert counts["nr_status"] == 1


def test_client_ip_trusts_x_real_ip_only_from_proxy():
    trusted = collector.parse_allowlist("127.0.0.1/32,::1/128")
    headers = {"X-Real-IP": "203.0.113.50", "X-Forwarded-For": "198.51.100.9, 127.0.0.1"}
    assert collector.client_ip("127.0.0.1", headers, trusted) == "203.0.113.50"
    # Untrusted peer cannot spoof via headers.
    assert collector.client_ip("203.0.113.1", headers, trusted) == "203.0.113.1"
    # Missing X-Real-IP falls back to leftmost X-Forwarded-For.
    assert collector.client_ip("127.0.0.1", {"X-Forwarded-For": "198.51.100.7, 10.0.0.1"}, trusted) == "198.51.100.7"
    # Garbage header falls back to peer.
    assert collector.client_ip("127.0.0.1", {"X-Real-IP": "not-an-ip"}, trusted) == "127.0.0.1"
    # IPv4-mapped IPv6 is normalized.
    assert collector.client_ip("127.0.0.1", {"X-Real-IP": "::ffff:203.0.113.77"}, trusted) == "203.0.113.77"


def test_sync_ingest_roles_replays_measurements(tmp_path):
    store = collector.Store(str(tmp_path / "sync.sqlite"), ttl_seconds=3600, max_bytes=1_048_576)
    snapshot = measurement_plain()["snapshots"][0]
    store.insert_events("measurements", "device1234", "203.0.113.50", [snapshot])
    sink = ClickHouseTelemetry("http://127.0.0.1:1")
    captured = {"writes": []}

    def fake_write(event_rows, value_rows):
        captured["writes"].append(event_rows)
        return len(event_rows)

    sink._write = fake_write
    sink.ready = True
    written = sink.sync_ingest_roles(store, cutoff=None)
    assert written >= 1
    assert any(row["job_type"] == "MEASURE" for batch in captured["writes"] for row in batch)
    assert any(row["source_ip"] == "203.0.113.50" for batch in captured["writes"] for row in batch)
    assert any(row["device_id"] == "device1234" for batch in captured["writes"] for row in batch)


def rum_event(**extra):
    event = {
        "event_id": "rum-event-1",
        "ts_ms": 1_700_000_000_000,
        "kind": "session",
        "level": "info",
        "code": "session",
        "message": "start",
        "app_version": "2.5.87",
        "version_code": 113,
        "sdk": 35,
        "model": "Pixel",
        "manufacturer": "Google",
    }
    event.update(extra)
    return event


def rum_body(event=None):
    return {
        "schema_version": 1,
        "device_id": "mysignal01",
        "batch_id": "batch-rum-1",
        "sent_at_ms": 1_700_000_000_000,
        "events": [event or rum_event()],
    }


def test_accepts_signed_rum_and_clips_message(server):
    long = rum_body(rum_event(message="x" * 600))
    parsed = collector.validate_rum(long)
    assert len(parsed["events"][0]["message"]) == 512
    crash = rum_event(
        kind="crash",
        level="error",
        code="IllegalState",
        message="boom",
        stack="java.lang.IllegalState\nat Main.run",
        logs=[{"ts_ms": 11, "level": "warn", "tag": "Speed", "message": "worker failed"}],
    )
    status, payload = server.request("/v1/rum", rum_body(crash), nonce="nonce-value-rum-0001")
    assert status == 200, payload
    assert payload["accepted"] == 1
    recent = server.config.store.recent("rum", 5)
    assert recent[0]["event"]["device_id"] == "mysignal01"
    assert "\n" in recent[0]["event"]["stack"]
    live = collector.live_snapshot(server.config.store)
    assert live["rum"][0]["event"]["kind"] == "crash"


def test_rejects_trace_on_rum_heartbeat(server):
    body = rum_body(rum_event(kind="heartbeat", stack="nope"))
    status, payload = server.request("/v1/rum", body, nonce="nonce-value-rum-0002")
    assert status == 400
    assert payload["error"] == "unexpected rum trace"


def test_rejects_unknown_rum_field(server):
    event = rum_event()
    event["imei"] = "123456789012345"
    status, payload = server.request("/v1/rum", rum_body(event), nonce="nonce-value-rum-0003")
    assert status == 400
    assert payload["error"] == "unexpected rum event fields"
