package com.aksa.mysignal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.aksa.mysignal.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Radio chart, speedtest, and video tabs. Sampling stays in [RadioService]. */
class MainActivity : AppCompatActivity() {
    private enum class Tab { RADIO, SPEED, VIDEO, COMPLAINT }

    private lateinit var binding: ActivityMainBinding
    private var windowMin = 10
    private var plmnFilter: String? = null
    private var ratFilter: String? = null
    private var plmnChoices: List<String> = emptyList()
    private var speedJob: Job? = null
    private var gatewayPingJob: Job? = null
    private var videoJob: Job? = null
    private var complaintJob: Job? = null
    private var nextSpeedFamily: IpFamily = IpFamily.IPV4
    private var running = false
    private var videoRunning = false
    private var currentTab = Tab.RADIO
    private val gatewayRttSamples = ArrayDeque<GatewayRttChartView.Point>(GATEWAY_RTT_CAPACITY)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        updatePermissionUi()
        if (hasCorePermissions()) RadioService.enable(this)
    }

    private val onSnapshot: (RadioSnapshot) -> Unit = {
        runOnUiThread {
            showChart()
            showWifiDetail(it.wifi)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        listOf(binding.win5 to 5, binding.win10 to 10, binding.win30 to 30).forEach { (button, minutes) ->
            button.setOnClickListener {
                windowMin = minutes
                showChart()
            }
        }
        listOf(binding.ratAll to null, binding.ratLte to "LTE", binding.ratNr to "NR").forEach { (button, rat) ->
            button.setOnClickListener {
                ratFilter = rat
                restyleRatFilters()
                showChart()
            }
        }
        restyleRatFilters()
        binding.grant.setOnClickListener { requestPermissions() }
        binding.tabRadio.setOnClickListener { showTab(Tab.RADIO) }
        binding.tabSpeed.setOnClickListener { showTab(Tab.SPEED) }
        binding.tabVideo.setOnClickListener { showTab(Tab.VIDEO) }
        binding.tabComplaint.setOnClickListener { showTab(Tab.COMPLAINT) }
        binding.complaintShare.setOnClickListener {
            val preview = binding.complaintPreview.text?.toString().orEmpty()
            if (binding.complaintShare.isEnabled && preview.isNotBlank()) ComplaintDraft.share(this, preview)
        }
        binding.speedCenter.setOnClickListener { toggleSpeedtest() }
        binding.videoGo.setOnClickListener { toggleVideo() }
        binding.speedServer.text = BuildConfig.COLLECTOR_HOST
        binding.speedLocal.text = "—"
        binding.speedPublic.text = "—"
        binding.speedGw4.text = "—"
        binding.speedGw6.text = "—"
        binding.videoServer.text = getString(
            R.string.speed_server,
            "${BuildConfig.COLLECTOR_HOST}/video",
        )
        nextSpeedFamily = IpFamily.IPV4
        wireRadioPager()
        wireComplaintPager()
        updatePermissionUi()
        requestPermissions()
        showTab(Tab.RADIO)
    }

    override fun onStart() {
        super.onStart()
        RadioState.addListener(onSnapshot)
        if (hasCorePermissions()) RadioService.start(this)
        showChart()
        showWifiDetail(RadioState.latest?.wifi)
    }

    override fun onStop() {
        stopGatewayPing()
        RadioState.removeListener(onSnapshot)
        super.onStop()
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        binding.radioPane.visibility = if (tab == Tab.RADIO) View.VISIBLE else View.GONE
        binding.speedPane.visibility = if (tab == Tab.SPEED) View.VISIBLE else View.GONE
        binding.videoPane.visibility = if (tab == Tab.VIDEO) View.VISIBLE else View.GONE
        binding.complaintPane.visibility = if (tab == Tab.COMPLAINT) View.VISIBLE else View.GONE
        binding.tabRadio.setTextColor(tabColor(tab == Tab.RADIO))
        binding.tabSpeed.setTextColor(tabColor(tab == Tab.SPEED))
        binding.tabVideo.setTextColor(tabColor(tab == Tab.VIDEO))
        binding.tabComplaint.setTextColor(tabColor(tab == Tab.COMPLAINT))
        if (tab != Tab.VIDEO && videoRunning) stopVideo()
        if (tab == Tab.RADIO) {
            binding.radioPane.setCurrentItem(1, false)
        }
        if (tab == Tab.SPEED) {
            refreshSpeedClientIps()
            startGatewayPing()
        } else {
            stopGatewayPing()
        }
        if (tab == Tab.COMPLAINT) {
            binding.complaintPane.setCurrentItem(1, false)
            showComplaint()
        }
    }

    private fun startGatewayPing() {
        if (gatewayPingJob?.isActive == true) return
        gatewayPingJob = lifecycleScope.launch {
            while (currentTab == Tab.SPEED) {
                val gw = withContext(Dispatchers.IO) {
                    GatewayMonitor.sample(this@MainActivity)
                }
                if (currentTab != Tab.SPEED) break
                val sample = GatewayRttChartView.Point(
                    tMs = System.currentTimeMillis(),
                    rtt4Ms = gw.ipv4RttMs,
                    rtt6Ms = gw.ipv6RttMs,
                    attempted4 = gw.ipv4 != null,
                    attempted6 = gw.ipv6 != null,
                )
                if (gatewayRttSamples.size >= GATEWAY_RTT_CAPACITY) gatewayRttSamples.removeFirst()
                gatewayRttSamples.addLast(sample)
                val snap = gatewayRttSamples.toList()
                val (lost4, att4) = gatewayLossCounts(snap, v4 = true)
                val (lost6, att6) = gatewayLossCounts(snap, v4 = false)
                binding.speedGw4.text = formatGwDetail(
                    gw.ipv4 ?: "—",
                    GatewayPinger.formatRtt(gw.ipv4RttMs),
                    GatewayPinger.formatLoss(lost4, att4),
                )
                binding.speedGw6.text = formatGwDetail(
                    gw.ipv6 ?: "—",
                    GatewayPinger.formatRtt(gw.ipv6RttMs),
                    GatewayPinger.formatLoss(lost6, att6),
                )
                // Packet-loss chart disabled (layout commented out).
                // binding.gatewayLossChart.setSamples(snap)
                binding.gatewayRttChart.setSamples(snap)
                delay(GATEWAY_PING_INTERVAL_MS)
            }
        }
    }

    private fun stopGatewayPing() {
        gatewayPingJob?.cancel()
        gatewayPingJob = null
    }

    private fun refreshSpeedClientIps(family: IpFamily = nextSpeedFamily) {
        val locals = ClientNetworkInfo.localAddressesPair(this)
        val gateway = ClientNetworkInfo.gatewayIpQuick(this)
        val gateway6 = ClientNetworkInfo.gatewayIpv6Quick(this)
        binding.speedLocal.text = formatLocalIps(locals)
        binding.speedPublic.text = "…"
        binding.speedGw4.text = formatGwDetail(if (gateway == "—") "…" else gateway, "…", "…")
        binding.speedGw6.text = formatGwDetail(if (gateway6 == "—") "…" else gateway6, "…", "…")
        binding.speedServer.text = getString(
            R.string.speed_server_stack,
            BuildConfig.COLLECTOR_HOST,
            family.label,
            "…",
        )
        lifecycleScope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching { SpeedtestTarget.resolve(family = family) }.getOrNull()
            }
            if (currentTab != Tab.SPEED) return@launch
            if (target == null) {
                binding.speedServer.text = getString(
                    R.string.speed_server_stack,
                    BuildConfig.COLLECTOR_HOST,
                    family.label,
                    "—",
                )
                binding.speedLocal.text = formatLocalIps(locals)
                binding.speedPublic.text = "—"
                return@launch
            }
            showSpeedTarget(target)
            val public = withContext(Dispatchers.IO) {
                ClientNetworkInfo.publicClientFromHello(target)
            }
            if (currentTab == Tab.SPEED) {
                binding.speedLocal.text = formatLocalIps(locals)
                binding.speedPublic.text = formatPublic(public.ip, public.provider)
            }
        }
    }

    private fun formatLocalIps(locals: ClientNetworkInfo.LocalAddresses): CharSequence {
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)
        val v4Color = ContextCompat.getColor(this, R.color.gateway_v4)
        val v6Color = ContextCompat.getColor(this, R.color.gateway_v6)
        val sb = SpannableStringBuilder()
        fun appendColored(text: String, color: Int) {
            val start = sb.length
            sb.append(text)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val v4 = locals.ipv4
        val v6 = locals.ipv6
        when {
            v4 != null && v6 != null -> {
                appendColored(v4, v4Color)
                appendColored("\n", secondary)
                appendColored(v6, v6Color)
            }
            v4 != null -> appendColored(v4, v4Color)
            v6 != null -> appendColored(v6, v6Color)
            else -> appendColored("—", secondary)
        }
        return sb
    }

    private fun formatPublic(ip: String, provider: String): String {
        val cleanIp = ip.ifBlank { "—" }
        val cleanProvider = provider.trim().takeIf { it.isNotEmpty() && it != "—" }
        return if (cleanProvider != null) {
            getString(R.string.speed_public, cleanIp, cleanProvider)
        } else {
            cleanIp
        }
    }

    private fun formatGwDetail(ip: String, rtt: String, loss: String): String =
        getString(R.string.speed_gw_detail, ip, rtt, loss)

    private fun gatewayLossCounts(
        samples: List<GatewayRttChartView.Point>,
        v4: Boolean,
    ): Pair<Int, Int> {
        var attempted = 0
        var lost = 0
        for (p in samples) {
            val att = if (v4) p.attempted4 else p.attempted6
            val ok = if (v4) p.rtt4Ms != null else p.rtt6Ms != null
            if (!att) continue
            attempted++
            if (!ok) lost++
        }
        return lost to attempted
    }

    private fun showSpeedTarget(target: SpeedtestTarget) {
        binding.speedServer.text = getString(
            R.string.speed_server_stack,
            target.hostname,
            target.family.label,
            target.serverIp,
        )
    }

    private fun wireRadioPager() {
        val wifi = binding.radioWifi
        val chart = binding.radioChart
        wifi.visibility = View.VISIBLE
        chart.visibility = View.VISIBLE
        (wifi.parent as ViewGroup).removeView(wifi)
        (chart.parent as ViewGroup).removeView(chart)
        binding.radioPane.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = 2

            override fun getItemViewType(position: Int): Int = position

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val page = if (viewType == 0) wifi else chart
                (page.parent as? ViewGroup)?.removeView(page)
                page.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                return object : RecyclerView.ViewHolder(page) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        binding.radioPane.setCurrentItem(1, false)
        showWifiDetail(RadioState.latest?.wifi)
    }

    private fun showWifiDetail(wifi: WifiSignal?) {
        val list = binding.radioWifiList
        list.removeAllViews()
        list.addView(wifiText(getString(R.string.radio_wifi_title), 18f, true))
        list.addView(wifiText(getString(R.string.radio_wifi_back), 11f, false, secondary = true))
        if (wifi == null) {
            list.addView(wifiText(getString(R.string.radio_wifi_empty), 14f, false))
            return
        }
        list.addView(wifiText(getString(R.string.radio_wifi_associated), 13f, true, secondary = true))
        list.addView(
            wifiText(
                buildString {
                    appendLine("status  ${wifi.status}")
                    appendLine("ssid    ${wifi.ssid.ifBlank { "—" }}")
                    appendLine("bssid   ${wifi.bssid.ifBlank { "—" }}")
                    appendLine("rssi    ${wifi.rssiDbm?.let { "$it dBm" } ?: "—"}")
                    appendLine("freq    ${wifi.frequencyMhz?.let { "$it MHz" } ?: "—"}")
                    appendLine("channel ${wifi.channel ?: "—"}")
                    appendLine("link    ${wifi.linkSpeedMbps?.let { "$it Mbps" } ?: "—"}")
                    append("std     ${wifi.standard.ifBlank { "—" }}")
                },
                14f,
                false,
            ),
        )
        val congestion = wifi.congestion
        list.addView(wifiText(getString(R.string.radio_wifi_congestion), 13f, true, secondary = true))
        if (congestion == null) {
            list.addView(wifiText("score   —", 14f, false))
        } else {
            list.addView(
                wifiText(
                    buildString {
                        appendLine("score      ${congestion.score}")
                        appendLine("same ch    ${congestion.sameChannel}")
                        appendLine("adjacent   ${congestion.adjacentChannel}")
                        appendLine("band APs   ${congestion.neighbors}")
                        appendLine("strongest  ${congestion.strongestNeighborRssiDbm?.let { "$it dBm" } ?: "—"}")
                        append("scan age   ${congestion.scanAgeMs?.let { "${it} ms" } ?: "—"}")
                    },
                    14f,
                    false,
                ),
            )
            list.addView(wifiText(getString(R.string.radio_wifi_neighbors), 13f, true, secondary = true))
            if (congestion.neighborsTop.isEmpty()) {
                list.addView(wifiText("No nearby APs in the last scan.", 14f, false))
            } else {
                congestion.neighborsTop.forEach { neighbor ->
                    val label = neighbor.ssid.ifBlank { neighbor.bssid.ifBlank { "AP" } }
                    val detail = listOfNotNull(
                        neighbor.rssiDbm?.let { "$it dBm" },
                        neighbor.channel?.let { "ch $it" },
                        neighbor.frequencyMhz?.let { "$it MHz" },
                        neighbor.channelWidthMhz?.let { "${it} MHz wide" },
                    ).joinToString(" · ")
                    list.addView(wifiText("$label\n$detail", 14f, false))
                }
            }
        }
    }

    private fun wifiText(value: String, size: Float, bold: Boolean, secondary: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (secondary) R.color.text_secondary else R.color.text_primary,
                ),
            )
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
        }

    private fun wireComplaintPager() {
        val history = binding.complaintHistory
        val current = binding.complaintCurrent
        history.visibility = View.VISIBLE
        current.visibility = View.VISIBLE
        (history.parent as ViewGroup).removeView(history)
        (current.parent as ViewGroup).removeView(current)
        binding.complaintPane.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = 2

            override fun getItemViewType(position: Int): Int = position

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val page = if (viewType == 0) history else current
                (page.parent as? ViewGroup)?.removeView(page)
                page.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                return object : RecyclerView.ViewHolder(page) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        binding.complaintPane.setCurrentItem(1, false)
        renderHistory()
    }

    private fun renderHistory() {
        val list = binding.complaintHistoryList
        list.removeAllViews()
        list.addView(historyText(getString(R.string.complaint_history), 18f, true))
        val items = ComplaintStore.history(this)
        if (items.isEmpty()) {
            list.addView(historyText(getString(R.string.complaint_history_empty), 14f, false))
            return
        }
        items.forEach { entry ->
            val whenText = historyClock.format(Date(entry.tsMs))
            val headline = entry.verdict.lineSequence().firstOrNull().orEmpty()
            list.addView(historyText("$whenText\n$headline\n${entry.draft}", 14f, false))
        }
    }

    private fun historyText(value: String, size: Float, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun showComplaint() {
        complaintJob?.cancel()
        binding.complaintVerdict.text = "Drafting post…"
        binding.complaintPreview.text = ""
        binding.complaintShare.isEnabled = false
        complaintJob = lifecycleScope.launch(Dispatchers.IO) {
            val review = ComplaintDraft.review(this@MainActivity)
            if (review.reviewed) ComplaintStore.remember(this@MainActivity, review)
            withContext(Dispatchers.Main) {
                if (currentTab != Tab.COMPLAINT) return@withContext
                binding.complaintVerdict.text = review.verdict
                binding.complaintPreview.text = review.draft
                binding.complaintShare.isEnabled = review.eligible
                renderHistory()
            }
        }
    }

    private val historyClock = SimpleDateFormat("dd/MM HH:mm", Locale("id", "ID")).apply {
        timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    }

    private fun tabColor(active: Boolean): Int =
        ContextCompat.getColor(this, if (active) R.color.accent else R.color.text_secondary)

    private fun toggleVideo() {
        if (videoRunning) {
            stopVideo()
            return
        }
        videoRunning = true
        clearVideoMetrics()
        binding.videoGo.text = getString(R.string.speed_stop)
        binding.videoGo.alpha = 0.55f
        binding.videoHudProgress.visibility = View.VISIBLE
        binding.videoStatus.text = getString(R.string.speed_running)
        val engine = VideoTestEngine(this, binding.videoPlayer)
        videoJob = lifecycleScope.launch {
            var round = 0
            try {
                while (videoRunning) {
                    round += 1
                    binding.videoGo.text = getString(R.string.speed_stop)
                    binding.videoGo.alpha = 0.55f
                    binding.videoHudProgress.visibility = View.VISIBLE
                    binding.videoStatus.text = getString(R.string.speed_loop, round)
                    clearVideoMetrics()
                    try {
                        val result = engine.run(VideoTestEngine.defaultBaseUrl()) { phase ->
                            if (!videoRunning) return@run
                            runOnUiThread {
                                if (videoRunning) {
                                    binding.videoStatus.text = "Loop $round · $phase"
                                    showVideoResolution(phase)
                                }
                            }
                        }
                        if (!videoRunning) break
                        renderVideoResult(result)
                        SpeedtestReport.uploadVideo(applicationContext, result)
                        binding.videoGo.alpha = 1f
                        for (left in 10 downTo 1) {
                            if (!videoRunning) break
                            binding.videoStatus.text = getString(R.string.speed_loop_pause, round, left)
                            delay(1_000)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (!videoRunning) break
                        binding.videoStatus.text = getString(
                            R.string.speed_failed,
                            error.message?.take(80) ?: error.javaClass.simpleName,
                        )
                        delay(2_000)
                    }
                }
            } catch (_: CancellationException) {
            } finally {
                videoRunning = false
                binding.videoGo.text = getString(R.string.speed_go)
                binding.videoGo.alpha = 1f
                binding.videoHudProgress.visibility = View.INVISIBLE
                val status = binding.videoStatus.text?.toString().orEmpty()
                if (!status.startsWith("Failed")) {
                    binding.videoStatus.text = ""
                }
            }
        }
    }

    private fun stopVideo() {
        videoJob?.cancel()
        videoRunning = false
        binding.videoGo.text = getString(R.string.speed_go)
        binding.videoGo.alpha = 1f
        binding.videoHudProgress.visibility = View.INVISIBLE
    }

    private fun clearVideoMetrics() {
        binding.videoLoad.text = "—"
        binding.videoResolution.text = "—"
        binding.videoResolutionUnit.visibility = View.GONE
        binding.videoBuffering.text = "—"
    }

    private fun renderVideoResult(result: VideoTestEngine.Result) {
        binding.videoLoad.text = "${result.loadTimeMs} ms"
        showVideoResolution(result.maxResolution)
        binding.videoBuffering.text = "${"%.1f".format(result.bufferingPct)}%"
    }

    private fun showVideoResolution(raw: String) {
        val cleaned = raw.trim()
        val match = Regex("""^(\d+)\s*p?$""", RegexOption.IGNORE_CASE).matchEntire(cleaned)
        if (match != null) {
            binding.videoResolution.text = match.groupValues[1]
            binding.videoResolutionUnit.visibility = View.VISIBLE
        } else {
            binding.videoResolution.text = cleaned.ifEmpty { "—" }
            binding.videoResolutionUnit.visibility = View.GONE
        }
    }

    private fun toggleSpeedtest() {
        if (running) {
            speedJob?.cancel()
            running = false
            binding.speedCenter.text = getString(R.string.speed_go)
            binding.speedStatus.text = ""
            binding.gauge.setReading(0.0, "")
            return
        }
        running = true
        binding.pingValue.text = "—"
        binding.downloadValue.text = "—"
        binding.uploadValue.text = "—"
        binding.jitterValue.text = getString(R.string.speed_jitter_idle)
        binding.speedCenter.text = getString(R.string.speed_stop)
        binding.speedStatus.text = getString(R.string.speed_running)
        speedJob = lifecycleScope.launch {
            var round = 0
            var family = nextSpeedFamily
            try {
                while (running) {
                    round += 1
                    binding.speedStatus.text = getString(R.string.speed_loop, round)
                    binding.speedCenter.text = getString(R.string.speed_stop)
                    binding.pingValue.text = "—"
                    binding.downloadValue.text = "—"
                    binding.uploadValue.text = "—"
                    binding.jitterValue.text = getString(R.string.speed_jitter_idle)
                    try {
                        val target = withContext(Dispatchers.IO) {
                            SpeedtestTarget.resolve(family = family)
                        }
                        nextSpeedFamily = family
                        showSpeedTarget(target)
                        refreshSpeedClientIps(family)
                        val result = SpeedtestEngine(target).run { progress ->
                            runOnUiThread { renderProgress(progress) }
                        }
                        SpeedtestReport.upload(applicationContext, result)
                        if (!running) break
                        // Hold final DL/UL on screen so the user can stop before the next round.
                        binding.pingValue.text = SpeedtestMath.formatMs(result.pingMs)
                        binding.jitterValue.text = "jitter ${result.jitterMs} ms"
                        binding.downloadValue.text = SpeedtestMath.formatMbps(result.downloadMbps)
                        binding.uploadValue.text = SpeedtestMath.formatMbps(result.uploadMbps)
                        binding.speedCenter.text = getString(R.string.speed_stop)
                        binding.gauge.setReading(result.downloadMbps, "DONE")
                        for (left in 10 downTo 1) {
                            if (!running) break
                            binding.speedStatus.text = getString(R.string.speed_loop_pause, round, left)
                            delay(1_000)
                        }
                        if (!running) break
                        family = family.next()
                        nextSpeedFamily = family
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (!running) break
                        android.util.Log.w(
                            "MySignalSpeed",
                            "${family.label} round failed: ${error.message}",
                            error,
                        )
                        binding.speedStatus.text = getString(
                            R.string.speed_family_unavailable,
                            family.label,
                            error.message?.take(48) ?: error.javaClass.simpleName,
                        )
                        family = family.next()
                        nextSpeedFamily = family
                        delay(2_000)
                    }
                }
            } catch (_: CancellationException) {
            } finally {
                running = false
                binding.speedCenter.text = getString(R.string.speed_go)
                val status = binding.speedStatus.text?.toString().orEmpty()
                if (!status.startsWith("Retrying") && !status.contains("unavailable")) {
                    binding.speedStatus.text = ""
                }
            }
        }
    }

    private fun renderProgress(progress: SpeedtestEngine.Progress) {
        if (!running) return
        if (progress.serverLabel.isNotEmpty()) {
            // Progress.serverLabel already formatted as "host · IPvX · ip"
            binding.speedServer.text = progress.serverLabel
        }
        progress.pingMs?.let { binding.pingValue.text = SpeedtestMath.formatMs(it) }
        progress.jitterMs?.let { binding.jitterValue.text = "jitter ${it} ms" }
        progress.downloadMbps?.let { binding.downloadValue.text = SpeedtestMath.formatMbps(it) }
        progress.uploadMbps?.let { binding.uploadValue.text = SpeedtestMath.formatMbps(it) }
        when (progress.phase) {
            SpeedtestEngine.Phase.PING -> {
                binding.speedCenter.text = getString(R.string.speed_stop)
                binding.gauge.setReading(0.0, "PING")
            }
            SpeedtestEngine.Phase.DOWNLOAD -> {
                binding.speedCenter.text = SpeedtestMath.formatMbps(progress.liveMbps)
                binding.gauge.setReading(progress.liveMbps, "DOWNLOAD")
            }
            SpeedtestEngine.Phase.UPLOAD -> {
                binding.speedCenter.text = SpeedtestMath.formatMbps(progress.liveMbps)
                binding.gauge.setReading(progress.liveMbps, "UPLOAD")
            }
            SpeedtestEngine.Phase.DONE -> {
                binding.speedCenter.text = getString(R.string.speed_stop)
                binding.gauge.setReading(progress.downloadMbps ?: 0.0, "DONE")
            }
            SpeedtestEngine.Phase.FAILED, SpeedtestEngine.Phase.IDLE -> Unit
        }
    }

    private fun showChart() {
        val points = RadioState.chartPoints()
        renderPlmnFilters(points.flatMap { it.cells.map { cell -> cell.plmn } }.filter { it.isNotEmpty() }.distinct().sorted())
        binding.chart.setData(points, windowMin, plmnFilter, ratFilter)
    }

    private fun renderPlmnFilters(plmns: List<String>) {
        val selected = plmnFilter?.takeIf { it in plmns }
        if (selected != plmnFilter) plmnFilter = selected
        if (plmns == plmnChoices && binding.plmnFilters.childCount == plmns.size + 1) {
            restylePlmnFilters()
            return
        }
        plmnChoices = plmns
        binding.plmnFilters.removeAllViews()
        binding.plmnFilters.addView(plmnButton("All", null))
        plmns.forEach { binding.plmnFilters.addView(plmnButton(RadioChartLabels.slotLabel(it), it)) }
    }

    private fun plmnButton(text: String, value: String?): MaterialButton {
        val height = (36 * resources.displayMetrics.density).toInt()
        val gap = (4 * resources.displayMetrics.density).toInt()
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            tag = value
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, height).apply {
                marginEnd = gap
            }
            setOnClickListener {
                plmnFilter = value
                restylePlmnFilters()
                binding.chart.setData(RadioState.chartPoints(), windowMin, plmnFilter, ratFilter)
            }
            applyPlmnStyle(this, value)
        }
    }

    private fun restylePlmnFilters() {
        for (index in 0 until binding.plmnFilters.childCount) {
            val button = binding.plmnFilters.getChildAt(index) as? MaterialButton ?: continue
            applyPlmnStyle(button, button.tag as? String)
        }
    }

    private fun restyleRatFilters() {
        listOf(binding.ratAll to null, binding.ratLte to "LTE", binding.ratNr to "NR").forEach { (button, rat) ->
            val selected = ratFilter == rat
            button.setTextColor(ContextCompat.getColor(this, if (selected) R.color.accent else R.color.text_secondary))
        }
    }

    private fun applyPlmnStyle(button: MaterialButton, value: String?) {
        val selected = value == plmnFilter
        button.setTextColor(ContextCompat.getColor(this, if (selected) R.color.accent else R.color.text_secondary))
    }

    private fun hasCorePermissions(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) && granted(Manifest.permission.READ_PHONE_STATE)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun updatePermissionUi() {
        binding.grant.visibility = if (hasCorePermissions()) View.GONE else View.VISIBLE
    }

    private fun requestPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRECISE_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        val needed = wanted.filterNot { granted(it) }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    companion object {
        private const val GATEWAY_PING_INTERVAL_MS = 1_000L
        private const val GATEWAY_RTT_CAPACITY = 60
    }
}
