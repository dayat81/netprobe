#!/usr/bin/env python3
"""Classify MySignal speed/video results with TypeSafe Jev (good vs bad → praise/complaint)."""

from __future__ import annotations

import json
import os
import pathlib
import urllib.error
import urllib.request

API_URL = "https://api.typesafe.ai/v1/systemone"
MODEL = os.environ.get("TYPESAFE_MODEL", "jev-latest")
ENV_FILES = (
    pathlib.Path("/data/opensignal/.env"),
    pathlib.Path("/home/azureuser/.hermes/.env"),
    pathlib.Path.home() / ".hermes" / ".env",
    pathlib.Path(__file__).resolve().parents[2] / ".env",
)


def load_api_key() -> str:
    key = (os.environ.get("TYPESAFE_API_KEY") or "").strip()
    if key:
        return key
    for path in ENV_FILES:
        try:
            if not path.is_file():
                continue
            for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.startswith("TYPESAFE_API_KEY="):
                    return line.split("=", 1)[1].strip().strip("'\"")
        except OSError:
            continue
    raise RuntimeError(
        "TYPESAFE_API_KEY tidak ditemukan (env, /data/opensignal/.env, atau ~/.hermes/.env)"
    )


def _intent_from_quality(quality: str, fallback: str) -> str:
    if quality in ("poor", "fair"):
        return "complaint"
    if quality in ("good", "excellent"):
        return "praise"
    if fallback in ("complaint", "praise"):
        return fallback
    return "complaint"


def classify_complaint(brief: dict, api_key: str | None = None, timeout: int = 45) -> dict:
    """Jev only judges quality (bagus/jelek). Share eligibility is left to the user."""
    key = api_key or load_api_key()
    body = {
        "model": MODEL,
        "state": {
            "brief": brief,
            "policy": {
                "language": "id-ID mobile network experience",
                "role": "classifier only — do not decide whether the user may share",
                "wifi_ok": True,
                "provider_fields": ["provider", "carrier", "mention", "public_ip"],
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
        "questions": {
            "quality_band": {
                "type": "choice",
                "instructions": (
                    "Overall quality of the measured speed and/or video experience in `brief`. "
                    "Use the numbers present. Cellular and Wi-Fi tests both count. "
                    "This is a quality label only — not a permission to post."
                ),
                "criteria": {
                    "poor": "Clearly bad experience",
                    "fair": "Usable but modest / annoying",
                    "good": "Comfortable everyday performance",
                    "excellent": "Strong high-end throughput",
                    "unknown": "Not enough evidence in the brief",
                },
            },
            "post_intent": {
                "type": "choice",
                "instructions": (
                    "If this experience were described in a short X post, would the natural tone "
                    "be a complaint or praise? Prefer complaint for poor/fair, praise for "
                    "good/excellent. Never choose skip."
                ),
                "criteria": {
                    "complaint": "Tone should be a mild public complaint",
                    "praise": "Tone should be public praise / thanks",
                },
            },
            "topic": {
                "type": "choice",
                "instructions": "Main everyday theme matching post_intent.",
                "criteria": {
                    "slow_download": "Slow download is the main story",
                    "high_latency": "High ping/latency is the main story",
                    "video_stall": "Laggy/poor video is the main story",
                    "mixed_poor": "Several poor symptoms together",
                    "fast_download": "Strong download is the main praise",
                    "smooth_video": "Smooth/high-res video is the main praise",
                    "snappy_latency": "Low latency is the main praise",
                    "mixed_strong": "Several strong strengths together",
                    "none": "No clear theme",
                },
            },
            "path_kind": {
                "type": "choice",
                "instructions": (
                    "IP path of the speed test if present in `brief.speed.server` / `brief.speed.ip_stack`"
                ),
                "criteria": {
                    "ipv4_native": "IPv4 A-record path",
                    "ipv6_native": "Native IPv6 AAAA path",
                    "ipv6_nat64": "DNS64/NAT64 synthetic IPv6 (64:ff9b::)",
                    "unknown": "No clear speed path label",
                    "not_applicable": "No speed test in the brief",
                },
            },
        },
    }
    request = urllib.request.Request(
        API_URL,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": "Bearer %s" % key,
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            parsed = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[:240]
        raise RuntimeError("Jev HTTP %d: %s" % (exc.code, detail)) from exc
    except urllib.error.URLError as exc:
        raise RuntimeError("Jev unreachable: %s" % exc.reason) from exc

    answers = parsed.get("answers") or {}
    intent = answers.get("post_intent") or {}
    topic = answers.get("topic") or {}
    path = answers.get("path_kind") or {}
    quality = answers.get("quality_band") or {}
    quality_choice = str(quality.get("choice") or "unknown")
    intent_raw = str(intent.get("choice") or "")
    intent_choice = _intent_from_quality(quality_choice, intent_raw)
    topic_choice = str(topic.get("choice") or "none")
    issue_alias = topic_choice if intent_choice == "complaint" else topic_choice
    return {
        # Always true after a successful classify: user decides whether to share.
        "eligible": True,
        "post_intent": intent_choice,
        "post_intent_confidence": float(intent.get("confidence") or 0.0),
        "post_intent_probabilities": intent.get("probabilities") or {},
        "topic": topic_choice,
        "topic_confidence": float(topic.get("confidence") or 0.0),
        "issue": issue_alias,
        "issue_confidence": float(topic.get("confidence") or 0.0),
        "issue_probabilities": topic.get("probabilities") or {},
        "eligible_noul": None,
        "eligible_threshold": None,
        "path_kind": str(path.get("choice") or "unknown"),
        "path_confidence": float(path.get("confidence") or 0.0),
        "quality_band": quality_choice,
        "quality_confidence": float(quality.get("confidence") or 0.0),
        "model": parsed.get("model") or MODEL,
        "usage": parsed.get("usage") or {},
    }
