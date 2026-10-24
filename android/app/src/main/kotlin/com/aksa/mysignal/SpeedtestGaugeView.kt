package com.aksa.mysignal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Speed gauge patterned after Ookla's `com.ookla.mobile4.views.gauge.Gauge`
 * (static REA from ookla-speedtest.apk): 270° arc from 135°, SweepGradient fill,
 * equal-angle interval ticks (Mbps scale 0…1000), separate DL/UL palettes.
 */
class SpeedtestGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = TRACK
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TICK
        textAlign = Paint.Align.CENTER
    }
    private val tickMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = TICK
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = NEEDLE
    }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = NEEDLE
    }
    private val familyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = dp(14f)
    }
    private val colorV4 = ContextCompat.getColor(context, R.color.gateway_v4)
    private val colorV6 = ContextCompat.getColor(context, R.color.gateway_v6)
    private val arc = RectF()
    private var mbps = 0f
    private var phase = ""
    private var family = ""

    fun setReading(speedMbps: Double, phaseLabel: String, familyLabel: String = family) {
        val next = speedMbps.toFloat().coerceAtLeast(0f)
        val phaseChanged = !phase.equals(phaseLabel, ignoreCase = true)
        // New phase (esp. DOWNLOAD→UPLOAD): snap to reading so the needle returns to 0
        // before upload climbs. Same-phase upload still eases to avoid jitter.
        mbps = when {
            phaseChanged -> next
            phaseLabel.contains("UPLOAD", ignoreCase = true) && mbps > 0f ->
                mbps * 0.82f + next * 0.18f
            else -> next
        }
        phase = phaseLabel
        family = familyLabel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = dp(16f)
        track.strokeWidth = stroke
        fill.strokeWidth = stroke
        needle.strokeWidth = dp(3.5f)
        tickMark.strokeWidth = dp(2f)
        tick.textSize = dp(10f)

        // Keep the hub on the view center so it sits on the GO/STOP control.
        // The same inset above and below leaves the crown labels inside the view.
        val inset = stroke / 2f + dp(20f)
        val diameter = min(width - 2 * inset, height - 2 * inset).coerceAtLeast(0f)
        val left = (width - diameter) / 2f
        val top = (height - diameter) / 2f
        arc.set(left, top, left + diameter, top + diameter)

        val cx = arc.centerX()
        val cy = arc.centerY()
        val radius = arc.width() / 2f

        canvas.drawArc(arc, START_ANGLE, SWEEP, false, track)

        val frac = fractionForMbps(mbps)
        if (frac > 0f) {
            val colors = gradientForPhase(phase)
            // SweepGradient angles are absolute; rotate so fill tracks the 135° start.
            fill.shader = SweepGradient(cx, cy, colors, floatArrayOf(0f, 0.55f, 1f)).also {
                val matrix = android.graphics.Matrix()
                matrix.setRotate(START_ANGLE, cx, cy)
                it.setLocalMatrix(matrix)
            }
            canvas.drawArc(arc, START_ANGLE, SWEEP * frac, false, fill)
            fill.shader = null
        }

        TICKS.forEachIndexed { index, mark ->
            val t = index.toFloat() / (TICKS.lastIndex)
            val deg = START_ANGLE + SWEEP * t
            val rad = Math.toRadians(deg.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val outer = radius + stroke * 0.15f
            val inner = radius - stroke * 0.55f
            canvas.drawLine(
                cx + inner * cosA,
                cy + inner * sinA,
                cx + outer * cosA,
                cy + outer * sinA,
                tickMark,
            )
            val labelR = radius + dp(16f)
            canvas.drawText(
                formatTick(mark),
                cx + labelR * cosA,
                cy + labelR * sinA + dp(3.5f),
                tick,
            )
        }

        if (frac > 0f || phase.isNotEmpty()) {
            val deg = START_ANGLE + SWEEP * frac
            val rad = Math.toRadians(deg.toDouble())
            val inner = radius - stroke
            canvas.drawLine(
                cx,
                cy,
                cx + inner * cos(rad).toFloat(),
                cy + inner * sin(rad).toFloat(),
                needle,
            )
            canvas.drawCircle(cx, cy, dp(5f), hub)
        }

        if (family.isNotEmpty()) {
            familyPaint.color = when {
                family.contains("6", ignoreCase = true) -> colorV6
                else -> colorV4
            }
            familyPaint.textSize = dp(15f)
            // Bottom of the open 270° arc — clear of the centered GO/STOP control.
            canvas.drawText(family, cx, (cy + radius * 0.72f).coerceAtMost(height - dp(6f)), familyPaint)
        }
    }

    private fun formatTick(mark: Int): String = when {
        mark >= 1000 -> "1k"
        else -> mark.toString()
    }

    private fun gradientForPhase(phaseLabel: String): IntArray = when {
        phaseLabel.contains("UPLOAD", ignoreCase = true) ->
            intArrayOf(UPLOAD_0, UPLOAD_1, UPLOAD_1)
        else ->
            intArrayOf(DOWNLOAD_0, DOWNLOAD_1, DOWNLOAD_1)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        /** Matches Ookla Gauge: start 135°, sweep 270°. */
        private const val START_ANGLE = 135f
        private const val SWEEP = 270f

        /** Ookla `gauge_scale_1000_megabits` — equal angular slots, non-linear Mbps. */
        private val TICKS = intArrayOf(0, 5, 10, 50, 100, 250, 500, 750, 1000)

        private const val TRACK = 0xFF26263B.toInt()
        private const val TICK = 0xFF9193A8.toInt()
        private const val NEEDLE = 0xFFE7EEF7.toInt()
        private const val DOWNLOAD_0 = 0xFF01A3E5.toInt() // ookla_ui_blue
        private const val DOWNLOAD_1 = 0xFF19BF78.toInt() // ookla_ui_green
        private const val UPLOAD_0 = 0xFF6B3295.toInt()   // ookla_ui_purple
        private const val UPLOAD_1 = 0xFFE97BCE.toInt()   // ookla_ui_pink

        fun fractionForMbps(mbps: Float): Float {
            if (mbps <= 0f) return 0f
            val last = TICKS.last().toFloat()
            if (mbps >= last) return 1f
            for (i in 0 until TICKS.lastIndex) {
                val a = TICKS[i].toFloat()
                val b = TICKS[i + 1].toFloat()
                if (mbps <= b) {
                    val local = (mbps - a) / (b - a)
                    return (i + local) / TICKS.lastIndex
                }
            }
            return 1f
        }
    }
}
