package com.aksa.magnon

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.aksa.magnon.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Speed, video, and post. */
class MainActivity : AppCompatActivity() {
    private enum class Tab { SPEED, VIDEO, COMPLAINT }

    private lateinit var binding: ActivityMainBinding
    private var speedJob: Job? = null
    private var videoJob: Job? = null
    private var complaintJob: Job? = null
    private val selectedEventKeys = linkedSetOf<String>()
    private val pickableEventLimit = 10
    private var draftCache: DraftCache? = null
    private var quotaDeviceId: String = ""
    private var running = false
    private var videoRunning = false
    private var currentTab = Tab.SPEED
    private var postChromeArmed = false
    private var rebindingPost = false
    private var shownProvider = ""
    private var shownTransport = ""
    private var integrityStopped = false
    private var pathCallback: ConnectivityManager.NetworkCallback? = null
    private var pathJob: Job? = null
    private val pathGeneration = PathGeneration()
    private var pathDirty = false
    private var watchedNetwork: Network? = null
    private var watchedKind = ""
    private var postOnline = false
    private var placeJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { updatePermissionUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.INTEGRITY_GATE && DeviceIntegrity.blocked(this)) {
            integrityStopped = true
            setContentView(R.layout.activity_integrity)
            window.decorView.postDelayed({
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 1_500)
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.speedPublicLabel.setText(R.string.speed_label_provider)
        binding.tabSpeed.setOnClickListener { showTab(Tab.SPEED) }
        binding.tabVideo.setOnClickListener { showTab(Tab.VIDEO) }
        binding.tabComplaint.setOnClickListener { showTab(Tab.COMPLAINT) }
        binding.complaintDraft.setOnClickListener { draftSelected() }
        binding.complaintCopy.setOnClickListener {
            val ident = quotaDeviceId
            if (ident.isBlank()) return@setOnClickListener
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("device", ident))
        }
        binding.complaintShare.setOnClickListener {
            val preview = binding.complaintPreview.text?.toString().orEmpty()
            if (!binding.complaintShare.isEnabled || preview.isBlank()) return@setOnClickListener
            shareComplaint(preview)
        }
        binding.speedCenter.setOnClickListener { toggleSpeedtest() }
        binding.videoGo.setOnClickListener { toggleVideo() }
        binding.speedServer.text = BuildConfig.COLLECTOR_HOST
        binding.speedPublic.text = "—"
        binding.speedTransport.text = "—"
        binding.videoServer.text = BuildConfig.COLLECTOR_HOST
        wireSpeedPager()
        wireVideoPager()
        wireComplaintPager()
        wirePostChoices()
        updatePermissionUi()
        requestPermissions()
        showTab(Tab.SPEED)
    }

    override fun onStart() {
        super.onStart()
        if (integrityStopped) return
        RumReporter.onFirstFrame()
        watchDefaultNetwork()
    }

    override fun onStop() {
        unwatchDefaultNetwork()
        super.onStop()
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        binding.speedPane.visibility = if (tab == Tab.SPEED) View.VISIBLE else View.GONE
        binding.videoPane.visibility = if (tab == Tab.VIDEO) View.VISIBLE else View.GONE
        binding.complaintPane.visibility = if (tab == Tab.COMPLAINT) View.VISIBLE else View.GONE
        binding.tabSpeed.setTextColor(tabColor(tab == Tab.SPEED))
        binding.tabVideo.setTextColor(tabColor(tab == Tab.VIDEO))
        binding.tabComplaint.setTextColor(tabColor(tab == Tab.COMPLAINT))
        if (tab != Tab.VIDEO && videoRunning) stopVideo()
        if (tab == Tab.SPEED) {
            binding.speedPane.setCurrentItem(1, false)
            refreshSpeedClientIps()
            if (!running) refreshTransport()
            renderSpeedHistory()
        }
        if (tab == Tab.VIDEO) {
            binding.videoPane.setCurrentItem(1, false)
            if (!videoRunning) refreshTransport()
            renderVideoHistory()
        }
        if (tab == Tab.COMPLAINT) {
            binding.complaintPane.setCurrentItem(1, false)
            showPost()
        }
    }




    /** Local address and public address. Lite shows the server name and the provider only. */
    /** Server name and provider. Lite does not resolve a gateway. */
    private suspend fun refreshPublic(target: SpeedtestTarget) {
        val generation = pathGeneration.next()
        pathJob?.cancel()
        binding.speedServer.text = BuildConfig.COLLECTOR_HOST
        val network = watchedNetwork ?: activeNetwork()
        val job = lifecycleScope.launch {
            val public = withContext(Dispatchers.IO) {
                ClientNetworkInfo.publicClientFromHello(target, network, publish = false)
            }
            if (!pathGeneration.accept(generation)) return@launch
            ClientNetworkInfo.remember(public)
            showProvider(public.provider)
        }
        pathJob = job
        job.join()
    }

    /** Gateway ping and traceroute, concurrent with the speed or video round. Premium only. */



    private fun refreshSpeedClientIps() {
        binding.speedServer.text = BuildConfig.COLLECTOR_HOST
        val network = watchedNetwork ?: activeNetwork()
        if (network == null) {
            showProvider("")
            return
        }
        requestProvider(network)
    }





    private fun showSpeedTarget(target: SpeedtestTarget) {
        binding.speedServer.text = target.root().let { BuildConfig.COLLECTOR_HOST }
    }

    private fun showProvider(name: String) {
        shownProvider = name.trim().takeIf { it.isNotEmpty() && it != "—" }?.uppercase(Locale.US).orEmpty()
        binding.speedPublic.text = shownProvider.ifBlank { "—" }
        renderVideoHeader()
    }

    private fun watchDefaultNetwork() {
        if (pathCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { applyPath(network) }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    if (watchedNetwork != null && watchedNetwork != network) return@runOnUiThread
                    applyPath(null)
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val kind = kindOf(caps)
                runOnUiThread {
                    showLink(network)
                    if (watchedNetwork != null && network != watchedNetwork) return@runOnUiThread
                    if (network == watchedNetwork && kind == watchedKind) return@runOnUiThread
                    applyPath(network)
                }
            }
        }
        pathCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    private fun unwatchDefaultNetwork() {
        val callback = pathCallback ?: return
        pathCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
        pathJob?.cancel()
    }

    private fun applyPath(network: Network?) {
        showLink(network)
        val kind = network?.let { kindOf(it) }.orEmpty()
        watchedNetwork = network
        watchedKind = kind
        if (running || videoRunning) {
            pathDirty = true
            return
        }
        pathDirty = false
        if (network == null || kind.isEmpty()) {
            pathGeneration.next()
            pathJob?.cancel()
            showTransport("")
            showProvider("")
            return
        }
        showTransport(kind)
        requestProvider(network)
    }

    private fun settlePath() {
        if (running || videoRunning || !pathDirty) return
        applyPath(watchedNetwork)
    }

    private fun requestProvider(network: Network) {
        val generation = pathGeneration.next()
        binding.speedPublic.text = "…"
        pathJob?.cancel()
        pathJob = lifecycleScope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching { SpeedtestTarget.resolve(family = IpFamily.IPV4) }.getOrNull()
            }
            if (!pathGeneration.accept(generation)) return@launch
            if (target == null) {
                showProvider("")
                return@launch
            }
            val public = withContext(Dispatchers.IO) {
                ClientNetworkInfo.publicClientFromHello(target, network, publish = false)
            }
            if (!pathGeneration.accept(generation)) return@launch
            ClientNetworkInfo.remember(public)
            showProvider(public.provider)
        }
    }

    private fun showLink(network: Network? = watchedNetwork ?: activeNetwork()) {
        val caps = network?.let { getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(it) }
        postOnline = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!::binding.isInitialized) return
        binding.complaintLink.text = getString(if (postOnline) R.string.complaint_online else R.string.complaint_offline)
        binding.complaintLink.setTextColor(
            ContextCompat.getColor(this, if (postOnline) R.color.accent else R.color.text_secondary),
        )
        refreshDraftActions()
    }

    private fun activeNetwork(): Network? =
        getSystemService(ConnectivityManager::class.java)?.activeNetwork

    private fun kindOf(network: Network): String {
        val caps = getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(network) ?: return ""
        return kindOf(caps)
    }

    private fun kindOf(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
        else -> ""
    }

    private fun refreshTransport() {
        binding.speedTransport.text = "…"
        lifecycleScope.launch {
            val kind = withContext(Dispatchers.IO) { NetworkPath.kind(applicationContext) }
            if (running || videoRunning) return@launch
            if (currentTab != Tab.SPEED && currentTab != Tab.VIDEO) return@launch
            showTransport(kind)
        }
    }

    private fun showTransport(kind: String) {
        shownTransport = NetworkPath.label(kind)
        binding.speedTransport.text = shownTransport.ifBlank { "—" }
        renderVideoHeader()
    }

    private fun renderVideoHeader() {
        binding.videoServer.text = listOf(BuildConfig.COLLECTOR_HOST, shownProvider, shownTransport)
            .filter { it.isNotBlank() }
            .joinToString("\n")
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
                append(NetworkPath.label(entry.transport).ifBlank { "—" })
                if (entry.provider.isNotBlank()) {
                    append(" · ").append(entry.provider.uppercase(Locale.US))
                }
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

    private fun wireVideoPager() {
        val history = binding.videoHistory
        val current = binding.videoCurrent
        history.visibility = View.VISIBLE
        current.visibility = View.VISIBLE
        (history.parent as ViewGroup).removeView(history)
        (current.parent as ViewGroup).removeView(current)
        binding.videoPane.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
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
        binding.videoPane.setCurrentItem(1, false)
        renderVideoHistory()
    }

    private fun renderVideoHistory() {
        val list = binding.videoHistoryList
        list.removeAllViews()
        list.addView(historyText(getString(R.string.video_history_title), 18f, true))
        list.addView(historyText(getString(R.string.video_history_back), 11f, false))
        val items = ComplaintStore.videoHistory(this)
        if (items.isEmpty()) {
            list.addView(historyText(getString(R.string.video_history_empty), 14f, false))
            return
        }
        items.forEach { entry ->
            val whenText = historyClock.format(Date(entry.tsMs))
            list.addView(
                historyText(
                    getString(
                        R.string.video_history_row,
                        whenText,
                        entry.resolution.ifBlank { "—" },
                        "${entry.loadMs} ms",
                        String.format(Locale.US, "%.1f%%", entry.bufferingPct),
                        NetworkPath.label(entry.transport).ifBlank { "—" },
                    ),
                    14f,
                    false,
                ),
            )
        }
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

    private fun wirePostChoices() {
        val languages = listOf("en", "id")
        val styles = listOf("biasa", "santai", "serius", "gaul")
        val autos = listOf("yes", "no")
        binding.complaintLanguage.onItemSelectedListener = spinnerListener { position ->
            val code = languages.getOrElse(position) { "en" }
            if (code != ComplaintStore.postLanguage(this)) {
                ComplaintStore.rememberLanguage(this, code)
                bindPostChoices()
                refreshDraftActions()
            }
        }
        binding.complaintStyle.onItemSelectedListener = spinnerListener { position ->
            val code = styles.getOrElse(position) { "biasa" }
            if (code != ComplaintStore.postStyle(this)) {
                ComplaintStore.rememberStyle(this, code)
                refreshDraftActions()
            }
        }
        binding.complaintAutoTag.onItemSelectedListener = spinnerListener { position ->
            val enabled = autos.getOrElse(position) { "yes" } == "yes"
            if (enabled != ComplaintStore.autoTag(this)) {
                ComplaintStore.rememberAutoTag(this, enabled)
                refreshDraftActions()
            }
        }
        bindPostChoices()
        postChromeArmed = true
    }

    private fun spinnerListener(onPick: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (!postChromeArmed || rebindingPost) return
            onPick(position)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun bindPostChoices() {
        val english = ComplaintStore.postLanguage(this) != "id"
        rebindingPost = true
        try {
            binding.complaintAutoLabel.setText(
                if (english) R.string.post_auto_label_en else R.string.post_auto_label_id,
            )
            bindSpinner(
                binding.complaintLanguage,
                listOf(getString(R.string.post_language_en), getString(R.string.post_language_id)),
                if (english) 0 else 1,
            )
            bindSpinner(
                binding.complaintStyle,
                if (english) {
                    listOf(
                        getString(R.string.post_style_plain),
                        getString(R.string.post_style_casual),
                        getString(R.string.post_style_serious),
                        getString(R.string.post_style_slang),
                    )
                } else {
                    listOf(
                        getString(R.string.post_style_biasa),
                        getString(R.string.post_style_santai),
                        getString(R.string.post_style_serius),
                        getString(R.string.post_style_gaul),
                    )
                },
                listOf("biasa", "santai", "serius", "gaul").indexOf(ComplaintStore.postStyle(this)).coerceAtLeast(0),
            )
            bindSpinner(
                binding.complaintAutoTag,
                if (english) {
                    listOf(getString(R.string.post_auto_yes), getString(R.string.post_auto_no))
                } else {
                    listOf(getString(R.string.post_auto_yes_id), getString(R.string.post_auto_no_id))
                },
                if (ComplaintStore.autoTag(this)) 0 else 1,
            )
        } finally {
            rebindingPost = false
        }
    }

    private fun bindSpinner(spinner: android.widget.Spinner, labels: List<String>, selection: Int) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(selection)
    }

    private fun showPost() {
        showLink()
        renderEventPicker()
        refreshDraftActions()
        loadQuota()
        fillPlaces()
    }

    private fun fillPlaces() {
        placeJob?.cancel()
        val events = ComplaintStore.postEvents(this).take(pickableEventLimit)
        placeJob = lifecycleScope.launch {
            var changed = false
            for (event in events) {
                if (!PlaceFill.pending(event.kelurahan, event.kecamatan, event.kabupaten, event.provinsi, event.latitude, event.longitude)) {
                    continue
                }
                val latitude = event.latitude ?: continue
                val longitude = event.longitude ?: continue
                val named = withContext(Dispatchers.IO) { PlaceCapture.names(applicationContext, latitude, longitude) }
                if (!PlaceFill.named(named.kelurahan, named.kecamatan, named.kabupaten, named.provinsi)) continue
                ComplaintStore.rememberPlace(
                    this@MainActivity,
                    event.key,
                    named.kelurahan,
                    named.kecamatan,
                    named.kabupaten,
                    named.provinsi,
                )
                changed = true
            }
            val newest = events.firstOrNull()
            val newestNamed = newest != null && PlaceFill.named(newest.kelurahan, newest.kecamatan, newest.kabupaten, newest.provinsi)
            if (newest != null && !newestNamed && newest.latitude == null) {
                val found = withContext(Dispatchers.IO) { PlaceCapture.district(applicationContext) }
                val latitude = found.latitude
                val longitude = found.longitude
                if (latitude != null && longitude != null) {
                    ComplaintStore.rememberPlace(
                        this@MainActivity,
                        newest.key,
                        found.kelurahan,
                        found.kecamatan,
                        found.kabupaten,
                        found.provinsi,
                        latitude,
                        longitude,
                    )
                    changed = true
                }
            }
            if (changed && currentTab == Tab.COMPLAINT) {
                renderEventPicker()
                refreshDraftActions()
            }
        }
    }

    private fun showQuota(freeUsed: Int, balance: Int) {
        binding.complaintQuota.text = getString(R.string.complaint_quota, freeUsed, balance)
    }

    private fun loadQuota() {
        val ident = CollectorClient.quotaDeviceId(this)
        quotaDeviceId = ident
        binding.complaintDevice.text = ident.ifBlank { "—" }
        binding.complaintCopy.isEnabled = ident.isNotBlank()
        if (ident.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val snapshot = runCatching { ComplaintDraft.quota(this@MainActivity) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (currentTab != Tab.COMPLAINT) return@withContext
                showQuota(snapshot.freeUsed, snapshot.balance)
            }
        }
    }

    private fun renderEventPicker() {
        val list = binding.complaintEvents
        list.removeAllViews()
        val events = ComplaintStore.postEvents(this).take(pickableEventLimit)
        val known = events.map { it.key }.toSet()
        selectedEventKeys.retainAll(known)
        val anchor = events.firstOrNull { it.key in selectedEventKeys }
        if (anchor != null) {
            selectedEventKeys.retainAll(events.filter { samePath(it, anchor) }.map { it.key }.toSet())
        }
        if (events.isEmpty()) {
            list.addView(historyText(getString(R.string.complaint_events_empty), 14f, false))
            return
        }
        events.forEach { event ->
            list.addView(eventCheck(event))
        }
        refreshEventAvailability()
    }

    private fun eventCheck(event: ComplaintStore.PostEvent): CheckBox {
        val whenText = historyClock.format(Date(event.tsMs))
        val kind = if (event.kind == "video") "Video" else "Speed"
        val provider = event.provider.trim().ifBlank { "—" }
        val transport = NetworkPath.label(event.transport).ifBlank { "—" }
        val place = PlaceFill.label(event.kelurahan, event.kecamatan, event.kabupaten, event.provinsi)
        val metrics = if (event.kind == "video") {
            val load = event.loadMs?.let { SpeedtestMath.formatMs(it) } ?: "—"
            val buffer = event.bufferingPct?.let { "%.1f".format(it) } ?: "—"
            "${event.resolution.ifBlank { "—" }} · load $load ms · buf $buffer%"
        } else {
            val down = event.downloadMbps?.let { SpeedtestMath.formatMbps(it) } ?: "—"
            val up = event.uploadMbps?.let { SpeedtestMath.formatMbps(it) } ?: "—"
            val ping = event.pingMs?.let { SpeedtestMath.formatMs(it) } ?: "—"
            "↓ $down  ↑ $up  ping $ping ms"
        }
        val label = buildString {
            append("$whenText · $kind\n$provider · $transport\n$place\n$metrics")
            if (event.eventId.isNotBlank()) append("\n${event.eventId}")
        }
        return CheckBox(this).apply {
            tag = event
            text = label
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            isChecked = selectedEventKeys.contains(event.key)
            setOnCheckedChangeListener { _, checked ->
                val current = selectedAnchor()
                if (checked && current != null && !samePath(event, current)) {
                    isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (checked) selectedEventKeys.add(event.key) else selectedEventKeys.remove(event.key)
                refreshEventAvailability()
                refreshDraftActions()
            }
        }
    }

    private fun selectedAnchor(): ComplaintStore.PostEvent? {
        val list = binding.complaintEvents
        for (index in 0 until list.childCount) {
            val box = list.getChildAt(index) as? CheckBox ?: continue
            val event = box.tag as? ComplaintStore.PostEvent ?: continue
            if (event.key in selectedEventKeys) return event
        }
        return null
    }

    private fun refreshEventAvailability() {
        val anchor = selectedAnchor()
        val list = binding.complaintEvents
        for (index in 0 until list.childCount) {
            val box = list.getChildAt(index) as? CheckBox ?: continue
            val event = box.tag as? ComplaintStore.PostEvent ?: continue
            box.isEnabled = anchor == null || event.key in selectedEventKeys || samePath(event, anchor)
            box.alpha = if (box.isEnabled) 1f else 0.4f
        }
    }

    private fun samePath(event: ComplaintStore.PostEvent, anchor: ComplaintStore.PostEvent): Boolean {
        val transport = NetworkPath.canonical(event.transport) == NetworkPath.canonical(anchor.transport)
        val provider = event.provider.trim().equals(anchor.provider.trim(), ignoreCase = true)
        return transport && provider
    }

    private fun selectionKey(): String {
        val ids = selectedEventKeys.sorted().joinToString(",")
        return listOf(
            ids,
            ComplaintStore.postLanguage(this),
            ComplaintStore.postStyle(this),
            ComplaintStore.autoTag(this).toString(),
        ).joinToString("|")
    }

    private fun refreshDraftActions() {
        binding.complaintDraft.isEnabled =
            postOnline && selectedEventKeys.isNotEmpty() && complaintJob?.isActive != true
        val cache = draftCache
        val matches = cache != null && cache.key == selectionKey()
        binding.complaintShare.isEnabled = matches && cache.eligible
    }

    private fun draftSelected() {
        if (!postOnline) return
        val key = selectionKey()
        val cached = draftCache
        if (cached != null && cached.key == key) {
            binding.complaintVerdict.text = cached.verdict
            binding.complaintPreview.text = cached.sentence
            refreshDraftActions()
            return
        }
        val chosen = ComplaintStore.postEvents(this).take(pickableEventLimit).filter { it.key in selectedEventKeys }
        complaintJob?.cancel()
        binding.complaintDraft.isEnabled = false
        binding.complaintShare.isEnabled = false
        binding.complaintVerdict.text = "Drafting post…"
        complaintJob = lifecycleScope.launch(Dispatchers.IO) {
            val review = ComplaintDraft.review(this@MainActivity, chosen)
            withContext(Dispatchers.Main) {
                if (review.reviewed) {
                    draftCache = DraftCache(key, review.verdict, review.draft, review.eligible)
                }
                val freeUsed = review.freeUsed
                val balance = review.balance
                if (freeUsed != null && balance != null) showQuota(freeUsed, balance)
                if (selectionKey() == key) {
                    binding.complaintVerdict.text = review.verdict
                    if (review.reviewed) binding.complaintPreview.text = review.draft
                }
                if (review.reviewed) ComplaintStore.remember(this@MainActivity, review)
                renderHistory()
                refreshDraftActions()
            }
        }
    }

    private data class DraftCache(
        val key: String,
        val verdict: String,
        val sentence: String,
        val eligible: Boolean,
    )

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
                    val place = async(Dispatchers.IO) { PlaceCapture.district(applicationContext) }
                    val path = async(Dispatchers.IO) { NetworkPath.kind(applicationContext) }
                    val network = watchedNetwork ?: activeNetwork()
                    val generation = pathGeneration.next()
                    pathJob?.cancel()
                    val providerName = lifecycleScope.async {
                        val info = withContext(Dispatchers.IO) {
                            val target = runCatching { SpeedtestTarget.resolve(family = IpFamily.IPV4) }.getOrNull()
                                ?: return@withContext null
                            ClientNetworkInfo.publicClientFromHello(target, network, publish = false)
                        } ?: return@async ""
                        if (pathGeneration.accept(generation)) {
                            ClientNetworkInfo.remember(info)
                            showProvider(info.provider)
                        }
                        info.provider
                    }
                    pathJob = providerName
                    try {
                        val transport = path.await()
                        showTransport(transport)
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
                        val named = providerName.await()
                        val found = place.await()
                        SpeedtestReport.uploadVideo(
                            applicationContext,
                            result,
                            found.kecamatan,
                            transport,
                            found.kelurahan,
                            found.kabupaten,
                            found.provinsi,
                            found.latitude,
                            found.longitude,
                            named,
                        )
                        binding.videoGo.alpha = 1f
                        for (left in 10 downTo 1) {
                            if (!videoRunning) break
                            binding.videoStatus.text = getString(R.string.speed_loop_pause, round, left)
                            delay(1_000)
                        }
                    } catch (cancelled: CancellationException) {
                        place.cancel()
                        path.cancel()
                        throw cancelled
                    } catch (error: Exception) {
                        place.cancel()
                        path.cancel()
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
                    } finally {
                        place.cancel()
                        path.cancel()
                    }
                }
            } catch (_: CancellationException) {
            } finally {
                videoRunning = false
                settlePath()
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
            var family = IpFamily.IPV4
            var holdOffline = false
            val triedSinceSuccess = mutableSetOf<IpFamily>()
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
                    val place = async(Dispatchers.IO) { PlaceCapture.district(applicationContext) }
                    val path = async(Dispatchers.IO) { NetworkPath.kind(applicationContext) }
                    try {
                        val transport = path.await()
                        showTransport(transport)
                        val target = withContext(Dispatchers.IO) {
                            SpeedtestTarget.resolve(family = family)
                        }
                        showSpeedTarget(target)
                        refreshPublic(target)
                        if (!running) break
                        binding.speedStatus.text = getString(R.string.speed_loop, round)
                        val result = SpeedtestEngine(target).run { progress ->
                            runOnUiThread { renderProgress(progress) }
                        }
                        triedSinceSuccess.clear()
                        val found = place.await()
                        SpeedtestReport.upload(
                            applicationContext,
                            result,
                            found.kecamatan,
                            transport,
                            found.kelurahan,
                            found.kabupaten,
                            found.provinsi,
                            found.latitude,
                            found.longitude,
                        )
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
                    } catch (cancelled: CancellationException) {
                        place.cancel()
                        path.cancel()
                        throw cancelled
                    } catch (error: Exception) {
                        place.cancel()
                        path.cancel()
                        if (!running) break
                        AppLog.w(
                            "MySignalSpeed",
                            "${family.label} round failed: ${error.message}",
                            error,
                        )
                        val kind = classifyReachFailure(error)
                        val reach = kind == ReachFailure.UNREACHABLE || kind == ReachFailure.NO_ROUTE
                        triedSinceSuccess += family
                        if (reach) {
                            holdOffline = true
                            binding.speedStatus.text = if (kind == ReachFailure.UNREACHABLE) {
                                getString(R.string.speed_server_unreachable, BuildConfig.COLLECTOR_HOST)
                            } else {
                                getString(R.string.speed_no_route, BuildConfig.COLLECTOR_HOST)
                            }
                            binding.gauge.setReading(0.0, "", "")
                            break
                        }
                        binding.speedStatus.text = getString(
                            R.string.speed_family_unavailable,
                            family.label,
                            error.message?.take(48) ?: error.javaClass.simpleName,
                        )
                        delay(2_000)
                    } finally {
                        place.cancel()
                        path.cancel()
                    }
                }
            } catch (_: CancellationException) {
            } finally {
                running = false
                settlePath()
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








    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun updatePermissionUi() = Unit

    private fun requestPermissions() {
        val wanted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val needed = wanted.filterNot { granted(it) }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }


    private fun shareComplaint(preview: String) {
        ComplaintDraft.share(this, preview)
    }
}
