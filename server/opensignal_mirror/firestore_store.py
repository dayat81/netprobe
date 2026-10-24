"""Firestore hot store for collector events and nonce replay."""

from __future__ import annotations

import json
import threading
import time

from google.cloud import firestore


class FirestoreStore:
    backend = "firestore"

    def __init__(self, ttl_seconds: int) -> None:
        self.ttl_seconds = ttl_seconds
        self.lock = threading.Lock()
        self.client = firestore.Client()
        self.events = self.client.collection("events")
        self.nonces = self.client.collection("nonces")

    def nonce_seen(self, nonce: str, now: int) -> bool:
        ref = self.nonces.document(nonce)
        snap = ref.get()
        if snap.exists:
            return True
        ref.set({"seen_at": now, "expire_at": now + min(self.ttl_seconds, 600)})
        return False

    def insert_events(self, role: str, device_id: str, source_ip: str, events: list[dict]) -> tuple[int, int]:
        from opensignal_mirror.session_gate import current_quality

        accepted = 0
        duplicate = 0
        now = int(time.time())
        quality = current_quality()
        for event in events:
            event_id = str(event["event_id"])
            ref = self.events.document(event_id)
            if ref.get().exists:
                duplicate += 1
                continue
            stored = dict(event)
            stored.setdefault("quality", quality)
            ref.set(
                {
                    "event_id": event_id,
                    "role": role,
                    "device_id": device_id,
                    "received_at": now,
                    "source_ip": source_ip,
                    "ts_ms": event.get("ts_ms"),
                    "quality": stored["quality"],
                    "expire_at": now + self.ttl_seconds,
                    "payload": json.dumps(stored, separators=(",", ":"), sort_keys=True),
                }
            )
            accepted += 1
        return accepted, duplicate

    def purge(self, now: int | None = None) -> int:
        return 0

    def counts(self) -> dict[str, int]:
        return {
            "flows": 0,
            "debug": 0,
            "measurements": 0,
            "tests": 0,
            "os_results": 0,
            "nr_status": 0,
            "bytes": 0,
        }

    def recent(self, role: str, limit: int) -> list[dict]:
        query = (
            self.events.where("role", "==", role)
            .order_by("received_at", direction=firestore.Query.DESCENDING)
            .limit(limit)
        )
        rows = []
        for snap in query.stream():
            data = snap.to_dict() or {}
            payload = data.get("payload") or "{}"
            event = json.loads(payload) if isinstance(payload, str) else payload
            rows.append(
                {
                    "received_at": data.get("received_at"),
                    "source_ip": data.get("source_ip"),
                    "device_id": data.get("device_id"),
                    "ts_ms": data.get("ts_ms"),
                    "rat": "",
                    "imei": "",
                    "imsi": "",
                    "test_type": "",
                    "event": event,
                }
            )
        return rows

    def search(self, query: str, limit: int = 50) -> list[dict]:
        snaps = (
            self.events.where("device_id", "==", query)
            .order_by("received_at", direction=firestore.Query.DESCENDING)
            .limit(limit)
            .stream()
        )
        rows = []
        for snap in snaps:
            data = snap.to_dict() or {}
            payload = data.get("payload") or "{}"
            event = json.loads(payload) if isinstance(payload, str) else payload
            rows.append(
                {
                    "role": data.get("role"),
                    "received_at": data.get("received_at"),
                    "source_ip": data.get("source_ip"),
                    "device_id": data.get("device_id"),
                    "ts_ms": data.get("ts_ms"),
                    "rat": "",
                    "imei": "",
                    "imsi": "",
                    "test_type": "",
                    "event": event,
                }
            )
        return rows
