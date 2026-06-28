package com.telcoagent.udpclient

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.telcoagent.udpclient.databinding.FragmentMaxBinding
import kotlinx.coroutines.launch

class MaxFragment : Fragment() {
    private var _binding: FragmentMaxBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMaxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.startButton.setOnClickListener {
            if (MaxService.isRunning()) {
                MaxService.stop(requireContext())
            } else {
                MaxService.start(requireContext())
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MaxService.state.collect { state ->
                    applyState(state)
                }
            }
        }
    }

    private fun applyState(state: MaxSessionState) {
        if (!state.active && state.result == null && state.logText.isEmpty()) {
            resetView()
            binding.statusText.setText(R.string.max_status_idle)
            binding.progressBar.visibility = View.GONE
            binding.startButton.isEnabled = true
            binding.startButton.setText(R.string.max_start)
            return
        }

        if (state.active && state.result == null) {
            resetView()
        }

        binding.statusText.text = state.statusText.ifBlank { getString(R.string.max_status_idle) }
        binding.progressBar.visibility = if (state.showProgress) View.VISIBLE else View.GONE
        if (state.showProgress) {
            binding.progressBar.setProgressCompat(state.progressPct, true)
        }
        binding.logText.text = state.logText
        if (state.logText.isNotEmpty()) {
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }

        state.result?.let { updateResults(it) }

        if (state.active) {
            binding.startButton.isEnabled = true
            binding.startButton.setText(R.string.max_stop)
        } else {
            binding.startButton.isEnabled = true
            binding.startButton.setText(R.string.max_start)
        }
    }

    private fun resetView() {
        binding.optimalStreamsValue.text = getString(R.string.value_dash)
        binding.maxThroughputValue.text = getString(R.string.value_dash)
        binding.burstThroughputValue.text = getString(R.string.value_dash)
        binding.resultsTable.removeAllViews()
    }

    private fun updateResults(result: MaxResult) {
        // Update optimal result cards
        binding.optimalStreamsValue.text = "${result.optimalStreams}"
        binding.optimalStreamsValue.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.metric_latency),
        )

        binding.maxThroughputValue.text = MaxClient.formatThroughputKB(result.optimalThroughputKBps)
        binding.maxThroughputValue.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.metric_loss_ok),
        )

        binding.burstThroughputValue.text = MaxClient.formatThroughputKB(result.burstThroughputKBps)
        binding.burstThroughputValue.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.metric_loss_ok),
        )

        // Build per-level results table
        buildResultsTable(result)
    }

    private fun buildResultsTable(result: MaxResult) {
        val table = binding.resultsTable
        table.removeAllViews()

        // Header row
        addTableRow(
            table,
            streams = "Streams",
            throughput = "Throughput",
            loss = "Loss",
            rtt = "Avg RTT",
            jitter = "Jitter",
            sent = "Sent",
            recv = "Recv",
            isHeader = true,
        )

        // Data rows
        for (stats in result.levelStats) {
            addTableRow(
                table,
                streams = "${stats.streamCount}",
                throughput = MaxClient.formatThroughputKB(stats.throughputKBps),
                loss = MaxClient.fmtPct(stats.lossPct),
                rtt = MaxClient.fmtMs(stats.avgRttMs),
                jitter = MaxClient.fmtMs(stats.jitterMs),
                sent = "${stats.totalSent}",
                recv = "${stats.totalReceived}",
                isHeader = false,
                lossPct = stats.lossPct,
                isOptimal = stats.streamCount == result.optimalStreams,
            )
        }
    }

    private fun addTableRow(
        table: android.widget.TableLayout,
        streams: String,
        throughput: String,
        loss: String,
        rtt: String,
        jitter: String,
        sent: String,
        recv: String,
        isHeader: Boolean,
        lossPct: Double? = null,
        isOptimal: Boolean = false,
    ) {
        val row = android.widget.TableRow(requireContext())
        val lp = android.widget.TableLayout.LayoutParams(
            android.widget.TableLayout.LayoutParams.MATCH_PARENT,
            android.widget.TableLayout.LayoutParams.WRAP_CONTENT,
        )
        row.layoutParams = lp

        val textSize = 10f
        val textColor = if (isHeader) {
            ContextCompat.getColor(requireContext(), R.color.text_secondary)
        } else {
            ContextCompat.getColor(requireContext(), R.color.text_primary)
        }
        val typeface = if (isHeader) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL

        fun makeCell(text: String, weight: Float = 1f, color: Int = textColor, bold: Boolean = false): TextView {
            return TextView(requireContext()).apply {
                this.text = text
                this.textSize = textSize
                this.setTextColor(color)
                if (bold) this.setTypeface(this.typeface, android.graphics.Typeface.BOLD)
                this.setPadding(4, 4, 4, 4)
                this.textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                this.layoutParams = android.widget.TableRow.LayoutParams(
                    0,
                    android.widget.TableRow.LayoutParams.WRAP_CONTENT,
                    weight,
                )
            }
        }

        val lossOkColor = ContextCompat.getColor(requireContext(), R.color.metric_loss_ok)
        val lossBadColor = ContextCompat.getColor(requireContext(), R.color.metric_loss_bad)
        val optimalColor = ContextCompat.getColor(requireContext(), R.color.metric_latency)

        val streamColor = if (isHeader) textColor else if (isOptimal) optimalColor else textColor
        val lossColor = if (isHeader) textColor else if ((lossPct ?: 0.0) >= 1.0) lossBadColor else lossOkColor

        row.addView(makeCell(streams, 0.7f, color = streamColor, bold = isOptimal))
        row.addView(makeCell(throughput, 1.2f))
        row.addView(makeCell(loss, 0.8f, color = lossColor))
        row.addView(makeCell(rtt, 0.9f))
        row.addView(makeCell(jitter, 0.9f))
        row.addView(makeCell(sent, 0.7f))
        row.addView(makeCell(recv, 0.7f))

        if (!isHeader) {
            row.setPadding(0, 2, 0, 2)
        }

        table.addView(row)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
