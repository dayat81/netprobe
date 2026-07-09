"""MAX Throughput Test — find maximum UDP throughput using optimal ABR packet size.

Algorithm:
1. Get optimal packet size from ABR (or use default 1400B)
2. Test with increasing parallel streams: 1, 2, 4, 8, 16, 32
3. For each level: send N packets per stream, measure throughput & loss
4. Find the level with highest throughput where loss < 1%
5. Also do a sustained burst test at the optimal level
"""

from __future__ import annotations

import socket
import time
import threading
from dataclasses import dataclass, field

DEFAULT_PACKET_SIZE = 1400
DEFAULT_STREAM_COUNTS = [1, 2, 4, 8, 16, 32]
PACKETS_PER_STREAM = 50
BURST_DURATION_S = 5.0  # sustained burst test duration
RECV_TIMEOUT = 2.0
LOSS_THRESHOLD = 1.0  # max loss % for valid result


def _format_throughput(bps: float | None) -> str:
    if bps is None:
        return "—"
    if bps >= 1_000_000:
        return f"{bps / 1_000_000:.1f} MB/s"
    if bps >= 1_000:
        return f"{bps / 1_000:.1f} KB/s"
    return f"{bps:.0f} B/s"


@dataclass
class StreamResult:
    stream_id: int
    sent: int = 0
    received: int = 0
    total_bytes: int = 0
    rtt_samples: list[float] = field(default_factory=list)

    @property
    def loss_pct(self) -> float:
        return ((self.sent - self.received) / self.sent * 100.0) if self.sent else 0.0


@dataclass
class LevelResult:
    parallel_streams: int
    packet_size: int
    streams: list[StreamResult] = field(default_factory=list)
    wall_time_s: float = 0.0

    @property
    def total_sent(self) -> int:
        return sum(s.sent for s in self.streams)

    @property
    def total_received(self) -> int:
        return sum(s.received for s in self.streams)

    @property
    def total_bytes(self) -> int:
        return sum(s.total_bytes for s in self.streams)

    @property
    def loss_pct(self) -> float:
        return ((self.total_sent - self.total_received) / self.total_sent * 100.0) if self.total_sent else 0.0

    @property
    def throughput(self) -> float | None:
        if self.wall_time_s <= 0 or self.total_bytes <= 0:
            return None
        return self.total_bytes / self.wall_time_s

    @property
    def avg_rtt(self) -> float | None:
        all_rtts = [r for s in self.streams for r in s.rtt_samples]
        return sum(all_rtts) / len(all_rtts) if all_rtts else None

    @property
    def jitter(self) -> float | None:
        all_rtts = [r for s in self.streams for r in s.rtt_samples]
        if len(all_rtts) < 2:
            return None
        diffs = [abs(all_rtts[i] - all_rtts[i - 1]) for i in range(1, len(all_rtts))]
        return sum(diffs) / len(diffs)

    def to_dict(self) -> dict:
        return {
            "parallel_streams": self.parallel_streams,
            "packet_size": self.packet_size,
            "total_sent": self.total_sent,
            "total_received": self.total_received,
            "total_bytes": self.total_bytes,
            "wall_time_s": round(self.wall_time_s, 3),
            "loss_pct": round(self.loss_pct, 2),
            "throughput": round(self.throughput, 2) if self.throughput else None,
            "throughput_fmt": _format_throughput(self.throughput),
            "avg_rtt_ms": round(self.avg_rtt, 2) if self.avg_rtt else None,
            "jitter_ms": round(self.jitter, 2) if self.jitter else None,
        }


def _stream_worker(
    stream_id: int,
    host: str,
    port: int,
    packet_size: int,
    packets: int,
    recv_timeout: float,
    result: StreamResult,
    barrier: threading.Barrier,
):
    """Worker thread: send packets and collect responses."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(recv_timeout)

    try:
        # Wait for all threads to start simultaneously
        barrier.wait()

        for seq in range(1, packets + 1):
            header = f"MAX:{stream_id}:{seq}:"
            padding = max(0, packet_size - len(header))
            payload = (header + "0" * padding).encode("utf-8")[:packet_size]
            if len(payload) < packet_size:
                payload += b"\x00" * (packet_size - len(payload))

            t1 = time.time()
            sock.sendto(payload, (host, port))
            result.sent += 1

            try:
                data, _ = sock.recvfrom(4096)
                rtt = (time.time() - t1) * 1000.0
                result.received += 1
                result.total_bytes += len(data)
                result.rtt_samples.append(rtt)
            except socket.timeout:
                pass

    finally:
        sock.close()


def _burst_worker(
    stream_id: int,
    host: str,
    port: int,
    packet_size: int,
    duration_s: float,
    recv_timeout: float,
    result: StreamResult,
    barrier: threading.Barrier,
    stop_event: threading.Event,
):
    """Worker thread: sustained burst - send as fast as possible for duration_s."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(recv_timeout)
    sock.setblocking(False)

    try:
        barrier.wait()
        t_start = time.time()
        seq = 0

        while not stop_event.is_set() and (time.time() - t_start) < duration_s:
            seq += 1
            header = f"MAXB:{stream_id}:{seq}:"
            padding = max(0, packet_size - len(header))
            payload = (header + "0" * padding).encode("utf-8")[:packet_size]
            if len(payload) < packet_size:
                payload += b"\x00" * (packet_size - len(payload))

            t1 = time.time()
            try:
                sock.sendto(payload, (host, port))
                result.sent += 1
            except BlockingIOError:
                continue

            # Try to receive (non-blocking)
            try:
                data, _ = sock.recvfrom(4096)
                rtt = (time.time() - t1) * 1000.0
                result.received += 1
                result.total_bytes += len(data)
                result.rtt_samples.append(rtt)
            except (BlockingIOError, socket.timeout):
                pass

        # Drain remaining responses
        drain_start = time.time()
        while (time.time() - drain_start) < recv_timeout:
            try:
                data, _ = sock.recvfrom(4096)
                result.received += 1
                result.total_bytes += len(data)
            except (BlockingIOError, socket.timeout):
                break

    finally:
        sock.close()


def run_max_test(
    host: str = "netprobe.xyz",
    port: int = 8765,
    packet_size: int = DEFAULT_PACKET_SIZE,
    stream_counts: list[int] | None = None,
    packets_per_stream: int = PACKETS_PER_STREAM,
    burst_duration_s: float = BURST_DURATION_S,
) -> dict:
    """Run max throughput test with parallel streams."""
    if stream_counts is None:
        stream_counts = DEFAULT_STREAM_COUNTS

    logs: list[str] = []
    try:
        target_ip = socket.gethostbyname(host)
    except socket.gaierror as exc:
        raise ValueError(f"Cannot resolve {host}: {exc}") from exc

    logs.append(f"MAX throughput test to {host} ({target_ip}):{port}")
    logs.append(f"Packet size: {packet_size} B")
    logs.append(f"Stream counts: {stream_counts}")
    logs.append(f"Packets per stream: {packets_per_stream}")

    # Phase 1: Test with increasing parallel streams
    logs.append("")
    logs.append("--- Phase 1: Parallel Stream Test ---")
    level_results: list[LevelResult] = []

    for num_streams in stream_counts:
        logs.append(f"\nTesting {num_streams} parallel streams...")
        barrier = threading.Barrier(num_streams)
        streams = [StreamResult(stream_id=i) for i in range(num_streams)]
        threads = []

        t_start = time.time()
        for i in range(num_streams):
            t = threading.Thread(
                target=_stream_worker,
                args=(i, host, port, packet_size, packets_per_stream, RECV_TIMEOUT, streams[i], barrier),
            )
            threads.append(t)
            t.start()

        for t in threads:
            t.join()
        wall_time = time.time() - t_start

        level = LevelResult(
            parallel_streams=num_streams,
            packet_size=packet_size,
            streams=streams,
            wall_time_s=wall_time,
        )
        level_results.append(level)

        tp_str = _format_throughput(level.throughput)
        logs.append(
            f"  {num_streams} streams: {tp_str} "
            f"(sent={level.total_sent}, recv={level.total_received}, "
            f"loss={level.loss_pct:.1f}%, rtt={level.avg_rtt:.1f}ms, "
            f"jitter={level.jitter:.1f}ms)"
            if level.avg_rtt
            else f"  {num_streams} streams: timeout (sent={level.total_sent}, recv={level.total_received})"
        )

    # Find optimal parallel streams (highest throughput with < 1% loss)
    def _is_valid(lr: LevelResult) -> bool:
        return lr.loss_pct < LOSS_THRESHOLD and lr.throughput is not None and lr.throughput > 0

    valid_levels = [lr for lr in level_results if _is_valid(lr)]
    optimal_level = max(valid_levels, key=lambda lr: lr.throughput) if valid_levels else None

    # Phase 2: Sustained burst test at optimal level
    burst_result = None
    if optimal_level and optimal_level.parallel_streams > 0:
        num_streams = optimal_level.parallel_streams
        logs.append(f"\n--- Phase 2: Sustained Burst Test ({num_streams} streams, {burst_duration_s:.0f}s) ---")

        barrier = threading.Barrier(num_streams)
        stop_event = threading.Event()
        streams = [StreamResult(stream_id=i) for i in range(num_streams)]
        threads = []

        t_start = time.time()
        for i in range(num_streams):
            t = threading.Thread(
                target=_burst_worker,
                args=(i, host, port, packet_size, burst_duration_s, RECV_TIMEOUT, streams[i], barrier, stop_event),
            )
            threads.append(t)
            t.start()

        for t in threads:
            t.join()
        wall_time = time.time() - t_start

        burst_result = LevelResult(
            parallel_streams=num_streams,
            packet_size=packet_size,
            streams=streams,
            wall_time_s=wall_time,
        )

        tp_str = _format_throughput(burst_result.throughput)
        logs.append(
            f"  Burst result: {tp_str} "
            f"(sent={burst_result.total_sent}, recv={burst_result.total_received}, "
            f"loss={burst_result.loss_pct:.1f}%, rtt={burst_result.avg_rtt:.1f}ms)"
            if burst_result.avg_rtt
            else f"  Burst result: timeout"
        )

    # Results
    logs.append("")
    logs.append("--- Results ---")
    if optimal_level:
        logs.append(
            f"Optimal: {optimal_level.parallel_streams} streams × {packet_size}B = "
            f"{_format_throughput(optimal_level.throughput)} "
            f"(loss={optimal_level.loss_pct:.1f}%)"
        )
    else:
        logs.append("No valid throughput level found (all had > 1% loss)")
    if burst_result:
        logs.append(
            f"Sustained burst: {_format_throughput(burst_result.throughput)} "
            f"over {burst_duration_s:.0f}s (loss={burst_result.loss_pct:.1f}%)"
        )
    logs.append("MAX test complete")

    return {
        "target": {"host": host, "ip": target_ip, "port": port},
        "config": {
            "packet_size": packet_size,
            "stream_counts": stream_counts,
            "packets_per_stream": packets_per_stream,
            "burst_duration_s": burst_duration_s,
        },
        "optimal": {
            "parallel_streams": optimal_level.parallel_streams,
            "throughput": round(optimal_level.throughput, 2) if optimal_level.throughput else None,
            "throughput_fmt": _format_throughput(optimal_level.throughput),
            "loss_pct": round(optimal_level.loss_pct, 2),
            "avg_rtt_ms": round(optimal_level.avg_rtt, 2) if optimal_level.avg_rtt else None,
            "jitter_ms": round(optimal_level.jitter, 2) if optimal_level.jitter else None,
        } if optimal_level else None,
        "burst": {
            "throughput": round(burst_result.throughput, 2) if burst_result.throughput else None,
            "throughput_fmt": _format_throughput(burst_result.throughput),
            "loss_pct": round(burst_result.loss_pct, 2),
            "total_sent": burst_result.total_sent,
            "total_received": burst_result.total_received,
            "avg_rtt_ms": round(burst_result.avg_rtt, 2) if burst_result.avg_rtt else None,
            "duration_s": round(burst_result.wall_time_s, 1),
        } if burst_result else None,
        "levels": [lr.to_dict() for lr in level_results],
        "logs": logs,
    }
