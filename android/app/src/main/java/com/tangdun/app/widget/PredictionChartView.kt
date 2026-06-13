package com.tangdun.app.widget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class PredictionChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var curveData: List<Double> = emptyList()
    private var currentGlucose: Double = 7.0
    private val targetLow = 3.9
    private val targetHigh = 10.0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#007A8C")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#007A8C")
        style = Paint.Style.FILL
    }

    private val currentDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 28f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        textSize = 30f
        isFakeBoldText = true
    }

    fun setCurveData(curve: List<Double>, current: Double) {
        curveData = curve
        currentGlucose = current
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val padding = 70f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2

        if (curveData.isEmpty()) {
            textPaint.textSize = 36f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("暂无预测数据", width / 2f, height / 2f, textPaint)
            return
        }

        val minY = 2.0
        val maxY = 16.0
        val totalPoints = curveData.size
        val maxMinutes = 120.0

        fun toX(index: Int) = padding + (index.toFloat() / (totalPoints - 1) * chartWidth)
        fun toY(value: Double) = padding + ((maxY - value.coerceIn(minY, maxY)) / (maxY - minY) * chartHeight).toFloat()

        // 网格线
        for (i in 0..6) {
            val y = toY(minY + i * (maxY - minY) / 6)
            canvas.drawLine(padding, y, width - padding, y, gridPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format("%.0f", minY + i * (maxY - minY) / 6), padding - 10f, y + 10f, textPaint)
        }

        // 时间标签
        textPaint.textAlign = Paint.Align.CENTER
        val timeLabels = listOf("0", "30", "60", "90", "120")
        for (i in timeLabels.indices) {
            val x = toX(i * (totalPoints - 1) / (timeLabels.size - 1))
            canvas.drawText("${timeLabels[i]}min", x, height - 10f, textPaint)
        }

        // 目标范围
        targetPaint.color = Color.parseColor("#804CAF50")
        canvas.drawLine(padding, toY(targetLow), width - padding, toY(targetLow), targetPaint)
        targetPaint.color = Color.parseColor("#80FF9800")
        canvas.drawLine(padding, toY(targetHigh), width - padding, toY(targetHigh), targetPaint)

        // 预测曲线
        val path = Path()
        for (i in curveData.indices) {
            val x = toX(i)
            val y = toY(curveData[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        // 数据点
        for (i in curveData.indices) {
            val x = toX(i)
            val y = toY(curveData[i])
            val paint = if (i == 0) currentDotPaint else dotPaint
            val radius = if (i == 0) 10f else 6f
            canvas.drawCircle(x, y, radius, paint)
        }

        // 标注当前值和终点值
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(String.format("%.1f", currentGlucose), toX(0) + 15, toY(curveData[0]) - 15, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format("%.1f", curveData.last()), toX(totalPoints - 1) - 15, toY(curveData.last()) - 15, labelPaint)
    }
}
