#!/usr/bin/env python3
"""Interpretasi jendela pengukuran OpenSignal dengan model telco OTel.

Modul ini menyusun *brief* angka yang ringkas (tanpa deret waktu penuh) lalu
mengirimkannya ke ``OTel-2.0-LLM-31B-IT`` lewat Featherless, sehingga panel
dashboard bisa menjelaskan satu window: SIM mana di carrier mana, ARFCN/PCI
mana yang memegang serving sepanjang waktu, apakah ada carrier aggregation
paralel, kualitas radio, dan status NR/ENDC.

Kunci API dibaca dari ``FEATHERLESS_API_KEY`` (env) atau berkas env pipeline
OTel lain: ``~/.hermes/.env``, lalu ``/data/opensignal/.env``.
"""

from __future__ import annotations

import json
import os
import pathlib
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

MODEL = "farbodtavakkoli/OTel-2.0-LLM-31B-IT"
BASE_URL = "https://api.featherless.ai/v1/chat/completions"
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
WIB = timezone(timedelta(hours=7))
ENV_FILES = (
    pathlib.Path("/data/opensignal/.env"),
    pathlib.Path("/home/azureuser/.hermes/.env"),
    pathlib.Path.home() / ".hermes" / ".env",
    pathlib.Path(__file__).resolve().parents[2] / ".env",
)
MAX_CELLS = 8

SYSTEM = (
    "Anda analis radio access untuk operator seluler di Indonesia. Anda menerima ringkasan "
    "kuantitatif SATU jendela waktu dari aplikasi pengukuran OpenSignal pada satu ponsel "
    "dengan beberapa SIM. Tulis dalam Bahasa Indonesia, ringkas, memakai markdown sederhana.\n"
    "Struktur wajib:\n"
    "**Ringkasan** — 2-3 kalimat: kondisi tiap SIM pada jendela ini.\n"
    "**Serving & mobility** — jelaskan pembagian waktu ARFCN/PCI per SIM memakai angka "
    "shares. Jika max_serving_cells_per_snapshot = 1, nyatakan itu handover/reselection "
    "(time-share), BUKAN carrier aggregation paralel; sebut jumlah perpindahan ARFCN dan "
    "perubahan PCI pada ARFCN yang sama. Jika > 1, sebut sebagai indikasi carrier aggregation.\n"
    "**Kualitas radio** — nilai RSRP/RSRQ/SINR tiap SIM. Pakai acuan umum: RSRP > -80 dBm "
    "baik, -80..-100 sedang, < -100 lemah; SINR > 13 dB baik, 0..13 sedang, < 0 buruk. "
    "Sebutkan bahwa ini acuan umum, bukan threshold operator.\n"
    "**NR / ENDC** — tafsirkan nr_state (3 = NR attached; SA maupun EN-DC, 2 = ikon 5G "
    "tanpa NR terpasang, 1 = LTE saja) serta sel NR yang dilaporkan (serving/neighbor, "
    "ss_rsrp, ss_sinr). Jika flag attached true, sebut NR memang terdeteksi walau "
    "nr_state per SIM masih 1 (data dari APK lama).\n"
    "**Yang perlu dicek** — 1-3 butir tindakan atau pengukuran lanjutan yang masuk akal.\n"
    "**Yang tidak bisa disimpulkan** — daftar hal yang data ini tidak buktikan.\n"
    "Aturan keras: gunakan HANYA angka dan nama yang ada di JSON brief. Jangan mengarang "
    "ARFCN, PCI, PLMN, carrier, counter, node, atau penyebab. Jangan menambah bagian lain. "
    "Maksimum 260 kata."
)


def load_api_key() -> str:
    key = (os.environ.get("FEATHERLESS_API_KEY") or "").strip()
    if key:
        return key
    for path in ENV_FILES:
        try:
            if not path.is_file():
                continue
            for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.startswith("FEATHERLESS_API_KEY="):
                    return line.split("=", 1)[1].strip().strip("'\"")
        except OSError:
            continue
    raise RuntimeError(
        "FEATHERLESS_API_KEY tidak ditemukan (env, ~/.hermes/.env, atau /data/opensignal/.env)"
    )


def _wib(timestamp) -> str | None:
    try:
        return datetime.fromtimestamp(float(timestamp), WIB).strftime("%Y-%m-%d %H:%M:%S WIB")
    except (TypeError, ValueError, OSError):
        return None


def _round(value, digits: int = 1):
    try:
        return round(float(value), digits) if value is not None else None
    except (TypeError, ValueError):
        return None


def _sim_label(slot, carrier, sub_id=None) -> str:
    label = "slot ?" if slot is None else "slot %d" % int(slot)
    if sub_id is not None:
        label += " · sub %d" % int(sub_id)
    if carrier:
        label += " · " + str(carrier).strip()
    return label


def _mobility(payload: dict) -> dict:
    """ARFCN changes per SIM, taken from the dwell segments of the trend payload."""
    slots = ((payload.get("trend") or {}).get("slots") or [])
    per_sim = []
    total = 0
    for slot in slots:
        segments = slot.get("segments") or []
        transitions = []
        for previous, current in zip(segments, segments[1:]):
            if previous.get("arfcn") == current.get("arfcn"):
                continue
            transitions.append(
                {
                    "at": _wib(current.get("start")),
                    "from_arfcn": previous.get("arfcn"),
                    "to_arfcn": current.get("arfcn"),
                    "previous_dwell_s": previous.get("duration_s"),
                    "from_rsrp": previous.get("rsrp"),
                    "to_rsrp": current.get("rsrp"),
                }
            )
        total += len(transitions)
        per_sim.append(
            {
                "sim": _sim_label(slot.get("slot"), slot.get("carrier")),
                "dwell_blocks": len(segments),
                "arfcn_changes": len(transitions),
                "transitions": transitions[:6],
            }
        )
    return {"total_arfcn_changes": total, "per_sim": per_sim}


def build_brief(payload: dict) -> dict:
    """Compact, numeric-only view of one measurement window for the model."""
    minutes = int(payload.get("minutes") or 15)
    now = payload.get("now")
    serving = payload.get("serving") or {}
    nr = payload.get("nr") or {}

    serving_sims = []
    for slot in serving.get("by_slot") or []:
        items = slot.get("items") or []
        serving_sims.append(
            {
                "sim": _sim_label(slot.get("slot"), slot.get("carrier")),
                "serving_snapshots": slot.get("serving_samples"),
                "same_arfcn_pci_changes": slot.get("pci_changes"),
                "shares": [
                    {
                        "arfcn": item.get("arfcn"),
                        "pci": item.get("pci"),
                        "share_pct": _round(100 * (item.get("sample_share") or 0), 1),
                        "serving_samples": item.get("samples"),
                        "rsrp_avg": _round(item.get("rsrp")),
                    }
                    for item in items[:MAX_CELLS]
                ],
            }
        )

    cells = [
        {
            "rat": cell.get("rat"),
            "arfcn": cell.get("arfcn"),
            "pci": cell.get("pci"),
            "sim": _sim_label(cell.get("sim_slot"), cell.get("carrier"), cell.get("sub_id")),
            "share_pct": _round(100 * (cell.get("serving_share") or 0), 1),
            "rsrp_avg": _round(cell.get("rsrp")),
            "rsrp_min": _round(cell.get("rsrp_min")),
            "rsrp_max": _round(cell.get("rsrp_max")),
            "rsrq_avg": _round(cell.get("rsrq")),
            "sinr_avg": _round(cell.get("sinr")),
            "samples": cell.get("samples"),
        }
        for cell in (payload.get("cells") or [])
        if cell.get("serving_samples")
    ][:MAX_CELLS]

    nr_cells = [
        {
            "arfcn": cell.get("arfcn"),
            "pci": cell.get("pci"),
            "sim": _sim_label(cell.get("sim_slot"), cell.get("carrier")),
            "role": "serving" if cell.get("serving_samples") else "neighbor",
            "ss_rsrp": _round(cell.get("rsrp")),
            "ss_rsrq": _round(cell.get("rsrq")),
            "ss_sinr": _round(cell.get("sinr")),
            "samples": cell.get("samples"),
        }
        for cell in (nr.get("cells") or [])[:4]
    ]
    nr_summary = nr.get("summary") or {}

    return {
        "window": {
            "minutes": minutes,
            "start": _wib((now or 0) - minutes * 60) if now else None,
            "end": _wib(now),
            "timezone": "WIB (UTC+7)",
        },
        "devices": [
            {
                "model": device.get("model"),
                "sdk_int": device.get("sdk_int"),
                "snapshots": device.get("snapshots"),
                "empty_snapshots": device.get("empty_snapshots"),
                "cells_avg": device.get("cells_avg"),
            }
            for device in (payload.get("devices") or [])
        ],
        "sims": [
            {
                "sim": _sim_label(sim.get("slot"), sim.get("carrier"), sim.get("sub_id")),
                "plmn": sim.get("plmn"),
                "rat": sim.get("rat"),
                "serving_arfcn": sim.get("serving_arfcn"),
                "serving_pci": sim.get("serving_pci"),
                "rsrp": _round(sim.get("rsrp")),
                "rsrq": _round(sim.get("rsrq")),
                "sinr": _round(sim.get("sinr")),
                "nr_state": sim.get("nr_state"),
                "service_state": sim.get("service_state"),
                "data_state": sim.get("data_state"),
                "samples": sim.get("samples"),
            }
            for sim in (payload.get("sims") or [])
        ],
        "serving": {
            "snapshots_checked": serving.get("snapshots"),
            "max_serving_cells_per_snapshot": serving.get("per_snapshot_max"),
            "avg_serving_cells_per_snapshot": serving.get("per_snapshot_avg"),
            "parallel_carriers_detected": bool(serving.get("parallel_carriers")),
            "per_sim": serving_sims,
        },
        "mobility": _mobility(payload),
        "serving_cells": cells,
        "nr": {
            "attached": bool(nr_summary.get("attached")) or bool(
                [cell for cell in (nr.get("cells") or []) if cell.get("serving_samples")]
            ),
            "state_samples": {key: value for key, value in (nr.get("states") or {}).items() if value},
            "nr_cells_seen": len(nr.get("cells") or []),
            "cells": nr_cells,
            "per_sim": [
                {
                    "sim": _sim_label(item.get("slot"), item.get("carrier"), item.get("sub_id")),
                    "nr_state": item.get("nr_state"),
                    "attached": item.get("attached"),
                    "serving_nrarfcn": (item.get("cells") or [{}])[0].get("arfcn") if item.get("cells") else None,
                    "serving_pci": (item.get("cells") or [{}])[0].get("pci") if item.get("cells") else None,
                }
                for item in (nr_summary.get("per_sim") or [])
            ],
        },
        "transport": {
            "transport": (payload.get("transport") or {}).get("transport"),
            "connected": (payload.get("transport") or {}).get("connected"),
        },
    }


def facts(brief: dict) -> list[str]:
    """Short labels the page can show above the interpretation text."""
    serving = brief.get("serving") or {}
    sims = brief.get("sims") or []
    serving_sims = serving.get("per_sim") or []
    arfcns = {
        share.get("arfcn")
        for slot in serving_sims
        for share in (slot.get("shares") or [])
        if share.get("arfcn") is not None
    }
    nr_state = (brief.get("nr") or {}).get("state_samples") or {}
    out = [
        "%d SIM" % len(sims),
        "%s snapshot" % (serving.get("snapshots_checked") or 0),
        "%s serving/SIM" % (serving.get("max_serving_cells_per_snapshot") or 0),
        "%d serving ARFCN" % len(arfcns),
        "%d pindah ARFCN" % ((brief.get("mobility") or {}).get("total_arfcn_changes") or 0),
        "NR " + ("attached" if (brief.get("nr") or {}).get("attached") else "tidak terlihat"),
    ]
    if nr_state:
        out.append("nr_state " + " / ".join("%s:%s" % (key, value) for key, value in sorted(nr_state.items())))
    return out


COMPLAINT_SYSTEM = (
    "You help an Indonesia mobile user draft a short X/Twitter post about a bad cellular experience. "
    "Reply with JSON only, no markdown: "
    "{\"eligible\": true or false, \"sentence\": \"...\", \"summary\": \"...\"}.\n"
    "eligible is true only when a submitted speed or video test has transport cellular and the result is poor. "
    "The top-level transport field is the network used while drafting and may be wifi; ignore it for eligibility. "
    "Poor, as a general guide: download under 5 Mbps, ping over 150 ms, "
    "360p resolution, video load time over 2000 ms, buffering over 5 percent. "
    "A test whose transport is not cellular is not eligible.\n"
    "sentence: the tweet body. Casual everyday social-media English (or natural Indo slang mixed with English if the JSON mention fits), "
    "at most 180 characters, no URL, no hashtags spam, no ARFCN, no PCI, no lab jargon. "
    "Sound like a real person posting on X: punchy, clear, a bit annoyed but not abusive. "
    "If mention is present (e.g. @myXL / @indosat), put it in the sentence once. "
    "Name the operator and the everyday symptom only (slow internet, laggy video, long ping).\n"
    "summary: four to eight plain sentences for the in-app detail page (not the tweet). "
    "Use only numbers present in the JSON. Do not invent any."
)


def interpret(brief: dict, api_key: str | None = None, model: str | None = None,
              timeout: int = 150, max_tokens: int = 1200, system: str | None = None) -> dict:
    """Send the brief to OTel and return its interpretation."""
    key = api_key or load_api_key()
    use_model = os.environ.get("OTEL_MODEL") or model or MODEL
    body = {
        "model": use_model,
        "temperature": 0.2,
        "max_tokens": max_tokens,
        "messages": [
            {"role": "system", "content": system or SYSTEM},
            {"role": "user", "content": json.dumps(brief, ensure_ascii=False)},
        ],
    }
    request = urllib.request.Request(
        BASE_URL,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": "Bearer %s" % key,
            "Content-Type": "application/json",
            "User-Agent": USER_AGENT,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            parsed = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[:200]
        raise RuntimeError("OTel HTTP %d: %s" % (exc.code, detail)) from exc
    except urllib.error.URLError as exc:
        raise RuntimeError("OTel tidak bisa dihubungi: %s" % exc.reason) from exc
    choice = (parsed.get("choices") or [{}])[0]
    text = ((choice.get("message") or {}).get("content") or "").strip()
    if not text:
        raise RuntimeError(
            "OTel tidak mengembalikan teks (finish=%s)" % choice.get("finish_reason")
        )
    return {
        "text": text,
        "model": parsed.get("model") or use_model,
        "usage": parsed.get("usage") or {},
        "finish_reason": choice.get("finish_reason"),
    }


def judge_complaint(brief: dict) -> dict:
    """Ask OTel whether this one sample is a valid operator complaint."""
    answer = interpret(brief, timeout=40, max_tokens=500, system=COMPLAINT_SYSTEM)
    text = answer["text"].strip()
    if text.startswith("```"):
        text = text.strip("`")
        text = text.split("\n", 1)[-1]
        if text.endswith("```"):
            text = text[:-3]
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        parsed = {}
    sentence = str(parsed.get("sentence") or "").replace("\n", " ").strip()[:180]
    summary = str(parsed.get("summary") or text).strip()[:2000]
    eligible = bool(parsed.get("eligible")) and bool(sentence)
    return {
        "eligible": eligible,
        "sentence": sentence,
        "summary": summary,
        "model": answer.get("model") or "",
    }
