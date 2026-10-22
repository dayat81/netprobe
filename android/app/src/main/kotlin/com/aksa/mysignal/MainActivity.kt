package com.aksa.mysignal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private var videoJob: Job? = null
    private var complaintJob: Job? = null
    private var running = false
    private var videoRunning = false
    private var currentTab = Tab.RADIO

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        updatePermissionUi()
        if (hasCorePermissions()) RadioService.enable(this)
    }

    private val onSnapshot: (RadioSnapshot) -> Unit = {
        runOnUiThread {
            showChart()
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
        binding.speedServer.text = getString(
            R.string.speed_server,
            BuildConfig.COLLECTOR_HOST,
        )
        binding.videoServer.text = getString(
            R.string.speed_server,
            "${BuildConfig.COLLECTOR_HOST}/video",
        )
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
    }

    override fun onStop() {
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
        if (tab == Tab.COMPLAINT) {
            binding.complaintPane.setCurrentItem(1, false)
            showComplaint()
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

    private fun showComplaint() {
        complaintJob?.cancel()
        binding.complaintVerdict.text = "Menilai kelayakan…"
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
        binding.videoLoad.text = "—"
        binding.videoResolution.text = "—"
        binding.videoBuffering.text = "—"
        binding.videoGo.text = "…"
        binding.videoStatus.text = getString(R.string.speed_running)
        val engine = VideoTestEngine(this, binding.videoPlayer)
        videoJob = lifecycleScope.launch {
            try {
                val result = engine.run(VideoTestEngine.defaultBaseUrl()) { phase ->
                    runOnUiThread { binding.videoStatus.text = phase }
                }
                binding.videoLoad.text = "${result.loadTimeMs} ms"
                binding.videoResolution.text = result.maxResolution
                binding.videoBuffering.text = "${"%.1f".format(result.bufferingPct)}%"
                binding.videoStatus.text = getString(R.string.speed_done)
                SpeedtestReport.uploadVideo(applicationContext, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                binding.videoStatus.text = getString(
                    R.string.speed_failed,
                    error.message?.take(80) ?: error.javaClass.simpleName,
                )
            } finally {
                videoRunning = false
                binding.videoGo.text = getString(R.string.speed_go)
            }
        }
    }

    private fun stopVideo() {
        videoJob?.cancel()
        videoRunning = false
        binding.videoGo.text = getString(R.string.speed_go)
        binding.videoStatus.text = ""
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
        binding.speedCenter.text = "…"
        binding.speedStatus.text = getString(R.string.speed_running)
        speedJob = lifecycleScope.launch {
            try {
                val result = SpeedtestEngine(SpeedtestEngine.defaultBaseUrl()).run { progress ->
                    runOnUiThread { renderProgress(progress) }
                }
                SpeedtestReport.upload(applicationContext, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                running = false
                binding.speedCenter.text = getString(R.string.speed_go)
                binding.gauge.setReading(0.0, "")
                binding.speedStatus.text = getString(
                    R.string.speed_failed,
                    error.message?.take(80) ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private fun renderProgress(progress: SpeedtestEngine.Progress) {
        progress.pingMs?.let { binding.pingValue.text = SpeedtestMath.formatMs(it) }
        progress.jitterMs?.let { binding.jitterValue.text = "jitter ${it} ms" }
        progress.downloadMbps?.let { binding.downloadValue.text = SpeedtestMath.formatMbps(it) }
        progress.uploadMbps?.let { binding.uploadValue.text = SpeedtestMath.formatMbps(it) }
        when (progress.phase) {
            SpeedtestEngine.Phase.PING -> {
                binding.speedCenter.text = "…"
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
                running = false
                binding.speedCenter.text = getString(R.string.speed_go)
                binding.gauge.setReading(progress.downloadMbps ?: 0.0, "DONE")
                binding.speedStatus.text = getString(R.string.speed_done)
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
        binding.plmnFilters.addView(plmnButton("Semua", null))
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
}
