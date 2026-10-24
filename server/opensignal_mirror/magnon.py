"""Magnon accounts, credits, points, and sponsor campaigns.

Netprobe leaves MAGNON_PORTAL unset, so this module is not attached there.
"""

from __future__ import annotations

import base64
import hashlib
import json
import math
import secrets
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Callable
from zoneinfo import ZoneInfo

JAKARTA = ZoneInfo("Asia/Jakarta")
SESSION_TTL = 30 * 24 * 3600
POST_LIMIT = 3
TEST_LIMIT = 20
RADIO_POINT_CAP = 30
POINT_VALUES = {"speed": 10, "video": 8, "radio": 1}
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


class MemoryLedger:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.users: dict[str, dict] = {}
        self.sessions: dict[str, dict] = {}
        self.credits: dict[tuple[str, str], dict] = {}
        self.points: dict[str, dict] = {}
        self.campaigns: dict[str, dict] = {}
        self.jobs: dict[str, dict] = {}
        self.contracts: list[dict] = []
        self.events: list[dict] = []

    def save_user(self, user: dict) -> None:
        with self.lock:
            self.users[user["sub"]] = dict(user)

    def user_by_sub(self, sub: str) -> dict | None:
        with self.lock:
            row = self.users.get(sub)
            return dict(row) if row else None

    def user_by_email(self, email: str) -> dict | None:
        email_l = email.lower()
        with self.lock:
            for row in self.users.values():
                if str(row.get("email", "")).lower() == email_l:
                    return dict(row)
        return None

    def user_by_device(self, device_id: str) -> dict | None:
        with self.lock:
            for row in self.users.values():
                if device_id in row.get("device_ids", []):
                    return dict(row)
        return None

    def premium_users(self) -> list[dict]:
        with self.lock:
            return [dict(row) for row in self.users.values() if row.get("level") == "premium"]

    def contributors(self) -> list[dict]:
        with self.lock:
            return [dict(row) for row in self.users.values() if row.get("level") in ("lite", "premium")]

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
        verify: Callable[[str, str], dict] | None = None,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self.ledger = ledger
        self.client_id = client_id
        self.operators = operators
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
            "level": "lite",
            "operator_brand": "",
            "commercial_access": False,
            "device_ids": [],
        }
        user["email"] = email
        user["name"] = str(info.get("name") or user.get("name") or email.split("@")[0])[:80]
        if email in self.operators:
            user["role"] = "operator"
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
            field, limit = "post_used", POST_LIMIT
        elif kind == "test":
            if user.get("level") != "premium":
                raise ValueError("stay_on_lite")
            field, limit = "test_used", TEST_LIMIT
        else:
            raise ValueError("bad_kind")
        ok, used = self.ledger.bump_credit(user["sub"], day_key(self.now()), field, limit)
        if not ok:
            raise ValueError("credit_exhausted")
        return {"ok": True, "used": used, "limit": limit, "profile": self._public(user, "premium" if kind == "test" else "lite")}

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
        if user.get("role") != "operator" or not user.get("commercial_access"):
            raise ValueError("contract_required")
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
            if level not in wanted or candidate.get("sub") == user["sub"]:
                continue
            if candidate.get("last_lat") is None or candidate.get("last_lon") is None:
                continue
            distance = haversine_m(lat, lon, float(candidate["last_lat"]), float(candidate["last_lon"]))
            if distance > radius:
                continue
            if level == "premium":
                credit = self.ledger.credit_row(candidate["sub"], today)
                if int(credit.get("test_used", 0)) >= TEST_LIMIT:
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
        if user.get("role") != "operator":
            raise ValueError("operator_only")
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
        if user.get("role") != "operator":
            raise ValueError("operator_only")
        brand = str(user.get("operator_brand") or "")
        if not user.get("commercial_access"):
            recent = self.ledger.recent_events(400)
            return {"commercial_access": False, "aggregate": aggregate(recent, brand), "campaigns": []}
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
        return {"commercial_access": True, "campaigns": campaigns}

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
        user["role"] = "operator"
        user["commercial_access"] = bool(commercial)
        if brand:
            user["operator_brand"] = brand[:80]
        self.ledger.save_user(user)
        return {"ok": True, "email": user["email"], "commercial_access": user["commercial_access"]}

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
            "role": user.get("role"),
            "level": user.get("level"),
            "flavor": flavor,
            "operator_brand": user.get("operator_brand") or "",
            "commercial_access": bool(user.get("commercial_access")),
            "points": balance,
            "history": [{"kind": row.get("kind"), "points": row.get("points"), "day": row.get("day")} for row in history],
            "post_used": int(credit.get("post_used", 0)),
            "post_limit": POST_LIMIT,
            "test_used": int(credit.get("test_used", 0)),
            "test_limit": TEST_LIMIT,
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
    if env.get("OSM_STORE", "sqlite") == "firestore":
        ledger: MemoryLedger = FirestoreLedger()
    else:
        ledger = MemoryLedger()
    return Portal(ledger, client_id, operators)


def _data_uri(filename: str) -> str:
    raw = (Path(__file__).resolve().parent / "static" / filename).read_bytes()
    return "data:image/png;base64," + base64.b64encode(raw).decode("ascii")


def home_html() -> bytes:
    page = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Magnon | Crowdsourced network quality</title>
<meta name="description" content="Magnon collects speed, video, and radio measurements from phones in the field and turns them into a shared picture of network quality.">
<link rel="icon" type="image/png" href="__ICON__">
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
<a href="#measure">Measure</a>
<a href="#contributors">Contributors</a>
<a href="#points">Points</a>
<a href="#request">Request a test</a>
<a class="signin" href="/magnon">Sign in</a>
</nav>
</header>
<div class="ribbon">Crowdsourced network quality</div>
<section class="hero">
<div>
<h1>Better data.<br>From real phones.<br>In real places.</h1>
<p>Magnon turns everyday phone tests into a shared picture of network quality. A speed, video, or radio measurement joins the record for that place.</p>
<a class="btn" href="/magnon">Sign in with Gmail</a>
</div>
<div class="rings" aria-hidden="true"></div>
</section>
<div class="stats">
<div><strong>10</strong><span>points for each speed test</span></div>
<div><strong>8</strong><span>points for each video test</span></div>
<div><strong>3</strong><span>Lite posts available each day</span></div>
<div><strong>20</strong><span>Premium tests available each day</span></div>
</div>
<section id="measure">
<h2>From one phone to a shared picture</h2>
<p class="sub">One instrument is not enough. Magnon brings ordinary measurements together.</p>
<div class="steps">
<div><strong>01 &nbsp;Test</strong>A contributor measures on the Magnon app, on the network they are using.</div>
<div><strong>02 &nbsp;Record</strong>The result is stored with that Gmail account and device.</div>
<div><strong>03 &nbsp;Share the picture</strong>Points go to the contributor. Aggregates do not include email or a home identity.</div>
</div>
</section>
<section id="contributors">
<h2>Two ways to measure</h2>
<p class="sub">Magnon assigns the account level. The same Gmail can still use Magnon Lite.</p>
<div class="grid">
<article>
<h3>Magnon Lite</h3>
<p>Speed, Video, Post, and Points. For everyday experience. A complaint or a compliment uses 1 post credit, 3 a day. Speed and video tests do not use that credit.</p>
</article>
<article>
<h3>Magnon Premium</h3>
<p>Speed, Video, Network, Radio, and Points. For technical measurement. Each speed or video round uses 1 test credit, 20 a day. Radio and network on the same round do not cost another credit.</p>
</article>
</div>
</section>
<section id="points">
<h2>Points after sign-in</h2>
<p class="sub">Each measurement counts once. The leaderboard shows the top 20 Google names, never an email. Premium radio earns 1 point, up to 30 radio points a day.</p>
<div id="board" class="board"></div>
</section>
<section id="request" class="band">
<h2>Ask for a test in a place and a time</h2>
<p class="sub">A contracted operator or sponsor submits an area, a radius, and a time window, for Lite contributors, Premium contributors, or both. Magnon matches people of that level who were last seen there. The job appears in the app.</p>
<a class="btn" href="/magnon">Open the dashboard</a>
</section>
<footer>
<div class="cols">
<div><h2>Product</h2><a href="#measure">How it works</a><a href="#contributors">Lite and Premium</a><a href="/magnon">Dashboard</a></div>
<div><h2>Contributors</h2><a href="#points">Points</a><a href="#points">Leaderboard</a></div>
<div><h2>Operators</h2><a href="#request">Request a test</a><a href="/magnon">Sign in with Gmail</a></div>
</div>
<p class="legal">magnon.site</p>
</footer>
<script>
fetch("/api/magnon/board").then(function(r){return r.json()}).then(function(board){
  var rows=(board.month||[]).slice(0,5);
  if(!rows.length) return;
  function safe(value){return String(value).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;")}
  document.getElementById("board").innerHTML="<h3>This month</h3><ol>"+rows.map(function(row){return "<li>"+safe(row.name)+" · "+safe(row.points)+"</li>"}).join("")+"</ol>";
}).catch(function(){});
</script>
</body></html>"""
    return page.replace("__LOGO__", _data_uri("magnon-logo.png")).replace("__ICON__", _data_uri("magnon-icon.png")).encode("utf-8")


def portal_html(client_id: str) -> bytes:
    configured = "true" if client_id else "false"
    client = client_id.replace('"', "")
    page = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Magnon | Dashboard</title>
<link rel="icon" type="image/png" href="__ICON__">
<style>
*{box-sizing:border-box}
body{margin:0;font-family:Arial,Helvetica,sans-serif;background:#fff;color:#141414}
a{color:#141414}
.top{display:flex;align-items:center;justify-content:space-between;background:#111;color:#fff;padding:14px 28px}
.brand{height:46px;width:auto;background:#fff;border-radius:4px;padding:2px 8px}
.top a{color:#fff;text-decoration:none}
main{max-width:880px;margin:0 auto;padding:36px 24px 72px}
h1{font-size:40px;letter-spacing:-.03em;margin:0 0 8px}
h2{font-size:28px;letter-spacing:-.02em;margin:28px 0 10px}
.muted{color:#555}
button,input,select{font:inherit}
button{background:#111;color:#fff;border:0;border-radius:4px;padding:10px 14px}
input,select{border:1px solid #ccc;border-radius:4px;padding:8px;margin:0 6px 8px 0}
form{margin:0 0 16px}
#signin{width:240px}
#mine,#sponsor{white-space:pre-wrap;background:#f4f6f8;padding:16px;border-radius:8px}
</style></head><body>
<header class="top"><a href="/"><img class="brand" src="__LOGO__" alt="Magnon"></a></header>
<main>
<h1>Dashboard</h1>
<p id="who" class="muted">Sign in with Gmail.</p>
<div id="signin"></div>
<p id="unconfigured" class="muted" hidden>Sign-in is not configured.</p>
<section id="app" hidden>
<p id="balance"></p>
<h2>My measurements</h2><div id="mine"></div>
<h2>Sponsor</h2>
<form id="contractForm">
<input name="note" placeholder="Request a contract" style="width:70%">
<button type="submit">Submit</button>
</form>
<form id="campaignForm">
<input name="lat" placeholder="lat" size="8">
<input name="lon" placeholder="lon" size="8">
<input name="radius_m" placeholder="radius m" size="10">
<input name="start" placeholder="start unix" size="12">
<input name="end" placeholder="end unix" size="12">
<select name="audience">
<option value="both">Lite and Premium</option>
<option value="lite">Lite</option>
<option value="premium">Premium</option>
</select>
<button type="submit">Request a test</button>
</form>
<div id="sponsor"></div>
</section>
<script>
const configured = __CONFIGURED__;
const clientId = "__CLIENT__";
async function api(path, body) {
  const opt = body ? {method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify(body)} : {};
  const res = await fetch(path, opt);
  const data = await res.json();
  if (!res.ok) throw data;
  return data;
}
async function refresh() {
  const me = await api("/api/magnon/me");
  document.getElementById("app").hidden = false;
  document.getElementById("who").textContent = me.name + " · " + me.level;
  document.getElementById("balance").textContent = "Points " + me.points;
  if (me.level === "premium") {
    const mine = await api("/api/magnon/mine");
    document.getElementById("mine").textContent = JSON.stringify(mine.events || [], null, 1);
  } else {
    document.getElementById("mine").textContent = "Measurement detail is available on a premium account.";
  }
  if (me.role === "operator") {
    const view = await api("/api/magnon/sponsor");
    document.getElementById("sponsor").textContent = JSON.stringify(view, null, 1);
  }
}
document.getElementById("contractForm").onsubmit = async (ev) => {
  ev.preventDefault();
  const note = new FormData(ev.target).get("note");
  await api("/api/magnon/contract", {note: note || ""});
  refresh();
};
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
    page = page.replace("__LOGO__", _data_uri("magnon-logo.png")).replace("__ICON__", _data_uri("magnon-icon.png"))
    return page.encode("utf-8")


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
        if method == "GET" and path == "/api/magnon/me":
            return 200, portal.me(token, str(body.get("flavor") or "lite")), None
        if method == "GET" and path == "/api/magnon/board":
            return 200, portal.leaderboard(), None
        if method == "GET" and path == "/api/magnon/mine":
            return 200, portal.own_detail(token), None
        if method == "GET" and path == "/api/magnon/sponsor":
            return 200, portal.sponsor_view(token), None
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
