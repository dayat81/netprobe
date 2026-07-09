package com.telcoagent.udpclient

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Filter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.telcoagent.udpclient.databinding.FragmentAnalysisBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AnalysisFragment : Fragment() {
    private var _binding: FragmentAnalysisBinding? = null
    private val binding get() = _binding!!

    private val logLines = mutableListOf<String>()
    private var connectedSince: Long? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private val trafficHandler = Handler(Looper.getMainLooper())
    private var lastTrafficRx: Long = 0
    private var lastTrafficTx: Long = 0
    private var lastTrafficTime: Long = 0
    private var trafficBaselineRx: Long = -1  // -1 = not set yet
    private var trafficBaselineTx: Long = -1
    private var lastPollRx: Long = 0        // for rate calculation
    private var lastPollTx: Long = 0
    private var lastPollTime: Long = 0

    private val trafficPollRunnable = object : Runnable {
        override fun run() {
            if (AnalysisVpnService.isConnected()) {
                fetchTrafficData()
                trafficHandler.postDelayed(this, 3000) // Poll every 3s for smoother rate
            }
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            connectedSince?.let { since ->
                val uptime = formatUptime(System.currentTimeMillis() - since)
                val currentState = AnalysisVpnService.state.value
                val bytesInfo = formatBytes(currentState.bytesRx, currentState.bytesTx)
                binding.statusDetail.text = buildString {
                    append("${currentState.serverAddress}:${currentState.serverPort}")
                    append(" · $uptime")
                    if (bytesInfo != null) append(" · $bytesInfo")
                }
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    private var allApps = listOf<SplitTunnelApp>()
    private val selectedPackages = mutableSetOf<String>()
    private lateinit var dropdownAdapter: AppDropdownAdapter

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            appendLog("VPN permission granted")
            startVpn()
        } else {
            appendLog("VPN permission denied")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAnalysisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        displayClientPublicKey()
        setupButtons()
        setupSplitTunnel()
        setupMtuDisplay()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AnalysisVpnService.state.collect { state ->
                    applyState(state)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh MTU display in case it was updated from the MTU tab
        val ctx = requireContext()
        binding.mtuDisplay.text = "MTU ${AnalysisPreferences.getMtu(ctx)}"
    }

    private fun setupSplitTunnel() {
        val ctx = requireContext()

        // Load saved state
        val enabled = AnalysisPreferences.getSplitTunnelEnabled(ctx)
        binding.splitTunnelToggle.isChecked = enabled
        binding.appDropdownLayout.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.selectedAppsChipGroup.visibility = if (enabled && selectedPackages.isNotEmpty()) View.VISIBLE else View.GONE

        // Restore saved selections
        val savedApps = AnalysisPreferences.getSplitTunnelApps(ctx)
        selectedPackages.clear()
        selectedPackages.addAll(savedApps)

        // Toggle listener
        binding.splitTunnelToggle.setOnCheckedChangeListener { _, isChecked ->
            AnalysisPreferences.setSplitTunnelEnabled(ctx, isChecked)
            binding.appDropdownLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.selectedAppsChipGroup.visibility = if (isChecked && selectedPackages.isNotEmpty()) View.VISIBLE else View.GONE
            if (isChecked && allApps.isEmpty()) {
                loadInstalledApps()
            }
            updateSplitTunnelCount()
        }

        // Load apps if enabled
        if (enabled) {
            loadInstalledApps()
        }
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = requireContext().packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolveInfos = pm.queryIntentActivities(intent, 0)

                resolveInfos
                    .filter { it.activityInfo.packageName != requireContext().packageName }
                    .map { info ->
                        SplitTunnelApp(
                            packageName = info.activityInfo.packageName,
                            appName = info.loadLabel(pm).toString(),
                            icon = try { info.loadIcon(pm) } catch (_: Exception) { null },
                            selected = false,
                        )
                    }
                    .sortedBy { it.appName.lowercase() }
                    .distinctBy { it.packageName }
            }

            allApps = apps

            // Setup dropdown
            dropdownAdapter = AppDropdownAdapter(requireContext(), apps)
            binding.appDropdown.setAdapter(dropdownAdapter)
            binding.appDropdown.setOnClickListener {
                binding.appDropdown.showDropDown()
            }
            binding.appDropdown.setOnItemClickListener { _, _, position, _ ->
                val app = dropdownAdapter.getItem(position) ?: return@setOnItemClickListener
                if (app.packageName !in selectedPackages) {
                    selectedPackages.add(app.packageName)
                    addChip(app)
                    updateSplitTunnelCount()
                    appendLog("Added: ${app.appName}")
                    (activity?.application as? NetProbeApplication)?.recordSplitTunnel("app_added", selectedPackages.size)
                }
                binding.appDropdown.setText("", false)
            }

            // Restore chips for saved selections
            restoreChips()
        }
    }

    private fun addChip(app: SplitTunnelApp) {
        val chip = Chip(requireContext()).apply {
            text = app.appName
            chipIcon = app.icon
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                selectedPackages.remove(app.packageName)
                binding.selectedAppsChipGroup.removeView(this)
                updateSplitTunnelCount()
                if (selectedPackages.isEmpty()) {
                    binding.selectedAppsChipGroup.visibility = View.GONE
                }
            }
        }
        binding.selectedAppsChipGroup.addView(chip)
        binding.selectedAppsChipGroup.visibility = View.VISIBLE
    }

    private fun restoreChips() {
        binding.selectedAppsChipGroup.removeAllViews()
        for (pkg in selectedPackages) {
            val app = allApps.find { it.packageName == pkg } ?: continue
            addChip(app)
        }
        binding.selectedAppsChipGroup.visibility = if (selectedPackages.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateSplitTunnelCount() {
        val ctx = requireContext()

        // Save selections
        AnalysisPreferences.setSplitTunnelApps(ctx, selectedPackages)

        // Update counter text
        binding.splitTunnelCount.text = if (selectedPackages.isEmpty()) {
            getString(R.string.split_tunnel_none)
        } else {
            getString(R.string.split_tunnel_selected, selectedPackages.size)
        }
    }

    private fun setupMtuDisplay() {
        val ctx = requireContext()
        binding.mtuDisplay.text = "MTU ${AnalysisPreferences.getMtu(ctx)}"
        binding.mtuDisplay.setOnClickListener {
            if (AnalysisVpnService.isConnected()) return@setOnClickListener
            val input = EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(AnalysisPreferences.getMtu(ctx).toString())
                setSelection(text.length)
            }
            AlertDialog.Builder(ctx)
                .setTitle("WireGuard MTU")
                .setMessage("Default: 1420\nICMP probe: run MTU tab to auto-discover\nRange: 576–1500")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val mtu = input.text.toString().toIntOrNull() ?: 1420
                    AnalysisPreferences.setMtu(ctx, mtu)
                    binding.mtuDisplay.text = "MTU $mtu"
                }
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset 1420") { _, _ ->
                    AnalysisPreferences.setMtu(ctx, 1420)
                    binding.mtuDisplay.text = "MTU 1420"
                }
                .show()
        }
    }

    private fun setupButtons() {
        binding.connectButton.setOnClickListener {
            if (AnalysisVpnService.isConnected()) {
                stopVpn()
            } else {
                requestVpnPermissionAndConnect()
            }
        }

        binding.regenerateKeyButton.setOnClickListener {
            autoConfig()
        }

        binding.autoConfigButton.setOnClickListener {
            autoConfig()
        }
    }

    private fun autoConfig() {
        val ctx = requireContext()

        appendLog("Requesting VPN config from server...")

        binding.autoConfigButton.isEnabled = false
        binding.autoConfigButton.text = "Configuring..."

        lifecycleScope.launch {
            try {
                val result = registerWithServer()
                if (result != null) {
                    // Store server-generated private key if provided
                    if (result.privateKey != null) {
                        AnalysisPreferences.setClientPrivateKey(ctx, result.privateKey)
                    }
                    AnalysisPreferences.setServerAddress(ctx, result.serverEndpoint)
                    AnalysisPreferences.setServerPort(ctx, result.serverPort)
                    AnalysisPreferences.setServerPublicKey(ctx, result.serverPublicKey)
                    AnalysisPreferences.setClientAddress(ctx, "${result.assignedIp}/32")
                    AnalysisPreferences.setAllowedIps(ctx, result.allowedIps)
                    AnalysisPreferences.setDnsServers(ctx, result.dns)

                    withContext(Dispatchers.Main) {
                        // Display server-provided public key (WireGuard-compatible)
                        if (result.clientPublicKey != null) {
                            binding.clientPubKeyText.text = result.clientPublicKey
                        } else {
                            displayClientPublicKey()
                        }
                        appendLog("✅ Auto-config complete!")
                        appendLog("Assigned IP: ${result.assignedIp}")
                        appendLog("Status: ${result.status}")
                        appendLog("Tap CONNECT VPN to connect")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        appendLog("❌ Auto-config failed — check server URL")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("❌ Error: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.autoConfigButton.isEnabled = true
                    binding.autoConfigButton.text = getString(R.string.analysis_auto_config)
                }
            }
        }
    }

    private data class VpnRegistrationResult(
        val status: String,
        val assignedIp: String,
        val serverPublicKey: String,
        val serverEndpoint: String,
        val serverPort: Int,
        val dns: String,
        val allowedIps: String,
        val privateKey: String? = null,
        val clientPublicKey: String? = null,
    )

    private suspend fun registerWithServer(): VpnRegistrationResult? = withContext(Dispatchers.IO) {
        val serverUrl = "https://netprobe.xyz/api/vpn/register"

        try {
            val url = URL(serverUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val jsonBody = JSONObject().apply {
                put("device_name", android.os.Build.MODEL)
            }

            conn.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray())
            }

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
            }

            if (responseCode == 200) {
                val json = JSONObject(responseBody)
                VpnRegistrationResult(
                    status = json.getString("status"),
                    assignedIp = json.getString("assigned_ip"),
                    serverPublicKey = json.getString("server_public_key"),
                    serverEndpoint = json.getString("server_endpoint"),
                    serverPort = json.getInt("server_port"),
                    dns = json.getString("dns"),
                    allowedIps = json.getString("allowed_ips"),
                    privateKey = json.optString("private_key", null),
                    clientPublicKey = json.optString("client_public_key", null),
                )
            } else {
                withContext(Dispatchers.Main) {
                    appendLog("Server error: $responseBody")
                }
                null
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendLog("Network error: ${e.message}")
            }
            null
        }
    }

    private fun displayClientPublicKey() {
        val ctx = requireContext()
        val privKey = AnalysisPreferences.getClientPrivateKey(ctx)
        if (privKey.isNotBlank()) {
            try {
                val privBytes = android.util.Base64.decode(privKey, android.util.Base64.NO_WRAP)
                val pubBytes = Curve25519.scalarMultBase(privBytes)
                binding.clientPubKeyText.text = android.util.Base64.encodeToString(pubBytes, android.util.Base64.NO_WRAP)
            } catch (_: Exception) {
                binding.clientPubKeyText.text = "—"
            }
        } else {
            binding.clientPubKeyText.text = "Not generated yet"
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val intent = VpnService.prepare(requireContext())
        if (intent != null) {
            appendLog("Requesting VPN permission…")
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val ctx = requireContext()
        if (!AnalysisPreferences.hasConfig(ctx)) {
            appendLog("Error: tap ⚡ Auto Config first")
            return
        }

        // Reset traffic baseline for fresh counter
        trafficBaselineRx = -1
        trafficBaselineTx = -1

        val enabled = AnalysisPreferences.getSplitTunnelEnabled(ctx)
        if (enabled && selectedPackages.isNotEmpty()) {
            appendLog("Split tunnel: ${selectedPackages.size} apps → VPN")
            (activity?.application as? NetProbeApplication)?.recordSplitTunnel("connect", selectedPackages.size)
        } else if (enabled) {
            appendLog("Split tunnel enabled but no apps selected — all traffic via VPN")
            (activity?.application as? NetProbeApplication)?.recordSplitTunnel("connect_no_apps", 0)
        }

        appendLog("Connecting to ${AnalysisPreferences.getServerAddress(ctx)}:${AnalysisPreferences.getServerPort(ctx)}…")
        (activity?.application as? NetProbeApplication)?.recordVpnEvent("connect_request")
        AnalysisVpnService.connect(ctx)
    }

    private fun stopVpn() {
        appendLog("Disconnecting…")
        (activity?.application as? NetProbeApplication)?.recordVpnEvent("disconnect_request")
        AnalysisVpnService.disconnect(requireContext())
    }

    private fun applyState(state: VpnState) {
        binding.statusText.text = state.statusText

        val dotColor = if (state.connected) {
            R.color.metric_loss_ok
        } else if (state.active) {
            R.color.sync_pending
        } else {
            R.color.metric_loss_bad
        }
        binding.statusDot.setBackgroundColor(ContextCompat.getColor(requireContext(), dotColor))

        if (state.connected && state.connectedSince != null) {
            if (connectedSince == null) {
                connectedSince = state.connectedSince
                timerHandler.post(timerRunnable)
                trafficHandler.post(trafficPollRunnable) // Start traffic polling
            }
        } else {
            timerHandler.removeCallbacks(timerRunnable)
            trafficHandler.removeCallbacks(trafficPollRunnable) // Stop traffic polling
            connectedSince = null
            trafficBaselineRx = -1  // Reset baseline
            trafficBaselineTx = -1
            lastPollRx = 0
            lastPollTx = 0
            lastPollTime = 0
            binding.statusDetail.text = ""
            binding.trafficMonitorCard.visibility = View.GONE
        }

        if (state.connected) {
            binding.connectButton.text = getString(R.string.analysis_disconnect)
            binding.connectButton.isEnabled = true
            binding.connectButton.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.metric_loss_bad),
            )
        } else if (state.active) {
            binding.connectButton.text = getString(R.string.analysis_connecting)
            binding.connectButton.isEnabled = false
        } else {
            binding.connectButton.text = getString(R.string.analysis_connect)
            binding.connectButton.isEnabled = true
            binding.connectButton.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.primary),
            )
        }

        binding.autoConfigButton.isEnabled = !state.connected
        binding.regenerateKeyButton.isEnabled = !state.connected
    }

    private fun appendLog(line: String) {
        logLines.add(line)
        if (logLines.size > 200) logLines.removeAt(0)
        binding.logText.text = logLines.joinToString("\n")
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun formatUptime(ms: Long): String {
        val secs = ms / 1000
        val mins = secs / 60
        val hrs = mins / 60
        return when {
            hrs > 0 -> "%d:%02d:%02d".format(hrs, mins % 60, secs % 60)
            else -> "%d:%02d".format(mins, secs % 60)
        }
    }

    private fun formatBytes(rx: Long?, tx: Long?): String? {
        if (rx == null && tx == null) return null
        return "↓ ${formatSize(rx ?: 0)} · ↑ ${formatSize(tx ?: 0)}"
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun fetchTrafficData() {
        lifecycleScope.launch {
            try {
                val ctx = requireContext()
                val clientIp = AnalysisPreferences.getClientAddress(ctx)
                    .replace("/32", "").replace("/24", "").trim()
                if (clientIp.isBlank()) return@launch

                val result = withContext(Dispatchers.IO) {
                    val url = URL("https://netprobe.xyz/api/vpn/traffic/client/$clientIp")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.requestMethod = "GET"

                    if (conn.responseCode == 200) {
                        conn.inputStream.bufferedReader().readText()
                    } else null
                }

                if (result != null) {
                    val json = JSONObject(result)
                    val peer = json.optJSONObject("peer")
                    val wgRx = peer?.optLong("wg_rx", 0) ?: 0
                    val wgTx = peer?.optLong("wg_tx", 0) ?: 0

                    // Set baseline on first poll after connect
                    if (trafficBaselineRx < 0) {
                        trafficBaselineRx = wgRx
                        trafficBaselineTx = wgTx
                        lastPollRx = wgRx
                        lastPollTx = wgTx
                        lastPollTime = System.currentTimeMillis()
                    }

                    // Show delta since connect
                    val deltaRx = maxOf(0L, wgRx - trafficBaselineRx)
                    val deltaTx = maxOf(0L, wgTx - trafficBaselineTx)

                    // Calculate rate from poll delta
                    val now = System.currentTimeMillis()
                    val timeDeltaSec = (now - lastPollTime) / 1000.0
                    var rxRateBps = 0L
                    var txRateBps = 0L
                    if (timeDeltaSec > 0.5) {
                        rxRateBps = maxOf(0L, ((wgRx - lastPollRx) / timeDeltaSec).toLong())
                        txRateBps = maxOf(0L, ((wgTx - lastPollTx) / timeDeltaSec).toLong())
                        lastPollRx = wgRx
                        lastPollTx = wgTx
                        lastPollTime = now
                    }

                    // Protocols from eBPF monitor
                    val protos = json.optJSONObject("protocols")
                    val protoStr = if (protos != null && protos.length() > 0) {
                        protos.keys().asSequence().map { "$it:${protos.getInt(it)}" }.joinToString(", ")
                    } else ""

                    // Destinations from eBPF monitor
                    val dsts = json.optJSONArray("top_destinations")
                    val dstStr = if (dsts != null && dsts.length() > 0) {
                        (0 until minOf(dsts.length(), 3)).map { i ->
                            val d = dsts.getJSONObject(i)
                            val host = d.optString("hostname", d.optString("ip", "?"))
                            val flows = d.optInt("flows", 0)
                            "$host ($flows)"
                        }.joinToString("\n")
                    } else ""

                    // Ports from eBPF monitor
                    val ports = json.optJSONArray("top_ports")
                    val portStr = if (ports != null && ports.length() > 0) {
                        (0 until minOf(ports.length(), 3)).map { i ->
                            val p = ports.getJSONObject(i)
                            ":${p.optInt("port", 0)}(${p.optString("name", "")})"
                        }.joinToString(", ")
                    } else ""

                    val activeFlows = json.optInt("active_flows", 0)
                    val totalFlows = json.optInt("total_flows", 0)

                    withContext(Dispatchers.Main) {
                        updateTrafficUI(deltaRx, deltaTx, rxRateBps, txRateBps,
                            activeFlows, totalFlows, protoStr, dstStr, portStr)
                    }
                }
            } catch (e: Exception) {
                // Silently ignore — server may not be reachable
            }
        }
    }

    private fun updateTrafficUI(
        wgRx: Long, wgTx: Long, rxRate: Long, txRate: Long,
        activeFlows: Int, totalFlows: Int,
        protocols: String, destinations: String, ports: String
    ) {
        binding.trafficMonitorCard.visibility = View.VISIBLE
        binding.trafficBytesIn.text = formatSize(wgTx)   // Download
        binding.trafficBytesOut.text = formatSize(wgRx)  // Upload
        binding.trafficRateIn.text = if (txRate > 0) "${formatSize(txRate)}/s" else "idle"
        binding.trafficRateOut.text = if (rxRate > 0) "${formatSize(rxRate)}/s" else "idle"

        // Flows info
        binding.trafficFlows.text = if (activeFlows > 0) {
            "● $activeFlows active / $totalFlows flows"
        } else {
            "○ $totalFlows flows · ${formatSize(wgRx + wgTx)} total"
        }

        // Protocols + ports
        val protoDisplay = if (ports.isNotBlank() && protocols.isNotBlank()) {
            "$protocols · $ports"
        } else protocols.ifBlank { ports }
        binding.trafficProtocols.text = protoDisplay
        binding.trafficProtocols.visibility = if (protoDisplay.isNotBlank()) View.VISIBLE else View.GONE

        // Destinations
        if (destinations.isNotBlank()) {
            binding.trafficDestinations.text = destinations
            binding.trafficDestinations.visibility = View.VISIBLE
        } else {
            binding.trafficDestinations.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        timerHandler.removeCallbacks(timerRunnable)
        trafficHandler.removeCallbacks(trafficPollRunnable)
        connectedSince = null
        trafficBaselineRx = -1
        trafficBaselineTx = -1
        lastPollRx = 0
        lastPollTx = 0
        lastPollTime = 0
        _binding = null
        super.onDestroyView()
    }

    /**
     * Custom ArrayAdapter for the app dropdown with icon support and filtering.
     */
    private inner class AppDropdownAdapter(
        context: android.content.Context,
        private val apps: List<SplitTunnelApp>,
    ) : ArrayAdapter<SplitTunnelApp>(context, android.R.layout.simple_dropdown_item_1line, apps.toMutableList()) {

        private val appFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString()?.lowercase() ?: ""
                val filtered = if (query.isBlank()) {
                    apps
                } else {
                    apps.filter {
                        it.appName.lowercase().contains(query) ||
                            it.packageName.lowercase().contains(query)
                    }
                }
                return FilterResults().apply {
                    values = filtered
                    count = filtered.size
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                clear()
                val filtered = results?.values as? List<SplitTunnelApp> ?: return
                addAll(filtered)
                notifyDataSetChanged()
            }

            override fun convertResultToString(resultValue: Any?): CharSequence {
                return (resultValue as? SplitTunnelApp)?.appName ?: ""
            }
        }

        override fun getFilter(): Filter = appFilter

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val app = getItem(position) ?: return view

            // Style: bold app name, grey package name below
            (view as? TextView)?.let { tv ->
                tv.text = buildString {
                    append(app.appName)
                    if (app.packageName in selectedPackages) {
                        append(" ✓")
                    }
                }
                tv.textSize = 13f
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(app.icon, null, null, null)
                tv.compoundDrawablePadding = 16
            }
            return view
        }
    }
}
