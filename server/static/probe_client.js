/* Browser UDP-like probe over WebTransport datagrams (binary v2 protocol). */
(function (global) {
  const MAGIC = 0x5544; // "UD"
  const SUMMARY_MAGIC = 0x5553; // "US"
  const VERSION = 1;
  const VERSION_V2 = 2;
  const SUMMARY_VERSION = 1;
  const SYNC_ROUND_ID = 0;
  const REQUEST_V2_SIZE = 15;
  const REPLY_V2_SIZE = 35;
  const SUMMARY_REQUEST_SIZE = 5;
  const SUMMARY_REPLY_SIZE = 9;
  const RECV_TIMEOUT_MS = 3000;
  const SUMMARY_TIMEOUT_MS = 500;
  const DEFAULT_ROUNDS = 5;
  const DEFAULT_PACKETS = 15;
  const SYNC_PACKETS = 3;

  const CSV_HEADER =
    "timestamp,lat,lon,imsi,imei,device_id,operator,tech,tac,site_id,cell_id,arfcn,rsrp,rsrq,snr," +
    "nr_pci,nr_gnb,nr_cell_id,nr_arfcn,nr_rsrp,nr_rsrq,nr_snr," +
    "local_ip,dns_servers,network_type,udp_latency_ms,udp_loss_pct,udp_jitter_ms," +
    "udp_uplink_ms,udp_downlink_ms,udp_uplink_loss_pct,udp_downlink_loss_pct";

  function wtProbeUrl() {
    const host = global.location?.hostname || "netprobe.xyz";
    const port = global.NETPROBE_WT_PORT || "4433";
    return `https://${host}:${port}/wt-probe`;
  }

  function deviceLabel() {
    const ua = navigator.userAgent || "";
    if (ua.includes("Edg/")) return "Browser Edge";
    if (ua.includes("Chrome/")) return "Browser Chrome";
    if (ua.includes("Firefox/")) return "Browser Firefox";
    if (ua.includes("Safari/") && !ua.includes("Chrome/")) return "Browser Safari";
    return "Browser";
  }

  function detectNetworkType() {
    return "";
  }

  function detectLocation() {
    if (!navigator.geolocation) {
      return Promise.resolve(null);
    }
    return new Promise((resolve) => {
      navigator.geolocation.getCurrentPosition(
        (pos) =>
          resolve({
            lat: pos.coords.latitude,
            lon: pos.coords.longitude,
            accuracy: pos.coords.accuracy,
          }),
        () => resolve(null),
        { enableHighAccuracy: true, timeout: 15000, maximumAge: 60000 },
      );
    });
  }

  async function detectProvider() {
    try {
      const res = await fetch("/api/isp/me");
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        return {
          error: data.detail || `HTTP ${res.status}`,
        };
      }
      return {
        operator: data.operator || data.isp || data.org || null,
        isp: data.isp || null,
        org: data.org || null,
        publicIp: data.ip || null,
      };
    } catch (err) {
      return { error: err.message || "network error" };
    }
  }

  async function detectContext() {
    const [location, provider] = await Promise.all([detectLocation(), detectProvider()]);
    return {
      lat: location?.lat ?? null,
      lon: location?.lon ?? null,
      accuracy: location?.accuracy ?? null,
      operator: provider?.operator ?? null,
      isp: provider?.isp ?? null,
      org: provider?.org ?? null,
      publicIp: provider?.publicIp ?? null,
      providerError: provider?.error ?? null,
      deviceId: deviceLabel(),
    };
  }

  function csvEscape(value) {
    if (value == null || value === "") return "";
    const text = String(value);
    if (text.includes(",") || text.includes('"') || text.includes("\n")) {
      return `"${text.replace(/"/g, '""')}"`;
    }
    return text;
  }

  function csvFloat(value) {
    return value == null || Number.isNaN(value) ? "" : Number(value).toFixed(2);
  }

  function buildProbeCsv(probeResult, context) {
    const overall = probeResult.overall || {};
    const cfg = probeResult.config || {};
    const now = new Date();
    const ts = now.toISOString().slice(0, 19);
    const stamp =
      now.getUTCFullYear().toString() +
      String(now.getUTCMonth() + 1).padStart(2, "0") +
      String(now.getUTCDate()).padStart(2, "0") +
      "-" +
      String(now.getUTCHours()).padStart(2, "0") +
      String(now.getUTCMinutes()).padStart(2, "0") +
      String(now.getUTCSeconds()).padStart(2, "0");
    const filename = `probe-web-${stamp}.csv`;
    const configLabel = `${cfg.rounds || DEFAULT_ROUNDS}x${cfg.packets || DEFAULT_PACKETS}`;
    const modeLabel = cfg.apply_offset_correction ? "webtransport-offset" : "webtransport-raw";
    const row = [
      ts,
      context.lat != null ? Number(context.lat).toFixed(6) : "",
      context.lon != null ? Number(context.lon).toFixed(6) : "",
      "",
      "",
      csvEscape(context.deviceId || deviceLabel()),
      csvEscape(context.operator || ""),
      "WEB-WT",
      configLabel,
      modeLabel,
      "",
      "",
      "",
      "",
      "",
      "",
      "",
      "",
      "",
      "",
      "",
      "",
      csvEscape(context.publicIp || ""),
      "",
      "",
      csvFloat(overall.avg_rtt_ms),
      csvFloat(overall.loss_pct),
      csvFloat(overall.jitter_ms),
      csvFloat(overall.avg_uplink_ms),
      csvFloat(overall.avg_downlink_ms),
      csvFloat(overall.avg_uplink_loss_pct),
      csvFloat(overall.avg_downlink_loss_pct),
    ].join(",");
    return { filename, content: `${CSV_HEADER}\n${row}\n` };
  }

  async function uploadProbeRecord(probeResult, context) {
    const { filename, content } = buildProbeCsv(probeResult, context);
    const form = new FormData();
    form.append("file", new Blob([content], { type: "text/csv" }), filename);
    form.append("device_id", context.deviceId || deviceLabel());
    form.append("app_version", "web dashboard");
    const res = await fetch("/api/logs/upload", { method: "POST", body: form });
    if (!res.ok) {
      const err = await res.text();
      throw new Error(`Upload failed (${res.status}): ${err}`);
    }
    return res.json();
  }

  function nowUtcNanos() {
    return BigInt(Date.now()) * 1000000n;
  }

  function packRequestV2(roundId, seq, t1) {
    const buf = new ArrayBuffer(REQUEST_V2_SIZE);
    const view = new DataView(buf);
    view.setUint16(0, MAGIC, false);
    view.setUint8(2, VERSION_V2);
    view.setUint16(3, roundId & 0xffff, false);
    view.setUint16(5, seq & 0xffff, false);
    const t1Big = BigInt.asUintN(64, typeof t1 === "bigint" ? t1 : BigInt(t1));
    view.setUint32(7, Number(t1Big >> 32n), false);
    view.setUint32(11, Number(t1Big & 0xffffffffn), false);
    return new Uint8Array(buf);
  }

  function unpackReplyV2(data) {
    if (data.byteLength < REPLY_V2_SIZE) throw new Error("reply too short");
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    if (view.getUint16(0, false) !== MAGIC) throw new Error("bad magic");
    if (view.getUint8(2) !== VERSION_V2) throw new Error("bad version");
    const roundId = view.getUint16(3, false);
    const seq = view.getUint16(5, false);
    const t1 = (BigInt(view.getUint32(7, false)) << 32n) | BigInt(view.getUint32(11, false));
    const t2 = (BigInt(view.getUint32(15, false)) << 32n) | BigInt(view.getUint32(19, false));
    const t3 = (BigInt(view.getUint32(23, false)) << 32n) | BigInt(view.getUint32(27, false));
    const serverRx = view.getUint16(31, false);
    const serverTx = view.getUint16(33, false);
    return { roundId, seq, t1, t2, t3, serverRx, serverTx };
  }

  function packSummaryRequest(roundId) {
    const buf = new ArrayBuffer(SUMMARY_REQUEST_SIZE);
    const view = new DataView(buf);
    view.setUint16(0, SUMMARY_MAGIC, false);
    view.setUint8(2, SUMMARY_VERSION);
    view.setUint16(3, roundId & 0xffff, false);
    return new Uint8Array(buf);
  }

  function unpackSummaryReply(data) {
    if (data.byteLength < SUMMARY_REPLY_SIZE) throw new Error("summary too short");
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    if (view.getUint16(0, false) !== SUMMARY_MAGIC) throw new Error("bad summary magic");
    return {
      roundId: view.getUint16(3, false),
      serverRx: view.getUint16(5, false),
      serverTx: view.getUint16(7, false),
    };
  }

  function computeLatency(reply, t4) {
    const uplinkMs = Number(reply.t2 - reply.t1) / 1e6;
    const downlinkMs = Number(BigInt.asUintN(64, typeof t4 === "bigint" ? t4 : BigInt(t4)) - reply.t3) / 1e6;
    return { seq: reply.seq, uplinkMs, downlinkMs, rttMs: uplinkMs + downlinkMs };
  }

  function clockOffsetMs(uplinkMs, downlinkMs) {
    return (uplinkMs - downlinkMs) / 2;
  }

  function median(values) {
    if (!values.length) return null;
    const sorted = [...values].sort((a, b) => a - b);
    const mid = Math.floor(sorted.length / 2);
    return sorted.length % 2 === 0
      ? (sorted[mid - 1] + sorted[mid]) / 2
      : sorted[mid];
  }

  function estimateOffset(samples) {
    if (!samples.length) return null;
    return median(samples.map((s) => clockOffsetMs(s.uplinkMs, s.downlinkMs)));
  }

  function correctLatency(lat, offsetMs) {
    const uplinkMs = lat.uplinkMs - offsetMs;
    const downlinkMs = lat.downlinkMs + offsetMs;
    return { ...lat, uplinkMs, downlinkMs, rttMs: uplinkMs + downlinkMs };
  }

  function correctedToleranceMs(rttMs, minimumMs = 1) {
    return Math.max(minimumMs, 0.02 * rttMs);
  }

  function isValidLatency(lat, toleranceMs = 0) {
    return lat.uplinkMs >= -toleranceMs && lat.downlinkMs >= -toleranceMs;
  }

  function finalizeRound(samples, applyOffset, fixedOffset) {
    if (!samples.length) return { offsetMs: fixedOffset ?? null, accepted: [], rejected: 0 };
    const offsetMs = fixedOffset ?? estimateOffset(samples);
    if (!applyOffset) {
      const accepted = samples.filter((s) => s.rttMs > 0 && s.rttMs < 60000);
      return { offsetMs, accepted, rejected: samples.length - accepted.length };
    }
    const accepted = [];
    let rejected = 0;
    for (const sample of samples) {
      const corrected = correctLatency(sample, offsetMs ?? 0);
      const tol = correctedToleranceMs(corrected.rttMs);
      if (isValidLatency(corrected, tol)) accepted.push(corrected);
      else rejected += 1;
    }
    return { offsetMs, accepted, rejected };
  }

  function avg(values) {
    return values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
  }

  function jitter(values) {
    if (values.length < 2) return null;
    let sum = 0;
    for (let i = 1; i < values.length; i++) sum += Math.abs(values[i] - values[i - 1]);
    return sum / (values.length - 1);
  }

  function uplinkLossPct(sent, serverRx) {
    if (sent <= 0) return null;
    return Math.max(0, ((sent - serverRx) / sent) * 100);
  }

  function downlinkLossPct(serverTx, replyRx) {
    if (serverTx <= 0) return null;
    return Math.max(0, ((serverTx - replyRx) / serverTx) * 100);
  }

  function createInbox(transport) {
    const inbox = [];
    const reader = transport.datagrams.readable.getReader();
    (async () => {
      try {
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          inbox.push(value);
        }
      } catch (_) {
        // stream closed
      } finally {
        reader.releaseLock();
      }
    })();
    return inbox;
  }

  async function waitForReply(inbox, matchFn, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      while (inbox.length) {
        const data = inbox.shift();
        try {
          const reply = unpackReplyV2(data);
          if (matchFn(reply)) return reply;
        } catch (_) {
          // ignore non-v2 frames
        }
      }
      await new Promise((r) => setTimeout(r, 10));
    }
    return null;
  }

  async function waitForSummary(inbox, roundId, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      while (inbox.length) {
        const data = inbox.shift();
        try {
          const summary = unpackSummaryReply(data);
          if (summary.roundId === roundId) return summary;
        } catch (_) {
          // ignore
        }
      }
      await new Promise((r) => setTimeout(r, 10));
    }
    return null;
  }

  async function measureClockOffset(writer, inbox, syncPackets, logs) {
    const samples = [];
    for (let seq = 1; seq <= syncPackets; seq++) {
      const t1 = nowUtcNanos();
      await writer.write(packRequestV2(SYNC_ROUND_ID, seq, t1));
      const reply = await waitForReply(
        inbox,
        (r) => r.roundId === SYNC_ROUND_ID && r.seq === seq && r.t1 === t1,
        RECV_TIMEOUT_MS,
      );
      if (reply) {
        const t4 = nowUtcNanos();
        samples.push(computeLatency(reply, t4));
      }
    }
    const offset = estimateOffset(samples);
    if (offset != null) {
      logs.push(`Clock sync: offset ${offset.toFixed(1)} ms (${samples.length}/${syncPackets} samples)`);
    } else {
      logs.push("Clock sync: no samples — per-round offset estimate");
    }
    return offset;
  }

  async function sendRound(writer, inbox, roundNo, packets, applyOffset, fixedOffset, logs) {
    const metrics = {
      round_no: roundNo,
      sent: 0,
      received: 0,
      reply_rx: 0,
      uplink_ms: [],
      downlink_ms: [],
      rtt_ms: [],
      skew_rejected: 0,
      clock_offset_ms: null,
      server_rx: null,
      server_tx: null,
      max_server_rx: 0,
      max_server_tx: 0,
    };
    const rawSamples = [];
    const roundId = roundNo & 0xffff;
    logs.push(`Round ${roundNo} started (WebTransport binary v2)`);

    for (let seq = 1; seq <= packets; seq++) {
      const t1 = nowUtcNanos();
      await writer.write(packRequestV2(roundId, seq, t1));
      metrics.sent += 1;
      const reply = await waitForReply(
        inbox,
        (r) => r.roundId === roundId && r.seq === seq && r.t1 === t1,
        RECV_TIMEOUT_MS,
      );
      if (!reply) {
        logs.push(`  ✗ pkt ${seq} timeout`);
        continue;
      }
      const t4 = nowUtcNanos();
      metrics.reply_rx += 1;
      metrics.max_server_rx = Math.max(metrics.max_server_rx, reply.serverRx);
      metrics.max_server_tx = Math.max(metrics.max_server_tx, reply.serverTx);
      const raw = computeLatency(reply, t4);
      rawSamples.push(raw);
      logs.push(`  ✓ pkt ${seq} raw rtt ${raw.rttMs.toFixed(0)} ms`);
    }

    const finalized = finalizeRound(rawSamples, applyOffset, fixedOffset);
    metrics.clock_offset_ms = finalized.offsetMs;
    metrics.skew_rejected = finalized.rejected;
    metrics.received = finalized.accepted.length;
    for (const lat of finalized.accepted) {
      metrics.uplink_ms.push(lat.uplinkMs);
      metrics.downlink_ms.push(lat.downlinkMs);
      metrics.rtt_ms.push(lat.rttMs);
    }

    await writer.write(packSummaryRequest(roundId));
    const summary = await waitForSummary(inbox, roundId, SUMMARY_TIMEOUT_MS);
    if (summary) {
      metrics.server_rx = summary.serverRx;
      metrics.server_tx = summary.serverTx;
      logs.push(`  · summary rx=${summary.serverRx} tx=${summary.serverTx}`);
    } else {
      logs.push("  · summary timeout (using reply counters)");
      if (metrics.max_server_rx > 0) metrics.server_rx = metrics.max_server_rx;
      if (metrics.max_server_tx > 0) metrics.server_tx = metrics.max_server_tx;
    }

    const sent = metrics.sent;
    const rx = metrics.server_rx ?? metrics.max_server_rx;
    const tx = metrics.server_tx ?? metrics.max_server_tx;
    const lossPct = sent ? ((sent - metrics.received) / sent) * 100 : 0;
    logs.push(
      `Round ${roundNo} done — rtt loss ${lossPct.toFixed(0)}% ` +
        `up ${rx != null ? uplinkLossPct(sent, rx)?.toFixed(0) : "—"}% ` +
        `down ${tx != null ? downlinkLossPct(tx, metrics.reply_rx)?.toFixed(0) : "—"}%`,
    );

    return {
      round_no: roundNo,
      sent: metrics.sent,
      received: metrics.received,
      loss_pct: round(lossPct, 2),
      uplink_loss_pct: rx != null ? round(uplinkLossPct(sent, rx), 2) : null,
      downlink_loss_pct: tx != null ? round(downlinkLossPct(tx, metrics.reply_rx), 2) : null,
      avg_uplink_ms: round(avg(metrics.uplink_ms), 2),
      avg_downlink_ms: round(avg(metrics.downlink_ms), 2),
      avg_rtt_ms: round(avg(metrics.rtt_ms), 2),
      jitter_ms: round(jitter(metrics.rtt_ms), 2),
      clock_offset_ms: metrics.clock_offset_ms != null ? round(metrics.clock_offset_ms, 2) : null,
      skew_rejected: metrics.skew_rejected,
    };
  }

  function round(value, digits) {
    if (value == null || Number.isNaN(value)) return null;
    const p = 10 ** digits;
    return Math.round(value * p) / p;
  }

  function combineRounds(rounds) {
    const sent = rounds.reduce((s, r) => s + r.sent, 0);
    const received = rounds.reduce((s, r) => s + r.received, 0);
    const uplink = rounds.flatMap((r) => (r.avg_uplink_ms != null ? [r.avg_uplink_ms] : []));
    const downlink = rounds.flatMap((r) => (r.avg_downlink_ms != null ? [r.avg_downlink_ms] : []));
    const rtt = rounds.flatMap((r) => (r.avg_rtt_ms != null ? [r.avg_rtt_ms] : []));
    const upLoss = rounds.flatMap((r) => (r.uplink_loss_pct != null ? [r.uplink_loss_pct] : []));
    const downLoss = rounds.flatMap((r) => (r.downlink_loss_pct != null ? [r.downlink_loss_pct] : []));
    const allRtt = rounds.flatMap((r) => (r.avg_rtt_ms != null ? [r.avg_rtt_ms] : []));
    return {
      sent,
      received,
      loss_pct: sent ? round(((sent - received) / sent) * 100, 2) : 0,
      avg_uplink_ms: round(avg(uplink), 2),
      avg_downlink_ms: round(avg(downlink), 2),
      avg_rtt_ms: round(avg(rtt), 2),
      jitter_ms: round(jitter(allRtt), 2),
      avg_uplink_loss_pct: round(avg(upLoss), 2),
      avg_downlink_loss_pct: round(avg(downLoss), 2),
    };
  }

  async function runProbe(options = {}) {
    if (typeof WebTransport === "undefined") {
      throw new Error("WebTransport is not supported in this browser");
    }

    const rounds = options.rounds ?? DEFAULT_ROUNDS;
    const packets = options.packets ?? DEFAULT_PACKETS;
    const applyOffset = options.apply_offset_correction ?? true;
    const url = options.url ?? wtProbeUrl();
    const logs = [];
    const transport = new WebTransport(url);

    try {
      await transport.ready;
      logs.push(`Connected to ${url}`);
      const inbox = createInbox(transport);
      const writer = transport.datagrams.writable.getWriter();

      let sessionOffset = null;
      if (applyOffset) {
        logs.push("Clock sync…");
        sessionOffset = await measureClockOffset(writer, inbox, SYNC_PACKETS, logs);
      }

      const roundResults = [];
      for (let roundNo = 1; roundNo <= rounds; roundNo++) {
        if (options.shouldCancel?.()) {
          throw new Error("Probe cancelled");
        }
        roundResults.push(
          await sendRound(writer, inbox, roundNo, packets, applyOffset, sessionOffset, logs),
        );
        if (roundNo < rounds) await new Promise((r) => setTimeout(r, 500));
      }

      logs.push("All rounds complete");
      const host = global.location?.hostname || "netprobe.xyz";
      return {
        target: { host, port: Number(global.NETPROBE_WT_PORT || 4433), ip: host },
        config: { rounds, packets, apply_offset_correction: applyOffset },
        clock_offset_ms: sessionOffset != null ? round(sessionOffset, 2) : null,
        overall: combineRounds(roundResults),
        rounds: roundResults,
        logs,
        context: options.context || null,
      };
    } finally {
      try {
        transport.close();
      } catch (_) {
        // ignore
      }
    }
  }

  global.NetProbeClient = {
    runProbe,
    wtProbeUrl,
    detectContext,
    detectLocation,
    detectProvider,
    detectNetworkType,
    deviceLabel,
    buildProbeCsv,
    uploadProbeRecord,
  };
})(typeof window !== "undefined" ? window : globalThis);
