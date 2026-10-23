package com.aksa.mysignal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

/** Compact dual sparkline of gateway ping RTT (ms) for IPv4 + IPv6. */
class GatewayRttChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    data class Point(val tMs: Long, val rtt4Ms: Double?, val rtt6Ms: Double?)

    private var samples: List<Point> = emptyList()

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
    private val miss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66F87171
        strokeWidth = dp(1.2f)
    }

    fun setSamples(values: List<Point>) {
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
        val rtts = samples.flatMap { listOfNotNull(it.rtt4Ms, it.rtt6Ms) }
        val maxRtt = max(20.0, (rtts.maxOrNull() ?: 20.0) * 1.15)
        val minRtt = 0.0

        for (i in 0..3) {
            val y = top + (bottom - top) * i / 3f
            canvas.drawLine(left, y, right, y, grid)
            val tick = maxRtt * (3 - i) / 3.0
            canvas.drawText(formatAxis(tick), dp(4f), y + dp(3f), label)
        }

        if (samples.isEmpty()) {
            canvas.drawText("gw4 / gw6 RTT", left + dp(8f), (top + bottom) / 2f, label)
            return
        }

        drawSeries(canvas, left, right, top, bottom, minRtt, maxRtt, { it.rtt4Ms }, fillV4, lineV4)
        drawSeries(canvas, left, right, top, bottom, minRtt, maxRtt, { it.rtt6Ms }, fillV6, lineV6)

        val latest4 = samples.lastOrNull()?.rtt4Ms
        val latest6 = samples.lastOrNull()?.rtt6Ms
        val yLabel = height - dp(3f)
        canvas.drawText("gw4 ${GatewayPinger.formatRtt(latest4)}", left + dp(4f), yLabel, labelV4)
        canvas.drawText(
            "gw6 ${GatewayPinger.formatRtt(latest6)}",
            left + dp(90f),
            yLabel,
            labelV6,
        )
    }

    private fun drawSeries(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        minRtt: Double,
        maxRtt: Double,
        pick: (Point) -> Double?,
        fillPaint: Paint,
        linePaint: Paint,
    ) {
        val path = Path()
        val fillPath = Path()
        var started = false
        val n = samples.size
        samples.forEachIndexed { index, sample ->
            val x = left + (right - left) * index / max(1, n - 1).toFloat()
            val rtt = pick(sample)
            if (rtt == null) {
                if (started) {
                    // break the stroke on miss; mark gap lightly
                    canvas.drawLine(x, top + dp(2f), x, bottom - dp(2f), miss)
                }
                started = false
                return@forEachIndexed
            }
            val y = bottom - ((rtt - minRtt) / (maxRtt - minRtt)).toFloat().coerceIn(0f, 1f) * (bottom - top)
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

    private fun formatAxis(ms: Double): String =
        if (ms >= 100) "${ms.toInt()}" else String.format("%.0f", ms)

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
