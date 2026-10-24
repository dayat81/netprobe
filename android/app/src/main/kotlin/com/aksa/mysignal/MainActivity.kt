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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
    private var tracerouteJob: Job? = null
    private var videoJob: Job? = null
    private var complaintJob: Job? = null
    private var nextSpeedFamily: IpFamily = IpFamily.IPV4
    private var running = false
    private var videoRunning = false
    private var currentTab = Tab.SPEED
    private var serverIpv4: String? = null
    private var serverIpv6: String? = null
    private val onPathChanged: () -> Unit = {
        if (currentTab == Tab.SPEED && !running) startServerTraceroute(force = true)
    }

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
        binding.speedGateway.text = "—"
        binding.videoServer.text = getString(
            R.string.speed_server,
            "${BuildConfig.COLLECTOR_HOST}/video",
        )
        nextSpeedFamily = IpFamily.IPV4
        wireRadioPager()
        wireSpeedPager()
        wireComplaintPager()
        updatePermissionUi()
        requestPermissions()
        showTab(Tab.SPEED)
    }

    override fun onStart() {
        super.onStart()
        RumReporter.onFirstFrame()
        RadioState.addListener(onSnapshot)
        GatewayMonitor.addPathListener(onPathChanged)
        if (hasCorePermissions()) RadioService.start(this)
        showChart()
        showWifiDetail(RadioState.latest?.wifi)
    }

    override fun onStop() {
        stopServerTraceroute()
        GatewayMonitor.removePathListener(onPathChanged)
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
            binding.speedPane.setCurrentItem(1, false)
            refreshSpeedClientIps()
            startServerTraceroute()
            renderSpeedHistory()
        } else {
            stopServerTraceroute()
        }
        if (tab == Tab.COMPLAINT) {
            binding.complaintPane.setCurrentItem(1, false)
            showComplaint()
        }
    }

    private fun startServerTraceroute(force: Boolean = false) {
        if (!force && tracerouteJob?.isActive == true) return
        tracerouteJob?.cancel()
        GatewayMonitor.start(applicationContext)
        binding.tracerouteHops.setLoading()
        tracerouteJob = lifecycleScope.launch {
            val bundle = traceServer()
            if (currentTab != Tab.SPEED || running) return@launch
            applyTrace(bundle)
        }
    }

    private suspend fun traceServer(): TraceBundle = coroutineScope {
        val host = BuildConfig.COLLECTOR_HOST
        val (target4, target6) = withContext(Dispatchers.IO) {
            val v4 = runCatching {
                SpeedtestTarget.resolve(hostname = host, family = IpFamily.IPV4).serverIp
            }.getOrNull()
            val v6 = runCatching {
                SpeedtestTarget.resolve(hostname = host, family = IpFamily.IPV6).serverIp
            }.getOrNull()
            v4 to v6
        }
        val v4 = async(Dispatchers.IO) {
            runInterruptible {
                NativeTraceroute.run(
                    this@MainActivity,
                    target4 ?: host,
                    ipv6 = false,
                    maxHops = 30,
                    timeoutSec = 25,
                )
            }
        }
        val v6 = async(Dispatchers.IO) {
            runInterruptible {
                if (target6 == null) {
                    emptyList()
                } else {
                    NativeTraceroute.run(
                        this@MainActivity,
                        target6,
                        ipv6 = true,
                        maxHops = 30,
                        timeoutSec = 25,
                    )
                }
            }
        }
        TraceBundle(v4.await(), v6.await(), target4, target6)
    }

    /** Local IP, public IP and provider, route gateway, then a fresh traceroute. */
    private suspend fun refreshRound(target: SpeedtestTarget): TraceBundle {
        val locals = withContext(Dispatchers.IO) {
            ClientNetworkInfo.localAddressesPair(this@MainActivity)
        }
        binding.speedLocal.text = formatLocalIps(locals)
        binding.speedPublic.text = "…"
        binding.speedGateway.text = "…"
        binding.tracerouteHops.setLoading()
        val public = withContext(Dispatchers.IO) {
            ClientNetworkInfo.publicClientFromHello(target)
        }
        if (!running) return TraceBundle(emptyList(), emptyList(), null, null)
        binding.speedPublic.text = formatPublic(public.ip, public.provider)
        val route = withContext(Dispatchers.IO) {
            ClientNetworkInfo.freshGateways(this@MainActivity)
        }
        if (!running) return TraceBundle(emptyList(), emptyList(), null, null)
        binding.speedGateway.text = formatGateway(
            route.first.takeIf { it != "—" },
            route.second.takeIf { it != "—" },
        )
        val bundle = traceServer()
        if (!running) return bundle
        applyTrace(bundle, keepRouteGateway = true)
        return bundle
    }

    private fun applyTrace(bundle: TraceBundle, keepRouteGateway: Boolean = false) {
        if (bundle.ip4 != null) serverIpv4 = bundle.ip4
        if (bundle.ip6 != null) serverIpv6 = bundle.ip6
        binding.speedServer.text = formatServerIps()
        val gw4 = bundle.hops4.firstOrNull { !it.host.isNullOrBlank() }?.host
        val gw6 = bundle.hops6.firstOrNull { !it.host.isNullOrBlank() }?.host
        val route4 = ClientNetworkInfo.lastGateway
        val route6 = ClientNetworkInfo.lastGatewayV6
        ClientNetworkInfo.noteFirstHops(gw4, gw6)
        val shown4 = gw4 ?: if (keepRouteGateway) route4 else null
        val shown6 = gw6 ?: if (keepRouteGateway) route6 else null
        binding.speedGateway.text = formatGateway(shown4, shown6)
        binding.tracerouteHops.setHops(
            TracerouteHopView.fromRaw(bundle.hops4),
            TracerouteHopView.fromRaw(bundle.hops6),
            statusText = null,
        )
    }

    private fun stopServerTraceroute() {
        tracerouteJob?.cancel()
        tracerouteJob = null
    }

    private fun refreshSpeedClientIps(family: IpFamily = nextSpeedFamily) {
        val locals = ClientNetworkInfo.localAddressesPair(this)
        binding.speedLocal.text = formatLocalIps(locals)
        binding.speedPublic.text = "…"
        binding.speedServer.text = formatServerIps(loading = true)
        lifecycleScope.launch {
            val (v4, v6) = withContext(Dispatchers.IO) {
                val a = runCatching {
                    SpeedtestTarget.resolve(family = IpFamily.IPV4).serverIp
                }.getOrNull()
                val b = runCatching {
                    SpeedtestTarget.resolve(family = IpFamily.IPV6).serverIp
                }.getOrNull()
                a to b
            }
            if (currentTab != Tab.SPEED) return@launch
            serverIpv4 = v4
            serverIpv6 = v6
            binding.speedServer.text = formatServerIps()
            val target = withContext(Dispatchers.IO) {
                runCatching { SpeedtestTarget.resolve(family = family) }.getOrNull()
            }
            if (currentTab != Tab.SPEED) return@launch
            if (target == null) {
                binding.speedLocal.text = formatLocalIps(locals)
                binding.speedPublic.text = "—"
                return@launch
            }
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

    private fun formatGateway(ipv4: String?, ipv6: String?): CharSequence {
        val locals = ClientNetworkInfo.LocalAddresses(ipv4, ipv6)
        return formatLocalIps(locals)
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

    private fun formatServerIps(loading: Boolean = false): CharSequence {
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)
        val v4Color = ContextCompat.getColor(this, R.color.gateway_v4)
        val v6Color = ContextCompat.getColor(this, R.color.gateway_v6)
        val sb = SpannableStringBuilder()
        fun appendColored(text: String, color: Int) {
            val start = sb.length
            sb.append(text)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        appendColored(BuildConfig.COLLECTOR_HOST, secondary)
        if (loading && serverIpv4 == null && serverIpv6 == null) {
            appendColored("\n…", secondary)
            return sb
        }
        val v4 = serverIpv4
        val v6 = serverIpv6
        when {
            v4 != null && v6 != null -> {
                appendColored("\n", secondary)
                appendColored(v4, v4Color)
                appendColored("\n", secondary)
                appendColored(v6, v6Color)
            }
            v4 != null -> {
                appendColored("\n", secondary)
                appendColored(v4, v4Color)
            }
            v6 != null -> {
                appendColored("\n", secondary)
                appendColored(v6, v6Color)
            }
            else -> {
                appendColored("\n—", secondary)
            }
        }
        return sb
    }

    private fun showSpeedTarget(target: SpeedtestTarget) {
        when (target.family) {
            IpFamily.IPV4 -> serverIpv4 = target.serverIp
            IpFamily.IPV6 -> serverIpv6 = target.serverIp
        }
        binding.speedServer.text = formatServerIps()
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

    private fun wireSpeedPager() {
        val history = binding.speedHistory
        val current = binding.speedCurrent
        history.visibility = View.VISIBLE
        current.visibility = View.VISIBLE
        (history.parent as ViewGroup).removeView(history)
        (current.parent as ViewGroup).removeView(current)
        binding.speedPane.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
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
        binding.speedPane.setCurrentItem(1, false)
        renderSpeedHistory()
    }

    private fun renderSpeedHistory() {
        val list = binding.speedHistoryList
        list.removeAllViews()
        list.addView(historyText(getString(R.string.speed_history_title), 18f, true))
        list.addView(historyText(getString(R.string.speed_history_back), 11f, false))
        val items = ComplaintStore.speedHistory(this)
        if (items.isEmpty()) {
            list.addView(historyText(getString(R.string.speed_history_empty), 14f, false))
            return
        }
        items.forEach { entry ->
            val whenText = historyClock.format(Date(entry.tsMs))
            val stack = entry.ipStack.ifBlank { "—" }
            val server = entry.server.ifBlank { "—" }
            val meta = buildString {
                append(entry.transport.ifBlank { "—" })
                if (entry.provider.isNotBlank()) append(" · ").append(entry.provider)
                if (entry.publicIp.isNotBlank()) append(" · ").append(entry.publicIp)
            }
            list.addView(
                historyText(
                    getString(
                        R.string.speed_history_row,
                        whenText,
                        stack,
                        SpeedtestMath.formatMbps(entry.downloadMbps),
                        SpeedtestMath.formatMbps(entry.uploadMbps),
                        "${entry.pingMs} ms",
                        "${entry.jitterMs} ms",
                        "$server\n$meta",
                    ),
                    14f,
                    false,
                ),
            )
        }
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
                        val kind = classifyReachFailure(error)
                        if (kind == ReachFailure.UNREACHABLE || kind == ReachFailure.NO_ROUTE) {
                            binding.videoStatus.text = if (kind == ReachFailure.NO_ROUTE) {
                                getString(R.string.speed_no_route, BuildConfig.COLLECTOR_HOST)
                            } else {
                                getString(R.string.speed_server_unreachable, BuildConfig.COLLECTOR_HOST)
                            }
                            break
                        }
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
                if (
                    !status.startsWith("Failed") &&
                    !status.contains("unreachable") &&
                    !status.contains("No route")
                ) {
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
            binding.gauge.setReading(0.0, "", "")
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
            var holdOffline = false
            val triedSinceSuccess = mutableSetOf<IpFamily>()
            var sawUnreachable = false
            try {
                while (running) {
                    if (triedSinceSuccess.isEmpty()) round += 1
                    binding.speedStatus.text = getString(R.string.speed_loop, round)
                    binding.speedCenter.text = getString(R.string.speed_stop)
                    binding.pingValue.text = "—"
                    binding.downloadValue.text = "—"
                    binding.uploadValue.text = "—"
                    binding.jitterValue.text = getString(R.string.speed_jitter_idle)
                    binding.gauge.setReading(0.0, "PING", family.label)
                    try {
                        val target = withContext(Dispatchers.IO) {
                            SpeedtestTarget.resolve(family = family)
                        }
                        nextSpeedFamily = family
                        showSpeedTarget(target)
                        binding.speedStatus.text = getString(R.string.speed_loop_route, round)
                        tracerouteJob?.cancel()
                        val trace = refreshRound(target)
                        if (!running) break
                        binding.speedStatus.text = getString(R.string.speed_loop, round)
                        val result = SpeedtestEngine(target).run { progress ->
                            runOnUiThread { renderProgress(progress) }
                        }
                        triedSinceSuccess.clear()
                        sawUnreachable = false
                        SpeedtestReport.upload(applicationContext, result, trace.hops4, trace.hops6)
                        renderSpeedHistory()
                        if (!running) break
                        // Hold final DL/UL on screen so the user can stop before the next round.
                        binding.pingValue.text = SpeedtestMath.formatMs(result.pingMs)
                        binding.jitterValue.text = "jitter ${result.jitterMs} ms"
                        binding.downloadValue.text = SpeedtestMath.formatMbps(result.downloadMbps)
                        binding.uploadValue.text = SpeedtestMath.formatMbps(result.uploadMbps)
                        binding.speedCenter.text = getString(R.string.speed_stop)
                        binding.gauge.setReading(result.downloadMbps, "DONE", result.ipFamily.label)
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
                        AppLog.w(
                            "MySignalSpeed",
                            "${family.label} round failed: ${error.message}",
                            error,
                        )
                        val kind = classifyReachFailure(error)
                        val reach = kind == ReachFailure.UNREACHABLE || kind == ReachFailure.NO_ROUTE
                        if (kind == ReachFailure.UNREACHABLE) sawUnreachable = true
                        triedSinceSuccess += family
                        val other = family.next()
                        if (reach && other !in triedSinceSuccess) {
                            binding.speedStatus.text = getString(
                                R.string.speed_family_unavailable,
                                family.label,
                                error.message?.take(48) ?: error.javaClass.simpleName,
                            )
                            family = other
                            nextSpeedFamily = family
                            delay(800)
                        } else if (reach) {
                            holdOffline = true
                            binding.speedStatus.text = if (sawUnreachable) {
                                getString(R.string.speed_server_unreachable, BuildConfig.COLLECTOR_HOST)
                            } else {
                                getString(R.string.speed_no_route, BuildConfig.COLLECTOR_HOST)
                            }
                            binding.gauge.setReading(0.0, "", "")
                            break
                        } else {
                            binding.speedStatus.text = getString(
                                R.string.speed_family_unavailable,
                                family.label,
                                error.message?.take(48) ?: error.javaClass.simpleName,
                            )
                            family = other
                            nextSpeedFamily = family
                            delay(2_000)
                        }
                    }
                }
            } catch (_: CancellationException) {
            } finally {
                running = false
                binding.speedCenter.text = getString(R.string.speed_go)
                binding.gauge.setReading(0.0, "", "")
                val status = binding.speedStatus.text?.toString().orEmpty()
                if (
                    !holdOffline &&
                    !status.startsWith("Retrying") &&
                    !status.contains("unavailable")
                ) {
                    binding.speedStatus.text = ""
                }
            }
        }
    }

    private fun renderProgress(progress: SpeedtestEngine.Progress) {
        if (!running) return
        val familyLabel = progress.ipFamily?.label.orEmpty()
        progress.pingMs?.let { binding.pingValue.text = SpeedtestMath.formatMs(it) }
        progress.jitterMs?.let { binding.jitterValue.text = "jitter ${it} ms" }
        progress.downloadMbps?.let { binding.downloadValue.text = SpeedtestMath.formatMbps(it) }
        progress.uploadMbps?.let { binding.uploadValue.text = SpeedtestMath.formatMbps(it) }
        when (progress.phase) {
            SpeedtestEngine.Phase.PING -> {
                binding.speedCenter.text = getString(R.string.speed_stop)
                binding.gauge.setReading(0.0, "PING", familyLabel)
            }
            SpeedtestEngine.Phase.DOWNLOAD -> {
                binding.speedCenter.text = SpeedtestMath.formatMbps(progress.liveMbps)
                binding.gauge.setReading(progress.liveMbps, "DOWNLOAD", familyLabel)
            }
            SpeedtestEngine.Phase.UPLOAD -> {
                binding.speedCenter.text = SpeedtestMath.formatMbps(progress.liveMbps)
                binding.gauge.setReading(progress.liveMbps, "UPLOAD", familyLabel)
            }
            SpeedtestEngine.Phase.DONE -> {
                binding.speedCenter.text = getString(R.string.speed_stop)
                binding.gauge.setReading(progress.downloadMbps ?: 0.0, "DONE", familyLabel)
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

    private data class TraceBundle(
        val hops4: List<NativeTraceroute.Hop>,
        val hops6: List<NativeTraceroute.Hop>,
        val ip4: String?,
        val ip6: String?,
    )
}
