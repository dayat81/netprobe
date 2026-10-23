package com.aksa.mysignal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Ookla-like semicircle gauge. Center label is drawn by the overlay TextView. */
class SpeedtestGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF1E2D42.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7C8FA3.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF38BDF8.toInt()
    }
    private val arc = RectF()
    private var mbps = 0f
    private var phase = ""

    fun setReading(speedMbps: Double, phaseLabel: String) {
        mbps = speedMbps.toFloat().coerceAtLeast(0f)
        phase = phaseLabel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = dp(18f)
        track.strokeWidth = stroke
        fill.strokeWidth = stroke
        needle.strokeWidth = dp(3f)
        tick.textSize = dp(10f)
        val pad = stroke / 2f + dp(18f)
        val size = min(width.toFloat(), height.toFloat() * 1.55f)
        val left = (width - size) / 2f + pad
        val top = height * 0.18f
        arc.set(left, top, left + size - 2 * pad, top + size - 2 * pad)
        canvas.drawArc(arc, 180f, 180f, false, track)
        val frac = (mbps / SCALE).coerceIn(0f, 1f)
        if (frac > 0f) {
            fill.shader = SweepGradient(
                arc.centerX(),
                arc.centerY(),
                intArrayOf(0xFF0EA5E9.toInt(), 0xFF22C55E.toInt(), 0xFFF59E0B.toInt()),
                floatArrayOf(0.5f, 0.75f, 1f),
            )
            canvas.drawArc(arc, 180f, 180f * frac, false, fill)
            fill.shader = null
        }
        listOf(0, 50, 100, 250, 500, 750).forEach { mark ->
            val t = mark / SCALE
            val angle = Math.PI - t * Math.PI
            val r = arc.width() / 2f
            val x = arc.centerX() + (r + dp(14f)) * cos(angle).toFloat()
            val y = arc.centerY() - (r + dp(14f)) * sin(angle).toFloat() + dp(4f)
            canvas.drawText(mark.toString(), x, y, tick)
        }
        val angle = Math.PI - frac * Math.PI
        val inner = arc.width() / 2f - stroke
        val nx = arc.centerX() + inner * cos(angle).toFloat()
        val ny = arc.centerY() - inner * sin(angle).toFloat()
        canvas.drawLine(arc.centerX(), arc.centerY(), nx, ny, needle)
        if (phase.isNotEmpty()) {
            tick.textSize = dp(11f)
            canvas.drawText(phase, arc.centerX(), arc.centerY() + dp(36f), tick)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val SCALE = 750f
    }
}
