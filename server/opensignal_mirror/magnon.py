"""Magnon accounts, credits, points, and sponsor campaigns.

Netprobe leaves MAGNON_PORTAL unset, so this module is not attached there.
"""

from __future__ import annotations

import base64
import hashlib
import html
import json
import math
import os
import re
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Callable
from zoneinfo import ZoneInfo

JAKARTA = ZoneInfo("Asia/Jakarta")
SESSION_TTL = 30 * 24 * 3600
POST_LIMIT = 3
FREE_POSTS_PER_DAY = 10
TEST_LIMIT = 20
RADIO_POINT_CAP = 30
POINT_VALUES = {"speed": 10, "video": 8, "radio": 1}


def credits_enabled() -> bool:
    """Points and daily credits stay off until MAGNON_CREDITS=1."""
    return os.environ.get("MAGNON_CREDITS", "0").strip() == "1"
MAX_RADIUS_M = 100_000

IDENTITY_KEYS = {
    "email",
    "name",
    "device_id",
    "imei",
    "imsi",
    "sub",
}
SENSITIVE_KEYS = IDENTITY_KEYS | {
    "lat",
    "lon",
    "latitude",
    "longitude",
    "enb",
    "gnb",
    "ci",
    "pci",
    "tac",
    "arfcn",
    "earfcn",
    "nrarfcn",
    "rsrp",
    "rsrq",
    "sinr",
    "rssi",
    "cqi",
    "ss_rsrp",
    "ss_rsrq",
    "ss_sinr",
    "gateway",
    "gateway_v6",
    "trace",
}


def day_key(now: float) -> str:
    return datetime.fromtimestamp(now, JAKARTA).date().isoformat()


def month_key(now: float) -> str:
    return day_key(now)[:7]


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlon / 2) ** 2
    return 2 * radius * math.asin(min(1.0, math.sqrt(a)))


def classify_test(event: dict) -> str | None:
    event_id = str(event.get("event_id") or "")
    summary = str(event.get("summary") or "")
    if event_id.startswith("myspd"):
        return "speed"
    if event_id.startswith("mysvd") or summary.startswith("mysignal-video"):
        return "video"
    return None


def _coords(event: dict) -> tuple[float, float] | None:
    for lat_key, lon_key in (("latitude", "longitude"), ("lat", "lon")):
        lat, lon = event.get(lat_key), event.get(lon_key)
        if isinstance(lat, (int, float)) and isinstance(lon, (int, float)):
            if -90 <= float(lat) <= 90 and -180 <= float(lon) <= 180:
                return float(lat), float(lon)
    return None


def strip_identity(event: dict) -> dict:
    return {key: value for key, value in event.items() if key not in IDENTITY_KEYS}


def aggregate(events: list[dict], brand: str) -> dict:
    brand_l = brand.strip().lower()
    totals = {"samples": 0, "lte": 0, "nr": 0, "wifi": 0, "cellular": 0}
    daily: dict[str, dict] = {}
    for event in events:
        if not _brand_match(event, brand_l):
            continue
        totals["samples"] += 1
        rat = str(event.get("rat") or event.get("data_label") or "").upper()
        if rat == "LTE" or "LTE" in rat:
            totals["lte"] += 1
            totals["cellular"] += 1
        elif rat in ("NR", "5G") or "NR" in rat:
            totals["nr"] += 1
            totals["cellular"] += 1
        transport = str(event.get("transport") or event.get("network") or "").lower()
        if "wifi" in transport or "wi-fi" in transport:
            totals["wifi"] += 1
        stamp = event.get("received_at") or event.get("ts_ms") or 0
        try:
            when = float(stamp) / 1000 if float(stamp) > 10_000_000_000 else float(stamp)
        except (TypeError, ValueError):
            when = 0
        day = day_key(when or time.time())
        bucket = daily.setdefault(
            day, {"download_bps": 0, "upload_bps": 0, "latency_ms": 0, "latency_n": 0, "samples": 0}
        )
        bucket["samples"] += 1
        for key in ("download_bps", "upload_bps"):
            value = event.get(key)
            if isinstance(value, (int, float)):
                bucket[key] += int(value)
        latency = event.get("latency_ms")
        if isinstance(latency, (int, float)):
            bucket["latency_ms"] += float(latency)
            bucket["latency_n"] += 1
    days = []
    for day, bucket in sorted(daily.items()):
        count = bucket["latency_n"] or 1
        days.append(
            {
                "day": day,
                "samples": bucket["samples"],
                "download_bps": bucket["download_bps"],
                "upload_bps": bucket["upload_bps"],
                "latency_ms": round(bucket["latency_ms"] / count, 1) if bucket["latency_n"] else None,
            }
        )
    return {"brand": brand, "totals": totals, "days": days}


def _brand_match(event: dict, brand_l: str) -> bool:
    if not brand_l:
        return False
    for key in ("provider", "operator", "carrier", "brand", "plmn_name"):
        value = event.get(key)
        if isinstance(value, str) and brand_l in value.lower():
            return True
    return False


def parse_operators(text: str) -> dict[str, str]:
    found = {}
    for part in text.split(","):
        item = part.strip()
        if not item:
            continue
        if "=" in item:
            email, brand = item.split("=", 1)
        else:
            email, brand = item, ""
        found[email.strip().lower()] = brand.strip()
    return found


def parse_emails(text: str) -> set[str]:
    return {part.strip().lower() for part in text.split(",") if part.strip()}


ROLE_ORDER = ("admin", "sponsor", "contributor")


def account_roles(user: dict) -> list[str]:
    """Roles on one Gmail account. A stored operator is a sponsor."""
    raw = user.get("roles")
    if isinstance(raw, list) and raw:
        source = [str(item) for item in raw]
    else:
        source = [part.strip() for part in str(user.get("role") or "contributor").split(",") if part.strip()]
    found: list[str] = []
    for item in source:
        name = "sponsor" if item == "operator" else item
        if name in ROLE_ORDER and name not in found:
            found.append(name)
    if not found:
        found.append("contributor")
    return sorted(found, key=ROLE_ORDER.index)


def store_roles(user: dict, roles: list[str]) -> None:
    user["roles"] = account_roles({"roles": roles})
    user["role"] = ",".join(user["roles"])


def parse_role_list(value: object) -> list[str]:
    if isinstance(value, str):
        parts = [part.strip() for part in value.split(",") if part.strip()]
    elif isinstance(value, (list, tuple)):
        parts = [str(part).strip() for part in value if str(part).strip()]
    else:
        raise ValueError("bad_role")
    if not parts:
        raise ValueError("bad_role")
    for part in parts:
        name = "sponsor" if part == "operator" else part
        if name not in ROLE_ORDER:
            raise ValueError("bad_role")
    return account_roles({"roles": parts})


def credit_limit(user: dict, kind: str) -> int:
    key, default = ("post_limit", POST_LIMIT) if kind == "post" else ("test_limit", TEST_LIMIT)
    try:
        value = int(user.get(key) if user.get(key) is not None else default)
    except (TypeError, ValueError):
        value = default
    return max(0, min(value, 1000))


class MemoryLedger:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self._user_rows: dict[str, dict] = {}
        self.sessions: dict[str, dict] = {}
        self.credits: dict[tuple[str, str], dict] = {}
        self.points: dict[str, dict] = {}
        self.campaigns: dict[str, dict] = {}
        self.jobs: dict[str, dict] = {}
        self.contracts: list[dict] = []
        self.events: list[dict] = []
        self.runs: dict[str, dict] = {}
        self.post_quotas: dict[str, dict] = {}

    def save_user(self, user: dict) -> None:
        with self.lock:
            self._user_rows[user["sub"]] = dict(user)

    def user_by_sub(self, sub: str) -> dict | None:
        with self.lock:
            row = self._user_rows.get(sub)
            return dict(row) if row else None

    def user_by_email(self, email: str) -> dict | None:
        email_l = email.lower()
        with self.lock:
            for row in self._user_rows.values():
                if str(row.get("email", "")).lower() == email_l:
                    return dict(row)
        return None

    def user_by_device(self, device_id: str) -> dict | None:
        with self.lock:
            for row in self._user_rows.values():
                if device_id in row.get("device_ids", []):
                    return dict(row)
        return None

    def premium_users(self) -> list[dict]:
        with self.lock:
            return [dict(row) for row in self._user_rows.values() if row.get("level") == "premium"]

    def contributors(self) -> list[dict]:
        with self.lock:
            return [dict(row) for row in self._user_rows.values() if row.get("level") in ("lite", "premium")]

    def users(self) -> list[dict]:
        with self.lock:
            return [dict(row) for row in self._user_rows.values()]

    def put_session(self, token: str, sub: str, exp: int) -> None:
        with self.lock:
            self.sessions[token] = {"sub": sub, "exp": exp}

    def session_sub(self, token: str, now: int) -> str | None:
        with self.lock:
            row = self.sessions.get(token)
            if not row or row["exp"] < now:
                return None
            return row["sub"]

    def bump_credit(self, sub: str, day: str, field: str, limit: int) -> tuple[bool, int]:
        with self.lock:
            row = self.credits.setdefault((sub, day), {"post_used": 0, "test_used": 0, "radio_points": 0})
            used = int(row.get(field, 0))
            if used >= limit:
                return False, used
            row[field] = used + 1
            return True, used + 1

    def credit_row(self, sub: str, day: str) -> dict:
        with self.lock:
            row = self.credits.get((sub, day), {"post_used": 0, "test_used": 0, "radio_points": 0})
            return dict(row)

    def add_point(self, event_id: str, row: dict) -> bool:
        with self.lock:
            if event_id in self.points:
                return False
            self.points[event_id] = dict(row)
            return True

    def points_rows(self) -> list[dict]:
        with self.lock:
            return [dict(row) for row in self.points.values()]

    def save_campaign(self, row: dict) -> None:
        with self.lock:
            self.campaigns[row["id"]] = dict(row)

    def save_job(self, row: dict) -> None:
        with self.lock:
            self.jobs[row["id"]] = dict(row)

    def job(self, job_id: str) -> dict | None:
        with self.lock:
            row = self.jobs.get(job_id)
            return dict(row) if row else None

    def jobs_for(self, sub: str) -> list[dict]:
        with self.lock:
            return [dict(row) for row in self.jobs.values() if row.get("sub") == sub]

    def campaigns_for(self, sub: str) -> list[dict]:
        with self.lock:
            return [dict(row) for row in self.campaigns.values() if row.get("owner_sub") == sub]

    def add_contract(self, row: dict) -> None:
        with self.lock:
            self.contracts.append(dict(row))

    def add_event(self, row: dict) -> None:
        with self.lock:
            self.events.append(dict(row))

    def events_for_devices(self, device_ids: list[str], limit: int) -> list[dict]:
        wanted = set(device_ids)
        with self.lock:
            rows = [dict(row) for row in self.events if row.get("device_id") in wanted]
        rows.sort(key=lambda item: item.get("received_at") or 0, reverse=True)
        return rows[:limit]

    def recent_events(self, limit: int) -> list[dict]:
        with self.lock:
            rows = [dict(row) for row in self.events]
        rows.sort(key=lambda item: item.get("received_at") or 0, reverse=True)
        return rows[:limit]

    def save_run(self, row: dict) -> None:
        with self.lock:
            self.runs[row["id"]] = dict(row)

    def run(self, run_id: str) -> dict | None:
        with self.lock:
            row = self.runs.get(run_id)
            return dict(row) if row else None

    def list_runs(self, limit: int = 500) -> list[dict]:
        with self.lock:
            rows = [dict(row) for row in self.runs.values()]
        rows.sort(key=lambda item: item.get("created_at") or 0, reverse=True)
        return rows[:limit]

    def post_quota(self, key: str) -> dict | None:
        with self.lock:
            row = self.post_quotas.get(key)
            return dict(row) if row else None

    def save_post_quota(self, key: str, row: dict) -> None:
        with self.lock:
            self.post_quotas[key] = dict(row)


class FirestoreLedger(MemoryLedger):
    """Same calls as MemoryLedger, stored in Firestore collections prefixed magnon_."""

    def __init__(self) -> None:
        super().__init__()
        from google.cloud import firestore

        self.client = firestore.Client()

    def save_user(self, user: dict) -> None:
        self.client.collection("magnon_users").document(user["sub"]).set(user)

    def user_by_sub(self, sub: str) -> dict | None:
        snap = self.client.collection("magnon_users").document(sub).get()
        return snap.to_dict() if snap.exists else None

    def user_by_email(self, email: str) -> dict | None:
        query = self.client.collection("magnon_users").where("email", "==", email.lower()).limit(1).stream()
        for snap in query:
            return snap.to_dict()
        return None

    def user_by_device(self, device_id: str) -> dict | None:
        query = (
            self.client.collection("magnon_users")
            .where("device_ids", "array_contains", device_id)
            .limit(1)
            .stream()
        )
        for snap in query:
            return snap.to_dict()
        return None

    def premium_users(self) -> list[dict]:
        query = self.client.collection("magnon_users").where("level", "==", "premium").stream()
        return [snap.to_dict() for snap in query]

    def contributors(self) -> list[dict]:
        return [snap.to_dict() for snap in self.client.collection("magnon_users").stream()]

    def users(self) -> list[dict]:
        return [snap.to_dict() for snap in self.client.collection("magnon_users").stream() if snap.to_dict()]

    def put_session(self, token: str, sub: str, exp: int) -> None:
        self.client.collection("magnon_sessions").document(token).set({"sub": sub, "exp": exp})

    def session_sub(self, token: str, now: int) -> str | None:
        snap = self.client.collection("magnon_sessions").document(token).get()
        if not snap.exists:
            return None
        row = snap.to_dict() or {}
        if int(row.get("exp", 0)) < now:
            return None
        return row.get("sub")

    def bump_credit(self, sub: str, day: str, field: str, limit: int) -> tuple[bool, int]:
        from google.cloud import firestore

        ref = self.client.collection("magnon_credits").document("%s_%s" % (sub, day))

        @firestore.transactional
        def update(transaction):
            snap = ref.get(transaction=transaction)
            row = snap.to_dict() if snap.exists else {"post_used": 0, "test_used": 0, "radio_points": 0}
            used = int(row.get(field, 0))
            if used >= limit:
                return False, used
            row[field] = used + 1
            transaction.set(ref, row)
            return True, used + 1

        return update(self.client.transaction())

    def credit_row(self, sub: str, day: str) -> dict:
        snap = self.client.collection("magnon_credits").document("%s_%s" % (sub, day)).get()
        if not snap.exists:
            return {"post_used": 0, "test_used": 0, "radio_points": 0}
        return snap.to_dict() or {}

    def add_point(self, event_id: str, row: dict) -> bool:
        ref = self.client.collection("magnon_points").document(event_id)
        if ref.get().exists:
            return False
        ref.set(row)
        return True

    def points_rows(self) -> list[dict]:
        return [snap.to_dict() for snap in self.client.collection("magnon_points").stream()]

    def save_campaign(self, row: dict) -> None:
        self.client.collection("magnon_campaigns").document(row["id"]).set(row)

    def save_job(self, row: dict) -> None:
        self.client.collection("magnon_jobs").document(row["id"]).set(row)

    def job(self, job_id: str) -> dict | None:
        snap = self.client.collection("magnon_jobs").document(job_id).get()
        return snap.to_dict() if snap.exists else None

    def jobs_for(self, sub: str) -> list[dict]:
        query = self.client.collection("magnon_jobs").where("sub", "==", sub).stream()
        return [snap.to_dict() for snap in query]

    def campaigns_for(self, sub: str) -> list[dict]:
        query = self.client.collection("magnon_campaigns").where("owner_sub", "==", sub).stream()
        return [snap.to_dict() for snap in query]

    def add_contract(self, row: dict) -> None:
        self.client.collection("magnon_contracts").document(row["id"]).set(row)

    def add_event(self, row: dict) -> None:
        return None

    def save_run(self, row: dict) -> None:
        self.client.collection("magnon_runs").document(row["id"]).set(row)

    def post_quota(self, key: str) -> dict | None:
        snap = self.client.collection("magnon_post_quota").document(key).get()
        return snap.to_dict() if snap.exists else None

    def save_post_quota(self, key: str, row: dict) -> None:
        self.client.collection("magnon_post_quota").document(key).set(row)

    def run(self, run_id: str) -> dict | None:
        snap = self.client.collection("magnon_runs").document(run_id).get()
        return snap.to_dict() if snap.exists else None

    def list_runs(self, limit: int = 500) -> list[dict]:
        rows = []
        for snap in self.client.collection("magnon_runs").stream():
            data = snap.to_dict()
            if data:
                rows.append(data)
            if len(rows) >= 2000:
                break
        rows.sort(key=lambda item: item.get("created_at") or 0, reverse=True)
        return rows[:limit]

    def events_for_devices(self, device_ids: list[str], limit: int) -> list[dict]:
        rows = []
        collection = self.client.collection("events")
        for device_id in device_ids[:10]:
            from google.cloud import firestore

            query = (
                collection.where("device_id", "==", device_id)
                .order_by("received_at", direction=firestore.Query.DESCENDING)
                .limit(limit)
                .stream()
            )
            for snap in query:
                rows.append(_event_row(snap.to_dict() or {}))
        rows.sort(key=lambda item: item.get("received_at") or 0, reverse=True)
        return rows[:limit]

    def recent_events(self, limit: int) -> list[dict]:
        from google.cloud import firestore

        query = (
            self.client.collection("events")
            .order_by("received_at", direction=firestore.Query.DESCENDING)
            .limit(limit)
            .stream()
        )
        return [_event_row(snap.to_dict() or {}) for snap in query]


def _event_row(data: dict) -> dict:
    payload = data.get("payload") or "{}"
    event = json.loads(payload) if isinstance(payload, str) else dict(payload)
    event["received_at"] = data.get("received_at")
    event["device_id"] = data.get("device_id")
    event["role"] = data.get("role")
    return event


class Portal:
    def __init__(
        self,
        ledger: MemoryLedger,
        client_id: str,
        operators: dict[str, str],
        admins: set[str] | None = None,
        verify: Callable[[str, str], dict] | None = None,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self.ledger = ledger
        self.client_id = client_id
        self.operators = operators
        self.admins = {item.lower() for item in (admins or set())}
        self.verify = verify or verify_google_token
        self.clock = clock or time.time

    def now(self) -> int:
        return int(self.clock())

    def login(self, id_token: str, device_id: str, flavor: str, nonce: str = "") -> dict:
        if not self.client_id:
            raise ValueError("login_not_configured")
        if flavor not in ("lite", "premium"):
            raise ValueError("bad_flavor")
        info = self.verify(id_token, self.client_id)
        email = str(info["email"]).lower()
        if not info.get("email_verified", True):
            raise ValueError("email_unverified")
        token_nonce = str(info.get("nonce") or "")
        if not nonce_matches(nonce, token_nonce):
            raise ValueError("bad_nonce")
        sub = str(info["sub"])
        user = self.ledger.user_by_sub(sub) or {
            "sub": sub,
            "email": email,
            "name": str(info.get("name") or email.split("@")[0])[:80],
            "role": "contributor",
            "roles": ["contributor"],
            "level": "lite",
            "post_limit": POST_LIMIT,
            "test_limit": TEST_LIMIT,
            "operator_brand": "",
            "commercial_access": False,
            "device_ids": [],
        }
        user["email"] = email
        user["name"] = str(info.get("name") or user.get("name") or email.split("@")[0])[:80]
        if user.get("post_limit") is None:
            user["post_limit"] = POST_LIMIT
        if user.get("test_limit") is None:
            user["test_limit"] = TEST_LIMIT
        roles = set(account_roles(user))
        if email in self.admins:
            roles.add("admin")
        if email in self.operators:
            roles.add("sponsor")
        store_roles(user, list(roles))
        if email in self.operators:
            user["operator_brand"] = self.operators[email] or user.get("operator_brand") or ""
        devices = list(user.get("device_ids") or [])
        if device_id and _device_ok(device_id) and device_id not in devices:
            devices.append(device_id)
            user["device_ids"] = devices[-8:]
        self.ledger.save_user(user)
        token = secrets.token_urlsafe(32)
        self.ledger.put_session(token, sub, self.now() + SESSION_TTL)
        return {"session": token, "profile": self._public(user, flavor)}

    def user_for_token(self, token: str) -> dict | None:
        if not token:
            return None
        sub = self.ledger.session_sub(token, self.now())
        if not sub:
            return None
        return self.ledger.user_by_sub(sub)

    def me(self, token: str, flavor: str) -> dict:
        user = self._need(token)
        return self._public(user, flavor)

    def reserve(self, token: str, kind: str) -> dict:
        user = self._need(token)
        if kind == "post":
            field, limit = "post_used", credit_limit(user, "post")
        elif kind == "test":
            if user.get("level") != "premium":
                raise ValueError("stay_on_lite")
            field, limit = "test_used", credit_limit(user, "test")
        else:
            raise ValueError("bad_kind")
        profile = self._public(user, "premium" if kind == "test" else "lite")
        if not credits_enabled():
            return {"ok": True, "used": 0, "limit": limit, "profile": profile}
        ok, used = self.ledger.bump_credit(user["sub"], day_key(self.now()), field, limit)
        if not ok:
            raise ValueError("credit_exhausted")
        return {"ok": True, "used": used, "limit": limit, "profile": profile}

    def award(self, device_id: str, event_id: str, kind: str, event: dict | None = None) -> int:
        if kind not in POINT_VALUES or not event_id:
            return 0
        user = self.ledger.user_by_device(device_id) if device_id else None
        if not user:
            return 0
        coords = _coords(event or {})
        if coords:
            user["last_lat"], user["last_lon"] = coords
            self.ledger.save_user(user)
        if not credits_enabled():
            return 0
        if kind == "radio":
            if user.get("level") != "premium":
                return 0
            ok, _used = self.ledger.bump_credit(user["sub"], day_key(self.now()), "radio_points", RADIO_POINT_CAP)
            if not ok:
                return 0
        points = POINT_VALUES[kind]
        stored = self.ledger.add_point(
            event_id,
            {
                "event_id": event_id,
                "sub": user["sub"],
                "name": user.get("name") or "Contributor",
                "kind": kind,
                "points": points,
                "day": day_key(self.now()),
                "at": self.now(),
            },
        )
        return points if stored else 0

    def note_tests(self, device_id: str, tests: list[dict], snapshots: list[dict] | None = None) -> None:
        location = {}
        for snap in snapshots or []:
            coords = _coords(snap)
            if coords:
                location = {"latitude": coords[0], "longitude": coords[1]}
                break
        for test in tests:
            kind = classify_test(test)
            if not kind:
                continue
            merged = dict(test)
            merged.update(location)
            self.award(device_id, str(test.get("event_id") or ""), kind, merged)

    def leaderboard(self) -> dict:
        month = month_key(self.now())
        month_scores: dict[str, dict] = {}
        all_scores: dict[str, dict] = {}
        for row in self.ledger.points_rows():
            sub = row["sub"]
            points = int(row.get("points") or 0)
            name = row.get("name") or "Contributor"
            bucket = all_scores.setdefault(sub, {"name": name, "points": 0})
            bucket["points"] += points
            bucket["name"] = name
            if str(row.get("day") or "").startswith(month):
                current = month_scores.setdefault(sub, {"name": name, "points": 0})
                current["points"] += points
                current["name"] = name
        return {"month": _top(month_scores), "all_time": _top(all_scores)}

    def jobs(self, token: str) -> list[dict]:
        user = self._need(token)
        return [row for row in self.ledger.jobs_for(user["sub"]) if row.get("status") == "open"]

    def decide_job(self, token: str, job_id: str, accept: bool) -> dict:
        user = self._need(token)
        job = self.ledger.job(job_id)
        if not job or job.get("sub") != user["sub"]:
            raise ValueError("job_not_found")
        if job.get("status") != "open":
            raise ValueError("job_closed")
        job["status"] = "accepted" if accept else "skipped"
        job["decided_at"] = self.now()
        self.ledger.save_job(job)
        return {"ok": True, "status": job["status"]}

    def create_campaign(self, token: str, body: dict) -> dict:
        user = self._need(token)
        if "sponsor" not in account_roles(user):
            raise ValueError("sponsor_only")
        lat = float(body["lat"])
        lon = float(body["lon"])
        radius = float(body["radius_m"])
        start = int(body["start"])
        end = int(body["end"])
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise ValueError("bad_area")
        if radius <= 0 or radius > MAX_RADIUS_M or end <= start:
            raise ValueError("bad_window")
        audience = str(body.get("audience") or "both").lower()
        if audience not in ("lite", "premium", "both"):
            raise ValueError("bad_audience")
        wanted = {"lite", "premium"} if audience == "both" else {audience}
        campaign_id = "cmp" + secrets.token_hex(6)
        matched = []
        counts = {"lite": 0, "premium": 0}
        today = day_key(self.now())
        for candidate in self.ledger.contributors():
            level = str(candidate.get("level") or "")
            if "contributor" not in account_roles(candidate):
                continue
            if level not in wanted or candidate.get("sub") == user["sub"]:
                continue
            if candidate.get("last_lat") is None or candidate.get("last_lon") is None:
                continue
            distance = haversine_m(lat, lon, float(candidate["last_lat"]), float(candidate["last_lon"]))
            if distance > radius:
                continue
            if level == "premium":
                credit = self.ledger.credit_row(candidate["sub"], today)
                if int(credit.get("test_used", 0)) >= credit_limit(candidate, "test"):
                    continue
            job_id = "job" + secrets.token_hex(6)
            self.ledger.save_job(
                {
                    "id": job_id,
                    "campaign_id": campaign_id,
                    "sub": candidate["sub"],
                    "level": level,
                    "status": "open",
                    "start": start,
                    "end": end,
                    "radius_m": radius,
                }
            )
            matched.append(job_id)
            counts[level] += 1
        campaign = {
            "id": campaign_id,
            "owner_sub": user["sub"],
            "brand": user.get("operator_brand") or "",
            "audience": audience,
            "lat": lat,
            "lon": lon,
            "radius_m": radius,
            "start": start,
            "end": end,
            "jobs": matched,
        }
        self.ledger.save_campaign(campaign)
        return {"id": campaign_id, "matched": len(matched), "lite": counts["lite"], "premium": counts["premium"]}

    def request_contract(self, token: str, note: str) -> dict:
        user = self._need(token)
        if "sponsor" not in account_roles(user):
            raise ValueError("sponsor_only")
        row = {
            "id": "ctr" + secrets.token_hex(6),
            "sub": user["sub"],
            "email": user.get("email"),
            "brand": user.get("operator_brand") or "",
            "note": note[:500],
            "at": self.now(),
        }
        self.ledger.add_contract(row)
        return {"ok": True, "id": row["id"]}

    def own_detail(self, token: str) -> dict:
        user = self._need(token)
        if user.get("level") != "premium":
            raise ValueError("premium_only")
        rows = self.ledger.events_for_devices(list(user.get("device_ids") or []), 40)
        return {"events": rows}

    def sponsor_view(self, token: str) -> dict:
        user = self._need(token)
        if "sponsor" not in account_roles(user):
            raise ValueError("sponsor_only")
        campaigns = []
        for campaign in self.ledger.campaigns_for(user["sub"]):
            accepted_devices = []
            for job_id in campaign.get("jobs") or []:
                job = self.ledger.job(job_id)
                if not job or job.get("status") != "accepted":
                    continue
                contributor = self.ledger.user_by_sub(job["sub"])
                if contributor:
                    accepted_devices.extend(contributor.get("device_ids") or [])
            rows = self.ledger.events_for_devices(accepted_devices, 80)
            detail = []
            for event in rows:
                stamp = int(event.get("received_at") or 0)
                if campaign["start"] <= stamp <= campaign["end"]:
                    detail.append(strip_identity(event))
            item = dict(campaign)
            item["results"] = detail
            campaigns.append(item)
        return {"campaigns": campaigns}

    def set_level(self, email: str, level: str) -> dict:
        if level not in ("lite", "premium"):
            raise ValueError("bad_level")
        user = self.ledger.user_by_email(email.lower())
        if not user:
            raise ValueError("user_not_found")
        user["level"] = level
        self.ledger.save_user(user)
        return {"ok": True, "email": user["email"], "level": level}

    def set_access(self, email: str, commercial: bool, brand: str) -> dict:
        user = self.ledger.user_by_email(email.lower())
        if not user:
            raise ValueError("user_not_found")
        roles = set(account_roles(user))
        if commercial:
            roles.add("sponsor")
        else:
            roles.discard("sponsor")
        if not roles:
            roles.add("contributor")
        store_roles(user, list(roles))
        user["commercial_access"] = bool(commercial)
        if brand:
            user["operator_brand"] = brand[:80]
        self.ledger.save_user(user)
        return {"ok": True, "email": user["email"], "commercial_access": user["commercial_access"]}

    def list_users(self, token: str) -> dict:
        actor = self._need(token)
        if "admin" not in account_roles(actor):
            raise PermissionError("admin_only")
        today = day_key(self.now())
        rows = []
        for user in self.ledger.users():
            if not user or not user.get("email"):
                continue
            credit = self.ledger.credit_row(user["sub"], today)
            rows.append(self._admin_row(user, credit))
        rows.sort(key=lambda row: row["email"])
        return {"users": rows}

    def update_user(self, token: str, email: str, role: object, level: str, post_limit: object, test_limit: object) -> dict:
        actor = self._need(token)
        if "admin" not in account_roles(actor):
            raise PermissionError("admin_only")
        chosen = parse_role_list(role)
        if level not in ("lite", "premium"):
            raise ValueError("bad_level")
        try:
            post_value = int(post_limit)
            test_value = int(test_limit)
        except (TypeError, ValueError):
            raise ValueError("bad_credit")
        if post_value < 0 or test_value < 0 or post_value > 1000 or test_value > 1000:
            raise ValueError("bad_credit")
        user = self.ledger.user_by_email(email.lower())
        if not user:
            raise ValueError("user_not_found")
        if str(actor.get("email") or "").lower() == str(user.get("email") or "").lower() and "admin" not in chosen:
            raise ValueError("keep_admin")
        store_roles(user, chosen)
        user["level"] = level
        user["post_limit"] = post_value
        user["test_limit"] = test_value
        self.ledger.save_user(user)
        return self._admin_row(user, self.ledger.credit_row(user["sub"], day_key(self.now())))

    def post_credit(self, token: str, device_id: str, amount: object = None) -> dict:
        actor = self._need(token)
        if "admin" not in account_roles(actor):
            raise PermissionError("admin_only")
        ident = str(device_id or "").strip()
        if len(ident) < 8 or len(ident) > 64:
            raise ValueError("bad_device")
        if amount in (None, ""):
            return read_post_quota(self.ledger, ident, self.now())
        try:
            added = int(amount)
        except (TypeError, ValueError):
            raise ValueError("bad_credit")
        if added < 1 or added > 1000:
            raise ValueError("bad_credit")
        return add_post_balance(self.ledger, ident, added, self.now())

    def _admin_row(self, user: dict, credit: dict) -> dict:
        return {
            "email": user.get("email"),
            "name": user.get("name") or "",
            "roles": account_roles(user),
            "level": user.get("level") or "lite",
            "post_limit": credit_limit(user, "post"),
            "test_limit": credit_limit(user, "test"),
            "post_used": int(credit.get("post_used", 0)),
            "test_used": int(credit.get("test_used", 0)),
        }

    def _need(self, token: str) -> dict:
        user = self.user_for_token(token)
        if not user:
            raise PermissionError("login_required")
        return user

    def _public(self, user: dict, flavor: str) -> dict:
        credit = self.ledger.credit_row(user["sub"], day_key(self.now()))
        mine = [row for row in self.ledger.points_rows() if row.get("sub") == user["sub"]]
        balance = sum(int(row.get("points") or 0) for row in mine)
        history = sorted(mine, key=lambda row: row.get("at") or 0, reverse=True)[:20]
        return {
            "sub": user.get("sub"),
            "email": user.get("email"),
            "name": user.get("name"),
            "roles": account_roles(user),
            "role": ",".join(account_roles(user)),
            "level": user.get("level"),
            "flavor": flavor,
            "operator_brand": user.get("operator_brand") or "",
            "commercial_access": bool(user.get("commercial_access")),
            "points": balance,
            "history": [{"kind": row.get("kind"), "points": row.get("points"), "day": row.get("day")} for row in history],
            "post_used": int(credit.get("post_used", 0)),
            "post_limit": credit_limit(user, "post"),
            "test_used": int(credit.get("test_used", 0)),
            "test_limit": credit_limit(user, "test"),
            "stay_on_lite": flavor == "premium" and user.get("level") != "premium",
        }


def _top(scores: dict[str, dict]) -> list[dict]:
    ordered = sorted(scores.values(), key=lambda row: row["points"], reverse=True)
    return [{"name": row["name"], "points": row["points"]} for row in ordered[:20]]


def _device_ok(value: str) -> bool:
    if len(value) < 8 or len(value) > 64:
        return False
    return all(ch.isalnum() or ch in "_-" for ch in value)


def nonce_matches(sent: str, token_nonce: str) -> bool:
    """Accept the raw nonce or the SHA-256 form Google sometimes puts in the token."""
    if not sent:
        return True
    got = token_nonce.strip()
    if got == sent:
        return True
    digest = hashlib.sha256(sent.encode("utf-8")).digest()
    encoded = base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")
    return got.rstrip("=") == encoded or got.lower() == digest.hex()


def verify_google_token(token: str, client_id: str) -> dict:
    from google.auth.transport import requests
    from google.oauth2 import id_token

    info = id_token.verify_oauth2_token(token, requests.Request(), client_id)
    if info.get("aud") != client_id:
        raise ValueError("bad_audience")
    if not info.get("email_verified"):
        raise ValueError("email_unverified")
    return {
        "sub": info["sub"],
        "email": info.get("email", ""),
        "name": info.get("name") or "",
        "email_verified": True,
        "nonce": info.get("nonce") or "",
    }


def build_portal(env: dict) -> Portal:
    client_id = env.get("MAGNON_GOOGLE_CLIENT_ID", "").strip()
    operators = parse_operators(env.get("MAGNON_OPERATOR_EMAILS", ""))
    admins = parse_emails(env.get("MAGNON_ADMIN_EMAILS", ""))
    if env.get("OSM_STORE", "sqlite") == "firestore":
        ledger: MemoryLedger = FirestoreLedger()
    else:
        ledger = MemoryLedger()
    return Portal(ledger, client_id, operators, admins)


def _data_uri(filename: str) -> str:
    raw = (Path(__file__).resolve().parent / "static" / filename).read_bytes()
    return "data:image/png;base64," + base64.b64encode(raw).decode("ascii")


_AREA_DIR = Path(__file__).resolve().parent / "static" / "areas"
_AREA_LOCK = threading.Lock()
_AREA_INDEX: dict | None = None
_AREA_FILES: dict[str, dict] = {}
_AREA_SPACE = re.compile(r"[^a-z0-9]+")
_AREA_PREFIX = re.compile(r"^(provinsi|kabupaten|kecamatan|kab|kec)\s+")
_AREA_JAKARTA_KOTA = re.compile(r"^kota(?:\s+administrasi)?\s+(?=jakarta\b)")
_AREA_SHORT = {"gn": "gunung", "tg": "tanjung", "pd": "pondok"}


def area_key(value: object, province: bool = False) -> str:
    """Lower-case place name with labels and Jakarta aliases folded together."""
    text = _AREA_SPACE.sub(" ", str(value or "").lower()).strip()
    text = _AREA_PREFIX.sub("", text).strip()
    text = _AREA_JAKARTA_KOTA.sub("", text).strip()
    text = " ".join(_AREA_SHORT.get(part, part) for part in text.split())
    compact = text.replace(" ", "")
    if province and compact in {
        "jakarta",
        "dkijakarta",
        "jakartaraya",
        "daerahkhususibukotajakarta",
    }:
        return "dkijakarta"
    if province and compact in {"yogyakarta", "diyogyakarta", "daerahistimewayogyakarta"}:
        return "yogyakarta"
    if province and compact in {"bangkabelitung", "kepulauanbangkabelitung"}:
        return "bangkabelitung"
    return compact


def kabupaten_keys(value: object) -> list[str]:
    """City and regency spellings that should meet the same boundary."""
    primary = area_key(value)
    if not primary:
        return []
    extra = primary[4:] if primary.startswith("kota") and len(primary) > 4 else "kota" + primary
    if extra and extra != primary:
        return [primary, extra]
    return [primary]


def _public_number(value: object):
    number = _number(value)
    if number is None:
        return None
    rounded = round(float(number), 1)
    if rounded == int(rounded):
        return int(rounded)
    return rounded


def _place_label(value: object) -> str:
    return str(value or "").strip()[:64]


def _network_bucket(value: object) -> str:
    text = re.sub(r"[^a-z]", "", str(value or "").lower())
    if text == "wifi":
        return "wifi"
    if text in {"cell", "cellular"}:
        return "cell"
    return ""


def _best_lane(items: list[dict], bucket: str) -> dict:
    best_speed = None
    best_video = None
    for item in items:
        if _network_bucket(item.get("transport")) != bucket:
            continue
        speed = item.get("speed") or {}
        download = _number(speed.get("download_mbps"))
        if download is not None and (best_speed is None or download > best_speed[0]):
            best_speed = (float(download), item)
        video = item.get("video") or {}
        load = _number(video.get("load_ms"))
        if load is not None and (best_video is None or load < best_video[0]):
            best_video = (float(load), item)
    speed_item = best_speed[1] if best_speed else {}
    video_item = best_video[1] if best_video else {}
    speed = speed_item.get("speed") or {}
    video = video_item.get("video") or {}
    return {
        "download_mbps": _public_number(speed.get("download_mbps")) if best_speed else None,
        "upload_mbps": _public_number(speed.get("upload_mbps")) if best_speed else None,
        "ping_ms": _public_number(speed.get("ping_ms")) if best_speed else None,
        "speed_provider": _place_label(speed.get("provider") or speed_item.get("provider")),
        "speed_when": _place_label(speed_item.get("when")),
        "video_resolution": str(video.get("resolution") or "") if best_video else "",
        "video_load_ms": _public_number(video.get("load_ms")) if best_video else None,
        "video_buffering_pct": _public_number(video.get("buffering_pct")) if best_video else None,
        "video_provider": _place_label(video_item.get("provider")),
        "video_when": _place_label(video_item.get("when")),
    }


def best_places(rows: list[dict]) -> list[dict]:
    """Best Wi-Fi and cellular speed and video per kecamatan. No identity or coordinates."""
    groups: dict[tuple[str, str, str], list[dict]] = {}
    for row in rows:
        provinsi = str(row.get("provinsi") or "").strip()
        kabupaten = str(row.get("kabupaten") or "").strip()
        kecamatan = str(row.get("kecamatan") or "").strip()
        key = (area_key(provinsi, province=True), area_key(kabupaten), area_key(kecamatan))
        if not all(key):
            continue
        groups.setdefault(key, []).append(row)
    places = []
    for key, items in groups.items():
        wifi = _best_lane(items, "wifi")
        cell = _best_lane(items, "cell")
        downloads = [
            value
            for value in (wifi["download_mbps"], cell["download_mbps"])
            if value is not None
        ]
        if not downloads and wifi["video_load_ms"] is None and cell["video_load_ms"] is None:
            continue
        sample = items[0]
        places.append(
            {
                "provinsi": sample.get("provinsi") or "",
                "kabupaten": sample.get("kabupaten") or "",
                "kecamatan": sample.get("kecamatan") or "",
                "provinsi_key": key[0],
                "kabupaten_key": key[1],
                "kabupaten_keys": kabupaten_keys(sample.get("kabupaten") or ""),
                "kecamatan_key": key[2],
                "download_mbps": max(downloads) if downloads else None,
                "wifi": wifi,
                "cell": cell,
            }
        )
    places.sort(key=lambda item: (item["provinsi_key"], item["kabupaten_key"], item["kecamatan_key"]))
    return places


def _area_index() -> dict:
    global _AREA_INDEX
    with _AREA_LOCK:
        if _AREA_INDEX is None:
            path = _AREA_DIR / "index.json"
            try:
                _AREA_INDEX = json.loads(path.read_text(encoding="utf-8"))
            except OSError:
                _AREA_INDEX = {"regions": []}
        return _AREA_INDEX


def _province_file(provinsi: str) -> Path | None:
    key = area_key(provinsi, province=True)
    for region in _area_index().get("regions") or []:
        if area_key(region.get("provinsi") or "", province=True) == key:
            return _AREA_DIR / str(region.get("file") or "")
    return None


def _province_geo(provinsi: str) -> dict:
    key = area_key(provinsi, province=True)
    with _AREA_LOCK:
        cached = _AREA_FILES.get(key)
    if cached is not None:
        return cached
    path = _province_file(provinsi)
    if path is None:
        return {"type": "FeatureCollection", "features": []}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except OSError:
        return {"type": "FeatureCollection", "features": []}
    with _AREA_LOCK:
        _AREA_FILES[key] = data
    return data


def area_regions() -> list[dict]:
    regions = []
    for region in _area_index().get("regions") or []:
        name = str(region.get("provinsi") or "")
        regions.append(
            {
                "provinsi": name,
                "provinsi_key": area_key(name, province=True),
                "kabupaten": [
                    {"name": item, "key": area_key(item)}
                    for item in region.get("kabupaten") or []
                ],
            }
        )
    return regions


def area_features(provinsi: str, kabupaten: str) -> dict:
    """Kecamatan polygons for one kabupaten. The homepage does not embed them."""
    if not str(provinsi or "").strip() or not str(kabupaten or "").strip():
        return {"type": "FeatureCollection", "features": []}
    wanted = area_key(kabupaten)
    features = []
    for feature in _province_geo(provinsi).get("features") or []:
        props = feature.get("properties") or {}
        if area_key(props.get("kabupaten") or "") != wanted:
            continue
        name = str(props.get("provinsi") or "")
        regency = str(props.get("kabupaten") or "")
        district = str(props.get("kecamatan") or "")
        features.append(
            {
                "type": "Feature",
                "properties": {
                    "provinsi": name,
                    "kabupaten": regency,
                    "kecamatan": district,
                    "provinsi_key": area_key(name, province=True),
                    "kabupaten_key": area_key(regency),
                    "kecamatan_key": area_key(district),
                },
                "geometry": feature.get("geometry"),
            }
        )
    return {"type": "FeatureCollection", "features": features}


def home_html() -> bytes:
    page = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Magnon | Crowdsourced network quality</title>
<meta name="description" content="Magnon Lite shares speed and video tests from real phones so anyone can compare results by district.">
<link rel="icon" type="image/png" href="__ICON__">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{margin:0;font-family:Arial,Helvetica,sans-serif;color:#141414;background:#fff;line-height:1.45}
a{color:inherit;text-decoration:none}
.top{display:flex;align-items:center;justify-content:space-between;gap:24px;background:#111;color:#fff;padding:14px 28px}
.brand{height:46px;width:auto;background:#fff;border-radius:4px;padding:2px 8px}
.top nav{display:flex;align-items:center;gap:22px;font-size:15px}
.top nav a:hover{opacity:.75}
.signin{border:1px solid #fff;border-radius:4px;padding:8px 14px}
.ribbon{background:#5b2dff;color:#fff;text-align:center;font-weight:700;padding:12px 16px}
.hero{display:grid;grid-template-columns:minmax(0,1.1fr) minmax(220px,.7fr);gap:32px;align-items:center;background:linear-gradient(110deg,#071824 0%,#0c3b55 58%,#123 100%);color:#fff;padding:72px 8vw 80px;min-height:520px}
h1{font-size:clamp(46px,6.4vw,84px);line-height:.95;letter-spacing:-.035em;font-weight:700;margin:0 0 22px}
.hero p{font-size:clamp(18px,2vw,24px);max-width:40rem;margin:0 0 28px;color:#d7e6ef}
.btn{display:inline-block;background:#fff;color:#111;font-weight:700;padding:14px 22px;border-radius:4px}
.btn.dark{background:#111;color:#fff}
.rings{height:280px;border-radius:50%;background:radial-gradient(circle,#7ee7ff 0 6px,transparent 7px),radial-gradient(circle,transparent 34%,rgba(126,231,255,.55) 35% 36%,transparent 37%),radial-gradient(circle,transparent 58%,rgba(126,231,255,.35) 59% 60%,transparent 61%),radial-gradient(circle,transparent 80%,rgba(126,231,255,.22) 81% 82%,transparent 83%);background-position:center;background-repeat:no-repeat}
.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:18px;padding:54px 8vw;background:#fff}
.stats strong{display:block;font-size:clamp(40px,5vw,64px);letter-spacing:-.04em;line-height:1}
.stats span{display:block;color:#444;margin-top:8px;max-width:14rem}
section{padding:72px 8vw}
h2{font-size:clamp(32px,4vw,48px);line-height:1.05;letter-spacing:-.03em;margin:0 0 12px}
.sub{font-size:20px;color:#333;max-width:42rem;margin:0 0 28px}
.steps{display:grid;grid-template-columns:repeat(3,1fr);gap:22px}
.steps div{border-top:3px solid #111;padding-top:16px}
.steps strong{display:block;font-size:20px;margin-bottom:8px}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:18px}
article{background:#f4f6f8;padding:28px;border-radius:8px}
article h3{margin:0 0 10px;font-size:28px}
article p{margin:0;font-size:17px}
.band{background:#071824;color:#fff}
.band .sub{color:#d7e6ef}
.board{margin-top:22px}
.board li{margin:6px 0}
.lang{display:flex;gap:8px}
.lang button{background:transparent;color:#fff;border:1px solid #fff;border-radius:4px;padding:6px 10px;cursor:pointer;font-weight:700}
.lang button.active{background:#fff;color:#111}
table{width:100%;border-collapse:collapse}
th,td{text-align:left;padding:12px 8px;border-bottom:1px solid #e4e4e4}
th{font-size:13px;color:#444}
.filters{display:flex;gap:12px;flex-wrap:wrap;margin:0 0 16px}
.filters label{display:flex;flex-direction:column;gap:6px;font-size:13px;color:#444}
.filters select{font:inherit;color:#141414;padding:10px 12px;min-width:220px;border:1px solid #ccc;border-radius:4px;background:#fff}
#placeMap{height:960px;border:1px solid #e4e4e4;border-radius:8px}
.place-pop .leaflet-popup-content{margin:12px 14px;width:300px !important}
.place-pop .place-title{display:block;font-size:18px;margin:0 0 4px}
.place-pop h3{margin:12px 0 8px;font-size:16px;letter-spacing:-.02em}
.place-pop .meta{color:#444;margin:0 0 8px;font-size:13px}
.place-pop .facts{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:0 0 4px}
.place-pop .facts div{background:#f4f6f8;border-radius:8px;padding:10px}
.place-pop .facts strong{display:block;color:#444;font-size:12px;font-weight:700}
.place-pop .facts span{display:block;font-size:16px;letter-spacing:-.03em;line-height:1.2}
#placeEmpty{color:#444;margin:0 0 12px}
footer{background:#111;color:#ddd;padding:48px 8vw 28px}
.cols{display:grid;grid-template-columns:repeat(3,1fr);gap:24px}
footer h2{color:#fff;font-size:16px;letter-spacing:0;margin:0 0 12px}
footer a{display:block;margin:8px 0;color:#ddd}
.legal{margin-top:28px;color:#9a9a9a;font-size:13px}
@media(max-width:800px){
.hero,.stats,.steps,.grid,.cols{grid-template-columns:1fr}
.top{align-items:flex-start;flex-direction:column}
.top nav{flex-wrap:wrap}
.hero{padding:48px 24px;min-height:0}
section,.stats{padding:48px 24px}
footer{padding:36px 24px}
.rings{height:180px}
}
</style></head><body>
<header class="top">
<a href="/"><img class="brand" src="__LOGO__" alt="Magnon"></a>
<nav>
<a href="#measure" data-i18n="navHow">How it works</a>
<a href="#places" data-i18n="navPlaces">Compare</a>
<a class="signin" href="/magnon-lite.apk" data-i18n="downloadApk">Download APK</a>
<div class="lang"><button id="langEn" type="button">EN</button><button id="langId" type="button">ID</button></div>
</nav>
</header>
<div class="ribbon" data-i18n="ribbon">Crowdsourced network quality</div>
<section class="hero">
<div>
<h1 data-i18n-html="heroTitle">Share a real test.<br>Compare the district.</h1>
<p data-i18n="heroBody">Magnon Lite measures speed and video on the phone you already use. Choose loops with the same provider and transport, then draft one public link for all of them. Google is not required.</p>
<a class="btn" href="/magnon-lite.apk" data-i18n="downloadApk">Download APK</a>
</div>
<div class="rings" aria-hidden="true"></div>
</section>
<section id="measure">
<h2 data-i18n="howTitle">From a phone test to a public comparison</h2>
<p class="sub" data-i18n="howSub">Each phone can post ten new tests a day. For more, copy the device id on the Post tab and send it to @MagnonTest on X.</p>
<div class="steps">
<div><strong data-i18n="step1">01 &nbsp;Test</strong><span data-i18n="step1Body">Run Speed or Video. Google is not required. Post lists only the ten newest loops.</span></div>
<div><strong data-i18n="step2">02 &nbsp;Draft</strong><span data-i18n="step2Body">Select loops that share a provider and a transport. One draft is one link, and the page lists every selected test.</span></div>
<div><strong data-i18n="step3">03 &nbsp;Compare</strong><span data-i18n="step3Body">Anyone can open the link. The district map compares shared results and shows no email and no exact place.</span></div>
</div>
</section>
<section id="places">
<h2 data-i18n="placesTitle">Best tests by district</h2>
<p class="sub" data-i18n="placesSub">Best Wi-Fi and cellular speed and video tests in each district.</p>
<div class="filters">
<label><span data-i18n="filterProvinsi">Province</span><select id="placeProvinsi"></select></label>
<label><span data-i18n="filterKabupaten">Regency</span><select id="placeKabupaten"></select></label>
</div>
<p id="placeEmpty" data-i18n="placesEmpty">Choose a province and a regency.</p>
<div id="placeMap"></div>
</section>
<footer>
<div class="cols">
<div><h2 data-i18n="footerProduct">Product</h2><a href="#measure" data-i18n="navHow">How it works</a><a href="#places" data-i18n="navPlaces">Compare</a></div>
<div><h2 data-i18n="footerPost">Post</h2><a href="/magnon-lite.apk" data-i18n="downloadApk">Download APK</a></div>
<div><h2>Magnon</h2><a href="/">magnon.site</a></div>
</div>
<p class="legal">magnon.site</p>
</footer>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
var copy = {
  en: {
    ribbon: "Crowdsourced network quality",
    heroTitle: "Share a real test.<br>Compare the district.",
    heroBody: "Magnon Lite measures speed and video on the phone you already use. Choose loops with the same provider and transport, then draft one public link for all of them. Google is not required.",
    signIn: "Share the draft",
    downloadApk: "Download APK",
    navHow: "How it works",
    navPlaces: "Compare",
    howTitle: "From a phone test to a public comparison",
    howSub: "Each phone can post ten new tests a day. For more, copy the device id on the Post tab and send it to @MagnonTest on X.",
    step1: "01  Test",
    step1Body: "Run Speed or Video. Google is not required. Post lists only the ten newest loops.",
    step2: "02  Draft",
    step2Body: "Select loops that share a provider and a transport. One draft is one link, and the page lists every selected test.",
    step3: "03  Compare",
    step3Body: "Anyone can open the link. The district map compares shared results and shows no email and no exact place.",
    placesTitle: "Best tests by district",
    placesSub: "Best Wi-Fi and cellular speed and video tests in each district.",
    placesEmpty: "Choose a province and a regency.",
    placesNone: "No shared result yet.",
    filterProvinsi: "Province",
    filterKabupaten: "Regency",
    footerProduct: "Product",
    footerPost: "Post",
    colDown: "Download",
    colUp: "Upload",
    colPing: "Ping",
    colResolution: "Resolution",
    colVideo: "Load",
    colBuffer: "Buffering",
    colProvider: "Provider",
    colWhen: "Time",
    wifiLabel: "Wi-Fi",
    cellLabel: "Cellular",
    speedLabel: "Speed",
    videoLabel: "Video"
  },
  id: {
    ribbon: "Kualitas jaringan dari banyak orang",
    heroTitle: "Bagikan hasil uji.<br>Bandingkan kecamatannya.",
    heroBody: "Magnon Lite mengukur kecepatan dan video di ponsel yang sedang dipakai. Pilih loop dengan provider dan transport yang sama, lalu buat satu tautan publik untuk semuanya. Google tidak wajib.",
    signIn: "Bagikan draf",
    downloadApk: "Unduh APK",
    navHow: "Cara kerja",
    navPlaces: "Bandingkan",
    howTitle: "Dari uji di ponsel ke perbandingan publik",
    howSub: "Setiap ponsel bisa memposting sepuluh uji baru sehari. Untuk menambahnya, salin device id di tab Post dan kirim ke @MagnonTest di X.",
    step1: "01  Uji",
    step1Body: "Jalankan Speed atau Video. Google tidak wajib. Tab Post hanya menampilkan sepuluh loop terbaru.",
    step2: "02  Draf",
    step2Body: "Pilih loop dengan provider dan transport yang sama. Satu draf menjadi satu tautan, dan halamannya memuat setiap uji yang dipilih.",
    step3: "03  Bandingkan",
    step3Body: "Siapa pun bisa membuka tautannya. Peta kecamatan membandingkan hasil yang dibagikan, tanpa email dan tanpa titik lokasi.",
    placesTitle: "Hasil terbaik per kecamatan",
    placesSub: "Hasil speed dan video terbaik untuk Wi-Fi dan seluler di tiap kecamatan.",
    placesEmpty: "Pilih provinsi dan kabupaten.",
    placesNone: "Belum ada hasil yang dibagikan.",
    filterProvinsi: "Provinsi",
    filterKabupaten: "Kabupaten",
    footerProduct: "Produk",
    footerPost: "Bagikan",
    colDown: "Unduh",
    colUp: "Unggah",
    colPing: "Ping",
    colResolution: "Resolusi",
    colVideo: "Muat",
    colBuffer: "Buffering",
    colProvider: "Provider",
    colWhen: "Jam",
    wifiLabel: "Wi-Fi",
    cellLabel: "Seluler",
    speedLabel: "Speed",
    videoLabel: "Video"
  }
};
var regions = [];
var places = [];
var map = null;
var shapes = null;
var drawn = null;
function currentLang() {
  var saved = localStorage.getItem("magnon-lang");
  if (saved === "id" || saved === "en") return saved;
  return (navigator.language || "").toLowerCase().indexOf("id") === 0 ? "id" : "en";
}
function escapeHtml(value) {
  return String(value == null ? "" : value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
function bootMap() {
  if (map || !window.L) return;
  map = L.map("placeMap").setView([-2.5, 118], 5);
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 18,
    attribution: "&copy; OpenStreetMap &middot; Boundaries &copy; GADM"
  }).addTo(map);
}
function option(value, label) {
  var node = document.createElement("option");
  node.value = value;
  node.textContent = label;
  return node;
}
function selectedRegion() {
  var key = document.getElementById("placeProvinsi").value;
  var found = null;
  regions.forEach(function(region) {
    if (region.provinsi_key === key) found = region;
  });
  return found;
}
function fillRegions() {
  var provinsi = document.getElementById("placeProvinsi");
  var kabupaten = document.getElementById("placeKabupaten");
  var selectedProvinsi = provinsi.value;
  var selectedKabupaten = kabupaten.value;
  var text = copy[currentLang()];
  provinsi.innerHTML = "";
  provinsi.appendChild(option("", text.filterProvinsi));
  regions.forEach(function(region) {
    provinsi.appendChild(option(region.provinsi_key, region.provinsi));
  });
  if (selectedProvinsi) provinsi.value = selectedProvinsi;
  if (provinsi.value !== selectedProvinsi) provinsi.value = "";
  fillKabupaten(selectedKabupaten);
}
function fillKabupaten(selected) {
  var kabupaten = document.getElementById("placeKabupaten");
  var text = copy[currentLang()];
  var region = selectedRegion();
  kabupaten.innerHTML = "";
  kabupaten.appendChild(option("", text.filterKabupaten));
  if (region) {
    region.kabupaten.forEach(function(item) {
      kabupaten.appendChild(option(item.key, item.name));
    });
  }
  if (selected) kabupaten.value = selected;
  if (kabupaten.value !== selected) kabupaten.value = "";
}
function placeFor(feature) {
  var props = feature.properties || {};
  var found = null;
  places.forEach(function(row) {
    if (row.provinsi_key !== props.provinsi_key || row.kecamatan_key !== props.kecamatan_key) return;
    var keys = row.kabupaten_keys || [row.kabupaten_key];
    if (keys.indexOf(props.kabupaten_key) < 0) return;
    if (!found || row.kabupaten_key === props.kabupaten_key) found = row;
  });
  return found;
}
function shade(value, low, high) {
  if (value == null || value === "") return "#d9d9d9";
  var span = high - low;
  var mix = span <= 0 ? 1 : (value - low) / span;
  if (mix < 0) mix = 0;
  if (mix > 1) mix = 1;
  var start = [199, 224, 214];
  var end = [14, 111, 78];
  var channels = [0, 1, 2].map(function(index) {
    return Math.round(start[index] + (end[index] - start[index]) * mix);
  });
  return "rgb(" + channels.join(",") + ")";
}
function factBox(label, value) {
  if (value == null || value === "") return "";
  return '<div><strong>' + escapeHtml(label) + '</strong><span>' + escapeHtml(value) + '</span></div>';
}
function factGrid(boxes) {
  if (!boxes) return "";
  return '<div class="facts">' + boxes + '</div>';
}
function shown(value, suffix) {
  if (value == null || value === "") return "";
  return value + suffix;
}
function laneBoxes(text, lane, kind) {
  if (kind === "speed") {
    return factBox(text.colProvider, lane.speed_provider)
      + factBox(text.colWhen, lane.speed_when)
      + factBox(text.colDown, shown(lane.download_mbps, " Mbps"))
      + factBox(text.colUp, shown(lane.upload_mbps, " Mbps"))
      + factBox(text.colPing, shown(lane.ping_ms, " ms"));
  }
  return factBox(text.colProvider, lane.video_provider)
    + factBox(text.colWhen, lane.video_when)
    + factBox(text.colResolution, lane.video_resolution)
    + factBox(text.colVideo, shown(lane.video_load_ms, " ms"))
    + factBox(text.colBuffer, shown(lane.video_buffering_pct, "%"));
}
function laneHtml(text, title, lane) {
  var head = '<h3>' + escapeHtml(title) + '</h3>';
  if (!lane || (lane.download_mbps == null && lane.video_load_ms == null)) {
    return head + '<p class="meta">' + escapeHtml(text.placesNone) + '</p>';
  }
  var shared = lane.download_mbps != null && lane.video_load_ms != null
    && (lane.speed_provider || "") === (lane.video_provider || "")
    && (lane.speed_when || "") === (lane.video_when || "");
  if (shared) {
    return head + factGrid(
      factBox(text.colProvider, lane.speed_provider)
      + factBox(text.colWhen, lane.speed_when)
      + factBox(text.colDown, shown(lane.download_mbps, " Mbps"))
      + factBox(text.colUp, shown(lane.upload_mbps, " Mbps"))
      + factBox(text.colPing, shown(lane.ping_ms, " ms"))
      + factBox(text.colResolution, lane.video_resolution)
      + factBox(text.colVideo, shown(lane.video_load_ms, " ms"))
      + factBox(text.colBuffer, shown(lane.video_buffering_pct, "%"))
    );
  }
  var body = head;
  if (lane.download_mbps != null) body += '<p class="meta">' + escapeHtml(text.speedLabel) + '</p>' + factGrid(laneBoxes(text, lane, "speed"));
  if (lane.video_load_ms != null) body += '<p class="meta">' + escapeHtml(text.videoLabel) + '</p>' + factGrid(laneBoxes(text, lane, "video"));
  return body;
}
function popupHtml(feature, row) {
  var text = copy[currentLang()];
  var title = '<strong class="place-title">' + escapeHtml((feature.properties || {}).kecamatan || "") + '</strong>';
  if (!row) return title + '<p class="meta">' + escapeHtml(text.placesNone) + '</p>';
  return title + laneHtml(text, text.wifiLabel, row.wifi) + laneHtml(text, text.cellLabel, row.cell);
}
function draw(collection) {
  bootMap();
  if (!map || !window.L) return;
  if (shapes) map.removeLayer(shapes);
  drawn = collection;
  var low = null;
  var high = null;
  (collection.features || []).forEach(function(feature) {
    var row = placeFor(feature);
    if (!row || row.download_mbps == null) return;
    if (low == null || row.download_mbps < low) low = row.download_mbps;
    if (high == null || row.download_mbps > high) high = row.download_mbps;
  });
  shapes = L.geoJSON(collection, {
    style: function(feature) {
      var row = placeFor(feature);
      var value = row ? row.download_mbps : null;
      return {color: "#4d5c66", weight: 1, fillColor: shade(value, low == null ? 0 : low, high == null ? 0 : high), fillOpacity: 0.75};
    },
    onEachFeature: function(feature, shape) {
      shape.bindPopup(popupHtml(feature, placeFor(feature)), {maxWidth: 360, minWidth: 300, autoPanPadding: [28, 28], className: "place-pop"});
    }
  }).addTo(map);
  if (shapes.getLayers().length) map.fitBounds(shapes.getBounds(), {padding: [18, 18]});
}
function loadArea() {
  var provinsi = document.getElementById("placeProvinsi").value;
  var kabupaten = document.getElementById("placeKabupaten").value;
  document.getElementById("placeEmpty").hidden = !!(provinsi && kabupaten);
  if (!provinsi || !kabupaten) {
    if (shapes && map) map.removeLayer(shapes);
    shapes = null;
    drawn = null;
    if (map) map.setView([-2.5, 118], 5);
    return;
  }
  fetch("/api/magnon/areas?provinsi=" + encodeURIComponent(provinsi) + "&kabupaten=" + encodeURIComponent(kabupaten)).then(function(response) {
    return response.json();
  }).then(draw).catch(function(){});
}
function apply(lang) {
  if (lang !== "id") lang = "en";
  localStorage.setItem("magnon-lang", lang);
  document.documentElement.lang = lang === "id" ? "id" : "en";
  var text = copy[lang];
  document.querySelectorAll("[data-i18n]").forEach(function(node) {
    var key = node.getAttribute("data-i18n");
    if (text[key]) node.textContent = text[key];
  });
  document.querySelectorAll("[data-i18n-html]").forEach(function(node) {
    var key = node.getAttribute("data-i18n-html");
    if (text[key]) node.innerHTML = text[key];
  });
  document.getElementById("langEn").className = lang === "en" ? "active" : "";
  document.getElementById("langId").className = lang === "id" ? "active" : "";
  fillRegions();
  if (drawn) draw(drawn);
  bootMap();
}
document.getElementById("langEn").onclick = function(){ apply("en"); };
document.getElementById("langId").onclick = function(){ apply("id"); };
document.getElementById("placeProvinsi").onchange = function() {
  fillKabupaten("");
  loadArea();
};
document.getElementById("placeKabupaten").onchange = loadArea;
apply(currentLang());
fetch("/api/magnon/places").then(function(response){ return response.json(); }).then(function(data){
  regions = data.regions || [];
  places = data.places || [];
  fillRegions();
  if (drawn) draw(drawn);
}).catch(function(){});
</script>
</body></html>"""
    return page.replace("__LOGO__", _data_uri("magnon-logo.png")).replace("__ICON__", _data_uri("magnon-icon.png")).encode("utf-8")


def portal_html(client_id: str, kind: str = "contrib") -> bytes:
    configured = "true" if client_id else "false"
    client = client_id.replace('"', "")
    page_kind = kind if kind in ("admin", "sponsor", "contrib") else "contrib"
    title = {"admin": "Admin", "sponsor": "Sponsor", "contrib": "Contributor"}[page_kind]
    page = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Magnon | __TITLE__</title>
<link rel="icon" type="image/png" href="__ICON__">
<style>
*{box-sizing:border-box}
body{margin:0;font-family:Arial,Helvetica,sans-serif;background:#fff;color:#141414}
a{color:#141414}
.top{display:flex;align-items:center;justify-content:space-between;background:#111;color:#fff;padding:14px 28px}
.brand{height:46px;width:auto;background:#fff;border-radius:4px;padding:2px 8px}
.top a{color:#fff;text-decoration:none;margin-left:18px}
.top nav a:first-child{margin-left:0}
main{max-width:880px;margin:0 auto;padding:36px 24px 72px}
h1{font-size:40px;letter-spacing:-.03em;margin:0 0 8px}
h2{font-size:28px;letter-spacing:-.02em;margin:28px 0 10px}
.muted{color:#555}
button,input,select{font:inherit}
button{background:#111;color:#fff;border:0;border-radius:4px;padding:10px 14px}
input,select{border:1px solid #ccc;border-radius:4px;padding:8px;margin:0 6px 8px 0}
form{margin:0 0 16px}
#signin{width:240px}
table{width:100%;border-collapse:collapse;margin:8px 0 20px}
th,td{text-align:left;border-bottom:1px solid #e4e4e4;padding:8px 6px;vertical-align:middle}
.card{background:#f4f6f8;border-radius:8px;padding:14px 16px;margin:0 0 10px}
.card h3{margin:0 0 8px;font-size:16px}
dl{display:grid;grid-template-columns:160px 1fr;gap:4px 12px;margin:0}
dt{color:#555} dd{margin:0}
.note{background:#f4f6f8;border-radius:8px;padding:16px}
.roles label{display:block;margin:2px 0}
</style></head><body>
<header class="top"><a href="/"><img class="brand" src="__LOGO__" alt="Magnon"></a>
<nav><a href="/admin">Admin</a><a href="/sponsor">Sponsor</a><a href="/contrib">Contributor</a></nav>
</header>
<main>
<h1>__TITLE__</h1>
<p id="who" class="muted">Sign in with Gmail.</p>
<div id="signin"></div>
<p id="unconfigured" class="muted" hidden>Sign-in is not configured.</p>
<p id="denied" class="note" hidden></p>
<section id="app" hidden>
<p id="balance"></p>
<section id="adminWrap" hidden>
<h2>Post balance</h2>
<form id="quotaForm">
<input id="quotaDevice" placeholder="device id" size="28">
<input id="quotaAdd" type="number" min="1" max="1000" placeholder="add" size="6">
<button type="submit">Add</button>
<button type="button" id="quotaLookup">Look up</button>
</form>
<p id="quotaStatus" class="muted"></p>
<h2>Accounts</h2>
<p id="adminStatus" class="muted"></p>
<div id="users"></div>
</section>
<section id="sponsorWrap" hidden>
<h2>Request a measurement</h2>
<form id="campaignForm">
<input name="lat" placeholder="latitude" size="10">
<input name="lon" placeholder="longitude" size="10">
<input name="radius_m" placeholder="radius m" size="10">
<input name="start" placeholder="start unix" size="12">
<input name="end" placeholder="end unix" size="12">
<select name="audience">
<option value="both">lite and premium</option>
<option value="lite">lite</option>
<option value="premium">premium</option>
</select>
<button type="submit">Request</button>
</form>
<div id="sponsor"></div>
</section>
<section id="mineWrap" hidden>
<h2>Radio and network</h2>
<div id="mine"></div>
</section>
<section id="liteWrap" hidden>
<h2>Magnon Lite</h2>
<p class="note">This account uses Magnon Lite. Points are shown here. Measurement detail is not available on Lite.</p>
</section>
</section>
<script>
const configured = __CONFIGURED__;
const clientId = "__CLIENT__";
const page = "__PAGE__";
const ROLE_NAMES = ["admin","sponsor","contributor"];
const DETAIL_KEYS = ["received_at","provider","rat","network_type","download_bps","upload_bps","latency_ms","rsrp","rsrq","sinr","rssi","ss_rsrp","ss_rsrq","ss_sinr","pci","arfcn","earfcn","nrarfcn","band","cqi"];
function esc(value){return String(value).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}
function pick(event){
  const found = {};
  function walk(obj){
    if (!obj || typeof obj !== "object" || Array.isArray(obj)) return;
    Object.keys(obj).forEach(function(key){
      const value = obj[key];
      if (value && typeof value === "object") walk(value);
      else if (DETAIL_KEYS.indexOf(key) >= 0 && found[key] == null && value != null && value !== "") found[key] = value;
    });
  }
  walk(event);
  return found;
}
function detailCards(events){
  if (!events.length) return "<p class='muted'>No radio or network measurements yet.</p>";
  const html = events.map(function(event){
    const fields = pick(event);
    const keys = Object.keys(fields);
    if (!keys.length) return "";
    return "<article class='card'><dl>"+keys.map(function(key){return "<dt>"+esc(key)+"</dt><dd>"+esc(fields[key])+"</dd>"}).join("")+"</dl></article>";
  }).join("");
  return html || "<p class='muted'>No radio or network measurements yet.</p>";
}
function campaignCards(campaigns){
  if (!campaigns.length) return "<p class='muted'>No requests yet.</p>";
  return campaigns.map(function(row){
    const results = (row.results || []).map(function(item){
      const fields = pick(item);
      const keys = Object.keys(fields);
      if (!keys.length) return "";
      return "<dl>"+keys.map(function(key){return "<dt>"+esc(key)+"</dt><dd>"+esc(fields[key])+"</dd>"}).join("")+"</dl>";
    }).join("");
    return "<article class='card'><h3>"+esc(row.audience)+" · "+esc(row.radius_m)+" m</h3><p class='muted'>"+esc(row.lat)+", "+esc(row.lon)+" · "+esc(row.start)+" – "+esc(row.end)+"</p>"+results+"</article>";
  }).join("");
}
async function api(path, body) {
  const opt = body ? {method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify(body)} : {};
  const res = await fetch(path, opt);
  const data = await res.json();
  if (!res.ok) throw data;
  return data;
}
async function readQuota(adding) {
  const status = document.getElementById("quotaStatus");
  status.textContent = "";
  const body = {device_id: document.getElementById("quotaDevice").value.trim()};
  if (adding) body.add = Number(document.getElementById("quotaAdd").value);
  try {
    const data = await api("/api/magnon/post-credit", body);
    status.textContent = "Usage " + data.free_used + "/" + data.free_limit + " · credit " + data.balance;
  } catch (err) {
    status.textContent = (err && err.error) || "Lookup failed";
  }
}
document.getElementById("quotaLookup").onclick = function(){ readQuota(false); };
document.getElementById("quotaForm").onsubmit = function(ev){ ev.preventDefault(); readQuota(true); };
async function loadUsers(){
  const data = await api("/api/magnon/users");
  const rows = data.users || [];
  document.getElementById("users").innerHTML = "<table><thead><tr><th>Email</th><th>Roles</th><th>Level</th><th>Post credit</th><th>Premium tests</th><th></th></tr></thead><tbody>"+
    rows.map(function(row, index){
      const roles = row.roles || [];
      const boxes = ROLE_NAMES.map(function(role){return "<label><input type='checkbox' data-k='"+role+"'"+(roles.indexOf(role)>=0?" checked":"")+"> "+role+"</label>"}).join("");
      return "<tr data-i='"+index+"'><td>"+esc(row.email)+"</td><td class='roles'>"+boxes+"</td><td><select data-k='level'>"+["lite","premium"].map(function(level){return "<option"+(row.level===level?" selected":"")+">"+level+"</option>"}).join("")+"</select></td><td><input data-k='post_limit' type='number' min='0' max='1000' value='"+esc(row.post_limit)+"' size='4'></td><td><input data-k='test_limit' type='number' min='0' max='1000' value='"+esc(row.test_limit)+"' size='4'></td><td><button type='button' data-save='"+esc(row.email)+"'>Save</button></td></tr>";
    }).join("")+"</tbody></table>";
  document.querySelectorAll("[data-save]").forEach(function(button){
    button.onclick = async function(){
      const tr = button.closest("tr");
      const status = document.getElementById("adminStatus");
      status.textContent = "";
      try {
        await api("/api/magnon/users", {
          email: button.getAttribute("data-save"),
          roles: ROLE_NAMES.filter(function(role){return tr.querySelector("[data-k="+role+"]").checked}),
          level: tr.querySelector("[data-k=level]").value,
          post_limit: Number(tr.querySelector("[data-k=post_limit]").value),
          test_limit: Number(tr.querySelector("[data-k=test_limit]").value)
        });
        status.textContent = "Saved " + button.getAttribute("data-save");
        refresh();
      } catch (err) {
        status.textContent = (err && err.error) || "Save failed";
      }
    };
  });
}
async function refresh() {
  const me = await api("/api/magnon/me");
  const roles = me.roles || String(me.role || "").split(",").filter(Boolean);
  const has = function(role){return roles.indexOf(role) >= 0};
  const needed = page === "contrib" ? "contributor" : page;
  document.getElementById("who").textContent = me.name + " · " + roles.join(", ") + " · " + me.level;
  document.getElementById("balance").textContent = "Points " + me.points + " · posts " + me.post_used + "/" + me.post_limit + " · premium tests " + me.test_used + "/" + me.test_limit;
  if (!has(needed)) {
    document.getElementById("app").hidden = true;
    const denied = document.getElementById("denied");
    denied.hidden = false;
    denied.textContent = "This account does not have the " + needed + " role.";
    return;
  }
  document.getElementById("denied").hidden = true;
  document.getElementById("app").hidden = false;
  const premium = page === "contrib" && me.level === "premium";
  document.getElementById("adminWrap").hidden = page !== "admin";
  document.getElementById("sponsorWrap").hidden = page !== "sponsor";
  document.getElementById("mineWrap").hidden = !premium;
  document.getElementById("liteWrap").hidden = page !== "contrib" || me.level === "premium";
  if (page === "admin") await loadUsers();
  if (page === "sponsor") {
    const view = await api("/api/magnon/sponsor");
    document.getElementById("sponsor").innerHTML = campaignCards(view.campaigns || []);
  }
  if (premium) {
    const mine = await api("/api/magnon/mine");
    document.getElementById("mine").innerHTML = detailCards(mine.events || []);
  }
}
document.getElementById("campaignForm").onsubmit = async (ev) => {
  ev.preventDefault();
  const form = new FormData(ev.target);
  await api("/api/magnon/campaigns", {
    lat: Number(form.get("lat")), lon: Number(form.get("lon")),
    radius_m: Number(form.get("radius_m")),
    start: Number(form.get("start")), end: Number(form.get("end")),
    audience: form.get("audience") || "both"
  });
  refresh();
};
window.onGoogle = async (resp) => {
  await api("/v1/auth/google", {id_token: resp.credential, device_id: "", flavor: "lite"});
  refresh();
};
if (!configured) {
  document.getElementById("unconfigured").hidden = false;
} else {
  const s = document.createElement("script");
  s.src = "https://accounts.google.com/gsi/client";
  s.onload = () => {
    google.accounts.id.initialize({client_id: clientId, callback: onGoogle});
    google.accounts.id.renderButton(document.getElementById("signin"), {theme:"filled_black", size:"large"});
  };
  document.head.appendChild(s);
  refresh().catch(() => {});
}
</script></main></body></html>"""
    page = page.replace("__CONFIGURED__", configured).replace("__CLIENT__", client)
    page = page.replace("__PAGE__", page_kind).replace("__TITLE__", title)
    page = page.replace("__LOGO__", _data_uri("magnon-logo.png")).replace("__ICON__", _data_uri("magnon-icon.png"))
    return page.encode("utf-8")


def _coord(value: object, low: float, high: float):
    number = _number(value)
    if number is None or float(number) < low or float(number) > high:
        return None
    return float(number)


def _component(components: list, *types: str) -> str:
    for wanted in types:
        for item in components:
            if not isinstance(item, dict):
                continue
            if wanted in (item.get("types") or []):
                return str(item.get("long_name") or "").strip()
    return ""


def place_from_components(components: list) -> dict:
    """Kelurahan, kecamatan, kabupaten, and province from a Google address."""
    if not isinstance(components, list):
        return {}
    kelurahan = _clean_place(
        _component(components, "administrative_area_level_4", "sublocality_level_1", "neighborhood"),
        "kelurahan",
    )
    kecamatan = _clean_place(
        _component(components, "administrative_area_level_3", "sublocality"),
        "kecamatan",
    )
    kabupaten = _clean_place(
        _component(components, "administrative_area_level_2", "locality"),
        "kabupaten",
    )
    provinsi = _clean_place(_component(components, "administrative_area_level_1"), "provinsi")
    return {
        "kelurahan": kelurahan,
        "kecamatan": kecamatan,
        "kabupaten": kabupaten,
        "provinsi": provinsi,
    }


def _clean_place(value: str, kind: str) -> str:
    text = re.sub(r"\s+\d{5}$", "", str(value or "")).strip()
    text = re.sub(r"(?i)^(kelurahan|kel\.?|desa|kecamatan|kec\.?|kabupaten|kab\.?|provinsi)\s+", "", text).strip()
    if kind == "kabupaten":
        jakarta = re.match(r"(?i)^kota(?:\s+administrasi)?\s+(jakarta.*)$", text)
        if jakarta:
            text = jakarta.group(1).strip()
    if kind == "provinsi":
        key = text.lower()
        if "daerah khusus" in key or key in {"dki jakarta", "jakarta", "jakarta raya"}:
            return "DKI Jakarta"
        if key in {"di yogyakarta", "daerah istimewa yogyakarta", "yogyakarta"}:
            return "Yogyakarta"
    return text[:80]


def reverse_geocode(latitude: float, longitude: float) -> dict:
    """Resolve a stored test fix. The key stays in the environment and the URL is not logged."""
    key = os.environ.get("MAGNON_GMAP_API_KEY", "").strip()
    if not key:
        return {}
    query = urllib.parse.urlencode(
        {
            "latlng": "%s,%s" % (latitude, longitude),
            "language": "id",
            "key": key,
        }
    )
    request = urllib.request.Request(
        "https://maps.googleapis.com/maps/api/geocode/json?" + query,
        headers={"User-Agent": "magnon"},
    )
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (TimeoutError, urllib.error.URLError, OSError, json.JSONDecodeError):
        return {}
    if not isinstance(payload, dict) or payload.get("status") != "OK":
        return {}
    results = payload.get("results") or []
    if not results or not isinstance(results[0], dict):
        return {}
    return place_from_components(results[0].get("address_components") or [])


def _use_stored_fix(brief: dict, fixes: list[dict] | None) -> None:
    for event in fixes or []:
        coords = _coords(event)
        if coords:
            brief["latitude"], brief["longitude"] = coords
            return


def _fill_place(brief: dict, geocode: Callable) -> None:
    if str(brief.get("kecamatan") or "").strip() and str(brief.get("provinsi") or "").strip():
        return
    coords = _coords(brief)
    if not coords:
        return
    try:
        found = geocode(coords[0], coords[1]) or {}
    except Exception:
        return
    if not isinstance(found, dict):
        return
    for key in ("kelurahan", "kecamatan", "kabupaten", "provinsi"):
        if str(brief.get(key) or "").strip():
            continue
        name = str(found.get(key) or "").strip()[:80]
        if name:
            brief[key] = name


def _quota_key(device_id: str) -> str:
    return hashlib.sha256(device_id.strip().encode("utf-8")).hexdigest()


def _quota_state(row: dict | None, today: str) -> tuple[list[str], int, int]:
    balance = int((row or {}).get("balance") or 0)
    if not row or row.get("day") != today:
        return [], 0, balance
    used = []
    for item in row.get("used_ids") or []:
        ident = str(item or "").strip()
        if ident and ident not in used:
            used.append(ident)
    return used, int(row.get("free_used") or 0), balance


def _quota_public(free_used: int, balance: int) -> dict:
    return {
        "free_used": free_used,
        "free_limit": FREE_POSTS_PER_DAY,
        "free_left": max(0, FREE_POSTS_PER_DAY - free_used),
        "balance": balance,
    }


def read_post_quota(ledger: MemoryLedger, device_id: str, now: float | None = None) -> dict:
    today = day_key(now if now is not None else time.time())
    used, free_used, balance = _quota_state(ledger.post_quota(_quota_key(device_id)), today)
    del used
    return _quota_public(free_used, balance)


def charge_post_quota(ledger: MemoryLedger, device_id: str, event_ids: list[str], now: float | None = None) -> dict:
    """Count new event ids. The first 10 today are free. The rest spend balance. A short balance writes nothing."""
    today = day_key(now if now is not None else time.time())
    key = _quota_key(device_id)
    used, free_used, balance = _quota_state(ledger.post_quota(key), today)
    seen = set(used)
    fresh = []
    for event_id in event_ids:
        ident = str(event_id or "").strip()[:80]
        if not ident or ident in seen:
            continue
        seen.add(ident)
        fresh.append(ident)
    free_left = max(0, FREE_POSTS_PER_DAY - free_used)
    take_free = min(len(fresh), free_left)
    take_paid = len(fresh) - take_free
    if take_paid > balance:
        refused = _quota_public(free_used, balance)
        refused["ok"] = False
        return refused
    free_used += take_free
    balance -= take_paid
    used.extend(fresh)
    ledger.save_post_quota(
        key,
        {"day": today, "used_ids": used[-200:], "free_used": free_used, "balance": balance},
    )
    charged = _quota_public(free_used, balance)
    charged["ok"] = True
    return charged


def add_post_balance(ledger: MemoryLedger, device_id: str, amount: int, now: float | None = None) -> dict:
    today = day_key(now if now is not None else time.time())
    key = _quota_key(device_id)
    used, free_used, balance = _quota_state(ledger.post_quota(key), today)
    balance += amount
    ledger.save_post_quota(
        key,
        {"day": today, "used_ids": used[-200:], "free_used": free_used, "balance": balance},
    )
    return _quota_public(free_used, balance)


def compose_post(
    raw: dict,
    classify: Callable | None = None,
    draft: Callable | None = None,
    store: MemoryLedger | None = None,
    fixes: list[dict] | None = None,
    geocode: Callable | None = None,
) -> dict:
    """Jev chooses complaint or praise. DeepSeek writes the sentence and does not change that choice."""
    if not isinstance(raw, dict):
        raise ValueError("bad_brief")
    brief = _compose_brief(raw)
    events = _compose_events(raw)
    if events:
        brief["events"] = events
        for key in ("kelurahan", "kecamatan", "kabupaten", "provinsi", "transport", "provider", "carrier"):
            brief[key] = _agreed(events, key)
        brief["latitude"] = None
        brief["longitude"] = None
    else:
        _use_stored_fix(brief, fixes)
        _fill_place(brief, geocode or reverse_geocode)
    if not _compose_has_result(brief):
        return {
            "eligible": False,
            "sentence": "",
            "summary": "Run a Speed or Video test first.\nJev will classify the result; you decide whether to share.",
            "url": "",
            "post_intent": "skip",
            "quality_band": "",
            "topic": "",
            "classifier": "local",
            "composer": "",
        }
    device_id = str(raw.get("device_id") or "").strip()
    ledger = store or MemoryLedger()
    quota = None
    if device_id and events:
        quota = charge_post_quota(ledger, device_id, [str(event.get("id") or "") for event in events])
        if not quota["ok"]:
            return {
                "eligible": False,
                "sentence": "",
                "summary": "Daily post limit reached.\nCopy the device id at the top and DM @MagnonTest on X.",
                "url": "",
                "post_intent": "skip",
                "quality_band": "",
                "topic": "",
                "classifier": "local",
                "composer": "",
                "quota": "exhausted",
                "free_used": quota["free_used"],
                "free_limit": quota["free_limit"],
                "free_left": quota["free_left"],
                "balance": quota["balance"],
            }
    model_brief = _model_brief(brief)
    classification = (classify or classify_with_jev)(model_brief)
    intent = str(classification.get("post_intent") or "")
    if intent not in ("complaint", "praise"):
        intent = "complaint"
    classification = dict(classification)
    classification["post_intent"] = intent
    _attach_event_bands(brief.get("events") or [], classification.get("event_bands") or [])
    written = (draft or draft_with_deepseek)(model_brief, classification)
    sentence = str(written.get("sentence") or "").strip()
    if not sentence:
        raise RuntimeError("empty_draft")
    return {
        "eligible": True,
        "sentence": sentence[:180],
        "summary": str(written.get("summary") or "").strip()[:800],
        "url": publish_run(ledger, brief, sentence[:180]),
        "post_intent": intent,
        "quality_band": str(classification.get("quality_band") or "unknown"),
        "topic": str(classification.get("topic") or ""),
        "classifier": "jev",
        "composer": "deepseek",
        **({
            "free_used": quota["free_used"],
            "free_limit": quota["free_limit"],
            "free_left": quota["free_left"],
            "balance": quota["balance"],
        } if quota else {}),
    }


def _compose_has_result(brief: dict) -> bool:
    if brief.get("events"):
        return True
    speed = brief.get("speed") or {}
    video = brief.get("video") or {}
    if speed.get("download_mbps") is not None or speed.get("upload_mbps") is not None:
        return True
    return bool(str(video.get("resolution") or "").strip())


def _attach_event_bands(events: list, bands: list) -> None:
    for index, event in enumerate(events):
        if not isinstance(event, dict) or index >= len(bands):
            continue
        band = bands[index] if isinstance(bands[index], dict) else {}
        label = str(band.get("quality_band") or "").strip()
        if label in ("poor", "fair", "good", "excellent", "unknown"):
            event["quality_band"] = label


def _agreed(events: list[dict], key: str) -> str:
    values = [str(event.get(key) or "").strip() for event in events]
    if not values or any(not value for value in values):
        return ""
    return values[0] if all(value == values[0] for value in values) else ""


def _compose_events(raw: dict) -> list[dict]:
    rows = raw.get("events")
    if not isinstance(rows, list):
        return []
    chosen = []
    for row in rows[:20]:
        if not isinstance(row, dict):
            continue
        kind = "video" if str(row.get("kind") or "") == "video" else "speed"
        item = {
            "id": _clip(row.get("id"), 40),
            "kind": kind,
            "when": _clip(row.get("when"), 40),
            "kelurahan": _clip(row.get("kelurahan"), 80),
            "kecamatan": _clip(row.get("kecamatan"), 80),
            "kabupaten": _clip(row.get("kabupaten"), 80),
            "provinsi": _clip(row.get("provinsi"), 80),
            "transport": _clip(row.get("transport"), 16),
            "provider": _clip(row.get("provider"), 64),
        }
        if kind == "video":
            item["load_ms"] = _number(row.get("load_ms"))
            item["resolution"] = _clip(row.get("resolution"), 16)
            item["buffering_pct"] = _number(row.get("buffering_pct"))
            if not item["resolution"] and item["load_ms"] is None:
                continue
        else:
            item["download_mbps"] = _number(row.get("download_mbps"))
            item["upload_mbps"] = _number(row.get("upload_mbps"))
            item["ping_ms"] = _number(row.get("ping_ms"))
            item["jitter_ms"] = _number(row.get("jitter_ms"))
            if item["download_mbps"] is None and item["upload_mbps"] is None:
                continue
        chosen.append(item)
    return chosen


def _clip(value: object, limit: int) -> str:
    return str(value or "").strip()[:limit]


def _number(value: object):
    if value is None or value == "":
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number != number:
        return None
    return number


def _compose_brief(raw: dict) -> dict:
    speed = raw.get("speed") if isinstance(raw.get("speed"), dict) else {}
    video = raw.get("video") if isinstance(raw.get("video"), dict) else {}
    language = "id" if str(raw.get("language") or "").lower().startswith("id") else "en"
    return {
        "language": language,
        "style": _style(raw.get("style")),
        "kelurahan": _clip(raw.get("kelurahan"), 80),
        "kecamatan": _clip(raw.get("kecamatan"), 80),
        "kabupaten": _clip(raw.get("kabupaten"), 80),
        "provinsi": _clip(raw.get("provinsi"), 80),
        "transport": _clip(raw.get("transport"), 16),
        "carrier": _clip(raw.get("carrier"), 64),
        "provider": _clip(raw.get("provider"), 64),
        "mention": _clip(raw.get("mention"), 40),
        "signal": _clip(raw.get("signal"), 160),
        "radio": _clip(raw.get("radio"), 240),
        "when": _clip(raw.get("when"), 32),
        "public_ip": _clip(raw.get("public_ip"), 64),
        "latitude": _coord(raw.get("latitude"), -90, 90),
        "longitude": _coord(raw.get("longitude"), -180, 180),
        "speed": {
            "download_mbps": _number(speed.get("download_mbps")),
            "upload_mbps": _number(speed.get("upload_mbps")),
            "ping_ms": _number(speed.get("ping_ms")),
            "jitter_ms": _number(speed.get("jitter_ms")),
            "transport": _clip(speed.get("transport"), 16),
            "server": _clip(speed.get("server"), 120),
            "ip_stack": _clip(speed.get("ip_stack"), 24),
            "provider": _clip(speed.get("provider"), 64),
            "samples": {
                "download": _sample_series(_series(speed, "download")),
                "upload": _sample_series(_series(speed, "upload")),
            },
        },
        "video": {
            "load_ms": _number(video.get("load_ms")),
            "resolution": _clip(video.get("resolution"), 16),
            "buffering_pct": _number(video.get("buffering_pct")),
            "transport": _clip(video.get("transport"), 16),
            "stages": _stage_series(video.get("stages")),
        },
    }


def _style(value: object) -> str:
    style = str(value or "").strip().lower()
    if style in ("biasa", "santai", "serius", "gaul"):
        return style
    return "biasa"


def _series(speed: dict, name: str):
    samples = speed.get("samples")
    if not isinstance(samples, dict):
        return []
    return samples.get(name)


def _sample_series(value: object) -> list[dict]:
    if not isinstance(value, list):
        return []
    points = []
    for item in value[:400]:
        if not isinstance(item, dict):
            continue
        elapsed = _number(item.get("t_ms"))
        mbps = _number(item.get("mbps"))
        if elapsed is None or mbps is None or elapsed < 0 or elapsed > 120000:
            continue
        points.append({"t_ms": int(elapsed), "mbps": round(float(mbps), 2)})
    if len(points) <= 120:
        return points
    step = len(points) / 120
    return [points[min(int(index * step), len(points) - 1)] for index in range(120)]


def _stage_series(value: object) -> list[dict]:
    if not isinstance(value, list):
        return []
    stages = []
    for item in value[:8]:
        if not isinstance(item, dict):
            continue
        resolution = _clip(item.get("resolution"), 16)
        if not resolution:
            continue
        stages.append(
            {
                "resolution": resolution,
                "load_ms": _number(item.get("load_ms")),
                "played_ms": _number(item.get("played_ms")),
                "buffer_ms": _number(item.get("buffer_ms")),
            }
        )
    return stages


def _model_brief(brief: dict) -> dict:
    """Numbers and place only. Charts stay on the saved run, not in the model call."""
    speed = brief.get("speed") or {}
    video = brief.get("video") or {}
    compact = {
        "language": brief.get("language") or "en",
        "style": brief.get("style") or "biasa",
        "kelurahan": brief.get("kelurahan") or "",
        "kecamatan": brief.get("kecamatan") or "",
        "kabupaten": brief.get("kabupaten") or "",
        "provinsi": brief.get("provinsi") or "",
        "transport": brief.get("transport") or "",
        "provider": brief.get("provider") or "",
        "mention": brief.get("mention") or "",
    }
    if brief.get("events"):
        compact["events"] = brief.get("events")
    else:
        compact["speed"] = {
            "download_mbps": speed.get("download_mbps"),
            "upload_mbps": speed.get("upload_mbps"),
            "ping_ms": speed.get("ping_ms"),
            "jitter_ms": speed.get("jitter_ms"),
        }
        compact["video"] = {
            "resolution": video.get("resolution") or "",
            "load_ms": video.get("load_ms"),
            "buffering_pct": video.get("buffering_pct"),
        }
    return {key: value for key, value in compact.items() if value not in ("", None)}


def publish_run(ledger: MemoryLedger, brief: dict, sentence: str = "") -> str:
    alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    run_id = "".join(secrets.choice(alphabet) for _ in range(12))
    speed = brief.get("speed") or {}
    video = brief.get("video") or {}
    transport = str(brief.get("transport") or "")
    if transport == "unknown":
        transport = ""
    ledger.save_run(
        {
            "id": run_id,
            "created_at": int(time.time()),
            "sentence": str(sentence or "")[:180],
            "provider": brief.get("provider") or "",
            "kelurahan": brief.get("kelurahan") or "",
            "kecamatan": brief.get("kecamatan") or "",
            "kabupaten": brief.get("kabupaten") or "",
            "provinsi": brief.get("provinsi") or "",
            "transport": transport,
            "when": brief.get("when") or "",
            "speed": {
                "download_mbps": speed.get("download_mbps"),
                "upload_mbps": speed.get("upload_mbps"),
                "ping_ms": speed.get("ping_ms"),
                "jitter_ms": speed.get("jitter_ms"),
                "download": (speed.get("samples") or {}).get("download") or [],
                "upload": (speed.get("samples") or {}).get("upload") or [],
            },
            "video": {
                "resolution": video.get("resolution") or "",
                "load_ms": video.get("load_ms"),
                "buffering_pct": video.get("buffering_pct"),
                "stages": video.get("stages") or [],
            },
            "events": brief.get("events") or [],
        }
    )
    host = os.environ.get("MAGNON_PUBLIC_HOST", "magnon.site").strip() or "magnon.site"
    return "https://%s/r/%s" % (host, run_id)


def result_page(ledger: MemoryLedger, run_id: str) -> tuple[int, bytes]:
    if not (len(run_id) == 12 and run_id.isalnum() and run_id == run_id.lower()):
        return 404, _result_missing()
    row = ledger.run(run_id)
    if not row:
        return 404, _result_missing()
    return 200, _result_html(row)


def _result_missing() -> bytes:
    return _result_document("Result not found", "<p>This Magnon result link is not available.</p>").encode("utf-8")


def _fmt(value: object, suffix: str) -> str:
    number = _number(value)
    if number is None:
        return ""
    if float(number).is_integer():
        shown = str(int(number))
    else:
        shown = ("%.1f" % float(number)).rstrip("0").rstrip(".")
    return shown + suffix


def _result_html(row: dict) -> bytes:
    speed = row.get("speed") or {}
    video = row.get("video") or {}
    facts = []
    for label, text in (
        ("Download", _fmt(speed.get("download_mbps"), " Mbps")),
        ("Upload", _fmt(speed.get("upload_mbps"), " Mbps")),
        ("Ping", _fmt(speed.get("ping_ms"), " ms")),
        ("Jitter", _fmt(speed.get("jitter_ms"), " ms")),
        ("Video", html.escape(str(video.get("resolution") or ""))),
        ("Load", _fmt(video.get("load_ms"), " ms")),
        ("Buffering", _fmt(video.get("buffering_pct"), "%")),
    ):
        if text:
            facts.append("<div><strong>%s</strong><span>%s</span></div>" % (label, text))
    provider = html.escape(str(row.get("provider") or "").strip())
    village = html.escape(str(row.get("kelurahan") or "").strip())
    place = html.escape(str(row.get("kecamatan") or "").strip())
    labeled = []
    if provider:
        labeled.append("<div><strong>Provider</strong><span>%s</span></div>" % provider)
    if village:
        labeled.append("<div><strong>Kelurahan</strong><span>%s</span></div>" % village)
    if place:
        labeled.append("<div><strong>Kecamatan</strong><span>%s</span></div>" % place)
    events = row.get("events") if isinstance(row.get("events"), list) else []
    shared = set()
    if events:
        for key in ("provider", "kelurahan", "kecamatan", "kabupaten", "provinsi"):
            if str(row.get(key) or "").strip():
                shared.add(key)
        facts = labeled
    else:
        facts = labeled + facts
    when = html.escape(str(row.get("when") or ""))
    transport = html.escape(str(row.get("transport") or ""))
    meta = " · ".join(part for part in (transport, when) if part)
    charts = []
    if not events:
        speed_chart = _speed_chart(speed.get("download") or [], speed.get("upload") or [])
        if speed_chart:
            charts.append("<h2>Speed</h2>" + speed_chart)
    sentence = html.escape(str(row.get("sentence") or "").strip())
    lead = "<p>%s</p>" % sentence if sentence else ""
    body = "%s<p class=\"meta\">%s</p><div class=\"facts\">%s</div>%s%s" % (
        lead,
        meta,
        "".join(facts),
        _events_html(events, shared),
        "".join(charts),
    )
    return _result_document("Magnon result", body).encode("utf-8")


def _fact(label: str, value: object) -> str:
    text = html.escape(str(value or "").strip())
    if not text:
        return ""
    return "<div><strong>%s</strong><span>%s</span></div>" % (label, text)


def _events_html(events: list, shared: set | None = None) -> str:
    if not events:
        return ""
    shown = shared or set()
    rows = []
    for event in events:
        if not isinstance(event, dict):
            continue
        kind = "Video" if event.get("kind") == "video" else "Speed"
        bits = []
        if "provider" not in shown:
            bits.append(_fact("Provider", event.get("provider")))
        if "kelurahan" not in shown:
            bits.append(_fact("Kelurahan", event.get("kelurahan")))
        if "kecamatan" not in shown:
            bits.append(_fact("Kecamatan", event.get("kecamatan")))
        if "kabupaten" not in shown:
            bits.append(_fact("Kabupaten", event.get("kabupaten")))
        if "provinsi" not in shown:
            bits.append(_fact("Provinsi", event.get("provinsi")))
        band = str(event.get("quality_band") or "").strip()
        if band:
            bits.append(_fact("Quality", band))
        if kind == "Video":
            bits.append(_fact("Video", event.get("resolution")))
            bits.append(_fact("Load", _fmt(event.get("load_ms"), " ms")))
            bits.append(_fact("Buffering", _fmt(event.get("buffering_pct"), "%")))
        else:
            bits.append(_fact("Download", _fmt(event.get("download_mbps"), " Mbps")))
            bits.append(_fact("Upload", _fmt(event.get("upload_mbps"), " Mbps")))
            bits.append(_fact("Ping", _fmt(event.get("ping_ms"), " ms")))
            bits.append(_fact("Jitter", _fmt(event.get("jitter_ms"), " ms")))
        meta_parts = [
            str(event.get("when") or "").strip(),
            str(event.get("transport") or "").strip(),
            str(event.get("id") or "").strip(),
        ]
        meta = " · ".join(html.escape(part) for part in meta_parts if part)
        rows.append(
            "<article class=\"event\"><h2>%s</h2><p class=\"meta\">%s</p><div class=\"facts\">%s</div></article>"
            % (kind, meta, "".join(bits))
        )
    return "".join(rows)


def _result_document(title: str, body: str) -> str:
    return """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>%s</title>
<style>
*{box-sizing:border-box}
body{margin:0;font-family:Arial,Helvetica,sans-serif;color:#141414;background:#fff;line-height:1.45}
.top{display:flex;align-items:center;background:#111;color:#fff;padding:14px 28px}
.brand{color:#fff;font-weight:700;font-size:20px;letter-spacing:-.03em;text-decoration:none}
main{padding:36px 8vw 64px;max-width:860px}
h1{font-size:clamp(36px,5vw,56px);line-height:.95;letter-spacing:-.035em;margin:0 0 8px}
.meta{color:#444;margin:0 0 22px}
.facts{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:28px}
.facts div{background:#f4f6f8;border-radius:8px;padding:16px}
.facts strong{display:block;color:#444;font-size:13px;font-weight:700}
.facts span{display:block;font-size:28px;letter-spacing:-.03em}
h2{font-size:28px;letter-spacing:-.03em;margin:28px 0 8px}
.event{margin:0 0 28px}
svg{width:100%%;height:auto;background:#071824;border-radius:8px}
.note{fill:#d7e6ef;font-size:12px}
@media(max-width:800px){.facts{grid-template-columns:1fr 1fr}main{padding:28px 24px}}
</style></head><body>
<header class="top"><a class="brand" href="/">Magnon</a></header>
<main><h1>%s</h1>%s</main></body></html>""" % (html.escape(title), html.escape(title), body)


def _chart_frame(paths: str, max_x: float, max_y: float, y_label: str) -> str:
    width, height = 640, 260
    return (
        '<svg viewBox="0 0 %d %d" role="img">'
        '<text class="note" x="46" y="18">%s</text>'
        '<text class="note" x="600" y="248" text-anchor="end">%s s</text>'
        "%s</svg>"
    ) % (width, height, html.escape(y_label), ("%.0f" % max_x), paths)


def _xy(t_s: float, value: float, max_x: float, max_y: float) -> tuple[float, float]:
    width, height = 640, 260
    pad_l, pad_r, pad_t, pad_b = 46, 16, 28, 28
    x = pad_l + (t_s / max_x) * (width - pad_l - pad_r)
    y = pad_t + (1 - value / max_y) * (height - pad_t - pad_b)
    return x, y


def _speed_chart(download: list, upload: list) -> str:
    down = [(float(point["t_ms"]) / 1000.0, float(point["mbps"])) for point in download if isinstance(point, dict)]
    shift = down[-1][0] if down else 0.0
    up = [(shift + float(point["t_ms"]) / 1000.0, float(point["mbps"])) for point in upload if isinstance(point, dict)]
    if len(down) + len(up) < 2:
        return ""
    max_x = max([item[0] for item in down + up] or [1]) or 1
    max_y = max([item[1] for item in down + up] or [1]) or 1

    def path(series: list[tuple[float, float]], color: str) -> str:
        if len(series) < 2:
            return ""
        commands = []
        for index, (t_s, mbps) in enumerate(series):
            x, y = _xy(t_s, mbps, max_x, max_y)
            commands.append("%s%.1f %.1f" % ("M" if index == 0 else "L", x, y))
        return '<path d="%s" fill="none" stroke="%s" stroke-width="2.5"/>' % (" ".join(commands), color)

    legend = (
        '<text class="note" x="46" y="36">Download</text>'
        '<text class="note" x="130" y="36">Upload</text>'
    )
    return _chart_frame(legend + path(down, "#7ee7ff") + path(up, "#b9a6ff"), max_x, max_y, "Mbps")


def _video_chart(stages: list) -> str:
    cursor = 0.0
    steps = []
    for stage in stages:
        if not isinstance(stage, dict):
            continue
        digits = "".join(ch for ch in str(stage.get("resolution") or "") if ch.isdigit())
        height = float(digits or 0)
        if height <= 0:
            continue
        duration = ((stage.get("load_ms") or 0) + (stage.get("played_ms") or 0)) / 1000.0
        if duration <= 0:
            duration = 0.2
        load = _fmt(stage.get("load_ms"), " ms load")
        buffer = _fmt(stage.get("buffer_ms"), " ms buffer")
        steps.append((cursor, cursor + duration, height, stage.get("resolution") or "", load, buffer))
        cursor += duration
    if not steps:
        return ""
    max_x = steps[-1][1] or 1
    max_y = max(step[2] for step in steps) or 1
    commands = []
    labels = []
    for index, (start, end, height, resolution, load, buffer) in enumerate(steps):
        x1, y = _xy(start, height, max_x, max_y)
        x2, _y2 = _xy(end, height, max_x, max_y)
        commands.append("%s%.1f %.1f L%.1f %.1f" % ("M" if index == 0 else "L", x1, y, x2, y))
        labels.append(
            '<text class="note" x="%.1f" y="%.1f">%s</text>'
            % (x1 + 4, max(y - 8, 48), html.escape("%s · %s · %s" % (resolution, load, buffer)))
        )
    path = '<path d="%s" fill="none" stroke="#7ee7ff" stroke-width="2.5"/>' % " ".join(commands)
    return _chart_frame(path + "".join(labels), max_x, max_y, "px")


_QUALITY_CRITERIA = {
    "poor": "Clearly bad experience",
    "fair": "Usable but modest / annoying",
    "good": "Comfortable everyday performance",
    "excellent": "Strong high-end throughput",
    "unknown": "Not enough evidence in the brief",
}


def _jev_questions(brief: dict) -> dict:
    events = brief.get("events") if isinstance(brief.get("events"), list) else []
    overall = (
        "Overall quality of every event in `brief.events`. Read each row and judge the whole set. "
        "Do not use only the first, last, best, or worst event."
        if events
        else "Overall quality of the measured speed and/or video experience in `brief`. Use the numbers present. Cellular and Wi-Fi tests both count."
    )
    questions = {
        "quality_band": {
            "type": "choice",
            "instructions": overall,
            "criteria": dict(_QUALITY_CRITERIA),
        }
    }
    for index, event in enumerate(events[:20]):
        if not isinstance(event, dict):
            continue
        event_id = str(event.get("id") or index)
        questions["event_%d" % index] = {
            "type": "choice",
            "instructions": (
                "Quality of brief.events[%d] only, id %s. Use only that row's numbers. Ignore every other event."
                % (index, event_id)
            ),
            "criteria": dict(_QUALITY_CRITERIA),
        }
    return questions


def classify_with_jev(brief: dict) -> dict:
    key = os.environ.get("TYPESAFE_API_KEY", "").strip()
    if not key:
        raise RuntimeError("jev_not_configured")
    events = brief.get("events") if isinstance(brief.get("events"), list) else []
    body = {
        "model": os.environ.get("TYPESAFE_MODEL", "jev-latest").strip() or "jev-latest",
        "state": {
            "brief": brief,
            "policy": {
                "language": "id-ID mobile network experience",
                "role": "classifier only — do not decide whether the user may share",
                "wifi_ok": True,
                "poor_guide": {
                    "download_mbps_under": 10,
                    "ping_ms_over": 120,
                    "video_resolution_at_most": "360p",
                    "video_load_ms_over": 2000,
                    "buffering_pct_over": 5,
                },
                "good_guide": {
                    "download_mbps_over": 50,
                    "ping_ms_under": 80,
                    "video_resolution_at_least": "720p",
                    "buffering_pct_under": 3,
                },
            },
        },
        "questions": _jev_questions(brief),
    }
    parsed = _post_json("https://api.typesafe.ai/v1/systemone", body, key, 20 if events else 8)
    answers = parsed.get("answers") or {}
    quality = str((answers.get("quality_band") or {}).get("choice") or "unknown")
    event_bands = []
    for index, event in enumerate(events[:20]):
        if not isinstance(event, dict):
            continue
        label = str((answers.get("event_%d" % index) or {}).get("choice") or "unknown")
        if label not in _QUALITY_CRITERIA:
            label = "unknown"
        event_bands.append({"id": str(event.get("id") or ""), "quality_band": label})
    labels = [item["quality_band"] for item in event_bands]
    if quality in ("poor", "fair"):
        intent = "complaint"
    elif quality in ("good", "excellent"):
        intent = "praise"
    elif any(label in ("poor", "fair") for label in labels):
        intent = "complaint"
    elif labels and all(label in ("good", "excellent") for label in labels):
        intent = "praise"
    else:
        intent = "complaint"
    return {
        "post_intent": intent,
        "quality_band": quality,
        "event_bands": event_bands,
        "topic": "none",
        "model": parsed.get("model") or "jev-latest",
    }


def draft_with_deepseek(brief: dict, classification: dict) -> dict:
    key = os.environ.get("MAGNON_DEEPSEEK_API_KEY", "").strip()
    if not key:
        raise RuntimeError("deepseek_not_configured")
    language = "Bahasa Indonesia" if brief.get("language") == "id" else "English"
    tones = {
        "biasa": "plain and direct",
        "santai": "relaxed and everyday",
        "serius": "calm and precise",
        "gaul": "informal slang, still not abusive",
    }
    tone = tones.get(str(brief.get("style") or "biasa"), tones["biasa"])
    system = (
        "You write one X post for a phone network test. "
        "classification.post_intent is already decided as complaint or praise. Do not change it. "
        "Reply with JSON only, no markdown: {\"sentence\":\"...\",\"summary\":\"...\"}. "
        "sentence is at most 180 characters, with no URL and no hashtag spam. "
        "Write sentence and summary in %s. "
        "Write in this tone: %s. "
        "If post_intent is complaint, be clear and mildly annoyed, not abusive. "
        "If post_intent is praise, be warm and specific. "
        "If provider is present, the sentence must include that provider name. "
        "Say whether the path is Wi-Fi or cellular. "
        "Include an @mention only when mention is present. "
        "If kelurahan is present, the sentence must include that kelurahan. "
        "If kecamatan is present, the sentence must include that kecamatan. Do not invent a place. Do not include coordinates. "
        "Use only numbers present in the brief. Do not invent measurements. "
        "When events is present, that list is the only set of measurements. "
        "Each event.quality_band was already classified. Do not change those labels. "
        "The sentence stays one post about the whole set. "
        "Name a place, provider, or transport only when that field is present."
    ) % (language, tone)
    payload = {
        "model": os.environ.get("MAGNON_DEEPSEEK_MODEL", "deepseek-flash").strip() or "deepseek-flash",
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": json.dumps({
                "brief": brief,
                "post_intent": classification.get("post_intent"),
                "quality_band": classification.get("quality_band"),
            }, ensure_ascii=False)},
        ],
        "temperature": 0.2,
        "max_tokens": 300,
        "response_format": {"type": "json_object"},
        "thinking": {"type": "disabled"},
    }
    base = os.environ.get("MAGNON_DEEPSEEK_BASE_URL", "https://api.deepseek.com").strip().rstrip("/") or "https://api.deepseek.com"
    parsed = _post_json(base + "/chat/completions", payload, key, 20)
    content = (((parsed.get("choices") or [{}])[0].get("message") or {}).get("content") or "")
    written = _json_object(content)
    return {"sentence": str(written.get("sentence") or "").strip(), "summary": str(written.get("summary") or "").strip()}


def _json_object(text: str) -> dict:
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.strip("`")
        if cleaned.lower().startswith("json"):
            cleaned = cleaned[4:].strip()
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start < 0 or end <= start:
        raise RuntimeError("draft_bad_json")
    try:
        parsed = json.loads(cleaned[start : end + 1])
    except json.JSONDecodeError as exc:
        raise RuntimeError("draft_bad_json") from exc
    if not isinstance(parsed, dict):
        raise RuntimeError("draft_bad_json")
    return parsed


def _post_json(url: str, body: dict, key: str, timeout: int) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Authorization": "Bearer %s" % key, "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            parsed = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read()
        del detail
        raise RuntimeError("upstream_%s" % exc.code) from exc
    except (TimeoutError, urllib.error.URLError, OSError) as exc:
        raise RuntimeError("upstream_unreachable") from exc
    except json.JSONDecodeError as exc:
        raise RuntimeError("upstream_bad_json") from exc
    if not isinstance(parsed, dict):
        raise RuntimeError("upstream_bad_json")
    return parsed


def handle(portal: Portal, method: str, path: str, token: str, body: dict, staff: bool) -> tuple[int, dict, str | None]:
    """Return status, JSON, and an optional session cookie value."""
    try:
        if method == "POST" and path == "/v1/auth/google":
            result = portal.login(
                str(body.get("id_token") or ""),
                str(body.get("device_id") or ""),
                str(body.get("flavor") or "lite"),
                str(body.get("nonce") or ""),
            )
            return 200, {"status": "ok", "session": result["session"], "profile": result["profile"]}, result["session"]
        if method == "GET" and path == "/api/magnon/places":
            return 200, {
                "regions": area_regions(),
                "places": best_places(portal.ledger.list_runs()),
            }, None
        if method == "GET" and path == "/api/magnon/areas":
            return 200, area_features(str(body.get("provinsi") or ""), str(body.get("kabupaten") or "")), None
        if method == "GET" and path == "/api/magnon/me":
            return 200, portal.me(token, str(body.get("flavor") or "lite")), None
        if method == "GET" and path == "/api/magnon/board":
            return 200, portal.leaderboard(), None
        if method == "GET" and path == "/api/magnon/mine":
            return 200, portal.own_detail(token), None
        if method == "GET" and path == "/api/magnon/sponsor":
            return 200, portal.sponsor_view(token), None
        if method == "GET" and path == "/api/magnon/users":
            return 200, portal.list_users(token), None
        if method == "POST" and path == "/api/magnon/post-credit":
            return 200, portal.post_credit(token, str(body.get("device_id") or ""), body.get("add")), None
        if method == "POST" and path == "/api/magnon/users":
            return 200, portal.update_user(
                token,
                str(body.get("email") or ""),
                body.get("roles") if isinstance(body.get("roles"), list) else body.get("role"),
                str(body.get("level") or ""),
                body.get("post_limit"),
                body.get("test_limit"),
            ), None
        if method == "GET" and path == "/v1/magnon/jobs":
            return 200, {"jobs": portal.jobs(token)}, None
        if method == "POST" and path == "/v1/magnon/reserve":
            return 200, portal.reserve(token, str(body.get("kind") or "")), None
        if method == "POST" and path == "/v1/magnon/jobs":
            return 200, portal.decide_job(token, str(body.get("job_id") or ""), bool(body.get("accept"))), None
        if method == "POST" and path == "/api/magnon/campaigns":
            return 200, portal.create_campaign(token, body), None
        if method == "POST" and path == "/api/magnon/contract":
            return 200, portal.request_contract(token, str(body.get("note") or "")), None
        if method == "POST" and path == "/api/magnon/level":
            if not staff:
                raise PermissionError("staff_only")
            return 200, portal.set_level(str(body.get("email") or ""), str(body.get("level") or "")), None
        if method == "POST" and path == "/api/magnon/access":
            if not staff:
                raise PermissionError("staff_only")
            return 200, portal.set_access(
                str(body.get("email") or ""),
                bool(body.get("commercial_access")),
                str(body.get("brand") or ""),
            ), None
        return 404, {"error": "not_found"}, None
    except PermissionError as exc:
        return 401, {"error": str(exc)}, None
    except ValueError as exc:
        return 400, {"error": str(exc)}, None
    except KeyError as exc:
        return 400, {"error": "missing_%s" % exc}, None


def token_from_headers(headers) -> str:
    auth = headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        return auth[7:].strip()
    for part in headers.get("Cookie", "").split(";"):
        part = part.strip()
        if part.startswith("magnon_session="):
            return part.split("=", 1)[1]
    return ""
