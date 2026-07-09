package com.telcoagent.udpclient

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.telcoagent.udpclient.databinding.FragmentRadioBinding
import com.telcoagent.udpclient.databinding.ItemRadioFieldBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RadioFragment : Fragment() {
    private var _binding: FragmentRadioBinding? = null
    private val binding get() = _binding!!
    private lateinit var collector: CellInfoCollector
    private lateinit var wifiCollector: WifiInfoCollector
    private var sessionState = RadioLogSessionState()

    private val primaryRows by lazy {
        listOf(
            binding.rowTech,
            binding.rowTac,
            binding.rowEnb,
            binding.rowCellId,
            binding.rowArfcn,
            binding.rowBand,
            binding.rowRsrp,
            binding.rowRsrq,
            binding.rowSnr,
        )
    }

    private val dcNrIdentityRows by lazy {
        listOf(
            binding.rowNrPci,
            binding.rowNrGnb,
            binding.rowNrCellId,
            binding.rowNrArfcn,
            binding.rowNrBand,
        )
    }

    private val dcNrSignalRows by lazy {
        listOf(
            binding.rowNrRsrp,
            binding.rowNrRsrq,
            binding.rowNrSnr,
        )
    }

    private val wifiRows by lazy {
        listOf(
            binding.rowWifiSsid,
            binding.rowWifiBssid,
            binding.rowWifiRssi,
            binding.rowWifiFrequency,
            binding.rowWifiLinkSpeed,
            binding.rowWifiChannel,
            binding.rowWifiSecurity,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRadioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collector = CellInfoCollector(requireContext())
        wifiCollector = WifiInfoCollector(requireContext())
        setupLabels()
        setupLogButton()
        setupDriveButton()
        refreshCellInfo()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    RadioLogService.state.collect { state ->
                        sessionState = state
                        updateLogButton()
                        refreshCellInfo()
                    }
                }
                launch {
                    RadioLogService.stoppedRecords.collect { record ->
                        Snackbar.make(
                            binding.root,
                            getString(R.string.radio_log_saved, record.lineCount, record.filename),
                            Snackbar.LENGTH_SHORT,
                        ).show()
                        uploadLogFile(record.id)
                    }
                }
                launch {
                    DriveService.state.collect { state ->
                        updateDriveUI(state)
                    }
                }
                while (isActive) {
                    collector.requestFreshCells(requireContext().mainExecutor)
                    refreshCellInfo()
                    delay(2000)
                }
            }
        }
    }

    // ── Drive ABR ──

    private fun setupDriveButton() {
        binding.driveButton.setOnClickListener {
            if (DriveService.isRunning()) {
                DriveService.stop(requireContext())
            } else {
                DriveService.start(requireContext())
            }
        }
        updateDriveUI(DriveService.state.value)
    }

    private fun updateDriveUI(state: DriveSessionState) {
        binding.driveStatusText.text = state.statusText.ifBlank { "Drive ABR: idle" }
        binding.driveRoundCount.text = "${state.roundCount} rounds"

        if (state.logText.isNotEmpty()) {
            binding.driveLogText.text = state.logText
            binding.driveLogScroll.post {
                binding.driveLogScroll.fullScroll(View.FOCUS_DOWN)
            }
        }

        if (state.active) {
            binding.driveButton.text = "■ Stop"
        } else {
            binding.driveButton.text = "▶ Drive"
        }
    }

    // ── Radio Logging ──

    private fun setupLogButton() {
        binding.logButton.setOnClickListener {
            if (RadioLogService.isLogging()) {
                stopLogging()
            } else {
                startLogging()
            }
        }
        updateLogButton()
    }

    private fun startLogging() {
        if (!collector.hasPermission()) {
            Snackbar.make(binding.root, R.string.radio_permission_required, Snackbar.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Snackbar.make(
                binding.root,
                R.string.radio_notification_permission_required,
                Snackbar.LENGTH_LONG,
            ).show()
        }
        RadioLogService.start(requireContext())
        refreshCellInfo()
    }

    private fun stopLogging() {
        RadioLogService.stop(requireContext())
    }

    private fun uploadLogFile(recordId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            Snackbar.make(binding.root, R.string.radio_log_uploading, Snackbar.LENGTH_SHORT).show()
            val result = LogSyncHelper.sync(requireContext(), recordId)
            result.fold(
                onSuccess = { sessionId ->
                    Snackbar.make(
                        binding.root,
                        getString(R.string.radio_log_uploaded, sessionId),
                        Snackbar.LENGTH_LONG,
                    ).show()
                },
                onFailure = { error ->
                    Snackbar.make(
                        binding.root,
                        getString(R.string.radio_log_upload_failed, error.message ?: "error"),
                        Snackbar.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    private fun updateLogButton() {
        binding.logButton.text = getString(
            if (sessionState.active) R.string.radio_stop_log else R.string.radio_start_log,
        )
    }

    // ── Radio Info ──

    private fun setupLabels() {
        bindRow(binding.rowTech, "TECH:")
        bindRow(binding.rowTac, "TAC:")
        bindRow(binding.rowEnb, "eNB:")
        bindRow(binding.rowCellId, "CELLID:")
        bindRow(binding.rowArfcn, "ARFCN:")
        bindRow(binding.rowBand, "BAND:")
        bindRow(binding.rowRsrp, "RSRP:")
        bindRow(binding.rowRsrq, "RSRQ:")
        bindRow(binding.rowSnr, "SNR:")

        bindRow(binding.rowNrPci, "PCI:")
        bindRow(binding.rowNrGnb, "gNB:")
        bindRow(binding.rowNrCellId, "CELLID:")
        bindRow(binding.rowNrArfcn, "ARFCN:")
        bindRow(binding.rowNrBand, "BAND:")
        bindRow(binding.rowNrRsrp, "RSRP:")
        bindRow(binding.rowNrRsrq, "RSRQ:")
        bindRow(binding.rowNrSnr, "SNR:")

        bindRow(binding.rowWifiSsid, "SSID:")
        bindRow(binding.rowWifiBssid, "BSSID:")
        bindRow(binding.rowWifiRssi, "RSSI:")
        bindRow(binding.rowWifiFrequency, "FREQ:")
        bindRow(binding.rowWifiLinkSpeed, "LINK:")
        bindRow(binding.rowWifiChannel, "CH:")
        bindRow(binding.rowWifiSecurity, "SEC:")
    }

    private fun refreshCellInfo() {
        val snapshot = if (sessionState.active && sessionState.lastSnapshot != null) {
            sessionState.lastSnapshot!!
        } else {
            collector.collect()
        }
        val hasPermission = collector.hasPermission()
        val dash = getString(R.string.value_dash)

        binding.permissionBanner.visibility =
            if (hasPermission) View.GONE else View.VISIBLE

        if (!hasPermission) {
            binding.lastUpdatedText.text = getString(R.string.radio_permission_required)
            clearValues()
            binding.dcNrSection.visibility = View.GONE
            binding.wifiSection.visibility = View.GONE
            return
        }

        if (snapshot.error != null) {
            binding.lastUpdatedText.text = snapshot.error
            clearValues()
            binding.dcNrSection.visibility = View.GONE
            binding.wifiSection.visibility = View.GONE
            return
        }

        val siteLabel = if (snapshot.tech == "NR") "gNB:" else "eNB:"
        bindRow(binding.rowTech, "TECH:", snapshot.tech)
        bindRow(binding.rowTac, "TAC:", snapshot.tac)
        bindRow(binding.rowEnb, siteLabel, snapshot.enb)
        bindRow(binding.rowCellId, "CELLID:", snapshot.cellId)
        bindRow(binding.rowArfcn, "ARFCN:", snapshot.arfcn)
        bindRow(binding.rowBand, "BAND:", snapshot.band)
        bindRow(binding.rowRsrp, "RSRP:", snapshot.rsrp)
        bindRow(binding.rowRsrq, "RSRQ:", snapshot.rsrq)
        bindRow(binding.rowSnr, "SNR:", snapshot.snr)

        val dcNr = snapshot.dcNr
        if (dcNr != null) {
            binding.dcNrSection.visibility = View.VISIBLE
            bindRow(binding.rowNrPci, "PCI:", dcNr.pci)
            bindRow(binding.rowNrGnb, "gNB:", dcNr.gnb)
            bindRow(binding.rowNrCellId, "CELLID:", dcNr.cellId)
            bindRow(binding.rowNrArfcn, "ARFCN:", dcNr.arfcn)
            bindRow(binding.rowNrBand, "BAND:", dcNr.band)
            bindRow(binding.rowNrRsrp, "RSRP:", dcNr.rsrp)
            bindRow(binding.rowNrRsrq, "RSRQ:", dcNr.rsrq)
            bindRow(binding.rowNrSnr, "SNR:", dcNr.snr)

            dcNrIdentityRows.forEach { row ->
                row.root.visibility =
                    if (row.fieldValue.text != dash) View.VISIBLE else View.GONE
            }
            binding.dcNrHeader.text = if (dcNr.hasIdentity()) {
                getString(R.string.radio_dc_nr)
            } else {
                getString(R.string.radio_dc_nr) + " · " + getString(R.string.radio_identity_unavailable)
            }
        } else {
            binding.dcNrSection.visibility = View.GONE
            (dcNrIdentityRows + dcNrSignalRows).forEach { row ->
                row.fieldValue.text = dash
            }
        }

        // WiFi section
        val wifiSnap = wifiCollector.collect()
        if (wifiSnap.isConnected && wifiSnap.error == null) {
            binding.wifiSection.visibility = View.VISIBLE
            bindRow(binding.rowWifiSsid, "SSID:", wifiSnap.ssid)
            bindRow(binding.rowWifiBssid, "BSSID:", wifiSnap.bssid)
            bindRow(binding.rowWifiRssi, "RSSI:", wifiSnap.rssi)
            bindRow(binding.rowWifiFrequency, "FREQ:", wifiSnap.frequency)
            bindRow(binding.rowWifiLinkSpeed, "LINK:", wifiSnap.linkSpeed)
            bindRow(binding.rowWifiChannel, "CH:", wifiSnap.channel)
            bindRow(binding.rowWifiSecurity, "SEC:", wifiSnap.security)
        } else {
            binding.wifiSection.visibility = View.GONE
            wifiRows.forEach { row ->
                row.fieldValue.text = dash
            }
        }

        val footer = buildString {
            if (sessionState.active && sessionState.fileName != null) {
                when {
                    sessionState.probing && sessionState.lastUdpLatencyMs == null -> {
                        append(getString(R.string.radio_udp_probing))
                        append(" · ")
                    }
                    sessionState.lastUdpLatencyMs != null -> {
                        append(getString(R.string.radio_udp_rtt, sessionState.lastUdpLatencyMs!!))
                        append(" · ")
                    }
                }
                append(
                    getString(
                        R.string.radio_logging,
                        sessionState.fileName!!,
                        sessionState.lineCount,
                    ),
                )
                append(" · ")
            }
            append(getString(R.string.radio_last_updated, snapshot.updatedAt))
            if (dcNr != null && !collector.hasPrecisePhoneStatePermission() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) {
                append(" · grant precise phone for gNB")
            }
        }
        binding.lastUpdatedText.text = footer
    }

    private fun bindRow(row: ItemRadioFieldBinding, label: String, value: String = "—") {
        val dash = getString(R.string.value_dash)
        row.fieldLabel.text = label
        row.fieldValue.text = value
        row.root.visibility = if (value == dash) View.GONE else View.VISIBLE
    }

    private fun clearValues() {
        val dash = getString(R.string.value_dash)
        primaryRows.forEach { row ->
            row.fieldValue.text = dash
            row.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
