package com.aksa.magnon

import android.content.Context
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

data class TimelineEntry(val tsMs: Long, val text: String)

data class ChartCell(
    val plmn: String,
    val rat: String,
    val earfcn: Int,
    val rsrp: Int?,
    val sinr: Int?,
    val ci: Long?,
    /** True when this NR point repeats the last modem report while nrState stays 3. */
    val held: Boolean = false,
)

data class ChartPoint(
    val t: Long,
    val transport: String,
    val downKbps: Int,
    val upKbps: Int,
    val ca: Boolean?,
    val activePlmn: String = "",
    val caByPlmn: Map<String, Boolean?> = emptyMap(),
    val bandwidths: List<Int>,
    val bandwidthsByPlmn: Map<String, List<Int>> = emptyMap(),
    val nr: Int?,
    val operator: String,
    val cells: List<ChartCell>,
)

/** Latest sampler output, observed by the activity, widget and notification. Owned by [RadioService]. */
object RadioState {
    private const val PREFS = "mysignal_settings"
    private const val KEY_ENABLED = "sampling_enabled"
    private const val MAX_TIMELINE = 120

    @Volatile var latest: RadioSnapshot? = null
        private set
    @Volatile var samples = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    private val listeners = CopyOnWriteArrayList<(RadioSnapshot) -> Unit>()
    private val timeline = CopyOnWriteArrayList<TimelineEntry>()
    private val chart = ArrayDeque<ChartPoint>()
    @Volatile private var lastKey: String? = null
    private var heldNr: List<ChartCell> = emptyList()
    private var heldNrPlmn: String = ""

    fun publish(snapshot: RadioSnapshot) {
        latest = snapshot
        samples += 1
        lastError = snapshot.error
        recordChange(snapshot)
        recordChart(snapshot)
        listeners.forEach { it(snapshot) }
    }

    /** Last 30 minutes of local samples for the full-screen chart. */
    fun chartPoints(): List<ChartPoint> = synchronized(chart) { chart.toList() }

    fun fail(message: String) {
        lastError = message
    }

    fun addListener(listener: (RadioSnapshot) -> Unit) {
        listeners += listener
        latest?.let(listener)
    }

    fun removeListener(listener: (RadioSnapshot) -> Unit) {
        listeners -= listener
    }

    fun timeline(): List<TimelineEntry> = timeline.toList()

    fun samplingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setSamplingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Change-only history of the data SIM: verdict, nrState, CA and serving channel. */
    private fun recordChange(snapshot: RadioSnapshot) {
        val sub = snapshot.primary ?: return
        val text = "${sub.plmn.ifEmpty { "-" }} ${sub.verdict.summary} " +
            "nr=${NrLabels.nrState(sub.nrState)}(${sub.nrStateSource}) " +
            "ca=${sub.ca ?: "?"} ${NrLabels.networkType(sub.networkType)} ch=${sub.channel ?: "?"}"
        if (text == lastKey) return
        lastKey = text
        timeline += TimelineEntry(snapshot.tsMs, text)
        while (timeline.size > MAX_TIMELINE) timeline.removeAt(0)
    }

    private fun recordChart(snapshot: RadioSnapshot) {
        val sub = snapshot.primary
        val activePlmn = if (sub == null) "" else radioId(sub)
        if (activePlmn != heldNrPlmn) {
            heldNr = emptyList()
            heldNrPlmn = activePlmn
        }
        val owners = snapshot.subscriptions.mapNotNull { radio -> radio.subId?.let { it to radio } }.toMap()
        val servingByPlmn = snapshot.subscriptions.mapNotNull { radio ->
            val channel = radio.channel ?: return@mapNotNull null
            radioId(radio).takeIf { it.isNotEmpty() }?.let { it to channel }
        }.toMap()
        val cells = ArrayList<ChartCell>()
        val nrOnActive = sub?.nrState == 3 && activePlmn.isNotEmpty()
        snapshot.cells.forEach { cell ->
            if (!cell.chartServing || (cell.rat != "LTE" && cell.rat != "NR")) return@forEach
            if (cell.rat == "NR" && nrOnActive) return@forEach
            val plmn = cellId(cell, owners)
            val raw = if (cell.rat == "NR") cell.nrarfcn else cell.earfcn
            if (plmn.isEmpty() || raw == null) return@forEach
            val serving = servingByPlmn[plmn]
            if (cell.rat == "LTE" && serving != null && raw != serving) return@forEach
            putCell(
                cells,
                ChartCell(
                    plmn,
                    cell.rat,
                    raw,
                    cell.rsrp,
                    cell.sinr,
                    cell.ci.toLongOrNull()?.takeIf { it in 1..268_435_455 },
                ),
            )
        }
        servingByPlmn.forEach { (plmn, channel) ->
            if (cells.any { it.plmn == plmn && it.rat == "LTE" && it.earfcn == channel }) return@forEach
            val donor = snapshot.cells.firstOrNull { cell ->
                cell.rat == "LTE" && cell.chartServing && cellId(cell, owners) == plmn &&
                    (cell.rsrp != null || cell.sinr != null)
            }
            putCell(
                cells,
                ChartCell(
                    plmn,
                    "LTE",
                    channel,
                    donor?.rsrp,
                    donor?.sinr,
                    donor?.ci?.toLongOrNull()?.takeIf { it in 1..268_435_455 },
                ),
            )
        }
        if (nrOnActive) {
            val nrCells = snapshot.cells.filter { it.rat == "NR" && it.chartServing && it.nrarfcn != null }
            val own = nrCells.filter { it.subId != null && it.subId == sub?.subId }
            (if (own.isNotEmpty()) own else nrCells).forEach { cell ->
                putCell(
                    cells,
                    ChartCell(
                        activePlmn,
                        "NR",
                        cell.nrarfcn!!,
                        cell.rsrp,
                        cell.sinr,
                        cell.ci.toLongOrNull()?.takeIf { it in 1..268_435_455 },
                    ),
                )
            }
        }
        val nrNow = cells.filter { it.rat == "NR" }
        if (nrNow.isNotEmpty()) {
            heldNr = nrNow
        } else if (sub?.nrState == 3) {
            heldNr.filter { activePlmn.isEmpty() || it.plmn == activePlmn }.forEach { held ->
                if (cells.none { it.rat == "NR" && it.plmn == held.plmn && it.earfcn == held.earfcn }) {
                    cells += held.copy(held = true)
                }
            }
        } else {
            heldNr = emptyList()
        }
        snapshot.subscriptions.forEach { radio ->
            val plmn = radioId(radio)
            if (plmn.isEmpty()) return@forEach
            radio.carriers.forEach { carrier ->
                if (carrier.rat != "LTE" && carrier.rat != "NR") return@forEach
                val channel = carrier.channel ?: return@forEach
                if (carrier.rat == "NR" && nrOnActive && plmn != activePlmn) {
                    putCell(cells, ChartCell(activePlmn, "NR", channel, null, null, null))
                    return@forEach
                }
                if (carrier.rat == "LTE" && radio.channel != null && channel != radio.channel &&
                    carrier.status != ComponentCarrier.STATUS_SECONDARY
                ) {
                    return@forEach
                }
                if (cells.any { it.plmn == plmn && it.rat == carrier.rat && it.earfcn == channel }) return@forEach
                cells += ChartCell(plmn, carrier.rat, channel, null, null, null)
            }
        }
        val bandwidthsByPlmn = bandwidthsByPlmn(snapshot)
        val bandwidths = cellBandwidths(snapshot)
        val point = ChartPoint(
            t = snapshot.tsMs,
            transport = snapshot.network.transport,
            downKbps = snapshot.network.downstreamKbps,
            upKbps = snapshot.network.upstreamKbps,
            ca = sub?.ca,
            activePlmn = activePlmn,
            caByPlmn = snapshot.subscriptions.mapNotNull { radio ->
                radioId(radio).takeIf { it.isNotEmpty() }?.let { it to radio.ca }
            }.toMap(),
            bandwidths = bandwidths,
            bandwidthsByPlmn = bandwidthsByPlmn,
            nr = sub?.nrState,
            operator = sub?.let { it.mcc + it.mnc }.orEmpty(),
            cells = cells,
        )
        synchronized(chart) {
            chart.addLast(point)
            val cutoff = snapshot.tsMs - CHART_WINDOW_MS
            while (chart.size > CHART_MAX || (chart.isNotEmpty() && chart.first().t < cutoff)) {
                chart.removeFirst()
            }
        }
    }

    private fun bandwidthsByPlmn(snapshot: RadioSnapshot): Map<String, List<Int>> {
        val owners = snapshot.subscriptions.mapNotNull { radio -> radio.subId?.let { it to radio } }.toMap()
        val out = LinkedHashMap<String, List<Int>>()
        snapshot.subscriptions.forEach { radio ->
            val plmn = radioId(radio)
            if (plmn.isEmpty()) return@forEach
            val widths = radio.cellBandwidthKhz.ifEmpty {
                snapshot.cells.filter { it.chartServing && cellId(it, owners) == plmn }.mapNotNull { it.bandwidthKhz }
            }
            if (widths.isNotEmpty()) out[plmn] = widths
        }
        return out
    }

    private fun radioId(radio: SubscriptionRadio): String = RadioChartLabels.slotId(radio.plmn, radio.slot)

    private fun cellId(cell: RadioCell, owners: Map<Int, SubscriptionRadio>): String {
        val owner = cell.subId?.let { owners[it] }
        val plmn = when {
            cell.mcc.isNotEmpty() && cell.mnc.isNotEmpty() -> "${cell.mcc}-${cell.mnc}"
            owner != null && owner.plmn.isNotEmpty() -> owner.plmn
            else -> ""
        }
        val slot = cell.simSlot ?: owner?.slot ?: -1
        return RadioChartLabels.slotId(plmn, slot)
    }

    /** Keep one point per PLMN, RAT and channel. Prefer the sample that has a signal. */
    private fun putCell(cells: ArrayList<ChartCell>, cell: ChartCell) {
        val index = cells.indexOfFirst { it.plmn == cell.plmn && it.rat == cell.rat && it.earfcn == cell.earfcn && it.held == cell.held }
        if (index < 0) {
            cells += cell
            return
        }
        val previous = cells[index]
        if (previous.rsrp == null && cell.rsrp != null) cells[index] = cell
    }

    private fun cellBandwidths(snapshot: RadioSnapshot): List<Int> {
        val fromService = snapshot.primary?.cellBandwidthKhz.orEmpty()
        if (fromService.isNotEmpty()) return fromService
        return snapshot.cells.filter { it.chartServing }.mapNotNull { it.bandwidthKhz }
    }

    private const val CHART_WINDOW_MS = 30L * 60L * 1000L
    private const val CHART_MAX = 700
}
