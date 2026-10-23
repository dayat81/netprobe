package com.aksa.mysignal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Compact sparkline of gateway ping RTT (ms). */
class GatewayRttChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var samples: List<GatewayPinger.Sample> = emptyList()

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C2A3D.toInt()
        strokeWidth = dp(1f)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFBBF24.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FBBF24
        style = Paint.Style.FILL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA8B3C5.toInt()
        textSize = dp(10f)
    }
    private val miss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66F87171
        strokeWidth = dp(1.2f)
    }

    fun setSamples(values: List<GatewayPinger.Sample>) {
        samples = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val padL = dp(36f)
        val padR = dp(8f)
        val padT = dp(8f)
        val padB = dp(14f)
        val left = padL
        val right = width - padR
        val top = padT
        val bottom = height - padB
        if (right <= left || bottom <= top) return

        canvas.drawColor(0xFF0A1017.toInt())
        val rtts = samples.mapNotNull { it.rttMs }
        val maxRtt = max(20.0, (rtts.maxOrNull() ?: 20.0) * 1.15)
        val minRtt = 0.0

        for (i in 0..3) {
            val y = top + (bottom - top) * i / 3f
            canvas.drawLine(left, y, right, y, grid)
            val tick = maxRtt * (3 - i) / 3.0
            canvas.drawText(formatAxis(tick), dp(4f), y + dp(3f), label)
        }

        if (samples.isEmpty()) {
            canvas.drawText("gateway RTT", left + dp(8f), (top + bottom) / 2f, label)
            return
        }

        val path = Path()
        val fillPath = Path()
        var started = false
        val n = samples.size
        samples.forEachIndexed { index, sample ->
            val x = left + (right - left) * index / max(1, n - 1).toFloat()
            val rtt = sample.rttMs
            if (rtt == null) {
                canvas.drawLine(x, top + dp(2f), x, bottom - dp(2f), miss)
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
            canvas.drawPath(fillPath, fill)
            canvas.drawPath(path, line)
        }

        val latest = samples.lastOrNull()?.rttMs
        canvas.drawText(
            "gw rtt ${GatewayPinger.formatRtt(latest)}",
            left + dp(4f),
            height - dp(3f),
            label,
        )
    }

    private fun formatAxis(ms: Double): String =
        if (ms >= 100) "${ms.toInt()}" else String.format("%.0f", ms)

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
