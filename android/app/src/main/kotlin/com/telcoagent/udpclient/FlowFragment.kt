package com.telcoagent.udpclient

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.telcoagent.udpclient.databinding.FragmentFlowBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class SortField { SERVER, PROTO, UPLINK, DOWNLINK }

class FlowFragment : Fragment() {
    private var _binding: FragmentFlowBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: FlowAdapter
    private val pollHandler = Handler(Looper.getMainLooper())

    private var currentSort = SortField.DOWNLINK
    private var sortAscending = false  // false = descending (default for downlink)
    private var lastFlows = listOf<FlowEntry>()
    private var lastActiveFlows = 0
    private var lastTotalFlows = 0

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (AnalysisVpnService.isConnected()) {
                fetchFlowData()
                pollHandler.postDelayed(this, 5000)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFlowBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FlowAdapter()
        binding.flowRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.flowRecycler.adapter = adapter
        binding.flowRecycler.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        // Sort header click listeners
        binding.sortByServer.setOnClickListener { toggleSort(SortField.SERVER) }
        binding.sortByProto.setOnClickListener { toggleSort(SortField.PROTO) }
        binding.sortByUplink.setOnClickListener { toggleSort(SortField.UPLINK) }
        binding.sortByDownlink.setOnClickListener { toggleSort(SortField.DOWNLINK) }

        updateSortHeaders()

        // Observe VPN state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AnalysisVpnService.state.collect { state ->
                    applyState(state)
                }
            }
        }
    }

    private fun toggleSort(field: SortField) {
        if (currentSort == field) {
            sortAscending = !sortAscending
        } else {
            currentSort = field
            sortAscending = when (field) {
                SortField.SERVER, SortField.PROTO -> true   // A→Z default
                SortField.UPLINK, SortField.DOWNLINK -> false // high→low default
            }
        }
        updateSortHeaders()
        applySorting()
    }

    private fun updateSortHeaders() {
        val headers = mapOf(
            SortField.SERVER to binding.sortByServer,
            SortField.PROTO to binding.sortByProto,
            SortField.UPLINK to binding.sortByUplink,
            SortField.DOWNLINK to binding.sortByDownlink,
        )
        val labels = mapOf(
            SortField.SERVER to "Server",
            SortField.PROTO to "Proto",
            SortField.UPLINK to "↑ Up",
            SortField.DOWNLINK to "↓ Down",
        )

        for ((field, tv) in headers) {
            val isActive = field == currentSort
            val arrow = if (isActive) {
                if (sortAscending) " ▲" else " ▼"
            } else ""

            tv.text = "${labels[field]}$arrow"
            tv.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isActive) R.color.primary else R.color.text_secondary
                )
            )
            tv.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun applySorting() {
        if (lastFlows.isEmpty()) return

        val sorted = sortFlows(lastFlows)
        adapter.submitList(sorted)
    }

    private fun sortFlows(flows: List<FlowEntry>): List<FlowEntry> {
        return if (sortAscending) {
            when (currentSort) {
                SortField.SERVER -> flows.sortedWith(compareBy<FlowEntry> { it.serverIp }.thenBy { it.serverPort })
                SortField.PROTO -> flows.sortedBy { it.proto }
                SortField.UPLINK -> flows.sortedBy { it.uplink }
                SortField.DOWNLINK -> flows.sortedBy { it.downlink }
            }
        } else {
            when (currentSort) {
                SortField.SERVER -> flows.sortedWith(compareByDescending<FlowEntry> { it.serverIp }.thenByDescending { it.serverPort })
                SortField.PROTO -> flows.sortedByDescending { it.proto }
                SortField.UPLINK -> flows.sortedByDescending { it.uplink }
                SortField.DOWNLINK -> flows.sortedByDescending { it.downlink }
            }
        }
    }

    private fun applyState(state: VpnState) {
        if (state.connected) {
            binding.flowStatusDot.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.metric_loss_ok)
            )
            binding.flowStatusText.text = "VPN connected — monitoring flows…"
            pollHandler.removeCallbacks(pollRunnable)
            pollHandler.post(pollRunnable)
        } else {
            binding.flowStatusDot.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.metric_loss_bad)
            )
            binding.flowStatusText.text = "VPN disconnected — connect in Trace tab to see flows"
            pollHandler.removeCallbacks(pollRunnable)
            binding.flowHeader.visibility = View.GONE
            binding.flowCountText.visibility = View.GONE
            binding.flowEmpty.visibility = View.VISIBLE
            binding.flowRecycler.visibility = View.GONE
            adapter.submitList(emptyList())
            lastFlows = emptyList()
        }
    }

    private fun fetchFlowData() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ctx = requireContext()
                val clientIp = AnalysisPreferences.getClientAddress(ctx)
                    .replace("/32", "").replace("/24", "").trim()
                if (clientIp.isBlank()) return@launch

                val result = withContext(Dispatchers.IO) {
                    val url = URL("https://netprobe.xyz/api/vpn/traffic/client/$clientIp")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.requestMethod = "GET"

                    if (conn.responseCode == 200) {
                        conn.inputStream.bufferedReader().readText()
                    } else null
                }

                if (result != null) {
                    val json = JSONObject(result)
                    val flowsDetail = json.optJSONArray("flows_detail")
                    val totalFlows = json.optInt("total_flows", 0)
                    val activeFlows = json.optInt("active_flows", 0)

                    val flows = mutableListOf<FlowEntry>()
                    if (flowsDetail != null) {
                        for (i in 0 until flowsDetail.length()) {
                            val f = flowsDetail.getJSONObject(i)
                            flows.add(
                                FlowEntry(
                                    clientIp = f.optString("client_ip", ""),
                                    clientPort = f.optInt("client_port", 0),
                                    serverIp = f.optString("server_ip", ""),
                                    serverPort = f.optInt("server_port", 0),
                                    proto = f.optString("proto", "?"),
                                    state = f.optString("state", ""),
                                    dir = f.optString("dir", ""),
                                    uplink = f.optLong("uplink", 0),
                                    downlink = f.optLong("downlink", 0),
                                )
                            )
                        }
                    }

                    lastFlows = flows
                    lastActiveFlows = activeFlows
                    lastTotalFlows = totalFlows

                    withContext(Dispatchers.Main) {
                        updateUI(flows, activeFlows, totalFlows)
                    }
                }
            } catch (_: Exception) {
                // Silently ignore — server may not be reachable
            }
        }
    }

    private fun updateUI(flows: List<FlowEntry>, activeFlows: Int, totalFlows: Int) {
        if (flows.isEmpty()) {
            binding.flowHeader.visibility = View.GONE
            binding.flowCountText.visibility = View.GONE
            binding.flowEmpty.visibility = View.VISIBLE
            binding.flowEmpty.text = "No active flows detected.\nTraffic will appear here when VPN is in use."
            binding.flowRecycler.visibility = View.GONE
        } else {
            binding.flowHeader.visibility = View.VISIBLE
            binding.flowRecycler.visibility = View.VISIBLE
            binding.flowEmpty.visibility = View.GONE

            val sorted = sortFlows(flows)
            adapter.submitList(sorted)

            binding.flowCountText.visibility = View.VISIBLE
            binding.flowCountText.text = "$activeFlows active / $totalFlows flows"
        }
    }

    override fun onDestroyView() {
        pollHandler.removeCallbacks(pollRunnable)
        _binding = null
        super.onDestroyView()
    }
}
