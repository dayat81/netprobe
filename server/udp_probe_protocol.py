"""UDP probe binary protocol — shared with Android/scripts clients."""

from __future__ import annotations

import statistics
import struct
from dataclasses import dataclass

MAGIC = b"UD"
SUMMARY_MAGIC = b"US"
VERSION = 1
VERSION_V2 = 2
SUMMARY_VERSION = 1
SYNC_ROUND_ID = 0
REQUEST_SIZE = 15
REPLY_SIZE = 31
REQUEST_V2_SIZE = 15
REPLY_V2_SIZE = 35
SUMMARY_REQUEST_SIZE = 5
SUMMARY_REPLY_SIZE = 9

_REQUEST_STRUCT = struct.Struct(">2sBIq")
_REPLY_STRUCT = struct.Struct(">2sBIqqq")
_REQUEST_V2_STRUCT = struct.Struct(">2sBHHq")
_REPLY_V2_STRUCT = struct.Struct(">2sBHHqqqHH")
_SUMMARY_REQUEST_STRUCT = struct.Struct(">2sBH")
_SUMMARY_REPLY_STRUCT = struct.Struct(">2sBHHH")


@dataclass(frozen=True)
class ProbeRequest:
    seq: int
    t1: int


@dataclass(frozen=True)
class ProbeReply:
    seq: int
    t1: int
    t2: int
    t3: int


@dataclass(frozen=True)
class ProbeRequestV2:
    round_id: int
    seq: int
    t1: int


@dataclass(frozen=True)
class ProbeReplyV2:
    round_id: int
    seq: int
    t1: int
    t2: int
    t3: int
    server_rx: int
    server_tx: int


@dataclass(frozen=True)
class SummaryReply:
    round_id: int
    server_rx: int
    server_tx: int


@dataclass(frozen=True)
class ProbeLatency:
    seq: int
    uplink_ms: float
    downlink_ms: float
    rtt_ms: float


def is_probe_request(data: bytes) -> bool:
    return len(data) >= REQUEST_SIZE and data[:2] == MAGIC


def is_summary_request(data: bytes) -> bool:
    return len(data) >= SUMMARY_REQUEST_SIZE and data[:2] == SUMMARY_MAGIC


def probe_request_version(data: bytes) -> int | None:
    if len(data) < REQUEST_SIZE or data[:2] != MAGIC:
        return None
    return data[2]


def unpack_request(data: bytes) -> ProbeRequest:
    if len(data) < REQUEST_SIZE:
        raise ValueError(f"request too short: {len(data)} < {REQUEST_SIZE}")
    magic, version, seq, t1 = _REQUEST_STRUCT.unpack_from(data)
    if magic != MAGIC:
        raise ValueError(f"bad magic: {magic!r}")
    if version != VERSION:
        raise ValueError(f"unsupported version: {version}")
    return ProbeRequest(seq=seq, t1=t1)


def unpack_request_v2(data: bytes) -> ProbeRequestV2:
    if len(data) < REQUEST_V2_SIZE:
        raise ValueError(f"request too short: {len(data)} < {REQUEST_V2_SIZE}")
    magic, version, round_id, seq, t1 = _REQUEST_V2_STRUCT.unpack_from(data)
    if magic != MAGIC:
        raise ValueError(f"bad magic: {magic!r}")
    if version != VERSION_V2:
        raise ValueError(f"unsupported version: {version}")
    return ProbeRequestV2(round_id=round_id, seq=seq, t1=t1)


def pack_reply(seq: int, t1: int, t2: int, t3: int) -> bytes:
    return _REPLY_STRUCT.pack(MAGIC, VERSION, seq & 0xFFFFFFFF, t1, t2, t3)


def pack_reply_v2(
    round_id: int,
    seq: int,
    t1: int,
    t2: int,
    t3: int,
    server_rx: int,
    server_tx: int,
) -> bytes:
    return _REPLY_V2_STRUCT.pack(
        MAGIC,
        VERSION_V2,
        round_id & 0xFFFF,
        seq & 0xFFFF,
        t1,
        t2,
        t3,
        server_rx & 0xFFFF,
        server_tx & 0xFFFF,
    )


def unpack_summary_request(data: bytes) -> int:
    if len(data) < SUMMARY_REQUEST_SIZE:
        raise ValueError(f"summary request too short: {len(data)} < {SUMMARY_REQUEST_SIZE}")
    magic, version, round_id = _SUMMARY_REQUEST_STRUCT.unpack_from(data)
    if magic != SUMMARY_MAGIC:
        raise ValueError(f"bad summary magic: {magic!r}")
    if version != SUMMARY_VERSION:
        raise ValueError(f"unsupported summary version: {version}")
    return round_id


def pack_summary_reply(round_id: int, server_rx: int, server_tx: int) -> bytes:
    return _SUMMARY_REPLY_STRUCT.pack(
        SUMMARY_MAGIC,
        SUMMARY_VERSION,
        round_id & 0xFFFF,
        server_rx & 0xFFFF,
        server_tx & 0xFFFF,
    )


def pack_request_v2(round_id: int, seq: int, t1: int) -> bytes:
    return _REQUEST_V2_STRUCT.pack(
        MAGIC,
        VERSION_V2,
        round_id & 0xFFFF,
        seq & 0xFFFF,
        t1,
    )


def pack_request(seq: int, t1: int) -> bytes:
    return _REQUEST_STRUCT.pack(MAGIC, VERSION, seq & 0xFFFFFFFF, t1)


def unpack_reply_v2(data: bytes) -> ProbeReplyV2:
    if len(data) < REPLY_V2_SIZE:
        raise ValueError(f"reply too short: {len(data)} < {REPLY_V2_SIZE}")
    magic, version, round_id, seq, t1, t2, t3, server_rx, server_tx = _REPLY_V2_STRUCT.unpack_from(data)
    if magic != MAGIC:
        raise ValueError(f"bad magic: {magic!r}")
    if version != VERSION_V2:
        raise ValueError(f"unsupported version: {version}")
    return ProbeReplyV2(
        round_id=round_id,
        seq=seq,
        t1=t1,
        t2=t2,
        t3=t3,
        server_rx=server_rx,
        server_tx=server_tx,
    )


def unpack_reply(data: bytes) -> ProbeReply:
    if len(data) < REPLY_SIZE:
        raise ValueError(f"reply too short: {len(data)} < {REPLY_SIZE}")
    magic, version, seq, t1, t2, t3 = _REPLY_STRUCT.unpack_from(data)
    if magic != MAGIC:
        raise ValueError(f"bad magic: {magic!r}")
    if version != VERSION:
        raise ValueError(f"unsupported version: {version}")
    return ProbeReply(seq=seq, t1=t1, t2=t2, t3=t3)


def pack_summary_request(round_id: int) -> bytes:
    return _SUMMARY_REQUEST_STRUCT.pack(SUMMARY_MAGIC, SUMMARY_VERSION, round_id & 0xFFFF)


def unpack_summary_reply(data: bytes) -> SummaryReply:
    if len(data) < SUMMARY_REPLY_SIZE:
        raise ValueError(f"summary reply too short: {len(data)} < {SUMMARY_REPLY_SIZE}")
    magic, version, round_id, server_rx, server_tx = _SUMMARY_REPLY_STRUCT.unpack_from(data)
    if magic != SUMMARY_MAGIC:
        raise ValueError(f"bad summary magic: {magic!r}")
    if version != SUMMARY_VERSION:
        raise ValueError(f"unsupported summary version: {version}")
    return SummaryReply(round_id=round_id, server_rx=server_rx, server_tx=server_tx)


def compute_latency(reply: ProbeReply, t4: int) -> ProbeLatency:
    uplink_ms = (reply.t2 - reply.t1) / 1_000_000.0
    downlink_ms = (t4 - reply.t3) / 1_000_000.0
    return ProbeLatency(
        seq=reply.seq,
        uplink_ms=uplink_ms,
        downlink_ms=downlink_ms,
        rtt_ms=uplink_ms + downlink_ms,
    )


def uplink_loss_pct(sent: int, server_rx: int) -> float:
    if sent <= 0:
        return 0.0
    return max(0.0, (sent - server_rx) / sent * 100.0)


def downlink_loss_pct(server_tx: int, reply_rx: int) -> float:
    if server_tx <= 0:
        return 0.0
    return max(0.0, (server_tx - reply_rx) / server_tx * 100.0)


def is_valid_latency(lat: ProbeLatency, tolerance_ms: float = 0.0) -> bool:
    return lat.uplink_ms >= -tolerance_ms and lat.downlink_ms >= -tolerance_ms


def clock_offset_ms(uplink_ms: float, downlink_ms: float) -> float:
    return (uplink_ms - downlink_ms) / 2.0


def estimate_offset_from_samples(samples: list[ProbeLatency]) -> float | None:
    if not samples:
        return None
    offsets = [clock_offset_ms(s.uplink_ms, s.downlink_ms) for s in samples]
    return statistics.median(offsets)


def correct_latency(lat: ProbeLatency, offset_ms: float) -> ProbeLatency:
    uplink_ms = lat.uplink_ms - offset_ms
    downlink_ms = lat.downlink_ms + offset_ms
    return ProbeLatency(
        seq=lat.seq,
        uplink_ms=uplink_ms,
        downlink_ms=downlink_ms,
        rtt_ms=uplink_ms + downlink_ms,
    )


def corrected_tolerance_ms(rtt_ms: float, minimum_ms: float = 1.0) -> float:
    return max(minimum_ms, 0.02 * rtt_ms)


def finalize_round_latencies(
    samples: list[ProbeLatency],
    minimum_tolerance_ms: float = 1.0,
    apply_offset_correction: bool = True,
    fixed_offset_ms: float | None = None,
) -> tuple[float | None, list[ProbeLatency], int]:
    if not samples:
        return fixed_offset_ms, [], 0
    offset = fixed_offset_ms if fixed_offset_ms is not None else estimate_offset_from_samples(samples)
    if not apply_offset_correction:
        accepted = [s for s in samples if 0.0 < s.rtt_ms < 60_000.0]
        return offset, accepted, len(samples) - len(accepted)
    accepted: list[ProbeLatency] = []
    rejected = 0
    for lat in samples:
        corrected = correct_latency(lat, offset or 0.0)
        tol = corrected_tolerance_ms(corrected.rtt_ms, minimum_tolerance_ms)
        if is_valid_latency(corrected, tol):
            accepted.append(corrected)
        else:
            rejected += 1
    return offset, accepted, rejected
