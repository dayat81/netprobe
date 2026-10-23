#!/usr/bin/env python3
"""Classify MySignal X-post candidates with TypeSafe Jev (complaint or praise)."""

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
# Post when Jev is fairly sure a public post (praise or complaint) is warranted.
WORTHY_THRESHOLD = float(os.environ.get("JEV_ELIGIBLE_THRESHOLD", "0.65"))


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


def classify_complaint(brief: dict, api_key: str | None = None, timeout: int = 45) -> dict:
    """Return Jev judgments that choose complaint vs praise vs skip for an X draft."""
    key = api_key or load_api_key()
    body = {
        "model": MODEL,
        "state": {
            "brief": brief,
            "policy": {
                "language": "id-ID mobile user drafting an X/Twitter post",
                "wifi_tests_ok_when_provider_known": True,
                "requires_cellular_test": False,
                "provider_fields": ["provider", "carrier", "mention", "public_ip"],
                "complaint_guide": {
                    "download_mbps_under": 5,
                    "ping_ms_over": 150,
                    "video_resolution_at_most": "360p",
                    "video_load_ms_over": 2000,
                    "buffering_pct_over": 5,
                },
                "praise_guide": {
                    "download_mbps_over": 80,
                    "ping_ms_under": 80,
                    "video_resolution_at_least": "720p",
                    "buffering_pct_under": 2,
                },
                "skip_when": "mid-tier / ordinary results that are neither notably bad nor notably great",
            },
        },
        "questions": {
            "post_intent": {
                "type": "choice",
                "instructions": (
                    "Should this speed/video measurement become a public X post tagging the operator, "
                    "and with what intent? Cellular tests always count. Wi-Fi tests also count when "
                    "`brief.provider` / `brief.carrier` / `brief.mention` identifies the ISP "
                    "(from public IP). Tag that provider. Choose skip for ordinary mid-tier results "
                    "or when there is no usable test."
                ),
                "criteria": {
                    "complaint": "Experience is notably poor; a short public complaint is appropriate",
                    "praise": "Experience is notably strong; a short public praise/thank-you is appropriate",
                    "skip": "Not distinctive enough to post (or no usable test/provider)",
                },
            },
            "topic": {
                "type": "choice",
                "instructions": (
                    "Main everyday theme the post should emphasize, matching post_intent. "
                    "For skip, prefer none."
                ),
                "criteria": {
                    "slow_download": "Slow download is the main story",
                    "high_latency": "High ping/latency is the main story",
                    "video_stall": "Laggy/poor video is the main story",
                    "mixed_poor": "Several poor symptoms together",
                    "fast_download": "Strong download is the main praise",
                    "smooth_video": "Smooth/high-res video is the main praise",
                    "snappy_latency": "Low latency is the main praise",
                    "mixed_strong": "Several strong strengths together",
                    "none": "No clear public-post theme",
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
            "worthy_to_post": {
                "type": "noul",
                "instructions": (
                    "A speed or video test in the brief is distinctive enough "
                    "(notably poor OR notably strong) that a short public X post tagging "
                    "the operator/ISP is warranted. Cellular tests qualify; Wi-Fi tests qualify "
                    "when provider/ISP is known from brief.provider/carrier/mention. "
                    "Ordinary mid-tier is not."
                ),
            },
            "quality_band": {
                "type": "choice",
                "instructions": "Overall quality band of the measured experience in this brief",
                "criteria": {
                    "poor": "Clearly bad experience",
                    "fair": "Usable but modest / annoying",
                    "good": "Comfortable everyday performance",
                    "excellent": "Strong high-end throughput",
                    "unknown": "Not enough evidence",
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
    worthy = answers.get("worthy_to_post") or {}
    quality = answers.get("quality_band") or {}
    noul = float(worthy.get("noul") or 0.0)
    intent_choice = str(intent.get("choice") or "skip")
    topic_choice = str(topic.get("choice") or "none")
    eligible = noul >= WORTHY_THRESHOLD and intent_choice in ("complaint", "praise")
    # Keep legacy "issue" alias for older clients/logs.
    issue_alias = topic_choice if intent_choice == "complaint" else (
        "not_poor" if intent_choice == "skip" else topic_choice
    )
    return {
        "eligible": eligible,
        "post_intent": intent_choice,
        "post_intent_confidence": float(intent.get("confidence") or 0.0),
        "post_intent_probabilities": intent.get("probabilities") or {},
        "topic": topic_choice,
        "topic_confidence": float(topic.get("confidence") or 0.0),
        "issue": issue_alias,
        "issue_confidence": float(topic.get("confidence") or 0.0),
        "issue_probabilities": topic.get("probabilities") or {},
        "eligible_noul": noul,
        "eligible_threshold": WORTHY_THRESHOLD,
        "path_kind": str(path.get("choice") or "unknown"),
        "path_confidence": float(path.get("confidence") or 0.0),
        "quality_band": str(quality.get("choice") or "unknown"),
        "quality_confidence": float(quality.get("confidence") or 0.0),
        "model": parsed.get("model") or MODEL,
        "usage": parsed.get("usage") or {},
    }
