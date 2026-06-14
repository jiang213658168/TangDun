package com.tangdun.app.widget

import android.graphics.*
import com.tangdun.app.data.local.entity.GlucoseRecord

/**
 * 通知栏血糖曲线渲染器 — 参考 xDrip+ 持久通知的sparkline图表
 *
 * 在通知栏显示:
 * - 最近2小时血糖实测(实线)
 * - 预测曲线(虚线，如有)
 * - 目标范围绿色背景
 */
object NotificationChartRenderer {

    private const val WIDTH = 400
    private const val HEIGHT = 150
    private const val PAD_LEFT = 8f
    private const val PAD_RIGHT = 8f
    private const val PAD_TOP = 8f
    private const val PAD_BOTTOM = 24f

    fun render(
        history: List<GlucoseRecord>,
        prediction: List<Double>?,
        targetLow: Double, targetHigh: Double
    ): Bitmap? {
        if (history.size < 2) return null

        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cw = WIDTH - PAD_LEFT - PAD_RIGHT
        val ch = HEIGHT - PAD_TOP - PAD_BOTTOM

        val values = history.map { it.value } + (prediction ?: emptyList())
        val minY = (values.min() - 1.0).coerceAtLeast(1.0)
        val maxY = (values.max() + 1.0).coerceAtMost(25.0)

        fun toX(idx: Int, total: Int) = PAD_LEFT + (idx.toFloat() / (total - 1).coerceAtLeast(1)) * cw
        fun toY(v: Double) = PAD_TOP + ((maxY - v.coerceIn(minY, maxY)) / (maxY - minY) * ch).toFloat()

        // 目标范围背景
        val targetTop = toY(targetHigh)
        val targetBottom = toY(targetLow)
        canvas.drawRect(PAD_LEFT, targetTop, WIDTH - PAD_RIGHT, targetBottom,
            Paint().apply { color = Color.parseColor("#2043A047"); style = Paint.Style.FILL })

        // 目标线
        val dashLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8043A047"); strokeWidth = 1f; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        canvas.drawLine(PAD_LEFT, targetBottom, WIDTH - PAD_RIGHT, targetBottom, dashLine)
        canvas.drawLine(PAD_LEFT, targetTop, WIDTH - PAD_RIGHT, targetTop, dashLine)

        // 历史曲线
        val histPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#14A3A8"); strokeWidth = 2.5f; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val histPath = Path()
        histPath.moveTo(toX(0, history.size), toY(history[0].value))
        for (i in 1 until history.size) {
            histPath.lineTo(toX(i, history.size + (prediction?.size ?: 0)), toY(history[i].value))
        }
        canvas.drawPath(histPath, histPaint)

        // 预测曲线（虚线）
        if (prediction != null && prediction.isNotEmpty()) {
            val predPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF7043"); strokeWidth = 2f; style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
            }
            val predPath = Path()
            val histN = history.size
            val totalN = histN + prediction.size
            // 起点: 最后一个历史点
            predPath.moveTo(toX(histN - 1, totalN), toY(history.last().value))
            for (i in prediction.indices) {
                predPath.lineTo(toX(histN + i, totalN), toY(prediction[i]))
            }
            canvas.drawPath(predPath, predPaint)
        }

        // 当前值标注
        val curPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val curX = toX(history.size - 1, history.size + (prediction?.size ?: 0))
        val curY = toY(history.last().value)
        canvas.drawCircle(curX, curY, 5f, curPaint)
        canvas.drawCircle(curX, curY, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4444"); style = Paint.Style.FILL })

        // 数值标签
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f; isFakeBoldText = true }
        canvas.drawText(String.format("%.1f", history.last().value), curX + 8f, curY + 6f, textPaint)

        return bmp
    }
}
