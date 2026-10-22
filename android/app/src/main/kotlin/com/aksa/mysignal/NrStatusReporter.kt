package com.aksa.mysignal

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class NrReportStatus(
    val ok: Boolean,
    val message: String,
    val tsMs: Long,
)

/**
 * Derived 5G path report for the legacy NR-only dashboard (`/v1/nr-status`).
 * Posts when the data path changes and otherwise once per heartbeat; MEASURE is the primary feed.
 */
class NrStatusReporter(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var lastPath: String? = null
    @Volatile private var lastPostMs = 0L

    fun maybePost(snapshot: NrSnapshot) {
        if (!CollectorClient.nrStatusEnabled || snapshot.error != null) return
        val path = NrPathStatus.from(snapshot.verdict.label).wire
        val due = System.currentTimeMillis() - lastPostMs >= HEARTBEAT_MS
        if (path == lastPath && !due) return
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                val body = buildBody(snapshot).toString().toByteArray(StandardCharsets.UTF_8)
                val code = CollectorClient.post(CollectorClient.NR_STATUS_PATH, body)
                lastPath = path
                lastPostMs = System.currentTimeMillis()
                latest = NrReportStatus(code in 200..299, "HTTP $code · $path", lastPostMs)
            } catch (e: Exception) {
                Log.w(TAG, "nr-status post failed", e)
                latest = NrReportStatus(false, "error ${e.javaClass.simpleName}", System.currentTimeMillis())
            } finally {
                busy.set(false)
            }
        }
    }

    fun close() {
        executor.shutdown()
    }

    private fun buildBody(snapshot: NrSnapshot): JSONObject {
        val path = NrPathStatus.from(snapshot.verdict.label)
        val cells = snapshot.cells.filter { it.rat == "LTE" || it.rat == "NR" }
        val serving = cells.filter { it.registered }
        val neighbors = cells.filter { !it.registered }
        val plmnCell = serving.firstOrNull { it.mcc.isNotEmpty() && it.mnc.isNotEmpty() }
        val deviceId = CollectorClient.deviceId(context)
        return JSONObject()
            .put("schema_version", 1)
            .put("device_id", deviceId)
            .put("event_id", "nrs" + CollectorClient.randomHex(12))
            .put("sent_at_ms", snapshot.tsMs)
            .put("path_status", path.wire)
            .put("verdict", snapshot.verdict.summary)
            .put("plmn", plmnCell?.let { it.mcc + it.mnc } ?: JSONObject.NULL)
            .put("mcc", plmnCell?.mcc ?: JSONObject.NULL)
            .put("mnc", plmnCell?.mnc ?: JSONObject.NULL)
            .put("override_network_type", snapshot.overrideNetworkType.orNull())
            .put("override_label", NrLabels.override(snapshot.overrideNetworkType))
            .put("nr_state", snapshot.nrState.orNull())
            .put("nr_state_label", NrLabels.nrState(snapshot.nrState))
            .put("data_network_type", snapshot.dataNetworkType.orNull())
            .put("data_label", NrLabels.networkType(snapshot.dataNetworkType))
            .put("display_network_type", snapshot.displayNetworkType.orNull())
            .put("nr_frequency_range", snapshot.nrFrequencyRange.orNull())
            .put("serving_cells", JSONArray().also { arr -> serving.take(8).forEach { arr.put(cellJson(it)) } })
            .put("neighbor_cells", JSONArray().also { arr -> neighbors.take(12).forEach { arr.put(cellJson(it)) } })
    }

    private fun cellJson(cell: RadioCell): JSONObject = JSONObject().apply {
        put("rat", cell.rat)
        put("role", cell.role)
        put("registered", cell.registered)
        if (cell.mcc.isNotEmpty()) put("mcc", cell.mcc)
        if (cell.mnc.isNotEmpty()) put("mnc", cell.mnc)
        cell.pci?.let { put("pci", it) }
        cell.tac?.let { put("tac", it) }
        cell.ci.toLongOrNull()?.let { put("ci", it) }
        cell.earfcn?.let { put("earfcn", it) }
        cell.nrarfcn?.let { put("nrarfcn", it) }
        cell.bandwidthKhz?.let { put("bandwidth_khz", it) }
        if (cell.bands.isNotEmpty()) put("bands", JSONArray(cell.bands.take(16)))
        cell.rsrp?.let { put("rsrp", it) }
        cell.rsrq?.let { put("rsrq", it) }
        cell.sinr?.let { put("sinr", it) }
        cell.rssi?.let { put("rssi", it) }
        cell.ssRsrp?.let { put("ss_rsrp", it) }
        cell.ssRsrq?.let { put("ss_rsrq", it) }
        cell.ssSinr?.let { put("ss_sinr", it) }
    }

    companion object {
        private const val TAG = "NrStatusReporter"
        private const val HEARTBEAT_MS = 60_000L

        @Volatile
        var latest: NrReportStatus? = null
            private set
    }
}
