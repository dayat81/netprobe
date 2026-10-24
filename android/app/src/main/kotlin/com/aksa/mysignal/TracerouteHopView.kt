package com.aksa.mysignal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Dual horizontal traceroute paths (IPv4 / IPv6) to the speedtest server.
 * Unknown (`*`) hops are skipped. Each node shows the full IP and raw hop RTT.
 */
class TracerouteHopView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class HopNode(
        val ttl: Int,
        val host: String,
        val rttMs: Double?,
    )

    private var hops4: List<HopNode> = emptyList()
    private var hops6: List<HopNode> = emptyList()
    private var status: String? = null

    private val colorV4 = ContextCompat.getColor(context, R.color.gateway_v4)
    private val colorV6 = ContextCompat.getColor(context, R.color.gateway_v6)
    private val colorMuted = 0xFF6B7280.toInt()
    private val colorBg = 0xFF0A1017.toInt()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(9f)
        color = 0xFFA8B3C5.toInt()
    }
    private val ipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(9f)
    }
    private val rttPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        isFakeBoldText = true
    }
    private val stackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f)
        isFakeBoldText = true
    }

    fun setHops(v4: List<HopNode>, v6: List<HopNode>, statusText: String? = null) {
        hops4 = v4
        hops6 = v6
        status = statusText
        requestLayout()
        invalidate()
    }

    fun setLoading() {
        status = "tracing…"
        hops4 = emptyList()
        hops6 = emptyList()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hopW = hopSlotWidth()
        val count = maxOf(hops4.size, hops6.size, 1)
        val contentW = (dp(28f) + count * hopW + dp(12f)).roundToInt()
        val wantH = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST ->
                MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(dp(120f).roundToInt())
            else -> dp(160f).roundToInt()
        }
        setMeasuredDimension(contentW, wantH)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colorBg)
        val padT = dp(8f)
        val padB = dp(8f)
        val labelW = dp(26f)
        val mid = height / 2f
        val row1Y = padT + (mid - padT) * 0.55f
        val row2Y = mid + (height - padB - mid) * 0.45f

        stackPaint.color = colorV4
        canvas.drawText("v4", dp(4f), row1Y + dp(3f), stackPaint)
        stackPaint.color = colorV6
        canvas.drawText("v6", dp(4f), row2Y + dp(3f), stackPaint)

        status?.let { s ->
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(s, labelW + dp(4f), height / 2f + dp(4f), labelPaint)
            if (hops4.isEmpty() && hops6.isEmpty()) return
        }

        drawPath(canvas, hops4, row1Y, colorV4, labelW)
        drawPath(canvas, hops6, row2Y, colorV6, labelW)
    }

    private fun drawPath(
        canvas: Canvas,
        hops: List<HopNode>,
        cy: Float,
        color: Int,
        labelW: Float,
    ) {
        if (hops.isEmpty()) {
            labelPaint.color = colorMuted
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("—", labelW + dp(8f), cy + dp(3f), labelPaint)
            return
        }
        val slot = hopSlotWidth()
        val r = dp(4.5f)
        linePaint.color = (color and 0x00FFFFFF) or (0x99 shl 24)
        ipPaint.color = color
        rttPaint.color = color

        var prevX = 0f
        hops.forEachIndexed { i, hop ->
            val cx = labelW + dp(10f) + i * slot + slot * 0.5f
            if (i > 0) {
                canvas.drawLine(prevX + r, cy, cx - r, cy, linePaint)
            }
            nodePaint.color = color
            canvas.drawCircle(cx, cy, r, nodePaint)

            ipPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(hop.host, cx, cy - dp(8f), ipPaint)

            val rttLabel = formatRtt(hop.rttMs)
            rttPaint.color = if (hop.rttMs == null) colorMuted else color
            rttPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(rttLabel, cx, cy + dp(14f), rttPaint)

            labelPaint.color = colorMuted
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("${hop.ttl}", cx, cy + dp(26f), labelPaint)

            prevX = cx
        }
    }

    private fun hopSlotWidth(): Float {
        val samples = hops4.map { it.host } + hops6.map { it.host } + listOf("0.0.0.0")
        var maxW = 0f
        for (s in samples) {
            maxW = maxOf(maxW, ipPaint.measureText(s))
        }
        return maxOf(dp(96f), maxW + dp(20f))
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        fun fromRaw(hops: List<NativeTraceroute.Hop>): List<HopNode> =
            hops.filter { !it.host.isNullOrBlank() }
                .map { HopNode(ttl = it.ttl, host = it.host!!, rttMs = it.rttMs) }

        fun formatRtt(rttMs: Double?): String {
            if (rttMs == null || !rttMs.isFinite()) return "—"
            return when {
                rttMs < 10.0 -> String.format("%.1f ms", rttMs)
                else -> "${rttMs.roundToInt()} ms"
            }
        }
    }
}
