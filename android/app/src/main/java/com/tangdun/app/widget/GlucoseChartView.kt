package com.tangdun.app.widget

import android.content.Context
import android.graphics.*
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * xDrip+ 风格血糖图表
 *
 * 特点：
 * - 目标范围绿色背景高亮
 * - 高血糖区域淡橙 / 低血糖区域淡红
 * - 可触摸查看具体值
 * - 数据点标记
 * - 时间网格和数值网格
 */
class GlucoseChartView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var dataPoints: List<Pair<Long, Double>> = emptyList()
    private var targetLow = 3.9
    private var targetHigh = 10.0
    private var minY = 2.0
    private var maxY = 16.0

    // 触摸
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            findNearest(e.x, e.y)
            return true
        }
    })
    private var selectedIndex = -1  // 选中的数据点

    // 画笔
    private val bgPaint = Paint().apply { color = Color.parseColor("#F2F5F7"); style = Paint.Style.FILL }
    private val targetBg = Paint().apply { color = Color.parseColor("#2043A047"); style = Paint.Style.FILL }
    private val highBg = Paint().apply { color = Color.parseColor("#10EF6C00"); style = Paint.Style.FILL }
    private val lowBg = Paint().apply { color = Color.parseColor("#10E53935"); style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E0E4E8"); strokeWidth = 1f; style = Paint.Style.STROKE }
    private val targetLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8043A047"); strokeWidth = 2f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f) }
    private val highLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#80EF6C00"); strokeWidth = 2f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#14A3A8"); strokeWidth = 3f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#14A3A8"); style = Paint.Style.FILL }
    private val selDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4444"); style = Paint.Style.FILL }
    private val selRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4444"); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val selLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#40FF4444"); strokeWidth = 1f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#666666"); textSize = 28f }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#333333"); textSize = 32f; isFakeBoldText = true }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#999999"); textSize = 22f; textAlign = Paint.Align.CENTER }

    fun setData(points: List<Pair<Long, Double>>) {
        dataPoints = points.sortedBy { it.first }
        if (dataPoints.isNotEmpty()) {
            minY = max(1.0, dataPoints.minOf { it.second } - 2.0).coerceAtMost(2.0)
            maxY = min(30.0, dataPoints.maxOf { it.second } + 3.0).coerceAtLeast(12.0)
        }
        invalidate()
    }

    fun setTargetRange(low: Double, high: Double) { targetLow = low; targetHigh = high; invalidate() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN) { findNearest(event.x, event.y); return true }
        return super.onTouchEvent(event)
    }

    private fun findNearest(x: Float, y: Float) {
        if (dataPoints.size < 2) return
        val pad = 60f; val cw = width - pad * 2
        val startTime = dataPoints.first().first; val timeRange = dataPoints.last().first - startTime
        if (timeRange == 0L) return
        val tapTime = startTime + ((x - pad) / cw * timeRange).toLong()
        selectedIndex = dataPoints.indices.minByOrNull { abs(dataPoints[it].first - tapTime) } ?: -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 60f; val cw = width - pad * 2; val ch = height - pad * 2
        val startTime = dataPoints.firstOrNull()?.first ?: return
        val timeRange = (dataPoints.lastOrNull()?.first ?: return) - startTime
        if (timeRange == 0L) return

        fun toX(ts: Long) = pad + ((ts - startTime).toFloat() / timeRange * cw)
        fun toY(v: Double) = pad + ((maxY - v.coerceIn(minY, maxY)) / (maxY - minY) * ch).toFloat()

        // 背景区域
        canvas.drawRect(pad, toY(targetHigh), width - pad, toY(targetLow), targetBg)  // 目标(绿)
        canvas.drawRect(pad, toY(maxY), width - pad, toY(targetHigh), highBg)         // 高(橙)
        canvas.drawRect(pad, toY(targetLow), width - pad, toY(minY), lowBg)           // 低(红)

        // 网格
        val step = when { maxY - minY > 12 -> 3.0; maxY - minY > 6 -> 2.0; else -> 1.0 }
        var v = (minY / step).toInt() * step
        while (v <= maxY) {
            val y = toY(v)
            canvas.drawLine(pad, y, width - pad, y, gridPaint)
            canvas.drawText(String.format("%.0f", v), 4f, y + 8f, textPaint)
            v += step
        }

        // 目标线
        canvas.drawLine(pad, toY(targetLow), width - pad, toY(targetLow), targetLinePaint)
        canvas.drawLine(pad, toY(targetHigh), width - pad, toY(targetHigh), highLinePaint)
        canvas.drawText("低", width - pad + 4f, toY(targetLow) + 6f, textPaint.apply { textAlign = Paint.Align.LEFT; textSize = 18f })
        canvas.drawText("高", width - pad + 4f, toY(targetHigh) + 6f, textPaint)

        // 曲线
        if (dataPoints.size >= 2) {
            val path = Path()
            path.moveTo(toX(dataPoints[0].first), toY(dataPoints[0].second))
            for (i in 1 until dataPoints.size) path.lineTo(toX(dataPoints[i].first), toY(dataPoints[i].second))
            canvas.drawPath(path, linePaint)

            // 数据点
            for (i in dataPoints.indices) {
                val dot = if (i == selectedIndex) selDotPaint else dotPaint
                val r = if (i == selectedIndex) 7f else 3f
                canvas.drawCircle(toX(dataPoints[i].first), toY(dataPoints[i].second), r, dot)
            }
        }

        // 选中标记
        if (selectedIndex in dataPoints.indices) {
            val (st, sv) = dataPoints[selectedIndex]
            val sx = toX(st); val sy = toY(sv)
            canvas.drawLine(sx, pad, sx, height - pad, selLinePaint)        // 竖线
            canvas.drawLine(pad, sy, width - pad, sy, selLinePaint)         // 横线
            canvas.drawCircle(sx, sy, 12f, selRingPaint)
            canvas.drawCircle(sx, sy, 8f, selDotPaint)

            // 标签
            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(st))
            val label = "${String.format("%.1f", sv)} mmol/L  $timeStr"
            val labelW = labelPaint.measureText(label)
            val labelX = if (sx + labelW + 20 > width) sx - labelW - 16 else sx + 16
            val labelY = if (sy < pad + 40) sy + 40 else sy - 16
            val bg = Paint().apply { color = Color.WHITE; setShadowLayer(4f, 0f, 2f, Color.parseColor("#40000000")) }
            canvas.drawRect(labelX - 8, labelY - 28, labelX + labelW + 8, labelY + 8, bg)
            canvas.drawText(label, labelX, labelY, labelPaint)
        }

        // 底部时间
        val totalH = timeRange / 3600000.0
        val intervalH = when { totalH <= 6 -> 1; totalH <= 12 -> 2; totalH <= 24 -> 4; else -> 6 }
        val cal = Calendar.getInstance().apply { timeInMillis = startTime; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        var ct = cal.timeInMillis
        while (ct <= dataPoints.last().first) {
            canvas.drawText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ct)), toX(ct), height - 6f, timePaint)
            ct += intervalH * 3600 * 1000L
        }
    }
}
