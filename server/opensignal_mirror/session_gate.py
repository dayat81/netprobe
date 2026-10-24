"""Request quality flag. Session enforcement stays off until Play Integrity is wired."""

from __future__ import annotations

import os
from contextvars import ContextVar

_quality: ContextVar[str] = ContextVar("osm_quality", default="gold")


def current_quality() -> str:
    return _quality.get()


def bind_request(headers) -> None:
    """Gold by default. OSM_REQUIRE_SESSION=1 rejects posts without X-Osm-Session."""
    token = (headers.get("X-Osm-Session") or "").strip()
    if os.environ.get("OSM_REQUIRE_SESSION", "0") != "1":
        _quality.set("gold" if token else "gold")
        return
    if len(token) < 16:
        raise PermissionError("session_required")
    _quality.set("gold")
