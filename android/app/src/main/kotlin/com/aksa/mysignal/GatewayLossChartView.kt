package com.aksa.mysignal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * Dual sparkline of rolling gateway ping packet loss (%) for IPv4 + IPv6.
 * Shares [GatewayRttChartView.Point] samples with the RTT chart below.
 */
class GatewayLossChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var samples: List<GatewayRttChartView.Point> = emptyList()

    private val colorV4 = ContextCompat.getColor(context, R.color.gateway_v4)
    private val colorV6 = ContextCompat.getColor(context, R.color.gateway_v6)

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C2A3D.toInt()
        strokeWidth = dp(1f)
    }
    private val lineV4 = strokePaint(colorV4)
    private val fillV4 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (colorV4 and 0x00FFFFFF) or 0x33000000
        style = Paint.Style.FILL
    }
    private val lineV6 = strokePaint(colorV6)
    private val fillV6 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (colorV6 and 0x00FFFFFF) or 0x28000000
        style = Paint.Style.FILL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA8B3C5.toInt()
        textSize = dp(10f)
    }
    private val labelV4 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorV4
        textSize = dp(10f)
    }
    private val labelV6 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorV6
        textSize = dp(10f)
    }

    fun setSamples(values: List<GatewayRttChartView.Point>) {
        samples = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val padL = dp(36f)
        val padR = dp(8f)
        val padT = dp(8f)
        val padB = dp(16f)
        val left = padL
        val right = width - padR
        val top = padT
        val bottom = height - padB
        if (right <= left || bottom <= top) return

        canvas.drawColor(0xFF0A1017.toInt())
        val maxLoss = 100.0
        val minLoss = 0.0

        for (i in 0..4) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, grid)
            val tick = maxLoss * (4 - i) / 4.0
            canvas.drawText("${tick.toInt()}%", dp(2f), y + dp(3f), label)
        }

        if (samples.isEmpty()) {
            canvas.drawText("gw4 / gw6 loss", left + dp(8f), (top + bottom) / 2f, label)
            return
        }

        val series4 = rollingLossPct(samples) { it.attempted4 to (it.rtt4Ms != null) }
        val series6 = rollingLossPct(samples) { it.attempted6 to (it.rtt6Ms != null) }
        drawSeries(canvas, left, right, top, bottom, minLoss, maxLoss, series4, fillV4, lineV4)
        drawSeries(canvas, left, right, top, bottom, minLoss, maxLoss, series6, fillV6, lineV6)

        val (lost4, att4) = countLoss(samples) { it.attempted4 to (it.rtt4Ms != null) }
        val (lost6, att6) = countLoss(samples) { it.attempted6 to (it.rtt6Ms != null) }
        val yLabel = height - dp(3f)
        canvas.drawText(
            "gw4 loss ${GatewayPinger.formatLoss(lost4, att4)}",
            left + dp(4f),
            yLabel,
            labelV4,
        )
        canvas.drawText(
            "gw6 loss ${GatewayPinger.formatLoss(lost6, att6)}",
            left + dp(110f),
            yLabel,
            labelV6,
        )
    }

    private fun rollingLossPct(
        values: List<GatewayRttChartView.Point>,
        pick: (GatewayRttChartView.Point) -> Pair<Boolean, Boolean>,
    ): List<Double?> {
        val out = ArrayList<Double?>(values.size)
        for (i in values.indices) {
            val from = max(0, i - WINDOW + 1)
            var attempted = 0
            var lost = 0
            for (j in from..i) {
                val (att, ok) = pick(values[j])
                if (!att) continue
                attempted++
                if (!ok) lost++
            }
            out.add(if (attempted > 0) lost * 100.0 / attempted else null)
        }
        return out
    }

    private fun countLoss(
        values: List<GatewayRttChartView.Point>,
        pick: (GatewayRttChartView.Point) -> Pair<Boolean, Boolean>,
    ): Pair<Int, Int> {
        var attempted = 0
        var lost = 0
        for (p in values) {
            val (att, ok) = pick(p)
            if (!att) continue
            attempted++
            if (!ok) lost++
        }
        return lost to attempted
    }

    private fun drawSeries(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        minY: Double,
        maxY: Double,
        series: List<Double?>,
        fillPaint: Paint,
        linePaint: Paint,
    ) {
        val path = Path()
        val fillPath = Path()
        var started = false
        val n = series.size
        series.forEachIndexed { index, value ->
            val x = left + (right - left) * index / max(1, n - 1).toFloat()
            if (value == null) {
                started = false
                return@forEachIndexed
            }
            val y = bottom - ((value - minY) / (maxY - minY)).toFloat().coerceIn(0f, 1f) * (bottom - top)
            if (!started) {
                path.moveTo(x, y)
                fillPath.moveTo(x, bottom)
                fillPath.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        if (started) {
            val lastX = left + (right - left) * (n - 1) / max(1, n - 1).toFloat()
            fillPath.lineTo(lastX, bottom)
            fillPath.close()
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(path, linePaint)
        }
    }

    private fun strokePaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        /** Sliding window for instantaneous loss % at each sample. */
        private const val WINDOW = 20
    }
}
