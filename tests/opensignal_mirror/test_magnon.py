"""Magnon credits, points, redaction, and sponsor matching. No Firestore."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))

from opensignal_mirror.magnon import (  # noqa: E402
    MemoryLedger,
    Portal,
    aggregate,
    haversine_m,
    strip_identity,
)


def _portal(operators: str = "") -> Portal:
    clock = {"now": 1_700_000_000.0}

    def verify(token: str, client_id: str) -> dict:
        email, _sep, name = token.partition("|")
        return {"sub": "sub-" + email, "email": email, "name": name or email, "email_verified": True}

    from opensignal_mirror.magnon import parse_operators

    return Portal(
        MemoryLedger(),
        "client",
        parse_operators(operators),
        verify=verify,
        clock=lambda: clock["now"],
    )


def test_login_starts_lite_and_binds_device():
    portal = _portal()
    result = portal.login("ada@gmail.com|Ada", "device1234", "lite")
    assert result["profile"]["level"] == "lite"
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


def test_post_credit_is_three_per_day_and_test_credit_is_premium_only():
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


def test_points_once_per_event_and_radio_cap():
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
    sponsor = portal.login("ops@gmail.com|Ops", "", "lite")
    token = sponsor["session"]
    try:
        portal.create_campaign(token, {"lat": -6.2, "lon": 106.8, "radius_m": 1000, "start": 1, "end": 2})
        denied = False
    except ValueError as exc:
        denied = str(exc) == "contract_required"
    assert denied
    plain = portal.sponsor_view(token)
    assert plain["commercial_access"] is False
    assert "latitude" not in str(plain)
    portal.set_access("ops@gmail.com", True, "Telco")
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
        }
    )
    detail = portal.sponsor_view(token)
    assert detail["commercial_access"] is True
    results = detail["campaigns"][0]["results"]
    assert results[0]["download_bps"] == 50
    assert "email" not in results[0]
    assert haversine_m(-6.2, 106.8, -6.21, 106.8) < 2000
    assert "device_id" not in strip_identity({"device_id": "x", "download_bps": 1})
