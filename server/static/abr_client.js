/* ABR (Adaptive Bit Rate) test client for NetProbe dashboard */

const AbrClient = (() => {
  let abrRunning = false;
  let abrChart = null;

  function init() {
    const btn = document.getElementById("abrStartBtn");
    if (btn) btn.addEventListener("click", runAbrTest);
  }

  function setAbrRunning(running) {
    abrRunning = running;
    const btn = document.getElementById("abrStartBtn");
    const progress = document.getElementById("abrProgress");
    const bar = document.getElementById("abrProgressBar");
    if (btn) btn.disabled = running;
    if (progress) progress.classList.toggle("visible", running);
    if (bar && !running) bar.style.width = "0%";
  }

  function formatBps(bps) {
    if (bps == null) return "—";
    if (bps >= 1_000_000_000) return (bps / 1_000_000_000).toFixed(1) + " Gbps";
    if (bps >= 1_000_000) return (bps / 1_000_000).toFixed(1) + " Mbps";
    if (bps >= 1_000) return (bps / 1_000).toFixed(1) + " Kbps";
    return bps.toFixed(0) + " bps";
  }

  function lossClass(loss) {
    if (loss == null) return "";
    if (loss < 1) return "udp-good";
    if (loss < 5) return "udp-warn";
    return "udp-bad";
  }

  function tpClass(tp, allResults) {
    if (tp == null) return "";
    const valid = allResults.filter(r => r.ul_throughput != null).map(r => r.ul_throughput);
    if (!valid.length) return "";
    const max = Math.max(...valid);
    if (tp >= max * 0.8) return "udp-good";
    if (tp >= max * 0.5) return "udp-warn";
    return "udp-bad";
  }

  function renderOptimalCard(el, label, color, optimal) {
    if (!optimal) {
      el.innerHTML = `<div class="udp-metric" style="border-left:3px solid #334155">
        <label>${label}</label><span style="color:var(--muted)">No valid result</span>
      </div>`;
      return;
    }
    el.innerHTML = `<div class="udp-metric" style="border-left:3px solid ${color}">
      <label>${label} — ${optimal.size} bytes</label>
      <span style="color:${color}">${formatBps(optimal.throughput_bps)}</span>
      <div style="font-size:11px;color:var(--muted);margin-top:4px">
        RTT ${optimal.rtt_ms != null ? optimal.rtt_ms.toFixed(1) + " ms" : "—"} · Loss ${optimal.loss_pct.toFixed(1)}%
      </div>
    </div>`;
  }

  function renderResultsTable(tbody, results) {
    tbody.innerHTML = results.map(r => `
      <tr>
        <td style="font-weight:600">${r.size}</td>
        <td>${r.ul_rtt_ms != null ? r.ul_rtt_ms.toFixed(1) : "—"}</td>
        <td class="${lossClass(r.ul_loss_pct)}">${r.ul_loss_pct.toFixed(1)}%</td>
        <td>${formatBps(r.ul_throughput)}</td>
        <td>${r.dl_rtt_ms != null ? r.dl_rtt_ms.toFixed(1) : "—"}</td>
        <td class="${lossClass(r.dl_loss_pct)}">${r.dl_loss_pct.toFixed(1)}%</td>
        <td>${formatBps(r.dl_throughput)}</td>
      </tr>
    `).join("");
  }

  function renderChart(canvas, results) {
    if (abrChart) abrChart.destroy();

    const labels = results.map(r => r.size + "B");
    const ulData = results.map(r => r.ul_throughput ? r.ul_throughput / 1_000_000 : 0);
    const dlData = results.map(r => r.dl_throughput ? r.dl_throughput / 1_000_000 : 0);

    const colors = [
      "rgba(59,130,246,0.8)",
      "rgba(99,102,241,0.8)",
      "rgba(139,92,246,0.8)",
      "rgba(168,85,247,0.8)",
      "rgba(217,70,239,0.8)",
      "rgba(236,72,153,0.8)",
      "rgba(239,68,68,0.8)",
    ];

    abrChart = new Chart(canvas, {
      type: "bar",
      data: {
        labels,
        datasets: [
          {
            label: "Uplink (Mbps)",
            data: ulData,
            backgroundColor: colors.slice(0, results.length),
            borderColor: colors.slice(0, results.length).map(c => c.replace("0.8", "1")),
            borderWidth: 1,
          },
          {
            label: "Downlink (Mbps)",
            data: dlData,
            backgroundColor: colors.slice(0, results.length).map(c => c.replace("0.8", "0.4")),
            borderColor: colors.slice(0, results.length),
            borderWidth: 1,
          },
        ],
      },
      options: {
        responsive: true,
        plugins: {
          legend: { labels: { color: "#e7ecf3" } },
          tooltip: {
            callbacks: {
              label: function(ctx) {
                return ctx.dataset.label + ": " + ctx.parsed.y.toFixed(2) + " Mbps";
              }
            }
          }
        },
        scales: {
          x: { ticks: { color: "#8b98a8" }, grid: { color: "#334155" } },
          y: {
            ticks: { color: "#8b98a8", callback: v => v.toFixed(1) + " Mbps" },
            grid: { color: "#334155" },
            title: { display: true, text: "Throughput (Mbps)", color: "#8b98a8" },
          },
        },
      },
    });
  }

  async function runAbrTest() {
    if (abrRunning) return;
    setAbrRunning(true);

    const logEl = document.getElementById("abrLog");
    const optimalUlEl = document.getElementById("abrOptimalUl");
    const optimalDlEl = document.getElementById("abrOptimalDl");
    const tbody = document.getElementById("abrResultsBody");
    const canvas = document.getElementById("abrChart");
    const bar = document.getElementById("abrProgressBar");

    logEl.textContent = "Starting ABR test…\n";
    optimalUlEl.innerHTML = "";
    optimalDlEl.innerHTML = "";
    tbody.innerHTML = "";

    try {
      bar.style.width = "10%";

      const res = await fetch("/api/abr/run", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          packets_per_round: 5,
          rounds: 3,
        }),
      });

      bar.style.width = "80%";

      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.detail || `HTTP ${res.status}`);
      }

      const data = await res.json();

      bar.style.width = "95%";

      // Render optimal cards
      renderOptimalCard(optimalUlEl, "Optimal Uplink", "#22c55e", data.optimal_uplink);
      renderOptimalCard(optimalDlEl, "Optimal Downlink", "#ef4444", data.optimal_downlink);

      // Render results table
      if (data.results && data.results.length) {
        renderResultsTable(tbody, data.results);
        renderChart(canvas, data.results);
      }

      // Render logs
      logEl.textContent = (data.logs || []).join("\n");

      bar.style.width = "100%";
    } catch (err) {
      logEl.textContent += `\nError: ${err.message}`;
    } finally {
      setAbrRunning(false);
    }
  }

  // Auto-init when DOM is ready
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }

  return { runAbrTest, init };
})();
