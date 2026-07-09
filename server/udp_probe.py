"""Run UDP probes from the NetProbe API server (browser uses HTTP proxy)."""

from __future__ import annotations

import socket
import time
from dataclasses import dataclass, field

from udp_probe_protocol import (
    SYNC_ROUND_ID,
    VERSION,
    VERSION_V2,
    ProbeLatency,
    ProbeReply,
    REPLY_V2_SIZE,
    compute_latency,
    finalize_round_latencies,
    pack_request,
    pack_request_v2,
    pack_summary_request,
    unpack_reply,
    unpack_reply_v2,
    unpack_summary_reply,
)


def _avg(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def _jitter(values: list[float]) -> float | None:
    if len(values) < 2:
        return None
    diffs = [abs(values[i] - values[i - 1]) for i in range(1, len(values))]
    return sum(diffs) / len(diffs)


@dataclass
class ProbeRunConfig:
    host: str = "netprobe.xyz"
    port: int = 8765
    rounds: int = 5
    packets: int = 15
    pause_ms: float = 500
    recv_timeout_ms: float = 3000
    summary_timeout_ms: float = 500
    apply_offset_correction: bool = True
    sync_packets: int = 3


@dataclass
class RoundResult:
    round_no: int
    sent: int = 0
    received: int = 0
    reply_rx: int = 0
    skew_rejected: int = 0
    clock_offset_ms: float | None = None
    uplink_ms: list[float] = field(default_factory=list)
    downlink_ms: list[float] = field(default_factory=list)
    rtt_ms: list[float] = field(default_factory=list)
    server_rx: int | None = None
    server_tx: int | None = None
    max_server_rx_seen: int = 0
    max_server_tx_seen: int = 0

    def _resolved_server_rx(self) -> int | None:
        if self.server_rx is not None:
            return self.server_rx
        return self.max_server_rx_seen if self.max_server_rx_seen > 0 else None

    def _resolved_server_tx(self) -> int | None:
        if self.server_tx is not None:
            return self.server_tx
        return self.max_server_tx_seen if self.max_server_tx_seen > 0 else None

    @property
    def loss_pct(self) -> float:
        return ((self.sent - self.received) / self.sent * 100.0) if self.sent else 0.0

    @property
    def uplink_loss_pct(self) -> float | None:
        rx = self._resolved_server_rx()
        if self.sent == 0 or rx is None:
            return None
        from udp_probe_protocol import uplink_loss_pct

        return uplink_loss_pct(self.sent, rx)

    @property
    def downlink_loss_pct(self) -> float | None:
        tx = self._resolved_server_tx()
        if tx is None:
            return None
        from udp_probe_protocol import downlink_loss_pct

        return downlink_loss_pct(tx, self.reply_rx)

    def to_dict(self) -> dict:
        return {
            "round_no": self.round_no,
            "sent": self.sent,
            "received": self.received,
            "loss_pct": round(self.loss_pct, 2),
            "uplink_loss_pct": round(self.uplink_loss_pct, 2) if self.uplink_loss_pct is not None else None,
            "downlink_loss_pct": round(self.downlink_loss_pct, 2) if self.downlink_loss_pct is not None else None,
            "avg_uplink_ms": round(_avg(self.uplink_ms), 2) if self.uplink_ms else None,
            "avg_downlink_ms": round(_avg(self.downlink_ms), 2) if self.downlink_ms else None,
            "avg_rtt_ms": round(_avg(self.rtt_ms), 2) if self.rtt_ms else None,
            "jitter_ms": round(j, 2) if (j := _jitter(self.rtt_ms)) is not None else None,
            "clock_offset_ms": round(self.clock_offset_ms, 2) if self.clock_offset_ms is not None else None,
            "skew_rejected": self.skew_rejected,
        }


def _measure_clock_offset(
    sock: socket.socket,
    host: str,
    port: int,
    sync_packets: int,
    recv_timeout_ms: float,
    logs: list[str],
) -> float | None:
    samples: list[ProbeLatency] = []
    round_id = SYNC_ROUND_ID
    previous_timeout = sock.gettimeout()
    sock.settimeout(recv_timeout_ms / 1000.0)
    try:
        for seq in range(1, sync_packets + 1):
            t1 = time.time_ns()
            sock.sendto(pack_request_v2(round_id, seq, t1), (host, port))
            try:
                data, _ = sock.recvfrom(1024)
                t4 = time.time_ns()
                if len(data) >= REPLY_V2_SIZE and data[2] == VERSION_V2:
                    reply = unpack_reply_v2(data)
                    if reply.round_id == round_id and reply.seq == seq and reply.t1 == t1:
                        samples.append(
                            compute_latency(
                                ProbeReply(reply.seq, reply.t1, reply.t2, reply.t3),
                                t4,
                            )
                        )
                elif len(data) >= 31 and data[2] == VERSION:
                    reply = unpack_reply(data)
                    if reply.seq == seq and reply.t1 == t1:
                        samples.append(compute_latency(reply, t4))
            except socket.timeout:
                continue
            except ValueError:
                continue
    finally:
        sock.settimeout(previous_timeout)

    from udp_probe_protocol import estimate_offset_from_samples

    offset = estimate_offset_from_samples(samples)
    if offset is not None:
        logs.append(f"Clock sync: offset {offset:.1f} ms ({len(samples)}/{sync_packets} samples)")
    else:
        logs.append("Clock sync: no samples — per-round offset estimate")
    return offset


def _send_round(
    host: str,
    port: int,
    round_no: int,
    packets: int,
    recv_timeout_ms: float,
    summary_timeout_ms: float,
    apply_offset_correction: bool,
    fixed_offset_ms: float | None,
    logs: list[str],
) -> RoundResult:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(recv_timeout_ms / 1000.0)
    metrics = RoundResult(round_no=round_no)
    raw_samples: list[ProbeLatency] = []
    round_id = round_no & 0xFFFF
    use_v2 = True

    try:
        logs.append(f"Round {round_no} started (binary v2)")
        for seq in range(1, packets + 1):
            t1 = time.time_ns()
            payload = pack_request_v2(round_id, seq, t1) if use_v2 else pack_request(seq, t1)
            sock.sendto(payload, (host, port))
            metrics.sent += 1
            try:
                data, addr = sock.recvfrom(1024)
                t4 = time.time_ns()
                if use_v2 and len(data) >= REPLY_V2_SIZE and data[2] == VERSION_V2:
                    reply = unpack_reply_v2(data)
                    if reply.round_id != round_id or reply.seq != seq or reply.t1 != t1:
                        logs.append(f"  ! pkt {seq} reply mismatch")
                        continue
                    metrics.max_server_rx_seen = max(metrics.max_server_rx_seen, reply.server_rx)
                    metrics.max_server_tx_seen = max(metrics.max_server_tx_seen, reply.server_tx)
                    metrics.reply_rx += 1
                    raw_samples.append(
                        compute_latency(
                            ProbeReply(reply.seq, reply.t1, reply.t2, reply.t3),
                            t4,
                        )
                    )
                    logs.append(f"  ✓ pkt {seq} raw rtt {raw_samples[-1].rtt_ms:.0f} ms from {addr[0]}")
                elif len(data) >= 31:
                    if use_v2 and data[2] == VERSION:
                        use_v2 = False
                        logs.append("  · server replied v1 — falling back")
                    reply = unpack_reply(data)
                    if reply.seq != seq or reply.t1 != t1:
                        logs.append(f"  ! pkt {seq} reply mismatch")
                        continue
                    metrics.reply_rx += 1
                    raw_samples.append(compute_latency(reply, t4))
                    logs.append(f"  ✓ pkt {seq} raw rtt {raw_samples[-1].rtt_ms:.0f} ms")
                else:
                    logs.append(f"  ! pkt {seq} short reply ({len(data)} bytes)")
            except socket.timeout:
                logs.append(f"  ✗ pkt {seq} timeout")
            except ValueError as exc:
                logs.append(f"  ✗ pkt {seq} bad reply: {exc}")

        offset, accepted, rejected = finalize_round_latencies(
            raw_samples,
            apply_offset_correction=apply_offset_correction,
            fixed_offset_ms=fixed_offset_ms,
        )
        metrics.clock_offset_ms = offset
        metrics.skew_rejected = rejected
        metrics.received = len(accepted)
        for lat in accepted:
            metrics.uplink_ms.append(lat.uplink_ms)
            metrics.downlink_ms.append(lat.downlink_ms)
            metrics.rtt_ms.append(lat.rtt_ms)

        if use_v2:
            previous_timeout = sock.gettimeout()
            try:
                sock.settimeout(summary_timeout_ms / 1000.0)
                sock.sendto(pack_summary_request(round_id), (host, port))
                data, _ = sock.recvfrom(1024)
                summary = unpack_summary_reply(data)
                if summary.round_id == round_id:
                    metrics.server_rx = summary.server_rx
                    metrics.server_tx = summary.server_tx
                    logs.append(f"  · summary rx={summary.server_rx} tx={summary.server_tx}")
            except socket.timeout:
                logs.append("  · summary timeout (using reply counters)")
            except ValueError as exc:
                logs.append(f"  · summary failed: {exc}")
            finally:
                sock.settimeout(previous_timeout)

        logs.append(
            f"Round {round_no} done — rtt loss {metrics.loss_pct:.0f}% "
            f"up {metrics.uplink_loss_pct if metrics.uplink_loss_pct is not None else '—'}% "
            f"down {metrics.downlink_loss_pct if metrics.downlink_loss_pct is not None else '—'}% "
            f"offset {offset if offset is not None else '—'} ms"
        )
    finally:
        sock.close()

    return metrics


def _combine_rounds(rounds: list[RoundResult]) -> dict:
    sent = sum(r.sent for r in rounds)
    received = sum(r.received for r in rounds)
    uplink = [v for r in rounds for v in r.uplink_ms]
    downlink = [v for r in rounds for v in r.downlink_ms]
    rtt = [v for r in rounds for v in r.rtt_ms]
    uplink_loss = [r.uplink_loss_pct for r in rounds if r.uplink_loss_pct is not None]
    downlink_loss = [r.downlink_loss_pct for r in rounds if r.downlink_loss_pct is not None]
    jitter = _jitter(rtt)
    return {
        "sent": sent,
        "received": received,
        "loss_pct": round((sent - received) / sent * 100.0, 2) if sent else 0.0,
        "avg_uplink_ms": round(_avg(uplink), 2) if uplink else None,
        "avg_downlink_ms": round(_avg(downlink), 2) if downlink else None,
        "avg_rtt_ms": round(_avg(rtt), 2) if rtt else None,
        "jitter_ms": round(jitter, 2) if jitter is not None else None,
        "avg_uplink_loss_pct": round(_avg(uplink_loss), 2) if uplink_loss else None,
        "avg_downlink_loss_pct": round(_avg(downlink_loss), 2) if downlink_loss else None,
    }


def run_probe(config: ProbeRunConfig) -> dict:
    logs: list[str] = []
    try:
        target_ip = socket.gethostbyname(config.host)
    except socket.gaierror as exc:
        raise ValueError(f"Cannot resolve {config.host}: {exc}") from exc

    logs.append(f"Connecting to {config.host} ({target_ip}):{config.port}")

    session_offset: float | None = None
    if config.apply_offset_correction and config.sync_packets > 0:
        sync_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            logs.append("Clock sync…")
            session_offset = _measure_clock_offset(
                sync_sock,
                config.host,
                config.port,
                config.sync_packets,
                config.recv_timeout_ms,
                logs,
            )
        finally:
            sync_sock.close()

    round_results: list[RoundResult] = []
    for round_no in range(1, config.rounds + 1):
        round_results.append(
            _send_round(
                host=config.host,
                port=config.port,
                round_no=round_no,
                packets=config.packets,
                recv_timeout_ms=config.recv_timeout_ms,
                summary_timeout_ms=config.summary_timeout_ms,
                apply_offset_correction=config.apply_offset_correction,
                fixed_offset_ms=session_offset,
                logs=logs,
            )
        )
        if round_no < config.rounds and config.pause_ms > 0:
            time.sleep(config.pause_ms / 1000.0)

    logs.append("All rounds complete")
    return {
        "target": {"host": config.host, "ip": target_ip, "port": config.port},
        "config": {
            "rounds": config.rounds,
            "packets": config.packets,
            "apply_offset_correction": config.apply_offset_correction,
        },
        "clock_offset_ms": round(session_offset, 2) if session_offset is not None else None,
        "overall": _combine_rounds(round_results),
        "rounds": [r.to_dict() for r in round_results],
        "logs": logs,
    }
