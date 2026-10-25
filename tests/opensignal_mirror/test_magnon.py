"""Magnon credits, points, redaction, and sponsor matching. No Firestore."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))

from opensignal_mirror.magnon import (  # noqa: E402
    MemoryLedger,
    Portal,
    aggregate,
    compose_post,
    draft_with_deepseek,
    area_features,
    area_key,
    best_places,
    handle,
    haversine_m,
    home_html,
    place_from_components,
    result_page,
    strip_identity,
)


def _portal(operators: str = "", admins: str = "") -> Portal:
    clock = {"now": 1_700_000_000.0}

    def verify(token: str, client_id: str) -> dict:
        email, _sep, name = token.partition("|")
        return {"sub": "sub-" + email, "email": email, "name": name or email, "email_verified": True}

    from opensignal_mirror.magnon import parse_emails, parse_operators

    return Portal(
        MemoryLedger(),
        "client",
        parse_operators(operators),
        parse_emails(admins),
        verify=verify,
        clock=lambda: clock["now"],
    )


def test_login_starts_lite_and_binds_device():
    portal = _portal()
    result = portal.login("ada@gmail.com|Ada", "device1234", "lite")
    assert result["profile"]["role"] == "contributor"
    assert result["profile"]["level"] == "lite"
    assert result["profile"]["post_limit"] == 3
    assert result["profile"]["test_limit"] == 20
    assert result["profile"]["stay_on_lite"] is False
    again = portal.login("ada@gmail.com|Ada", "device1234", "premium")
    assert again["profile"]["level"] == "lite"
    assert again["profile"]["stay_on_lite"] is True


def test_premium_account_may_open_lite():
    portal = _portal()
    token = portal.login("ada@gmail.com|Ada", "device1234", "lite")["session"]
    portal.set_level("ada@gmail.com", "premium")
    profile = portal.me(token, "lite")
    assert profile["level"] == "premium"
    assert profile["stay_on_lite"] is False


def test_points_and_credits_stay_off():
    portal = _portal()
    token = portal.login("ada@gmail.com|Ada", "device1234", "lite")["session"]
    for _ in range(5):
        assert portal.reserve(token, "post")["ok"] is True
    assert portal.award("device1234", "myspd1", "speed", {"latitude": -6.2, "longitude": 106.8}) == 0
    assert portal.me(token, "lite")["points"] == 0
    portal.set_level("ada@gmail.com", "premium")
    assert portal.reserve(token, "test")["ok"] is True


def test_post_credit_is_three_per_day_and_test_credit_is_premium_only(monkeypatch):
    monkeypatch.setenv("MAGNON_CREDITS", "1")
    portal = _portal()
    token = portal.login("ada@gmail.com|Ada", "device1234", "lite")["session"]
    try:
        portal.reserve(token, "test")
        raised = False
    except ValueError as exc:
        raised = str(exc) == "stay_on_lite"
    assert raised
    for _ in range(3):
        assert portal.reserve(token, "post")["ok"] is True
    try:
        portal.reserve(token, "post")
        blocked = False
    except ValueError as exc:
        blocked = str(exc) == "credit_exhausted"
    assert blocked
    portal.set_level("ada@gmail.com", "premium")
    for _ in range(20):
        portal.reserve(token, "test")
    try:
        portal.reserve(token, "test")
        blocked = False
    except ValueError as exc:
        blocked = str(exc) == "credit_exhausted"
    assert blocked


def test_points_once_per_event_and_radio_cap(monkeypatch):
    monkeypatch.setenv("MAGNON_CREDITS", "1")
    portal = _portal()
    portal.login("ada@gmail.com|Ada", "device1234", "lite")
    assert portal.award("device1234", "myspd1", "speed", {}) == 10
    assert portal.award("device1234", "myspd1", "speed", {}) == 0
    assert portal.award("device1234", "mysvd1", "video", {}) == 8
    assert portal.award("device1234", "nrs1", "radio", {}) == 0
    portal.set_level("ada@gmail.com", "premium")
    for index in range(30):
        assert portal.award("device1234", "nrs%d" % index, "radio", {}) == 1
    assert portal.award("device1234", "nrs-over", "radio", {}) == 0
    board = portal.leaderboard()
    assert board["all_time"][0]["name"] == "Ada"
    assert "email" not in board["all_time"][0]
    assert board["all_time"][0]["points"] == 10 + 8 + 30


def test_aggregate_hides_sensitive_fields_and_matches_brand():
    rows = [
        {"provider": "Telco", "rat": "LTE", "download_bps": 100, "latitude": -6.2, "imei": "secret", "received_at": 1_700_000_000},
        {"provider": "Other", "rat": "NR", "download_bps": 900, "received_at": 1_700_000_000},
    ]
    view = aggregate(rows, "Telco")
    assert view["totals"]["samples"] == 1
    assert view["totals"]["lte"] == 1
    blob = str(view)
    assert "secret" not in blob
    assert "-6.2" not in blob


def test_campaign_matches_premium_inside_the_area():
    portal = _portal("ops@gmail.com=Telco")
    premium = portal.login("ada@gmail.com|Ada", "device1234", "premium")
    portal.set_level("ada@gmail.com", "premium")
    portal.award("device1234", "myspd9", "speed", {"latitude": -6.2, "longitude": 106.8})
    bee = portal.login("bee@gmail.com|Bee", "device9999", "lite")
    portal.award("device9999", "myspd8", "speed", {"latitude": -6.2, "longitude": 106.8})
    try:
        portal.create_campaign(premium["session"], {"lat": -6.2, "lon": 106.8, "radius_m": 1000, "start": 1, "end": 2})
        denied = False
    except ValueError as exc:
        denied = str(exc) == "sponsor_only"
    assert denied
    sponsor = portal.login("ops@gmail.com|Ops", "", "lite")
    token = sponsor["session"]
    assert sponsor["profile"]["roles"] == ["sponsor", "contributor"]
    created = portal.create_campaign(token, {"lat": -6.2, "lon": 106.8, "radius_m": 2000, "start": 1, "end": 9_999_999_999, "audience": "both"})
    assert created["matched"] == 2
    assert created["lite"] == 1 and created["premium"] == 1
    only_premium = portal.create_campaign(token, {"lat": -6.2, "lon": 106.8, "radius_m": 2000, "start": 1, "end": 9_999_999_999, "audience": "premium"})
    assert only_premium["lite"] == 0 and only_premium["premium"] == 1
    jobs = portal.jobs(premium["session"])
    assert len(jobs) == 2
    assert len(portal.jobs(bee["session"])) == 1
    portal.decide_job(premium["session"], jobs[0]["id"], True)
    portal.ledger.add_event(
        {
            "device_id": "device1234",
            "received_at": 100,
            "email": "ada@gmail.com",
            "latitude": -6.2,
            "download_bps": 50,
            "rsrp": -90,
        }
    )
    detail = portal.sponsor_view(token)
    results = detail["campaigns"][0]["results"]
    assert results[0]["download_bps"] == 50
    assert "email" not in results[0]
    assert haversine_m(-6.2, 106.8, -6.21, 106.8) < 2000
    assert "device_id" not in strip_identity({"device_id": "x", "download_bps": 1})


def test_admin_sets_credits_and_cannot_drop_self(monkeypatch):
    monkeypatch.setenv("MAGNON_CREDITS", "1")
    portal = _portal(admins="root@gmail.com")
    ada = portal.login("ada@gmail.com|Ada", "device1234", "lite")
    root = portal.login("root@gmail.com|Root", "", "lite")
    assert root["profile"]["roles"] == ["admin", "contributor"]
    assert root["profile"]["role"] == "admin,contributor"
    try:
        portal.list_users(ada["session"])
        hidden = False
    except PermissionError as exc:
        hidden = str(exc) == "admin_only"
    assert hidden
    listed = portal.list_users(root["session"])
    assert {row["email"] for row in listed["users"]} == {"ada@gmail.com", "root@gmail.com"}
    saved = portal.update_user(root["session"], "ada@gmail.com", "contributor", "premium", 1, 2)
    assert saved["level"] == "premium"
    assert saved["post_limit"] == 1
    assert portal.reserve(ada["session"], "post")["ok"] is True
    try:
        portal.reserve(ada["session"], "post")
        blocked = False
    except ValueError as exc:
        blocked = str(exc) == "credit_exhausted"
    assert blocked
    for _ in range(2):
        portal.reserve(ada["session"], "test")
    try:
        portal.reserve(ada["session"], "test")
        blocked = False
    except ValueError as exc:
        blocked = str(exc) == "credit_exhausted"
    assert blocked
    try:
        portal.update_user(root["session"], "root@gmail.com", "contributor", "lite", 3, 20)
        kept = False
    except ValueError as exc:
        kept = str(exc) == "keep_admin"
    assert kept
    again = portal.login("ada@gmail.com|Ada", "device1234", "lite")
    assert again["profile"]["role"] == "contributor"
    assert again["profile"]["post_limit"] == 1


def test_lite_detail_is_premium_only_and_operator_is_sponsor():
    portal = _portal("old@gmail.com")
    lite = portal.login("ada@gmail.com|Ada", "device1234", "lite")
    try:
        portal.own_detail(lite["session"])
        opened = False
    except ValueError as exc:
        opened = str(exc) == "premium_only"
    assert opened
    portal.set_level("ada@gmail.com", "premium")
    portal.ledger.add_event({"device_id": "device1234", "received_at": 5, "rsrp": -88, "email": "ada@gmail.com"})
    detail = portal.own_detail(lite["session"])
    assert detail["events"][0]["rsrp"] == -88
    sponsor = portal.login("old@gmail.com|Old", "", "lite")
    assert sponsor["profile"]["roles"] == ["sponsor", "contributor"]
    stored = portal.ledger.user_by_email("old@gmail.com")
    stored.pop("roles", None)
    stored["role"] = "operator"
    portal.ledger.save_user(stored)
    again = portal.login("old@gmail.com|Old", "", "lite")
    assert again["profile"]["roles"] == ["sponsor"]


def test_one_account_can_hold_every_role():
    portal = _portal(admins="root@gmail.com")
    ada = portal.login("ada@gmail.com|Ada", "device1234", "lite")
    root = portal.login("root@gmail.com|Root", "", "lite")
    saved = portal.update_user(
        root["session"],
        "ada@gmail.com",
        ["sponsor", "admin", "contributor"],
        "premium",
        3,
        20,
    )
    assert saved["roles"] == ["admin", "sponsor", "contributor"]
    assert portal.list_users(ada["session"])["users"]
    created = portal.create_campaign(
        ada["session"],
        {"lat": -6.2, "lon": 106.8, "radius_m": 1000, "start": 1, "end": 9, "audience": "lite"},
    )
    assert created["matched"] == 0
    portal.ledger.add_event({"device_id": "device1234", "received_at": 5, "rsrp": -70})
    assert portal.own_detail(ada["session"])["events"][0]["rsrp"] == -70
    portal.update_user(root["session"], "ada@gmail.com", ["admin", "contributor"], "premium", 3, 20)
    try:
        portal.create_campaign(ada["session"], {"lat": -6.2, "lon": 106.8, "radius_m": 1000, "start": 1, "end": 9})
        still = False
    except ValueError as exc:
        still = str(exc) == "sponsor_only"
    assert still
    both = portal.update_user(root["session"], "root@gmail.com", ["admin", "sponsor"], "lite", 3, 20)
    assert both["roles"] == ["admin", "sponsor"]


def test_compose_skips_without_a_result():
    called = {"n": 0}

    def classify(_brief):
        called["n"] += 1
        return {"post_intent": "complaint"}

    result = compose_post(
        {"language": "id", "speed": {"download_mbps": None}, "video": {"resolution": ""}},
        classify=classify,
        draft=classify,
    )
    assert result["post_intent"] == "skip"
    assert result["sentence"] == ""
    assert result["url"] == ""
    assert called["n"] == 0


def test_compose_keeps_jev_intent_and_writes_the_requested_language():
    seen = {}

    def classify(brief):
        seen["language"] = brief["language"]
        seen["download"] = brief["speed"]["download_mbps"]
        return {"post_intent": "praise", "quality_band": "good", "topic": "fast_download"}

    def draft(_brief, classification):
        seen["intent"] = classification["post_intent"]
        return {"sentence": "Bagus " * 50, "summary": "ringkas", "post_intent": "complaint"}

    result = compose_post(
        {"language": "id-ID", "speed": {"download_mbps": 80.5}},
        classify=classify,
        draft=draft,
    )
    assert result["post_intent"] == "praise"
    assert result["quality_band"] == "good"
    assert result["topic"] == "fast_download"
    assert result["url"].startswith("https://magnon.site/r/")
    assert result["classifier"] == "jev"
    assert result["composer"] == "deepseek"
    assert len(result["sentence"]) <= 180
    assert result["sentence"].startswith("Bagus")
    assert seen["language"] == "id"
    assert seen["download"] == 80.5
    assert seen["intent"] == "praise"


def test_draft_calls_deepseek(monkeypatch):
    seen = {}

    def fake_post(url, body, key, timeout):
        seen["url"] = url
        seen["model"] = body["model"]
        seen["key"] = key
        seen["timeout"] = timeout
        seen["intent"] = "praise" in body["messages"][1]["content"]
        seen["thinking"] = body.get("thinking")
        return {"choices": [{"message": {"content": "{\"sentence\":\"Telkom Menteng\",\"summary\":\"ok\"}"}}]}

    monkeypatch.setenv("MAGNON_DEEPSEEK_API_KEY", "test-key")
    monkeypatch.setattr("opensignal_mirror.magnon._post_json", fake_post)
    written = draft_with_deepseek(
        {"language": "id", "style": "biasa", "provider": "Telkom", "kecamatan": "Menteng"},
        {"post_intent": "praise"},
    )
    assert seen["url"] == "https://api.deepseek.com/chat/completions"
    assert seen["model"] == "deepseek-flash"
    assert seen["key"] == "test-key"
    assert seen["timeout"] == 20
    assert seen["thinking"] == {"type": "disabled"}
    assert seen["intent"]
    assert written["sentence"] == "Telkom Menteng"


def test_compose_publishes_speed_and_video_charts_without_identity():
    ledger = MemoryLedger()
    seen = {}

    def classify(brief):
        seen["style"] = brief.get("style")
        seen["kelurahan"] = brief.get("kelurahan")
        seen["kecamatan"] = brief.get("kecamatan")
        seen["keys"] = set(brief)
        seen["speed_keys"] = set(brief.get("speed") or {})
        return {"post_intent": "complaint", "quality_band": "poor", "topic": "mixed_poor"}

    def draft(_brief, _classification):
        return {"sentence": "Telkom is slow.", "summary": "Slow Wi-Fi."}

    result = compose_post(
        {
            "language": "en",
            "style": "gaul",
            "kelurahan": "Gondangdia",
            "kecamatan": "Menteng",
            "location": "-6.20000, 106.80000",
            "provider": "Telkom",
            "transport": "unknown",
            "public_ip": "203.0.113.10",
            "radio": "LTE RSRP -110",
            "speed": {
                "download_mbps": 4.2,
                "upload_mbps": 1.1,
                "ping_ms": 180,
                "samples": {
                    "download": [{"t_ms": 0, "mbps": 1}, {"t_ms": 500, "mbps": 4.2}],
                    "upload": [{"t_ms": 0, "mbps": 0.4}, {"t_ms": 500, "mbps": 1.1}],
                },
            },
            "video": {
                "resolution": "1080p",
                "load_ms": 838,
                "buffering_pct": 1,
                "stages": [
                    {"resolution": "360p", "load_ms": 400, "played_ms": 4000, "buffer_ms": 0},
                    {"resolution": "1080p", "load_ms": 838, "played_ms": 4000, "buffer_ms": 20},
                ],
            },
        },
        classify=classify,
        draft=draft,
        store=ledger,
    )
    run_id = result["url"].rsplit("/", 1)[-1]
    status, page = result_page(ledger, run_id)
    text = page.decode("utf-8")
    assert status == 200
    assert "Download" in text
    assert "1080p" in text
    assert "<h2>Video</h2>" not in text
    assert "<strong>Provider</strong><span>Telkom</span>" in text
    assert "<strong>Kelurahan</strong><span>Gondangdia</span>" in text
    assert "<strong>Kecamatan</strong><span>Menteng</span>" in text
    assert "-6.20000" not in text
    assert "203.0.113.10" not in text
    assert "RSRP" not in text
    assert "unknown" not in text
    assert seen["style"] == "gaul"
    assert seen["kelurahan"] == "Gondangdia"
    assert seen["kecamatan"] == "Menteng"
    assert "radio" not in seen["keys"]
    assert "samples" not in seen["speed_keys"]
    stored = ledger.run(run_id)
    assert stored.get("events") == []
    assert "public_ip" not in stored
    assert "location" not in stored
    assert result_page(ledger, "notfoundpage")[0] == 404


def test_compose_lists_selected_events_and_omits_a_disputed_place():
    ledger = MemoryLedger()
    seen = {}

    def classify(brief):
        seen["events"] = [row["id"] for row in brief["events"]]
        seen["kecamatan"] = brief.get("kecamatan", "")
        seen["provider"] = brief.get("provider", "")
        assert "speed" not in brief
        return {"post_intent": "complaint", "quality_band": "poor"}

    def draft(brief, _classification):
        seen["draft_ids"] = [row["id"] for row in brief["events"]]
        return {"sentence": "Two slow tests.", "summary": "Both slow."}

    result = compose_post(
        {
            "language": "en",
            "latitude": -6.2,
            "longitude": 106.8,
            "public_ip": "203.0.113.9",
            "events": [
                {
                    "id": "myspd0000000000000001",
                    "kind": "speed",
                    "when": "25/10 14:00",
                    "kecamatan": "Menteng",
                    "provider": "Telkomsel",
                    "transport": "cellular",
                    "download_mbps": 3.5,
                    "upload_mbps": 1.0,
                    "ping_ms": 200,
                },
                {
                    "id": "mysvd0000000000000002",
                    "kind": "video",
                    "when": "25/10 14:05",
                    "kecamatan": "Gambir",
                    "provider": "Telkomsel",
                    "transport": "cellular",
                    "resolution": "360p",
                    "load_ms": 2400,
                    "buffering_pct": 8,
                },
            ],
        },
        classify=classify,
        draft=draft,
        store=ledger,
    )
    assert seen["events"] == ["myspd0000000000000001", "mysvd0000000000000002"]
    assert seen["draft_ids"] == seen["events"]
    assert seen["kecamatan"] == ""
    assert seen["provider"] == "Telkomsel"
    run_id = result["url"].rsplit("/", 1)[-1]
    status, page = result_page(ledger, run_id)
    text = page.decode("utf-8")
    assert status == 200
    assert "myspd0000000000000001" in text
    assert "mysvd0000000000000002" in text
    assert "3.5" in text
    assert "360p" in text
    assert "Menteng" not in text
    assert "Gambir" not in text
    assert "-6.2" not in text
    assert "203.0.113.9" not in text
    assert "<strong>Provider</strong><span>Telkomsel</span>" in text
    assert "<h2>Speed</h2>" not in text


def test_abbreviated_district_matches_the_map_name():
    assert area_key("Gn. Putri") == area_key("Gunung Putri") == "gunungputri"


def test_google_components_become_district_names():
    assert place_from_components(
        [
            {"long_name": "Gondangdia", "types": ["administrative_area_level_4", "political"]},
            {"long_name": "Kecamatan Menteng", "types": ["administrative_area_level_3", "political"]},
            {"long_name": "Kota Administrasi Jakarta Pusat", "types": ["administrative_area_level_2", "political"]},
            {"long_name": "Daerah Khusus Ibukota Jakarta", "types": ["administrative_area_level_1", "political"]},
        ]
    ) == {
        "kelurahan": "Gondangdia",
        "kecamatan": "Menteng",
        "kabupaten": "Jakarta Pusat",
        "provinsi": "DKI Jakarta",
    }


def test_compose_names_a_stored_fix_without_publishing_coordinates():
    ledger = MemoryLedger()
    seen = {}

    def geocode(latitude, longitude):
        seen["fix"] = (latitude, longitude)
        return {
            "kelurahan": "Gondangdia",
            "kecamatan": "Menteng",
            "kabupaten": "Jakarta Pusat",
            "provinsi": "DKI Jakarta",
        }

    result = compose_post(
        {"provider": "Telkom", "speed": {"download_mbps": 20}, "latitude": -6.11, "longitude": 106.11},
        classify=lambda brief: {"post_intent": "praise", "quality_band": "excellent", "topic": "none"},
        draft=lambda _brief, _classification: {"sentence": "Telkom di Menteng lancar.", "summary": "ok"},
        store=ledger,
        fixes=[{"latitude": -6.18421, "longitude": 106.82745}],
        geocode=geocode,
    )
    assert seen["fix"] == (-6.18421, 106.82745)
    run_id = result["url"].rsplit("/", 1)[-1]
    stored = ledger.run(run_id)
    assert stored["kelurahan"] == "Gondangdia"
    assert stored["kecamatan"] == "Menteng"
    assert stored["kabupaten"] == "Jakarta Pusat"
    assert stored["provinsi"] == "DKI Jakarta"
    assert "latitude" not in stored
    assert "longitude" not in stored
    page = result_page(ledger, run_id)[1].decode("utf-8")
    assert "<strong>Kelurahan</strong><span>Gondangdia</span>" in page
    assert "<strong>Kecamatan</strong><span>Menteng</span>" in page
    assert "-6.18421" not in page
    assert "106.82745" not in page


def test_public_portal_compares_districts_without_the_old_pitch():
    portal = _portal()
    portal.ledger.save_run({
        "id": "a",
        "created_at": 2,
        "provinsi": "DKI Jakarta",
        "kabupaten": "Jakarta Pusat",
        "kecamatan": "Menteng",
        "public_ip": "203.0.113.9",
        "transport": "wifi",
        "provider": "Telkom",
        "when": "25/10 10:00",
        "speed": {"download_mbps": 10, "upload_mbps": 5, "ping_ms": 20},
        "video": {"resolution": "720p", "load_ms": 400, "buffering_pct": 2},
    })
    portal.ledger.save_run({
        "id": "b",
        "created_at": 3,
        "provinsi": "DKI Jakarta",
        "kabupaten": "Jakarta Pusat",
        "kecamatan": "Menteng",
        "transport": "wifi",
        "provider": "Indosat",
        "when": "25/10 11:00",
        "speed": {"download_mbps": 30, "upload_mbps": 15, "ping_ms": 40},
        "video": {"resolution": "1080p", "load_ms": 800, "buffering_pct": 9},
    })
    portal.ledger.save_run({
        "id": "e",
        "created_at": 5,
        "provinsi": "DKI Jakarta",
        "kabupaten": "Jakarta Pusat",
        "kecamatan": "Menteng",
        "transport": "cell",
        "provider": "Telkomsel",
        "when": "25/10 12:00",
        "speed": {"download_mbps": 50, "upload_mbps": 8, "ping_ms": 25},
        "video": {"resolution": "480p", "load_ms": 900, "buffering_pct": 4},
    })
    portal.ledger.save_run({
        "id": "f",
        "created_at": 6,
        "provinsi": "DKI Jakarta",
        "kabupaten": "Jakarta Pusat",
        "kecamatan": "Menteng",
        "transport": "cellular",
        "provider": "XL",
        "when": "25/10 09:00",
        "speed": {"download_mbps": 12, "upload_mbps": 3, "ping_ms": 60},
        "video": {"resolution": "1080p", "load_ms": 250, "buffering_pct": 1},
    })
    portal.ledger.save_run({"id": "c", "created_at": 1, "kecamatan": "Menteng, Jakarta", "speed": {"download_mbps": 99}})
    portal.ledger.save_run({
        "id": "d",
        "created_at": 4,
        "provinsi": "DKI Jakarta",
        "kabupaten": "Jakarta Pusat",
        "kecamatan": "",
        "speed": {"download_mbps": 70},
    })
    status, payload, cookie = handle(portal, "GET", "/api/magnon/places", "", {}, False)
    assert status == 200 and cookie is None
    assert payload["places"] == [{
        "provinsi": "DKI Jakarta",
        "kabupaten": "Jakarta Pusat",
        "kecamatan": "Menteng",
        "provinsi_key": "dkijakarta",
        "kabupaten_key": "jakartapusat",
        "kabupaten_keys": ["jakartapusat", "kotajakartapusat"],
        "kecamatan_key": "menteng",
        "download_mbps": 50,
        "wifi": {
            "download_mbps": 30,
            "upload_mbps": 15,
            "ping_ms": 40,
            "speed_provider": "Indosat",
            "speed_when": "25/10 11:00",
            "video_resolution": "720p",
            "video_load_ms": 400,
            "video_buffering_pct": 2,
            "video_provider": "Telkom",
            "video_when": "25/10 10:00",
        },
        "cell": {
            "download_mbps": 50,
            "upload_mbps": 8,
            "ping_ms": 25,
            "speed_provider": "Telkomsel",
            "speed_when": "25/10 12:00",
            "video_resolution": "1080p",
            "video_load_ms": 250,
            "video_buffering_pct": 1,
            "video_provider": "XL",
            "video_when": "25/10 09:00",
        },
    }]
    assert "203.0.113.9" not in str(payload["places"])
    assert "DKI Jakarta" in [item["provinsi"] for item in payload["regions"]]
    areas = area_features("dkijakarta", "jakartapusat")
    assert "Menteng" in [feature["properties"]["kecamatan"] for feature in areas["features"]]
    assert {feature["properties"]["kabupaten"] for feature in areas["features"]} == {"Jakarta Pusat"}
    assert best_places(portal.ledger.list_runs())[0]["wifi"]["download_mbps"] == 30
    assert best_places(portal.ledger.list_runs())[0]["cell"]["video_load_ms"] == 250
    page = home_html().decode("utf-8")
    visible = re.sub(r"data:[^\"]+", "", page).lower()
    for banned in ("sponsor", "premium", "credit", "points", "radio", "/admin"):
        assert banned not in visible, banned
    assert "placetable" not in visible
    assert "median" not in visible
    assert "placemap" in visible
    assert "placeprovinsi" in visible
    assert "placekabupaten" in visible
    assert "leaflet" in visible
    assert "openstreetmap" in visible
    assert "choose a province and a regency." in visible
    assert "pilih provinsi dan kabupaten." in visible
    assert "magnon lite" in visible
    assert "/api/magnon/places" in visible
    assert "bagikan hasil uji" in visible
    assert "/magnon-lite.apk" in visible
    assert "unduh apk" in visible
    assert "download apk" in visible
