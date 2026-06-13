package com.tangdun.app.widget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.*

/**
 * 血糖曲线图
 *
 * 参考xDrip+的图表设计：
 * - 支持缩放和平移
 * - 显示时间轴
 * - 目标范围高亮
 * - 血糖值标注
 */
class GlucoseChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 数据点 (timestamp_ms, glucose_mmol)
    private var dataPoints: List<Pair<Long, Double>> = emptyList()

    // 显示范围
    private var startTime: Long = 0
    private var endTime: Long = 0

    // 目标范围
    private var targetLow = 3.9
    private var targetHigh = 10.0

    // 画笔
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#007A8C")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#20007A8C")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        textSize = 32f
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        textSize = 36f
        isFakeBoldText = true
    }

    /**
     * 设置数据
     *
     * @param points 数据点列表 (timestamp_ms, glucose_mmol)
     */
    fun setData(points: List<Pair<Long, Double>>) {
        dataPoints = points.sortedBy { it.first }
        if (dataPoints.isNotEmpty()) {
            startTime = dataPoints.first().first
            endTime = dataPoints.last().first
        }
        invalidate()
    }

    /**
     * 设置目标范围
     */
    fun setTargetRange(low: Double, high: Double) {
        targetLow = low
        targetHigh = high
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val padding = 70f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2

        if (dataPoints.isEmpty()) {
            drawEmptyState(canvas, width / 2f, height / 2f)
            return
        }

        val minY = 2.0
        val maxY = 16.0

        fun toX(timestamp: Long): Float {
            return padding + ((timestamp - startTime).toFloat() / (endTime - startTime) * chartWidth)
        }

        fun toY(value: Double): Float {
            return padding + ((maxY - value.coerceIn(minY, maxY)) / (maxY - minY) * chartHeight).toFloat()
        }

        // 绘制网格
        drawGrid(canvas, padding, chartWidth, chartHeight, minY, maxY)

        // 绘制时间轴
        drawTimeAxis(canvas, padding, chartWidth, chartHeight)

        // 绘制目标范围
        drawTargetRange(canvas, padding, chartWidth) { value -> toY(value) }

        // 绘制血糖曲线
        drawGlucoseCurve(canvas, { timestamp -> toX(timestamp) }, { value -> toY(value) })

        // 绘制当前值
        if (dataPoints.isNotEmpty()) {
            drawCurrentValue(canvas, dataPoints.last(), { timestamp -> toX(timestamp) }, { value -> toY(value) })
        }
    }

    private fun drawEmptyState(canvas: Canvas, x: Float, y: Float) {
        textPaint.textSize = 36f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("暂无血糖数据", x, y, textPaint)
        textPaint.textSize = 28f
        canvas.drawText("记录血糖后显示曲线", x, y + 50f, textPaint)
    }

    private fun drawGrid(canvas: Canvas, padding: Float, chartWidth: Float, chartHeight: Float, minY: Double, maxY: Double) {
        // 水平网格线（血糖值）
        for (i in 0..7) {
            val value = minY + i * (maxY - minY) / 7
            val y = padding + ((maxY - value) / (maxY - minY) * chartHeight).toFloat()
            canvas.drawLine(padding, y, width - padding, y, gridPaint)

            // 血糖值标签
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.textSize = 24f
            canvas.drawText(String.format("%.1f", value), padding - 10f, y + 8f, textPaint)
        }
    }

    private fun drawTimeAxis(canvas: Canvas, padding: Float, chartWidth: Float, chartHeight: Float) {
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val totalHours = (endTime - startTime) / (1000.0 * 3600)

        // 根据时间跨度决定标签间隔
        val intervalHours = when {
            totalHours <= 6 -> 1
            totalHours <= 12 -> 2
            totalHours <= 24 -> 4
            else -> 6
        }

        val startTimeCal = Calendar.getInstance().apply { timeInMillis = startTime }
        startTimeCal.set(Calendar.MINUTE, 0)
        startTimeCal.set(Calendar.SECOND, 0)

        var currentTime = startTimeCal.timeInMillis
        while (currentTime <= endTime) {
            val x = padding + ((currentTime - startTime).toFloat() / (endTime - startTime) * chartWidth)

            // 时间标签
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 24f
            val timeStr = dateFormat.format(Date(currentTime))
            canvas.drawText(timeStr, x, height - 10f, textPaint)

            // 垂直网格线
            canvas.drawLine(x, padding, x, height - padding, gridPaint)

            currentTime += intervalHours * 3600 * 1000L
        }
    }

    private fun drawTargetRange(canvas: Canvas, padding: Float, chartWidth: Float, toY: (Double) -> Float) {
        val targetLowY = toY(targetLow)
        val targetHighY = toY(targetHigh)

        // 目标范围背景
        val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#104CAF50")
            style = Paint.Style.FILL
        }
        canvas.drawRect(padding, targetHighY, width - padding, targetLowY, rangePaint)

        // 目标线
        targetPaint.color = Color.parseColor("#4CAF50")
        canvas.drawLine(padding, targetLowY, width - padding, targetLowY, targetPaint)
        targetPaint.color = Color.parseColor("#FF9800")
        canvas.drawLine(padding, targetHighY, width - padding, targetHighY, targetPaint)

        // 标签
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 20f
        textPaint.color = Color.parseColor("#4CAF50")
        canvas.drawText("低", width - padding + 5f, targetLowY + 6f, textPaint)
        textPaint.color = Color.parseColor("#FF9800")
        canvas.drawText("高", width - padding + 5f, targetHighY + 6f, textPaint)
    }

    private fun drawGlucoseCurve(canvas: Canvas, toX: (Long) -> Float, toY: (Double) -> Float) {
        if (dataPoints.size < 2) return

        val path = Path()
        val fillPath = Path()
        var first = true

        for ((timestamp, value) in dataPoints) {
            val x = toX(timestamp)
            val y = toY(value)

            if (first) {
                path.moveTo(x, y)
                fillPath.moveTo(x, toY(2.0))
                fillPath.lineTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        // 填充区域
        fillPath.lineTo(toX(dataPoints.last().first), toY(2.0))
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // 曲线
        canvas.drawPath(path, linePaint)
    }

    private fun drawCurrentValue(canvas: Canvas, point: Pair<Long, Double>, toX: (Long) -> Float, toY: (Double) -> Float) {
        val x = toX(point.first)
        val y = toY(point.second)

        // 当前值圆点
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = getGlucoseColor(point.second)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, 8f, dotPaint)

        // 外圈
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(x, y, 10f, ringPaint)
    }

    private fun getGlucoseColor(value: Double): Int {
        return when {
            value < 3.9 -> Color.parseColor("#F44336")  // 低血糖 - 红色
            value > 10.0 -> Color.parseColor("#FF9800") // 高血糖 - 橙色
            else -> Color.parseColor("#4CAF50")         // 正常 - 绿色
        }
    }
}
