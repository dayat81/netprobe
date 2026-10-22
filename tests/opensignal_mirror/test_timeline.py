import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))

from opensignal_mirror import collector  # noqa: E402


def _event(**extra):
    event = {
        "event_id": "tl0123456789abcdef",
        "ts_ms": 1_700_000_000_000,
        "session_id": "ses0123456789ab",
        "kind": "test_start",
        "test_kind": "speed",
        "run_id": "",
        "result_id": "",
        "plmn": None,
        "prev_plmn": None,
        "duration_ms": None,
        "detail": "started",
    }
    event.update(extra)
    return event


def _body(**extra):
    body = {
        "schema_version": 1,
        "device_id": "ook-device12",
        "app": "ookla",
        "events": [_event()],
    }
    body.update(extra)
    return body


def test_validate_timeline_accepts_event():
    parsed = collector.validate_timeline(_body())
    assert parsed["app"] == "ookla"
    assert parsed["events"][0]["kind"] == "test_start"
    assert parsed["events"][0]["detail"] == "started"


def test_validate_timeline_plmn_change():
    event = _event(
        kind="plmn_change",
        test_kind="",
        plmn="51010",
        prev_plmn="51001",
        detail="source=serving_cell all=51010,51011",
    )
    parsed = collector.validate_timeline(_body(app="opensignal", events=[event]))
    assert parsed["events"][0]["plmn"] == "51010"
    assert parsed["events"][0]["prev_plmn"] == "51001"


def test_validate_timeline_rejects_bad_input():
    for bad in (
        _body(app="unknown"),
        _body(events=[]),
        _body(events=[_event(kind="bogus")]),
        _body(events=[_event(plmn="5101")]),
        _body(events=[_event(plmn="abcde")]),
        _body(events=[_event(duration_ms=-1)]),
        _body(events=[dict(_event(), extra="x")]),
    ):
        try:
            collector.validate_timeline(bad)
        except ValueError:
            continue
        raise AssertionError("expected ValueError for %r" % (bad,))


def test_snapshot_plmn_rule():
    """Mirror of Timeline.observeSnapshot: serving cell wins, neighbor ignored."""
    snapshot = {
        "subscriptions": [{"mcc": "510", "mnc": "01"}],
        "cells": [
            {"role": "serving", "registered": True, "mcc": "510", "mnc": "11"},
            {"role": "serving", "registered": True, "mcc": "510", "mnc": "01"},
            {"role": "neighbor", "registered": False, "mcc": "510", "mnc": "99"},
        ],
    }
    assert timeline_plmn(snapshot) == ("51001", "serving_cell")
    empty = {"subscriptions": [{"mcc": "510", "mnc": "89"}], "cells": []}
    assert timeline_plmn(empty) == ("51089", "subscription")
    none = {"subscriptions": [], "cells": []}
    assert timeline_plmn(none) is None


def timeline_plmn(snapshot):
    subs = snapshot.get("subscriptions") or []
    sub = subs[0] if subs else {}
    sub_mcc = sub.get("mcc", "")
    sub_mnc = sub.get("mnc", "")
    chosen = None
    for cell in snapshot.get("cells") or []:
        if not cell.get("registered") or cell.get("role") != "serving":
            continue
        mcc = cell.get("mcc", "")
        mnc = cell.get("mnc", "")
        if not mcc or not mnc:
            continue
        candidate = mcc + mnc
        if chosen is None:
            chosen = candidate
        if mcc == sub_mcc and mnc == sub_mnc:
            chosen = candidate
    if chosen is None and sub_mcc and sub_mnc:
        return sub_mcc + sub_mnc, "subscription"
    if chosen is None:
        return None
    return chosen, "serving_cell"


def test_ookla_timeline_hook_injection():
    spec = importlib.util.spec_from_file_location(
        "patch_ookla",
        ROOT / "scripts" / "ookla_mirror" / "patch_ookla.py",
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    smali = (
        ".class public Lcom/ookla/speedtest/app/M;\n"
        ".super Ljava/lang/Object;\n\n"
        ".method public g(Lcom/ookla/speedtestengine/t;Lcom/ookla/speedtestengine/l1;)V\n"
        "    .locals 1\n"
        "    return-void\n"
        ".end method\n\n"
        ".method public d(Lcom/ookla/error/c;Lcom/ookla/speedtestengine/l1;)V\n"
        "    .locals 2\n"
        "    return-void\n"
        ".end method\n\n"
        ".method public c(Lcom/ookla/speedtestengine/l1;Ljava/lang/Exception;)V\n"
        "    .locals 0\n"
        "    return-void\n"
        ".end method\n"
    )
    updated, hits = mod.inject_timeline_hook(
        smali,
        mod.TIMELINE_BEGIN_METHOD,
        "OoklaReporter;->captureStart",
        "\n    invoke-static {}, Lcom/ookla/lab/OoklaReporter;->captureStart()V\n",
        "begin-test",
    )
    updated, fail_hits = mod.inject_timeline_hook(
        updated,
        mod.TIMELINE_FAIL_METHOD,
        "OoklaReporter;->captureFail",
        "\n    invoke-static {p1}, Lcom/ookla/lab/OoklaReporter;"
        "->captureFail(Ljava/lang/Object;)V\n",
        "fail-test",
    )
    updated, upload_hits = mod.inject_timeline_hook(
        updated,
        mod.TIMELINE_FAIL_UPLOAD_METHOD,
        "OoklaReporter;->captureFail",
        "\n    invoke-static {p2}, Lcom/ookla/lab/OoklaReporter;"
        "->captureFail(Ljava/lang/Object;)V\n",
        "fail-upload",
    )
    assert (hits, fail_hits, upload_hits) == (1, 1, 1)
    assert "OoklaReporter;->captureStart()V" in updated
    assert "invoke-static {p1}, Lcom/ookla/lab/OoklaReporter;->captureFail" in updated
    assert "invoke-static {p2}, Lcom/ookla/lab/OoklaReporter;->captureFail" in updated


def test_ookla_video_start_hook_injection():
    spec = importlib.util.spec_from_file_location(
        "patch_ookla",
        ROOT / "scripts" / "ookla_mirror" / "patch_ookla.py",
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    smali = (
        ".class public Lcom/ookla/speedtest/video/l;\n"
        ".super Ljava/lang/Object;\n\n"
        ".method public b(Lcom/ookla/speedtest/video/G;)V\n"
        "    .locals 5\n"
        "    return-void\n"
        ".end method\n"
    )
    updated, hits = mod.inject_timeline_hook(
        smali,
        mod.TIMELINE_VIDEO_BEGIN_METHOD,
        "OoklaReporter;->captureStartVideo",
        "\n    invoke-static {}, Lcom/ookla/lab/OoklaReporter;->captureStartVideo()V\n",
        "video-begin",
    )
    assert hits == 1
    assert "OoklaReporter;->captureStartVideo()V" in updated


def test_ookla_video_fail_hook_injection():
    spec = importlib.util.spec_from_file_location(
        "patch_ookla",
        ROOT / "scripts" / "ookla_mirror" / "patch_ookla.py",
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    smali = (
        ".class public Lcom/ookla/speedtest/video/l;\n\n"
        ".method private static final t(Lcom/ookla/speedtest/video/l;"
        "Lcom/ookla/speedtest/videosdk/core/config/o;Lkotlin/Pair;)Ljava/lang/Boolean;\n"
        "    .locals 7\n"
        "    const-string p2, \"videoFailedReason\"\n"
        "    const-string p1, \"videoTestFailed\"\n"
        "    return-object v0\n"
        ".end method\n"
    )
    updated, hits = mod.inject_video_fail_hook(smali, "video-fail")
    assert hits == 1
    assert "OoklaReporter;->captureFailVideo()V" in updated
    # idempotent: second pass must not duplicate
    again, again_hits = mod.inject_video_fail_hook(updated, "video-fail")
    assert again_hits == 1
    assert again.count("captureFailVideo") == 1


def test_opensignal_phase_hook_includes_timeline():
    spec = importlib.util.spec_from_file_location(
        "patch_opensignal",
        ROOT / "scripts" / "opensignal_mirror" / "patch_opensignal.py",
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    method = (
        mod.PHASE_HEADER + "\n"
        "    .locals 1\n"
        "    return-object p1\n"
        ".end method\n"
    )
    updated = mod.transform_phase_method(method)
    assert "OsmReporter;->capturePayload" in updated
    assert "com/opensignal/mirror/Timeline;->beginTestSpeed()V" in updated
