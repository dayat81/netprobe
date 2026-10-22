package com.aksa.mysignal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Full-screen native radio chart: Transport, CA, nrState, RSRP, SINR, Carrier, eNB. */
class RadioChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var points: List<ChartPoint> = emptyList()
    private var windowMin = 10
    private var plmnFilter: String? = null
    private var ratFilter: String? = null
    private var rowScroll = 0f
    private var lowerTop = 0f
    private var lowerBottom = 0f
    private var lowerContent = 0f
    private var touchY = 0f
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val plot = RectF()

    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7C8FA3.toInt(); textSize = dp(8f) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6EDF5.toInt()
        textSize = dp(11f)
        isFakeBoldText = true
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7C8FA3.toInt(); textSize = dp(8f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.4f) }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF182430.toInt(); strokeWidth = dp(1f) }
    private val plotPaint = Paint().apply { color = 0xFF0A1017.toInt() }

    fun setData(samples: List<ChartPoint>, minutes: Int, plmn: String? = null, rat: String? = null) {
        points = samples
        windowMin = minutes
        plmnFilter = plmn
        ratFilter = rat
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val span = max(0f, lowerContent - (lowerBottom - lowerTop))
        if (span <= 0f) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y < lowerTop || event.y > lowerBottom) return false
                touchY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                rowScroll = (rowScroll + touchY - event.y).coerceIn(0f, span)
                touchY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF080C11.toInt())
        val now = points.lastOrNull()?.t ?: System.currentTimeMillis()
        val t0 = now - windowMin * 60_000L
        val shown = points.filter { it.t in t0..now + 1_000L }
        if (shown.isEmpty()) {
            label.textAlign = Paint.Align.LEFT
            canvas.drawText("Menunggu sampel…", dp(16f), dp(40f), label)
            return
        }
        val cells = shown.flatMap { sample -> sample.cells.map { it to sample.t } }
            .filter { plmnFilter == null || it.first.plmn == plmnFilter }
            .filter { ratFilter == null || it.first.rat == ratFilter }
        val plmnCount = cells.map { it.first.plmn }.filter { it.isNotEmpty() }.distinct().size
        val showPlmn = plmnFilter == null && (ratFilter == "NR" || plmnCount > 1)
        val carriers = cells.map { it.first.key() }.distinct().sorted()
        val colors = carriers.mapIndexed { index, key -> key to PAL[index % PAL.size] }.toMap()
        val enbs = cells.mapNotNull { (cell, _) ->
            val ci = cell.ci ?: return@mapNotNull null
            RadioChartLabels.siteKey(cell.rat, ci, cell.plmn)
        }.distinct().sorted()

        var labelW = dp(56f)
        carriers.forEach { labelW = max(labelW, axis.measureText(RadioChartLabels.carrierLabel(it, showPlmn))) }
        enbs.forEach { labelW = max(labelW, axis.measureText(RadioChartLabels.siteLabel(it, showPlmn))) }
        shown.lastOrNull()?.let { latest ->
            labelW = max(labelW, sub.measureText(RadioChartLabels.slotLabel(shownPlmn(latest)).ifEmpty { "—" }))
            val khz = bandwidthsFor(latest)
            if (khz.isNotEmpty()) labelW = max(labelW, sub.measureText(RadioRules.mhzLabel(khz)))
        }
        val padL = min(labelW + dp(8f), width * 0.30f).coerceAtLeast(dp(64f))
        val padR = dp(4f)
        val padT = dp(6f)
        val padB = dp(16f)
        plot.set(padL, padT, width - padR, height - padB)
        val rowH = dp(ROW_DP)
        val carrierH = dp(LANE_HEAD_DP) + rowH * max(carriers.size, 1)
        val enbH = dp(LANE_HEAD_DP) + rowH * max(enbs.size, 1)
        val gap = dp(8f)
        val lowerNeeded = carrierH + gap + enbH
        val lowerView = min(lowerNeeded, max(dp(88f), plot.height() * 0.28f))
        lowerTop = plot.bottom - lowerView
        lowerBottom = plot.bottom
        lowerContent = lowerNeeded
        rowScroll = rowScroll.coerceIn(0f, max(0f, lowerNeeded - lowerView))
        val upperWeights = floatArrayOf(36f, 52f, 72f, 140f, 120f)
        val upperInner = max(0f, lowerTop - plot.top - gap * upperWeights.size)
        val upperSum = upperWeights.sum()
        val lanes = FloatArray(7)
        val heights = FloatArray(7)
        var y = plot.top
        upperWeights.forEachIndexed { i, w ->
            val h = upperInner * (w / upperSum)
            lanes[i] = y
            heights[i] = h
            y += h + gap
        }
        lanes[5] = lowerTop
        heights[5] = carrierH
        lanes[6] = lowerTop + carrierH + gap
        heights[6] = enbH
        canvas.drawRoundRect(plot, dp(6f), dp(6f), plotPaint)

        val xOf = { t: Long ->
            val f = ((t - t0).toFloat() / max(now - t0, 1L)).coerceIn(0f, 1f)
            plot.left + f * plot.width()
        }
        drawTimeAxis(canvas, t0, now, xOf)
        drawBands(canvas, shown, lanes[0], heights[0], xOf)
        drawCa(canvas, shown, lanes[1], heights[1], xOf)
        drawNr(canvas, shown, lanes[2], heights[2], xOf)
        drawSeries(canvas, cells, carriers, colors, lanes[3], heights[3], xOf, true)
        drawSeries(canvas, cells, carriers, colors, lanes[4], heights[4], xOf, false)
        canvas.save()
        canvas.clipRect(0f, lowerTop, width.toFloat(), lowerBottom)
        canvas.translate(0f, -rowScroll)
        drawRows(canvas, carriers, lanes[5], heights[5], xOf) { key, cy ->
            axis.color = colors[key] ?: 0xFF94A3B8.toInt()
            axis.textAlign = Paint.Align.RIGHT
            canvas.drawText(RadioChartLabels.carrierLabel(key, showPlmn), plot.left - dp(4f), cy + dp(3f), axis)
            cells.filter { it.first.key() == key }.forEach { (cell, t) ->
                mark(canvas, xOf(t), cy, colors[key] ?: axis.color, cell.held)
            }
        }
        drawRows(canvas, enbs, lanes[6], heights[6], xOf) { key, cy ->
            val plmn = RadioChartLabels.plmnOfSite(key)
            axis.color = PLCOL[RadioChartLabels.plmnOf(plmn)] ?: 0xFF94A3B8.toInt()
            axis.textAlign = Paint.Align.RIGHT
            canvas.drawText(RadioChartLabels.siteLabel(key, showPlmn), plot.left - dp(4f), cy + dp(3f), axis)
            cells.filter { (cell, _) ->
                cell.ci != null && RadioChartLabels.siteKey(cell.rat, cell.ci, cell.plmn) == key
            }.forEach { (cell, t) ->
                mark(canvas, xOf(t), cy, colors[cell.key()] ?: axis.color, cell.held)
            }
        }
        label.textAlign = Paint.Align.LEFT
        label.color = 0xFFE6EDF5.toInt()
        listOf("Carrier", "eNB").forEachIndexed { i, name ->
            canvas.drawText(name, dp(6f), lanes[i + 5] + dp(12f), label)
        }
        canvas.restore()
        listOf("Transport", "CA", "nrState", "RSRP", "SINR").forEachIndexed { i, name ->
            canvas.drawText(name, dp(6f), lanes[i] + dp(12f), label)
        }
        sub.textAlign = Paint.Align.LEFT
        canvas.drawText("0 · 2 · 3", dp(6f), lanes[2] + dp(23f), sub)
        shown.lastOrNull()?.let { latest ->
            drawCaLines(canvas, latest, dp(6f), lanes[1] + dp(22f), sub)
        }
    }

    private fun drawTimeAxis(canvas: Canvas, t0: Long, t1: Long, xOf: (Long) -> Float) {
        val span = (t1 - t0) / 1000
        val step = when {
            span > 900 -> 120_000L
            span > 360 -> 60_000L
            else -> 30_000L
        }
        axis.color = 0xFF7C8FA3.toInt()
        axis.textAlign = Paint.Align.CENTER
        var t = ceil(t0.toDouble() / step).toLong() * step
        while (t <= t1) {
            canvas.drawText(timeFmt.format(Date(t)), xOf(t), height - dp(3f), axis)
            t += step
        }
    }

    private fun drawBands(canvas: Canvas, shown: List<ChartPoint>, top: Float, height: Float, xOf: (Long) -> Float) {
        runs(shown.indices.toList()) { i, j -> shown[j].transport == shown[i].transport && shown[j].t - shown[j - 1].t <= 30_000L }
            .forEach { (i, j) ->
                val color = when (shown[i].transport) {
                    "wifi" -> 0xFF34D399.toInt()
                    "cellular" -> 0xFFF59E0B.toInt()
                    else -> 0xFF64748B.toInt()
                }
                val xa = edge(shown, i, j, xOf, true)
                val xb = edge(shown, i, j, xOf, false)
                fill.color = color
                fill.alpha = 56
                canvas.drawRoundRect(xa, top + dp(4f), max(xb, xa + dp(2f)), top + height - dp(2f), dp(3f), dp(3f), fill)
                fill.alpha = 255
                if (xb - xa > dp(64f)) {
                    axis.color = color
                    axis.textAlign = Paint.Align.LEFT
                    val mbps = if (shown[i].downKbps > 0) " ${shown[i].downKbps / 1000}/${shown[i].upKbps / 1000}" else ""
                    canvas.drawText(shown[i].transport + mbps, xa + dp(4f), top + height / 2 + dp(3f), axis)
                }
            }
    }

    private fun drawCa(canvas: Canvas, shown: List<ChartPoint>, top: Float, height: Float, xOf: (Long) -> Float) {
        val rows = shown
        runs(rows.indices.toList()) { i, j ->
            caFor(rows[j]) == caFor(rows[i]) &&
                shownPlmn(rows[j]) == shownPlmn(rows[i]) &&
                bandwidthsFor(rows[j]) == bandwidthsFor(rows[i]) &&
                rows[j].t - rows[j - 1].t <= 30_000L
        }.forEach { (i, j) ->
            val on = caFor(rows[i]) == true
            val color = if (on) 0xFF34D399.toInt() else 0xFF64748B.toInt()
            val xa = if (i == 0) xOf(rows[i].t) else (xOf(rows[i].t) + xOf(rows[i - 1].t)) / 2f
            val xb = if (j == rows.lastIndex) min(xOf(rows[j].t) + dp(3f), plot.right) else (xOf(rows[j].t) + xOf(rows[j + 1].t)) / 2f
            fill.color = color
            fill.alpha = if (on) 60 else 40
            canvas.drawRoundRect(xa, top + dp(4f), max(xb, xa + dp(2f)), top + height - dp(2f), dp(3f), dp(3f), fill)
            fill.alpha = 255
            if (xb - xa > dp(72f)) {
                axis.color = color
                axis.textAlign = Paint.Align.LEFT
                drawCaLines(canvas, rows[i], xa + dp(4f), top + height / 2f - dp(4f), axis)
            }
        }
    }

    private fun drawNr(canvas: Canvas, shown: List<ChartPoint>, top: Float, height: Float, xOf: (Long) -> Float) {
        val yy = { v: Int -> top + height - dp(6f) - (v + 0.4f) / 3.8f * (height - dp(28f)) }
        axis.color = 0xFF7C8FA3.toInt()
        axis.textAlign = Paint.Align.RIGHT
        listOf(0, 2, 3).forEach { canvas.drawText(it.toString(), plot.left - dp(4f), yy(it) + dp(3f), axis) }
        val rows = shown.filter { it.nr != null }
        rows.forEachIndexed { index, point ->
            val prev = rows.getOrNull(index - 1)
            val color = nrColor(point.nr)
            if (prev?.nr != null && point.t - prev.t <= 20_000L) {
                line.color = nrColor(prev.nr)
                line.strokeWidth = dp(3f)
                canvas.drawLine(xOf(prev.t), yy(prev.nr!!), xOf(point.t), yy(prev.nr), line)
                if (point.nr != prev.nr) canvas.drawLine(xOf(point.t), yy(prev.nr), xOf(point.t), yy(point.nr!!), line)
            }
            fill.color = color
            val y = yy(point.nr!!)
            canvas.drawRect(xOf(point.t) - dp(1.5f), y - dp(1.5f), xOf(point.t) + dp(1.5f), y + dp(1.5f), fill)
        }
    }

    private fun drawSeries(
        canvas: Canvas,
        cells: List<Pair<ChartCell, Long>>,
        carriers: List<String>,
        colors: Map<String, Int>,
        top: Float,
        height: Float,
        xOf: (Long) -> Float,
        rsrp: Boolean,
    ) {
        val values = cells.mapNotNull { if (rsrp) it.first.rsrp else it.first.sinr }
        val lo: Float
        val hi: Float
        if (rsrp) {
            if (values.isEmpty()) return
            lo = floor((values.min() - 3) / 5f) * 5f
            hi = ceil((values.max() + 3) / 5f) * 5f
        } else {
            val lim = max(5f, ceil(max(values.maxOfOrNull { kotlin.math.abs(it) } ?: 0, 0) / 5f) * 5f)
            lo = -lim
            hi = lim
        }
        val yy = { v: Float -> top + height - dp(4f) - (v - lo) / max(hi - lo, 1f) * (height - dp(24f)) }
        axis.color = 0xFF7C8FA3.toInt()
        axis.textAlign = Paint.Align.RIGHT
        for (step in 0..4) {
            val v = lo + (hi - lo) * step / 4f
            canvas.drawText(v.toInt().toString(), plot.left - dp(4f), yy(v) + dp(3f), axis)
        }
        carriers.forEach { key ->
            val arr = cells.filter { it.first.key() == key && (if (rsrp) it.first.rsrp else it.first.sinr) != null }
                .sortedBy { it.second }
            line.color = colors[key] ?: 0xFF94A3B8.toInt()
            line.strokeWidth = dp(1.3f)
            arr.zipWithNext().forEach { (a, b) ->
                if (b.second - a.second <= 15_000L) {
                    val ya = if (rsrp) a.first.rsrp!! else a.first.sinr!!
                    val yb = if (rsrp) b.first.rsrp!! else b.first.sinr!!
                    canvas.drawLine(xOf(a.second), yy(ya.toFloat()), xOf(b.second), yy(yb.toFloat()), line)
                }
            }
            arr.forEach { (cell, t) ->
                val y = yy(((if (rsrp) cell.rsrp else cell.sinr)!!).toFloat())
                mark(canvas, xOf(t), y, line.color, cell.held)
            }
        }
    }

    private fun drawRows(
        canvas: Canvas,
        keys: List<String>,
        top: Float,
        height: Float,
        xOf: (Long) -> Float,
        paint: (String, Float) -> Unit,
    ) {
        if (keys.isEmpty()) return
        val rowH = dp(ROW_DP)
        keys.forEachIndexed { index, key ->
            val cy = top + dp(LANE_HEAD_DP) + index * rowH + rowH / 2f
            grid.strokeWidth = dp(10f)
            canvas.drawLine(plot.left, cy, plot.right, cy, grid)
            paint(key, cy)
        }
    }

    private fun edge(shown: List<ChartPoint>, i: Int, j: Int, xOf: (Long) -> Float, start: Boolean): Float {
        return if (start) {
            if (i == 0) xOf(shown[i].t) else (xOf(shown[i].t) + xOf(shown[i - 1].t)) / 2f
        } else if (j == shown.lastIndex) {
            min(xOf(shown[j].t) + dp(3f), plot.right)
        } else {
            (xOf(shown[j].t) + xOf(shown[j + 1].t)) / 2f
        }
    }

    private fun runs(indexes: List<Int>, cont: (Int, Int) -> Boolean): List<Pair<Int, Int>> {
        if (indexes.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < indexes.size) {
            var j = i
            while (j + 1 < indexes.size && cont(i, j + 1)) j++
            out += i to j
            i = j + 1
        }
        return out
    }

    /** Primary bandwidths when every PLMN is shown. The selected PLMN's list otherwise. */
    private fun bandwidthsFor(point: ChartPoint): List<Int> {
        val selected = plmnFilter ?: return point.bandwidths
        return point.bandwidthsByPlmn[selected].orEmpty()
    }

    private fun caFor(point: ChartPoint): Boolean? {
        val selected = plmnFilter ?: return point.ca
        return point.caByPlmn[selected]
    }

    private fun shownPlmn(point: ChartPoint): String = plmnFilter ?: point.activePlmn

    private fun drawCaLines(canvas: Canvas, point: ChartPoint, x: Float, y: Float, paint: Paint) {
        val plmn = RadioChartLabels.slotLabel(shownPlmn(point)).ifEmpty { "—" }
        canvas.drawText(plmn, x, y, paint)
        val khz = bandwidthsFor(point)
        if (khz.isNotEmpty()) canvas.drawText(RadioRules.mhzLabel(khz), x, y + dp(10f), paint)
    }

    /** Filled square for a live modem sample. Open circle when the NR value is held. */
    private fun mark(canvas: Canvas, x: Float, y: Float, color: Int, held: Boolean) {
        fill.color = color
        if (held) {
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = dp(1.3f)
            canvas.drawCircle(x, y, dp(2.4f), fill)
            fill.style = Paint.Style.FILL
        } else {
            val r = dp(1.4f)
            canvas.drawRect(x - r, y - r, x + r, y + r, fill)
        }
    }

    private fun nrColor(value: Int?) = when (value) {
        3 -> 0xFF34D399.toInt()
        2 -> 0xFFFBBF24.toInt()
        else -> 0xFF64748B.toInt()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun ChartCell.key() = RadioChartLabels.carrierKey(plmn, rat, earfcn)

    companion object {
        private const val ROW_DP = 22f
        private const val LANE_HEAD_DP = 20f
        private val PAL = intArrayOf(0xFF38BDF8.toInt(), 0xFFA78BFA.toInt(), 0xFFFB7185.toInt(), 0xFF34D399.toInt(), 0xFFF59E0B.toInt(), 0xFF94A3B8.toInt())
        private val PLCOL = mapOf("510-01" to 0xFF38BDF8.toInt(), "510-11" to 0xFFFB7185.toInt())
    }
}
