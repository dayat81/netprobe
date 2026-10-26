package com.aksa.magnon

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.aksa.magnon.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Network tab: pcap, gateway ping, and traceroute. Radio stays on its own tab. */
internal object ProbeChrome {
    private var activity: MainActivity? = null
    private var binding: ActivityMainBinding? = null
    private var pcapPackage = ""
    private var captureStartedMs = 0L
    private var sawRunning = false
    private var windowMin = 10
    private var rat: String? = null
    private var listening = false
    private val tick = object : Runnable {
        override fun run() {
            val host = activity ?: return
            val views = binding ?: return
            renderPcap(host, views)
            views.pcapStatus.postDelayed(this, 500)
        }
    }

    fun install(host: MainActivity, views: ActivityMainBinding) {
        activity = host
        binding = views
        views.tabNetwork.setOnClickListener { host.openProbeTab("NETWORK") }
        views.tabRadio.setOnClickListener { host.openProbeTab("RADIO") }
        views.pcapPick.setOnClickListener { pickApp(host) }
        views.pcapToggle.setOnClickListener { toggle(host) }
        views.ratAll.setOnClickListener { rat = null; refreshChart() }
        views.ratLte.setOnClickListener { rat = "LTE"; refreshChart() }
        views.ratNr.setOnClickListener { rat = "NR"; refreshChart() }
        views.win5.setOnClickListener { windowMin = 5; refreshChart() }
        views.win10.setOnClickListener { windowMin = 10; refreshChart() }
        views.win30.setOnClickListener { windowMin = 30; refreshChart() }
        views.grant.setOnClickListener { host.requestRadioPermissions() }
        if (!listening) {
            listening = true
            RadioState.addListener {
                val current = activity ?: return@addListener
                current.runOnUiThread { refreshChart() }
            }
        }
        GatewayMonitor.start(host)
        show("SPEED")
    }

    fun start(host: MainActivity) {
        val views = binding ?: return
        views.pcapStatus.removeCallbacks(tick)
        views.pcapStatus.post(tick)
        RadioService.start(host)
    }

    fun stop(host: MainActivity) {
        binding?.pcapStatus?.removeCallbacks(tick)
    }

    fun show(tab: String) {
        val views = binding ?: return
        views.networkPane.visibility = if (tab == "NETWORK") View.VISIBLE else View.GONE
        views.radioPane.visibility = View.GONE
        views.radioChart.visibility = if (tab == "RADIO") View.VISIBLE else View.GONE
        views.tabNetwork.setTextColor(color(tab == "NETWORK"))
        views.tabRadio.setTextColor(color(tab == "RADIO"))
        if (tab == "RADIO") refreshChart()
    }

    fun onRound(host: MainActivity) {
        Thread({
            val sample = runCatching { GatewayMonitor.sample(host) }.getOrNull()
            if (sample != null) {
                host.runOnUiThread { showGateway(sample) }
                MeasureUploader.enqueue(host, gatewayJson(sample))
            }
        }, "magnon-gw").start()
        Thread({
            val hops4 = runCatching { NativeTraceroute.run(host, "1.1.1.1", ipv6 = false, maxHops = 8, timeoutSec = 12) }.getOrDefault(emptyList())
            val hops6 = runCatching { NativeTraceroute.run(host, "2606:4700:4700::1111", ipv6 = true, maxHops = 8, timeoutSec = 12) }.getOrDefault(emptyList())
            host.runOnUiThread {
                binding?.networkHops?.setHops(
                    TracerouteHopView.fromRaw(hops4),
                    TracerouteHopView.fromRaw(hops6),
                    null,
                )
            }
            val trace = SpeedtestTrace.traceJson(hops4, hops6) ?: return@Thread
            MeasureUploader.enqueue(host, JSONObject().put("kind", "traceroute").put("event_id", UUID.randomUUID().toString()).put("ts_ms", System.currentTimeMillis()).put("trace", trace))
        }, "magnon-trace").start()
    }

    private fun showGateway(sample: GatewayMonitor.Sample) {
        val line = binding?.networkGateway ?: return
        val v4 = sample.ipv4?.let { host ->
            val rtt = sample.ipv4RttMs?.let { "${it.toInt()} ms" } ?: "—"
            "$host · $rtt"
        } ?: "—"
        line.text = v4
    }

    private fun gatewayJson(sample: GatewayMonitor.Sample): JSONObject {
        val row = JSONObject()
            .put("kind", "gateway")
            .put("event_id", UUID.randomUUID().toString())
            .put("ts_ms", System.currentTimeMillis())
        sample.ipv4?.let { row.put("ipv4", it) }
        sample.ipv4RttMs?.let { row.put("ipv4_rtt_ms", it) }
        sample.ipv6?.let { row.put("ipv6", it) }
        sample.ipv6RttMs?.let { row.put("ipv6_rtt_ms", it) }
        return row
    }

    private fun color(active: Boolean): Int {
        val host = activity ?: return 0
        return host.getColor(if (active) R.color.accent else R.color.text_secondary)
    }

    private fun refreshChart() {
        val views = binding ?: return
        views.chart.setData(RadioState.chartPoints(), windowMin, null, rat)
    }

    private fun pickApp(host: MainActivity) {
        val views = binding ?: return
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val rows = host.packageManager.queryIntentActivities(launch, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == host.packageName) return@mapNotNull null
                pkg to (info.loadLabel(host.packageManager)?.toString()?.ifBlank { pkg } ?: pkg)
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        if (rows.isEmpty()) {
            views.pcapStatus.text = host.getString(R.string.pcap_none)
            return
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.pcap_pick)
            .setItems(rows.map { it.second }.toTypedArray()) { _, which ->
                pcapPackage = rows[which].first
                renderPcap(host, views)
            }
            .show()
    }

    private fun toggle(host: MainActivity) {
        if (PcapVpnService.running) {
            PcapVpnService.stop(host)
            return
        }
        if (pcapPackage.isBlank()) {
            pickApp(host)
            return
        }
        val prepare = VpnService.prepare(host)
        if (prepare != null) host.launchVpnConsent(prepare) else beginCapture(host)
    }

    fun onVpnConsent(host: MainActivity, granted: Boolean) {
        val views = binding ?: return
        if (granted && pcapPackage.isNotBlank()) beginCapture(host)
        else if (!granted) views.pcapStatus.text = host.getString(R.string.pcap_denied)
    }

    private fun beginCapture(host: MainActivity) {
        captureStartedMs = System.currentTimeMillis()
        sawRunning = true
        PcapVpnService.start(host, pcapPackage)
    }

    private fun renderPcap(host: MainActivity, views: ActivityMainBinding) {
        val active = PcapVpnService.running
        if (sawRunning && !active) {
            sawRunning = false
            uploadSummary(host)
        }
        if (active) sawRunning = true
        val pkg = if (active) PcapVpnService.targetPackage else pcapPackage
        val count = PcapVpnService.packets
        val saved = PcapVpnService.path
        val note = PcapVpnService.message
        views.pcapStatus.text = when {
            active -> host.getString(R.string.pcap_running, pkg, count)
            note.isNotBlank() -> note
            count > 0L && saved.isNotBlank() -> host.getString(R.string.pcap_saved, count, File(saved).name)
            pkg.isNotBlank() -> pkg
            else -> host.getString(R.string.pcap_idle)
        }
        views.pcapToggle.setText(if (active) R.string.pcap_stop else R.string.pcap_start)
    }

    private fun uploadSummary(host: MainActivity) {
        val started = captureStartedMs
        if (started <= 0L) return
        captureStartedMs = 0L
        val row = JSONObject()
            .put("kind", "pcap")
            .put("event_id", UUID.randomUUID().toString())
            .put("ts_ms", System.currentTimeMillis())
            .put("app", PcapVpnService.targetPackage.ifBlank { pcapPackage }.take(80))
            .put("packets", PcapVpnService.packets)
            .put("bytes", PcapVpnService.bytes)
            .put("duration_ms", (System.currentTimeMillis() - started).coerceAtLeast(0L))
        MeasureUploader.enqueue(host, row)
    }
}
