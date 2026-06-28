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
import com.telcoagent.udpclient.databinding.FragmentProbeBinding
import kotlinx.coroutines.launch

class ProbeFragment : Fragment() {
    private var _binding: FragmentProbeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentProbeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.offsetCorrectionSwitch.isChecked =
            ProbePreferences.isOffsetCorrectionEnabled(requireContext())
        binding.offsetCorrectionSwitch.setOnCheckedChangeListener { _, checked ->
            ProbePreferences.setOffsetCorrectionEnabled(requireContext(), checked)
        }
        binding.startButton.setOnClickListener {
            if (ProbeService.isRunning()) {
                ProbeService.stop(requireContext())
            } else {
                ProbeService.start(requireContext())
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProbeService.state.collect { state ->
                    applyState(state)
                }
            }
        }
    }

    private fun applyState(state: ProbeSessionState) {
        if (!state.active && state.overallMetrics == null && state.logText.isEmpty()) {
            resetMetrics()
            binding.statusText.setText(R.string.status_idle)
            binding.progressBar.visibility = View.GONE
            binding.startButton.isEnabled = true
            binding.startButton.setText(R.string.start_probe)
            return
        }

        if (state.active && state.roundMetrics == null && state.overallMetrics == null) {
            resetMetrics()
        }

        binding.statusText.text = state.statusText.ifBlank { getString(R.string.status_idle) }
        binding.progressBar.visibility = if (state.showProgress) View.VISIBLE else View.GONE
        if (state.showProgress) {
            binding.progressBar.setProgressCompat(state.progressPct, true)
        }
        binding.logText.text = state.logText
        if (state.logText.isNotEmpty()) {
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }

        state.roundMetrics?.let { updateMetrics(it) }
        state.overallMetrics?.let { updateMetrics(it) }
        state.roundsLabel?.let { binding.roundsValue.text = it }

        if (state.active) {
            binding.startButton.isEnabled = true
            binding.startButton.setText(R.string.stop_probe)
        } else {
            binding.startButton.isEnabled = true
            binding.startButton.setText(R.string.start_probe)
        }
    }

    private fun resetMetrics() {
        binding.latencyValue.text = getString(R.string.value_dash)
        binding.uplinkValue.text = getString(R.string.value_dash)
        binding.downlinkValue.text = getString(R.string.value_dash)
        binding.lossValue.text = getString(R.string.value_dash)
        binding.uplinkLossValue.text = getString(R.string.value_dash)
        binding.downlinkLossValue.text = getString(R.string.value_dash)
        binding.jitterValue.text = getString(R.string.value_dash)
        binding.roundsValue.text = getString(R.string.value_dash)
        binding.lossValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.metric_loss_ok))
        binding.uplinkLossValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.metric_loss_ok))
        binding.downlinkLossValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.metric_loss_ok))
    }

    private fun updateMetrics(metrics: RoundMetrics) {
        binding.latencyValue.text = metrics.avgRttMs?.let { "${"%.0f".format(it)} ms" }
            ?: metrics.avgLatencyMs?.let { "${"%.0f".format(it)} ms" } ?: "—"
        binding.uplinkValue.text = metrics.avgUplinkMs?.let { "${"%.0f".format(it)} ms" } ?: "—"
        binding.downlinkValue.text = metrics.avgDownlinkMs?.let { "${"%.0f".format(it)} ms" } ?: "—"
        binding.lossValue.text = formatLossPct(metrics.lossPct)
        binding.uplinkLossValue.text = formatOptionalLossPct(metrics.uplinkLossPct)
        binding.downlinkLossValue.text = formatOptionalLossPct(metrics.downlinkLossPct)
        binding.jitterValue.text = metrics.jitterMs?.let { "${"%.0f".format(it)} ms" } ?: "—"
        applyLossColor(binding.lossValue, metrics.lossPct)
        applyOptionalLossColor(binding.uplinkLossValue, metrics.uplinkLossPct)
        applyOptionalLossColor(binding.downlinkLossValue, metrics.downlinkLossPct)
    }

    private fun updateMetrics(overall: OverallMetrics) {
        binding.latencyValue.text = overall.avgRttMs?.let { "${"%.0f".format(it)} ms" }
            ?: overall.avgLatencyMs?.let { "${"%.0f".format(it)} ms" } ?: "—"
        binding.uplinkValue.text = overall.avgUplinkMs?.let { "${"%.0f".format(it)} ms" } ?: "—"
        binding.downlinkValue.text = overall.avgDownlinkMs?.let { "${"%.0f".format(it)} ms" } ?: "—"
        binding.lossValue.text = formatLossPct(overall.lossPct)
        binding.uplinkLossValue.text = formatOptionalLossPct(overall.uplinkLossPct)
        binding.downlinkLossValue.text = formatOptionalLossPct(overall.downlinkLossPct)
        binding.jitterValue.text = overall.jitterMs?.let { "${"%.0f".format(it)} ms" } ?: "—"
        applyLossColor(binding.lossValue, overall.lossPct)
        applyOptionalLossColor(binding.uplinkLossValue, overall.uplinkLossPct)
        applyOptionalLossColor(binding.downlinkLossValue, overall.downlinkLossPct)
    }

    private fun formatLossPct(value: Double): String = "${"%.1f".format(value)}%"

    private fun formatOptionalLossPct(value: Double?): String {
        return value?.let { "${"%.1f".format(it)}%" } ?: "—"
    }

    private fun applyLossColor(view: TextView, lossPct: Double) {
        val color = if (lossPct > 0.0) R.color.metric_loss_bad else R.color.metric_loss_ok
        view.setTextColor(ContextCompat.getColor(requireContext(), color))
    }

    private fun applyOptionalLossColor(view: TextView, lossPct: Double?) {
        if (lossPct == null) {
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.metric_loss_ok))
            return
        }
        applyLossColor(view, lossPct)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
