package com.telcoagent.udpclient

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProbeReportWriter(private val context: Context) {
    private val cellSnapshots = mutableListOf<CellInfoSnapshot>()

    fun recordCell(snapshot: CellInfoSnapshot) {
        cellSnapshots.add(snapshot)
    }

    fun sampleCount(): Int = cellSnapshots.size

    fun write(overall: OverallMetrics, config: ProbeConfig): File {
        val file = newProbeFile()
        writeRows(file, buildRows(config), buildExtras(overall))
        cellSnapshots.clear()
        return file
    }

    fun writeFailed(config: ProbeConfig, error: String?): File {
        val file = newProbeFile()
        val network = NetworkInfoCollector.read(context)
        val extras = LogSessionExtras(
            localIp = network.localIp,
            dnsServers = network.dnsServers,
            networkType = network.networkType,
        )
        writeRows(file, buildRows(config, failureNote = error?.take(120)), extras)
        cellSnapshots.clear()
        return file
    }

    private fun newProbeFile(): File {
        val dir = File(context.getExternalFilesDir(null), "probes").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "probe-$stamp.csv")
    }

    private fun buildExtras(overall: OverallMetrics): LogSessionExtras {
        val network = NetworkInfoCollector.read(context)
        return LogSessionExtras(
            localIp = network.localIp,
            dnsServers = network.dnsServers,
            networkType = network.networkType,
            udpLatencyMs = overall.avgRttMs ?: overall.avgLatencyMs,
            udpLossPct = overall.lossPct,
            udpJitterMs = overall.jitterMs,
            udpUplinkMs = overall.avgUplinkMs,
            udpDownlinkMs = overall.avgDownlinkMs,
            udpUplinkLossPct = overall.uplinkLossPct,
            udpDownlinkLossPct = overall.downlinkLossPct,
        )
    }

    private fun buildRows(
        config: ProbeConfig,
        failureNote: String? = null,
    ): List<CellInfoSnapshot> {
        val configLabel = "${config.rounds}x${config.packets}"
        val modeLabel = when {
            failureNote != null -> "failed: ${failureNote.take(80)}"
            config.applyOffsetCorrection -> "offset-on"
            else -> "raw"
        }
        return if (cellSnapshots.isNotEmpty()) {
            cellSnapshots.mapIndexed { index, snapshot ->
                if (index == 0) {
                    snapshot.copy(tac = configLabel, enb = modeLabel)
                } else {
                    snapshot
                }
            }
        } else {
            listOf(fallbackSnapshot(config, failureNote))
        }
    }

    private fun writeRows(
        file: File,
        rows: List<CellInfoSnapshot>,
        extras: LogSessionExtras,
    ) {
        file.bufferedWriter().use { writer ->
            writer.write(CsvLogFormat.HEADER)
            writer.newLine()
            for (snapshot in rows) {
                writer.write(CsvLogFormat.formatRow(snapshot, extras))
                writer.newLine()
            }
        }
    }

    private fun fallbackSnapshot(
        config: ProbeConfig,
        failureNote: String? = null,
    ): CellInfoSnapshot {
        val collector = CellInfoCollector(context)
        val cell = if (collector.hasPermission()) {
            runCatching { collector.collect() }.getOrNull()
        } else {
            null
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val configLabel = "${config.rounds}x${config.packets}"
        val modeLabel = when {
            failureNote != null -> "failed: ${failureNote.take(80)}"
            config.applyOffsetCorrection -> "offset-on"
            else -> "raw"
        }
        return cell?.copy(
            updatedAt = timestamp,
            tac = configLabel,
            enb = modeLabel,
        ) ?: CellInfoSnapshot(
            tech = "PROBE",
            deviceId = "${Build.MANUFACTURER} ${Build.MODEL}",
            updatedAt = timestamp,
            tac = configLabel,
            enb = modeLabel,
        )
    }
}
