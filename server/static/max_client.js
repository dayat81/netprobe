/* MAX Throughput Test client — runs parallel stream test via server API */
(function () {
  "use strict";

  const startBtn = document.getElementById("maxStartBtn");
  const stopBtn = document.getElementById("maxStopBtn");
  const progressEl = document.getElementById("maxProgress");
  const progressBar = document.getElementById("maxProgressBar");
  const metricsEl = document.getElementById("maxMetrics");
  const resultsTable = document.getElementById("maxResultsTable");
  const resultsBody = document.getElementById("maxResultsBody");
  const chartCanvas = document.getElementById("maxChart");
  const burstChartCanvas = document.getElementById("maxBurstChart");
  const burstChartWrap = document.getElementById("maxBurstChartWrap");
  const logEl = document.getElementById("maxLog");

  let maxChart = null;
  let burstChart = null;
  let running = false;
  let controller = null;

  function fmtTp(bps) {
    if (bps == null) return "—";
    if (bps >= 1e6) return (bps / 1e6).toFixed(1) + " MB/s";
    if (bps >= 1e3) return (bps / 1e3).toFixed(1) + " KB/s";
    return bps.toFixed(0) + " B/s";
  }

  function fmtMs(ms) {
    if (ms == null) return "—";
    return ms >= 1000 ? (ms / 1000).toFixed(2) + "s" : ms.toFixed(1) + "ms";
  }

  function tpClass(tp) {
    if (tp == null) return "";
    if (tp >= 500000) return "udp-good";
    if (tp >= 100000) return "udp-warn";
    return "udp-bad";
  }

  function lossClass(loss) {
    if (loss == null) return "";
    if (loss <= 0.5) return "udp-good";
    if (loss <= 1.0) return "udp-warn";
    return "udp-bad";
  }

  function renderMetrics(capacity, optimal, burst, burstAbove) {
    let html = "";
    // Capacity from Phase 1 (0% loss)
    if (capacity) {
      html += '<div class="udp-metric">' +
        '<label>🟢 Capacity (0% loss)</label>' +
        '<span class="' + tpClass(capacity.throughput) + '">' + fmtTp(capacity.throughput) + '</span>' +
        '<span class="meta">' + capacity.parallel_streams + ' streams × 1400B · loss ' + capacity.loss_pct + '%</span>' +
      '</div>';
    }
    // Saturation from Phase 1 (<5% loss)
    if (optimal) {
      html += '<div class="udp-metric">' +
        '<label>🏆 Peak Throughput</label>' +
        '<span class="' + tpClass(optimal.throughput) + '">' + fmtTp(optimal.throughput) + '</span>' +
        '<span class="meta">' + optimal.parallel_streams + ' streams × 1400B · loss ' + optimal.loss_pct + '%</span>' +
      '</div>';
    }
    if (burst) {
      html += '<div class="udp-metric">' +
        '<label>🔥 Stable Burst</label>' +
        '<span class="' + tpClass(burst.throughput) + '">' + fmtTp(burst.throughput) + '</span>' +
        '<span class="meta">' + (burst.streams || '') + ' streams · ' + burst.duration_s + 's · loss ' + burst.loss_pct + '%</span>' +
      '</div>';
    }
    if (burstAbove) {
      html += '<div class="udp-metric">' +
        '<label>🔴 Above Capacity</label>' +
        '<span class="' + tpClass(burstAbove.throughput) + '">' + fmtTp(burstAbove.throughput) + '</span>' +
        '<span class="meta">' + (burstAbove.streams || '') + ' streams · ' + burstAbove.duration_s + 's · loss ' + burstAbove.loss_pct + '%</span>' +
      '</div>';
    }
    if (optimal) {
      html += '<div class="udp-metric">' +
        '<label>📊 Optimal Streams</label>' +
        '<span>' + optimal.parallel_streams + '</span>' +
        '<span class="meta">' + fmtMs(optimal.avg_rtt_ms) + ' rtt · ' + fmtMs(optimal.jitter_ms) + ' jitter</span>' +
      '</div>';
    }
    metricsEl.innerHTML = html || '<div class="empty">No results</div>';
  }

  function renderTable(levels) {
    if (!levels || !levels.length) {
      resultsTable.style.display = "none";
      return;
    }
    resultsTable.style.display = "table";
    resultsBody.innerHTML = levels.map(function(r) {
      return '<tr>' +
        '<td style="font-weight:600">' + r.parallel_streams + '</td>' +
        '<td class="' + tpClass(r.throughput) + '">' + fmtTp(r.throughput) + '</td>' +
        '<td class="' + lossClass(r.loss_pct) + '">' + r.loss_pct.toFixed(1) + '%</td>' +
        '<td>' + fmtMs(r.avg_rtt_ms) + '</td>' +
        '<td class="meta">' + fmtMs(r.jitter_ms) + '</td>' +
        '<td>' + r.total_sent + '</td>' +
        '<td>' + r.total_received + '</td>' +
      '</tr>';
    }).join("");
  }

  function renderChart(levels) {
    if (!levels || !levels.length) return;
    var labels = levels.map(function(r) { return r.parallel_streams + " streams"; });
    var tpData = levels.map(function(r) { return r.throughput ? r.throughput / 1000 : 0; });
    var lossData = levels.map(function(r) { return r.loss_pct; });

    if (maxChart) maxChart.destroy();
    maxChart = new Chart(chartCanvas, {
      type: "bar",
      data: {
        labels: labels,
        datasets: [
          {
            label: "Throughput (KB/s)",
            data: tpData,
            backgroundColor: levels.map(function(r) { return r.loss_pct < 1 ? "rgba(34,197,94,0.7)" : "rgba(239,68,68,0.7)"; }),
            borderColor: levels.map(function(r) { return r.loss_pct < 1 ? "#22c55e" : "#ef4444"; }),
            borderWidth: 1,
            yAxisID: "y",
          },
          {
            label: "Loss (%)",
            data: lossData,
            type: "line",
            borderColor: "#ef4444",
            borderDash: [5, 5],
            pointRadius: 4,
            yAxisID: "y1",
          },
        ],
      },
      options: {
        responsive: true,
        plugins: {
          legend: { labels: { color: "#e7ecf3" } },
          title: { display: true, text: "Throughput vs Parallel Streams", color: "#e7ecf3" },
        },
        scales: {
          x: { ticks: { color: "#8b98a8" }, grid: { color: "#334155" } },
          y: { type: "linear", position: "left", ticks: { color: "#8b98a8" }, grid: { color: "#334155" }, title: { display: true, text: "KB/s", color: "#8b98a8" } },
          y1: { type: "linear", position: "right", min: 0, ticks: { color: "#8b98a8" }, grid: { drawOnChartArea: false }, title: { display: true, text: "Loss %", color: "#8b98a8" } },
        },
      },
    });
  }

  /* ── Burst Timeline Chart ──────────────────────────────────────── */
  function renderBurstChart(timeseries, burstData, capacityKbps) {
    if (!timeseries || timeseries.length < 2) {
      if (burstChartWrap) burstChartWrap.style.display = "none";
      return;
    }
    if (burstChartWrap) burstChartWrap.style.display = "block";

    if (capacityKbps == null) capacityKbps = burstData ? (burstData.capacity_kbps || 0) : 0;

    var labels = timeseries.map(function(p) { return p.t.toFixed(1) + "s"; });
    var kbpsData = timeseries.map(function(p) { return p.kbps; });
    var lossData = timeseries.map(function(p) { return p.loss != null ? p.loss : 0; });

    // Find burst region (where traffic > 98% capacity)
    var burstStart = -1, burstEnd = -1;
    for (var i = 0; i < kbpsData.length; i++) {
      if (kbpsData[i] > capacityKbps * 0.98) {
        if (burstStart < 0) burstStart = i;
        burstEnd = i;
      }
    }

    // Capacity line (constant dataset)
    var capacityLine = kbpsData.map(function() { return capacityKbps; });

    if (burstChart) burstChart.destroy();

    // Plugin: draw burst region shading + vertical lines + label
    var burstRegionPlugin = {
      id: "burstRegion",
      beforeDatasetsDraw: function(chart) {
        if (burstStart < 0 || burstEnd < 0) return;
        var ctx = chart.ctx;
        var chartArea = chart.chartArea;
        var xScale = chart.scales.x;

        var x1 = xScale.getPixelForValue(Math.max(-0.5, burstStart - 0.5));
        var x2 = xScale.getPixelForValue(Math.min(burstEnd + 0.5, kbpsData.length - 1));

        // Red translucent burst region
        ctx.save();
        ctx.fillStyle = "rgba(239, 68, 68, 0.08)";
        ctx.fillRect(x1, chartArea.top, x2 - x1, chartArea.bottom - chartArea.top);

        // Red dashed vertical lines at burst boundaries
        ctx.strokeStyle = "rgba(239, 68, 68, 0.6)";
        ctx.lineWidth = 2;
        ctx.setLineDash([6, 4]);
        ctx.beginPath();
        ctx.moveTo(x1, chartArea.top);
        ctx.lineTo(x1, chartArea.bottom);
        ctx.moveTo(x2, chartArea.top);
        ctx.lineTo(x2, chartArea.bottom);
        ctx.stroke();

        // "Burst" label
        ctx.setLineDash([]);
        ctx.fillStyle = "#ef4444";
        ctx.font = "bold 13px 'Segoe UI', system-ui, sans-serif";
        ctx.textAlign = "center";
        ctx.fillText("Burst", (x1 + x2) / 2, chartArea.bottom + 38);
        ctx.restore();
      }
    };

    // Plugin: draw "Network Capacity" label on the right
    var capacityLabelPlugin = {
      id: "capacityLabel",
      afterDatasetsDraw: function(chart) {
        var ctx = chart.ctx;
        var chartArea = chart.chartArea;
        var yScale = chart.scales.y;
        var yPixel = yScale.getPixelForValue(capacityKbps);
        if (yPixel < chartArea.top || yPixel > chartArea.bottom) return;

        ctx.save();
        ctx.fillStyle = "#3b82f6";
        ctx.font = "bold 12px 'Segoe UI', system-ui, sans-serif";
        ctx.textAlign = "left";
        ctx.fillText("Capacity", chartArea.left + 8, yPixel - 6);
        ctx.restore();
      }
    };

    burstChart = new Chart(burstChartCanvas, {
      type: "line",
      data: {
        labels: labels,
        datasets: [
          {
            label: "Traffic (Kbps)",
            data: kbpsData,
            borderColor: "#22c55e",
            backgroundColor: "rgba(34,197,94,0.1)",
            borderWidth: 2.5,
            pointRadius: 3,
            pointBackgroundColor: kbpsData.map(function(v) {
              return v > capacityKbps * 0.98 ? "rgba(239,68,68,0.9)" : "rgba(34,197,94,0.9)";
            }),
            fill: false,
            tension: 0.3,
            segment: {
              borderColor: function(ctx) {
                var i = ctx.p0DataIndex;
                return (kbpsData[i] > capacityKbps * 0.98) ? "#ef4444" : "#22c55e";
              }
            },
            order: 1,
          },
          {
            label: "Network Capacity",
            data: capacityLine,
            borderColor: "#3b82f6",
            borderWidth: 2,
            borderDash: [8, 4],
            pointRadius: 0,
            fill: false,
            order: 2,
          },
          {
            label: "Packet Loss (%)",
            data: lossData,
            borderColor: "#f59e0b",
            backgroundColor: "rgba(245, 158, 11, 0.1)",
            borderWidth: 2,
            pointRadius: 3,
            pointBackgroundColor: "rgba(245, 158, 11, 0.9)",
            fill: false,
            tension: 0.3,
            yAxisID: "y1",
            order: 3,
          },
        ],
      },
      options: {
        responsive: true,
        interaction: { mode: "index", intersect: false },
        plugins: {
          legend: {
            labels: {
              color: "#e7ecf3",
              usePointStyle: true,
              pointStyle: "line",
            }
          },
          tooltip: {
            callbacks: {
              label: function(ctx) {
                if (ctx.datasetIndex === 0) return "Traffic: " + ctx.parsed.y.toFixed(1) + " Kbps";
                if (ctx.datasetIndex === 2) return "Loss: " + ctx.parsed.y.toFixed(1) + "%";
                return "Capacity: " + ctx.parsed.y.toFixed(1) + " Kbps";
              }
            }
          }
        },
        scales: {
          x: {
            ticks: { color: "#8b98a8", maxRotation: 0, autoSkip: true, maxTicksLimit: 15 },
            grid: { color: "#334155" },
            title: { display: true, text: "Time", color: "#8b98a8" },
          },
          y: {
            position: "left",
            ticks: {
              color: "#8b98a8",
              callback: function(v) {
                return v >= 1000 ? (v / 1000).toFixed(0) + " Mbps" : v.toFixed(0) + " Kbps";
              }
            },
            grid: { color: "#334155" },
            title: { display: true, text: "Network Traffic", color: "#8b98a8" },
            beginAtZero: true,
          },
          y1: {
            position: "right",
            min: 0,
            max: 100,
            ticks: {
              color: "#f59e0b",
              callback: function(v) { return v + "%"; }
            },
            grid: { drawOnChartArea: false },
            title: { display: true, text: "Packet Loss (%)", color: "#f59e0b" },
          },
        },
      },
      plugins: [burstRegionPlugin, capacityLabelPlugin],
    });
  }

  async function runMax() {
    running = true;
    startBtn.disabled = true;
    startBtn.textContent = "Running…";
    stopBtn.classList.remove("hidden");
    stopBtn.disabled = false;
    progressEl.classList.add("visible");
    progressBar.style.width = "0%";
    logEl.textContent = "Starting MAX throughput test…\n";
    metricsEl.innerHTML = "";
    resultsBody.innerHTML = "";
    resultsTable.style.display = "none";
    if (maxChart) { maxChart.destroy(); maxChart = null; }
    if (burstChart) { burstChart.destroy(); burstChart = null; }
    if (burstChartWrap) burstChartWrap.style.display = "none";

    controller = new AbortController();

    var totalPhases = 7;
    var phase = 0;
    var progressInterval = setInterval(function() {
      if (!running) { clearInterval(progressInterval); return; }
      phase++;
      var pct = Math.min(95, (phase / totalPhases) * 100);
      progressBar.style.width = pct + "%";
    }, 8000);

    try {
      var resp = await fetch("/api/max/run", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({}),
        signal: controller.signal,
      });

      clearInterval(progressInterval);
      progressBar.style.width = "100%";

      if (!resp.ok) {
        var err = await resp.json().catch(function() { return { detail: resp.statusText }; });
        throw new Error(err.detail || "MAX request failed");
      }

      var data = await resp.json();

      if (data.logs) logEl.textContent = data.logs.join("\n");

      renderMetrics(data.capacity, data.optimal, data.burst, data.burst_above);
      renderTable(data.levels);
      renderChart(data.levels);
      // Prefer stable burst (Phase 2, low loss) over above-capacity (Phase 3, high loss)
      var burstTs = data.burst_timeseries && data.burst_timeseries.length >= 2
        ? data.burst_timeseries : data.burst_above_timeseries;
      // Capacity from stable burst (Phase 2)
      var burstCap = data.burst ? data.burst.capacity_kbps : 0;
      renderBurstChart(burstTs, data.burst, burstCap);

    } catch (err) {
      if (err.name === "AbortError") {
        logEl.textContent += "\n⚠ Test stopped by user.";
      } else {
        logEl.textContent += "\n✗ Error: " + err.message;
      }
    } finally {
      clearInterval(progressInterval);
      running = false;
      controller = null;
      startBtn.disabled = false;
      startBtn.textContent = "Start MAX Test";
      stopBtn.classList.add("hidden");
      setTimeout(function() { progressEl.classList.remove("visible"); }, 1000);
    }
  }

  function stopMax() {
    if (controller) controller.abort();
    running = false;
  }

  if (startBtn) startBtn.addEventListener("click", runMax);
  if (stopBtn) stopBtn.addEventListener("click", stopMax);
})();