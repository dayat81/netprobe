"""ABR (Adaptive Bit Rate) UDP probe — server-side v2.

Improvements over v1:
- Burst sending: send N packets back-to-back, receive all responses
- Actual throughput measurement: total_bytes / wall_clock_time
- Better statistics: jitter, min/max/median RTT, stddev
- Interleaved UL/DL per size for consistent network conditions
- More packets: 10 per round × 5 rounds = 50 per direction per size
"""

from __future__ import annotations

import socket
import time
import statistics
from dataclasses import dataclass, field

DEFAULT_SIZES = [64, 128, 256, 512, 1024, 1200, 1400]
DEFAULT_PACKETS = 10  # packets per round
DEFAULT_ROUNDS = 5    # rounds per size
RECV_TIMEOUT = 2.0    # seconds per receive
BURST_DELAY = 0.005   # 5ms between burst packets


def _avg(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def _median(values: list[float]) -> float | None:
    if not values:
        return None
    sorted_vals = sorted(values)
    n = len(sorted_vals)
    if n % 2 == 0:
        return (sorted_vals[n // 2 - 1] + sorted_vals[n // 2]) / 2.0
    return sorted_vals[n // 2]


def _jitter(values: list[float]) -> float | None:
    """Calculate jitter as average absolute difference between consecutive RTTs."""
    if len(values) < 2:
        return None
    diffs = [abs(values[i] - values[i - 1]) for i in range(1, len(values))]
    return sum(diffs) / len(diffs)


def _stddev(values: list[float]) -> float | None:
    if len(values) < 2:
        return None
    return statistics.stdev(values)


@dataclass
class AbrSizeResult:
    size: int
    ul_rtt_ms: list[float] = field(default_factory=list)
    dl_rtt_ms: list[float] = field(default_factory=list)
    ul_sent: int = 0
    ul_received: int = 0
    dl_sent: int = 0
    dl_received: int = 0
    ul_total_bytes: int = 0
    dl_total_bytes: int = 0
    ul_wall_time_s: float = 0.0
    dl_wall_time_s: float = 0.0

    @property
    def ul_loss_pct(self) -> float:
        return ((self.ul_sent - self.ul_received) / self.ul_sent * 100.0) if self.ul_sent else 0.0

    @property
    def dl_loss_pct(self) -> float:
        return ((self.dl_sent - self.dl_received) / self.dl_sent * 100.0) if self.dl_sent else 0.0

    @property
    def ul_avg_rtt(self) -> float | None:
        return _avg(self.ul_rtt_ms)

    @property
    def dl_avg_rtt(self) -> float | None:
        return _avg(self.dl_rtt_ms)

    @property
    def ul_throughput(self) -> float | None:
        """Throughput calculated from actual bytes / wall time."""
        if self.ul_wall_time_s <= 0 or self.ul_total_bytes <= 0:
            return None
        return self.ul_total_bytes / self.ul_wall_time_s

    @property
    def dl_throughput(self) -> float | None:
        """Throughput calculated from actual bytes / wall time."""
        if self.dl_wall_time_s <= 0 or self.dl_total_bytes <= 0:
            return None
        return self.dl_total_bytes / self.dl_wall_time_s

    @property
    def ul_jitter(self) -> float | None:
        return _jitter(self.ul_rtt_ms)

    @property
    def dl_jitter(self) -> float | None:
        return _jitter(self.dl_rtt_ms)

    @property
    def ul_min_rtt(self) -> float | None:
        return min(self.ul_rtt_ms) if self.ul_rtt_ms else None

    @property
    def ul_max_rtt(self) -> float | None:
        return max(self.ul_rtt_ms) if self.ul_rtt_ms else None

    @property
    def ul_median_rtt(self) -> float | None:
        return _median(self.ul_rtt_ms)

    @property
    def dl_min_rtt(self) -> float | None:
        return min(self.dl_rtt_ms) if self.dl_rtt_ms else None

    @property
    def dl_max_rtt(self) -> float | None:
        return max(self.dl_rtt_ms) if self.dl_rtt_ms else None

    @property
    def dl_median_rtt(self) -> float | None:
        return _median(self.dl_rtt_ms)

    @property
    def ul_stddev(self) -> float | None:
        return _stddev(self.ul_rtt_ms)

    @property
    def dl_stddev(self) -> float | None:
        return _stddev(self.dl_rtt_ms)

    def to_dict(self) -> dict:
        return {
            "size": self.size,
            "ul_rtt_ms": round(self.ul_avg_rtt, 2) if self.ul_avg_rtt is not None else None,
            "ul_min_rtt_ms": round(self.ul_min_rtt, 2) if self.ul_min_rtt is not None else None,
            "ul_max_rtt_ms": round(self.ul_max_rtt, 2) if self.ul_max_rtt is not None else None,
            "ul_median_rtt_ms": round(self.ul_median_rtt, 2) if self.ul_median_rtt is not None else None,
            "ul_jitter_ms": round(self.ul_jitter, 2) if self.ul_jitter is not None else None,
            "ul_stddev_ms": round(self.ul_stddev, 2) if self.ul_stddev is not None else None,
            "ul_loss_pct": round(self.ul_loss_pct, 2),
            "ul_throughput": round(self.ul_throughput, 2) if self.ul_throughput is not None else None,
            "ul_sent": self.ul_sent,
            "ul_received": self.ul_received,
            "dl_rtt_ms": round(self.dl_avg_rtt, 2) if self.dl_avg_rtt is not None else None,
            "dl_min_rtt_ms": round(self.dl_min_rtt, 2) if self.dl_min_rtt is not None else None,
            "dl_max_rtt_ms": round(self.dl_max_rtt, 2) if self.dl_max_rtt is not None else None,
            "dl_median_rtt_ms": round(self.dl_median_rtt, 2) if self.dl_median_rtt is not None else None,
            "dl_jitter_ms": round(self.dl_jitter, 2) if self.dl_jitter is not None else None,
            "dl_stddev_ms": round(self.dl_stddev, 2) if self.dl_stddev is not None else None,
            "dl_loss_pct": round(self.dl_loss_pct, 2),
            "dl_throughput": round(self.dl_throughput, 2) if self.dl_throughput is not None else None,
            "dl_sent": self.dl_sent,
            "dl_received": self.dl_received,
        }


def _format_throughput(bps: float | None) -> str:
    if bps is None:
        return "—"
    if bps >= 1_000_000:
        return f"{bps / 1_000_000:.1f} MB/s"
    if bps >= 1_000:
        return f"{bps / 1_000:.1f} KB/s"
    return f"{bps:.0f} B/s"


def _send_burst_ul(
    sock: socket.socket,
    host: str,
    port: int,
    size: int,
    round_no: int,
    packets: int,
    recv_timeout: float,
) -> tuple[list[float], int, int, int, float]:
    """Send UL burst, return (rtt_samples, sent, received, total_bytes, wall_time)."""
    rtt_samples = []
    sent = 0
    received = 0
    total_bytes = 0
    t_start = time.time()

    for seq in range(1, packets + 1):
        header = f"ABR:U:{size}:{round_no}:{seq}:"
        padding = max(0, size - len(header))
        payload = (header + "0" * padding).encode("utf-8")[:size]
        if len(payload) < size:
            payload += b"\x00" * (size - len(payload))

        t1 = time.time()
        sock.sendto(payload, (host, port))
        sent += 1

        try:
            data, _ = sock.recvfrom(4096)
            rtt = (time.time() - t1) * 1000.0
            received += 1
            total_bytes += len(data)
            rtt_samples.append(rtt)
        except socket.timeout:
            pass

        # Small delay between packets in burst
        if seq < packets:
            time.sleep(BURST_DELAY)

    wall_time = time.time() - t_start
    return rtt_samples, sent, received, total_bytes, wall_time


def _send_burst_dl(
    sock: socket.socket,
    host: str,
    port: int,
    size: int,
    round_no: int,
    packets: int,
    recv_timeout: float,
) -> tuple[list[float], int, int, int, float]:
    """Send DL burst requests, return (rtt_samples, sent, received, total_bytes, wall_time)."""
    rtt_samples = []
    sent = 0
    received = 0
    total_bytes = 0
    t_start = time.time()

    for seq in range(1, packets + 1):
        header = f"ABR:D:{size}:{round_no}:{seq}:"
        t1 = time.time()
        sock.sendto(header.encode("utf-8"), (host, port))
        sent += 1

        try:
            data, _ = sock.recvfrom(4096)
            rtt = (time.time() - t1) * 1000.0
            received += 1
            total_bytes += len(data)
            rtt_samples.append(rtt)
        except socket.timeout:
            pass

        # Small delay between packets in burst
        if seq < packets:
            time.sleep(BURST_DELAY)

    wall_time = time.time() - t_start
    return rtt_samples, sent, received, total_bytes, wall_time


def run_abr(
    host: str = "netprobe.xyz",
    port: int = 8765,
    sizes: list[int] | None = None,
    packets_per_round: int = DEFAULT_PACKETS,
    rounds: int = DEFAULT_ROUNDS,
) -> dict:
    """Run improved ABR test with burst sending and actual throughput measurement."""
    if sizes is None:
        sizes = DEFAULT_SIZES

    logs: list[str] = []
    try:
        target_ip = socket.gethostbyname(host)
    except socket.gaierror as exc:
        raise ValueError(f"Cannot resolve {host}: {exc}") from exc

    total_packets_per_dir = packets_per_round * rounds
    logs.append(f"ABR probe to {host} ({target_ip}):{port}")
    logs.append(f"Sizes: {', '.join(str(s) for s in sizes)} B")
    logs.append(f"Config: {packets_per_round} pkts/round × {rounds} rounds = {total_packets_per_dir} probes/direction/size")
    logs.append(f"Burst delay: {BURST_DELAY*1000:.0f}ms, recv timeout: {RECV_TIMEOUT}s")

    results: list[AbrSizeResult] = []
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(RECV_TIMEOUT)

    try:
        for size in sizes:
            sr = AbrSizeResult(size=size)

            # --- Uplink test for this size ---
            logs.append(f"\n--- UL {size}B ---")
            for rnd in range(1, rounds + 1):
                rtt_samples, sent, recv, total_bytes, wall_time = _send_burst_ul(
                    sock, host, port, size, rnd, packets_per_round, RECV_TIMEOUT
                )
                sr.ul_rtt_ms.extend(rtt_samples)
                sr.ul_sent += sent
                sr.ul_received += recv
                sr.ul_total_bytes += total_bytes
                sr.ul_wall_time_s += wall_time

            avg = sr.ul_avg_rtt
            jitter = sr.ul_jitter
            tp = sr.ul_throughput
            logs.append(
                f"UL {size}B: avg={avg:.1f}ms "
                f"min={sr.ul_min_rtt:.1f}ms max={sr.ul_max_rtt:.1f}ms "
                f"jitter={jitter:.1f}ms loss={sr.ul_loss_pct:.0f}% "
                f"tp={_format_throughput(tp)} "
                f"({sr.ul_received}/{sr.ul_sent})"
                if avg is not None
                else f"UL {size}B: timeout (100% loss, {sr.ul_sent} sent)"
            )

            # --- Downlink test for this size ---
            logs.append(f"--- DL {size}B ---")
            for rnd in range(1, rounds + 1):
                rtt_samples, sent, recv, total_bytes, wall_time = _send_burst_dl(
                    sock, host, port, size, rnd, packets_per_round, RECV_TIMEOUT
                )
                sr.dl_rtt_ms.extend(rtt_samples)
                sr.dl_sent += sent
                sr.dl_received += recv
                sr.dl_total_bytes += total_bytes
                sr.dl_wall_time_s += wall_time

            avg = sr.dl_avg_rtt
            jitter = sr.dl_jitter
            tp = sr.dl_throughput
            logs.append(
                f"DL {size}B: avg={avg:.1f}ms "
                f"min={sr.dl_min_rtt:.1f}ms max={sr.dl_max_rtt:.1f}ms "
                f"jitter={jitter:.1f}ms loss={sr.dl_loss_pct:.0f}% "
                f"tp={_format_throughput(tp)} "
                f"({sr.dl_received}/{sr.dl_sent})"
                if avg is not None
                else f"DL {size}B: timeout (100% loss, {sr.dl_sent} sent)"
            )

            results.append(sr)

    finally:
        sock.close()

    # Find optimal (highest throughput, <5% loss)
    def _find_optimal(direction: str):
        best = None
        best_tp = -1.0
        for sr in results:
            if direction == "ul":
                tp = sr.ul_throughput
                loss = sr.ul_loss_pct
            else:
                tp = sr.dl_throughput
                loss = sr.dl_loss_pct
            if tp is not None and loss < 5.0 and tp > best_tp:
                best_tp = tp
                best = sr
        return best

    opt_ul = _find_optimal("ul")
    opt_dl = _find_optimal("dl")

    logs.append("")
    logs.append("--- Results ---")
    if opt_ul:
        logs.append(
            f"Optimal uplink: {opt_ul.size} B @ {_format_throughput(opt_ul.ul_throughput)} "
            f"(rtt={opt_ul.ul_avg_rtt:.1f}ms, jitter={opt_ul.ul_jitter:.1f}ms, loss={opt_ul.ul_loss_pct:.1f}%)"
        )
    else:
        logs.append("Optimal uplink: none (< 5% loss)")
    if opt_dl:
        logs.append(
            f"Optimal downlink: {opt_dl.size} B @ {_format_throughput(opt_dl.dl_throughput)} "
            f"(rtt={opt_dl.dl_avg_rtt:.1f}ms, jitter={opt_dl.dl_jitter:.1f}ms, loss={opt_dl.dl_loss_pct:.1f}%)"
        )
    else:
        logs.append("Optimal downlink: none (< 5% loss)")
    logs.append("ABR test complete")

    return {
        "target": {"host": host, "ip": target_ip, "port": port},
        "config": {
            "sizes": sizes,
            "packets_per_round": packets_per_round,
            "rounds": rounds,
            "total_per_direction": total_packets_per_dir,
        },
        "optimal_uplink": {
            "size": opt_ul.size,
            "throughput": round(opt_ul.ul_throughput, 2) if opt_ul.ul_throughput else None,
            "throughput_fmt": _format_throughput(opt_ul.ul_throughput) if opt_ul else None,
            "rtt_ms": round(opt_ul.ul_avg_rtt, 2) if opt_ul.ul_avg_rtt else None,
            "jitter_ms": round(opt_ul.ul_jitter, 2) if opt_ul.ul_jitter else None,
            "loss_pct": round(opt_ul.ul_loss_pct, 2) if opt_ul else None,
        } if opt_ul else None,
        "optimal_downlink": {
            "size": opt_dl.size,
            "throughput": round(opt_dl.dl_throughput, 2) if opt_dl.dl_throughput else None,
            "throughput_fmt": _format_throughput(opt_dl.dl_throughput) if opt_dl else None,
            "rtt_ms": round(opt_dl.dl_avg_rtt, 2) if opt_dl.dl_avg_rtt else None,
            "jitter_ms": round(opt_dl.dl_jitter, 2) if opt_dl.dl_jitter else None,
            "loss_pct": round(opt_dl.dl_loss_pct, 2) if opt_dl else None,
        } if opt_dl else None,
        "results": [sr.to_dict() for sr in results],
        "logs": logs,
    }
