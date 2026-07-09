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
import com.telcoagent.udpclient.databinding.FragmentAbrBinding
import kotlinx.coroutines.launch

class AbrFragment : Fragment() {
    private var _binding: FragmentAbrBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAbrBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.startButton.setOnClickListener {
            if (AbrService.isRunning()) {
                AbrService.stop(requireContext())
            } else {
                AbrService.start(requireContext())
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AbrService.state.collect { state ->
                    applyState(state)
                }
            }
        }
    }

    private fun applyState(state: AbrSessionState) {
        if (!state.active && state.result == null && state.logText.isEmpty()) {
            resetView()
            binding.statusText.setText(R.string.mtu_status_idle)
            binding.progressBar.visibility = View.GONE
            binding.startButton.isEnabled = true
            binding.startButton.setText(R.string.mtu_start)
            return
        }

        if (state.active && state.result == null) {
            resetView()
        }

        binding.statusText.text = state.statusText.ifBlank { getString(R.string.mtu_status_idle) }
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
            binding.startButton.setText(R.string.mtu_stop)
        } else {
            binding.startButton.isEnabled = true
            binding.startButton.setText(R.string.mtu_start)
        }
    }

    private fun resetView() {
        binding.pmtuValue.text = getString(R.string.value_dash)
        binding.pmtuHost.text = getString(R.string.value_dash)
        binding.wgMtuValue.text = getString(R.string.value_dash)
        binding.wgMtuFormula.text = getString(R.string.value_dash)
        binding.probeTable.removeAllViews()
    }

    private fun updateResults(result: AbrResult) {
        // Path MTU card
        if (result.pmtu != null) {
            binding.pmtuValue.text = "${result.pmtu} B"
            binding.pmtuValue.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.metric_loss_ok),
            )
        } else {
            binding.pmtuValue.text = "N/A"
            binding.pmtuValue.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.metric_loss_bad),
            )
        }
        binding.pmtuHost.text = result.host

        // WireGuard MTU card
        val wgMtu = result.wgMtu
        if (wgMtu != null) {
            binding.wgMtuValue.text = "$wgMtu B"
            binding.wgMtuValue.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.metric_loss_ok),
            )
            binding.wgMtuFormula.text = if (result.pmtu != null) {
                "${result.pmtu} − 60 = $wgMtu"
            } else {
                "fallback"
            }
        } else {
            binding.wgMtuValue.text = "N/A"
            binding.wgMtuValue.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.metric_loss_bad),
            )
        }

        // Build probe steps table
        buildProbeTable(result)

        // Save MTU for WireGuard config
        if (wgMtu != null) {
            AnalysisPreferences.setMtu(requireContext(), wgMtu)
        }
    }

    private fun buildProbeTable(result: AbrResult) {
        val table = binding.probeTable
        table.removeAllViews()

        // Header
        addProbeRow(table, "#", "Size", "Result", "RTT", isHeader = true)

        // Data rows
        for ((i, step) in result.probeSteps.withIndex()) {
            val resultText = if (step.success) "✓ pass" else "✗ frag"
            val rttText = if (step.rttMs != null) AbrClient.fmtMs(step.rttMs) else "—"
            addProbeRow(
                table,
                "${i + 1}",
                "${step.probeSize}B",
                resultText,
                rttText,
                isHeader = false,
                success = step.success,
            )
        }
    }

    private fun addProbeRow(
        table: android.widget.TableLayout,
        num: String,
        size: String,
        result: String,
        rtt: String,
        isHeader: Boolean,
        success: Boolean? = null,
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

        fun makeCell(text: String, weight: Float = 1f, color: Int = textColor): TextView {
            return TextView(requireContext()).apply {
                this.text = text
                this.textSize = textSize
                this.setTextColor(color)
                this.setPadding(4, 4, 4, 4)
                this.textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                this.layoutParams = android.widget.TableRow.LayoutParams(
                    0, android.widget.TableRow.LayoutParams.WRAP_CONTENT, weight,
                )
            }
        }

        val okColor = ContextCompat.getColor(requireContext(), R.color.metric_loss_ok)
        val badColor = ContextCompat.getColor(requireContext(), R.color.metric_loss_bad)

        row.addView(makeCell(num, 0.5f))
        row.addView(makeCell(size, 0.8f))
        row.addView(makeCell(result, color = if (isHeader) textColor else if (success == true) okColor else badColor))
        row.addView(makeCell(rtt))

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
