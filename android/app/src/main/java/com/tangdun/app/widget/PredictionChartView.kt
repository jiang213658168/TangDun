package com.tangdun.app.widget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 血糖预测曲线图 — 与 GlucoseChartView 统一样式
 *
 * 特点：
 * - 历史血糖实线 + 预测曲线虚线（颜色不同）
 * - 目标范围绿色背景
 * - 触摸查看预测时间点值
 */
class PredictionChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var historyData: List<Pair<Long, Double>> = emptyList()
    private var predictionCurve: List<Double> = emptyList()    // 最终融合预测
    private var physioCurve: List<Double> = emptyList()         // DallaMan生理模型
    private var incrementalCurve: List<Double> = emptyList()    // 增量自学习残差
    private var currentGlucose: Double = 7.0
    private var targetLow: Double = 3.9
    private var targetHigh: Double = 10.0

    // 触摸
    private var selectedIdx = -1
    private var selectedPredIdx = -1

    // 画布范围
    private var minY: Double = 2.0
    private var maxY: Double = 16.0
    private var startTime: Long = 0
    private var endTime: Long = 0

    // 画笔
    private val targetBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2043A047"); style = Paint.Style.FILL }
    private val highBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#10EF6C00"); style = Paint.Style.FILL }
    private val lowBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#10E53935"); style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E0E4E8"); strokeWidth = 1f; style = Paint.Style.STROKE }
    private val tgtLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8043A047"); strokeWidth = 2f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f) }
    private val highLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#80EF6C00"); strokeWidth = 2f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f) }
    private val histPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#14A3A8"); strokeWidth = 3f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val predPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF7043"); strokeWidth = 3f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f) }
    private val curDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4444"); style = Paint.Style.FILL }
    private val selLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#40FF4444"); strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#666666"); textSize = 26f }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#333333"); textSize = 30f; isFakeBoldText = true }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#999999"); textSize = 20f; textAlign = Paint.Align.CENTER }

    // 三条预测曲线画笔
    private val physioPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5C6BC0"); strokeWidth = 2.5f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f) }
    private val incrementalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66BB6A"); strokeWidth = 2f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; pathEffect = DashPathEffect(floatArrayOf(4f, 8f), 0f) }
    // predPaint (最终融合) 改为实线更突出
    private val finalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF7043"); strokeWidth = 3.5f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    fun setData(history: List<Pair<Long, Double>>, curve: List<Double>, current: Double) {
        historyData = history.sortedBy { it.first }
        predictionCurve = curve
        physioCurve = emptyList()
        incrementalCurve = emptyList()
        currentGlucose = current
        updateBounds(curve)
        invalidate()
    }

    fun setThreeCurves(history: List<Pair<Long, Double>>, physio: List<Double>, incremental: List<Double>, final: List<Double>, current: Double) {
        historyData = history.sortedBy { it.first }
        physioCurve = physio
        incrementalCurve = incremental
        predictionCurve = final
        currentGlucose = current
        updateBounds(final)
        invalidate()
    }

    private fun updateBounds(curve: List<Double>) {
        if (historyData.isNotEmpty()) {
            startTime = historyData.first().first
            endTime = if (curve.size >= 25) historyData.last().first + 120 * 60000 else historyData.last().first
            minY = max(1.0, minOf(historyData.minOf { it.second }, currentGlucose) - 2.0)
            maxY = min(30.0, maxOf(historyData.maxOf { it.second }, currentGlucose) + 3.0)
            if (curve.isNotEmpty()) {
                minY = min(minY, curve.min() - 1.0).coerceAtLeast(1.0)
                maxY = max(maxY, curve.max() + 1.0).coerceAtMost(30.0)
            }
        }
        invalidate()
    }

    fun setTargets(low: Double, high: Double) { targetLow = low; targetHigh = high; invalidate() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && historyData.size >= 2) {
            val pad = 60f; val cw = width - pad * 2
            val timeRange = endTime - startTime
            if (timeRange > 0) {
                val tapTime = startTime + ((event.x - pad) / cw * timeRange).toLong()
                selectedIdx = historyData.indices.minByOrNull { abs(historyData[it].first - tapTime) } ?: -1
            }
            if (predictionCurve.size >= 25) {
                val predStart = historyData.lastOrNull()?.first ?: startTime
                val predRange = 120 * 60000L
                val tapPred = ((event.x - pad) / cw * (endTime - startTime)).coerceIn(0f, predRange.toFloat()).toLong()
                selectedPredIdx = (tapPred / (5 * 60000L)).toInt().coerceIn(0, 24)
            }
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (historyData.size < 2) return
        val pad = 60f; val cw = width - pad * 2; val ch = height - pad * 2
        val timeRange = (endTime - startTime).toFloat()
        if (timeRange <= 0) return

        fun toX(ts: Long) = pad + ((ts - startTime).toFloat() / timeRange * cw)
        fun toY(v: Double) = pad + ((maxY - v.coerceIn(minY, maxY)) / (maxY - minY) * ch).toFloat()

        // 背景区域
        canvas.drawRect(pad, toY(targetHigh), width - pad, toY(targetLow), targetBg)
        canvas.drawRect(pad, toY(maxY), width - pad, toY(targetHigh), highBg)
        canvas.drawRect(pad, toY(targetLow), width - pad, toY(minY), lowBg)

        // 网格
        for (v in (minY.toInt()..maxY.toInt())) {
            canvas.drawLine(pad, toY(v.toDouble()), width - pad, toY(v.toDouble()), gridPaint)
            canvas.drawText("$v", 4f, toY(v.toDouble()) + 8f, textPaint)
        }

        // 目标线
        canvas.drawLine(pad, toY(targetLow), width - pad, toY(targetLow), tgtLine)
        canvas.drawLine(pad, toY(targetHigh), width - pad, toY(targetHigh), highLine)

        // 历史曲线（实线）
        val histPath = Path()
        histPath.moveTo(toX(historyData[0].first), toY(historyData[0].second))
        for (i in 1 until historyData.size) histPath.lineTo(toX(historyData[i].first), toY(historyData[i].second))
        canvas.drawPath(histPath, histPaint)

        // 数据点
        for (i in historyData.indices) {
            val r = if (i == selectedIdx) 7f else 2.5f
            canvas.drawCircle(toX(historyData[i].first), toY(historyData[i].second), r, if (i == selectedIdx) curDot else histPaint)
        }

        // ── 三条预测曲线 ──
        if (predictionCurve.size >= 25) {
            val predStart = historyData.last().first
            val n = predictionCurve.size

            // 画增量残差曲线 (最细, 最淡)
            if (incrementalCurve.size >= 25) {
                val incPath = Path()
                incPath.moveTo(toX(predStart), toY(incrementalCurve[0]))
                for (i in 1 until n) incPath.lineTo(toX(predStart + i * 5 * 60000L), toY(incrementalCurve[i]))
                canvas.drawPath(incPath, incrementalPaint)
            }

            // 画DallaMan生理曲线 (中粗虚线)
            if (physioCurve.size >= 25) {
                val phyPath = Path()
                phyPath.moveTo(toX(predStart), toY(physioCurve[0]))
                for (i in 1 until n) phyPath.lineTo(toX(predStart + i * 5 * 60000L), toY(physioCurve[i]))
                canvas.drawPath(phyPath, physioPaint)
            }

            // 画最终融合曲线 (最粗实线, 最突出)
            val finalPath = Path()
            finalPath.moveTo(toX(predStart), toY(predictionCurve[0]))
            for (i in 1 until n) finalPath.lineTo(toX(predStart + i * 5 * 60000L), toY(predictionCurve[i]))
            canvas.drawPath(finalPath, finalPaint)

            // 图例
            val legendX = width - pad - 120f; val legendY = pad + 10f
            canvas.drawText("— 最终融合", legendX, legendY, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF7043"); textSize = 22f })
            if (physioCurve.size >= 25)
                canvas.drawText("- - 生理模型", legendX, legendY + 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5C6BC0"); textSize = 22f })
            if (incrementalCurve.size >= 25)
                canvas.drawText("·· 增量残差", legendX, legendY + 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66BB6A"); textSize = 22f })

            // 预测终点标记 (在最终曲线上)
            if (selectedPredIdx in predictionCurve.indices) {
                val st = predStart + selectedPredIdx * 5 * 60000L
                val sv = predictionCurve[selectedPredIdx]
                canvas.drawCircle(toX(st), toY(sv), 8f, curDot)
                canvas.drawLine(toX(st), pad, toX(st), height - pad, selLine)
                val label = "${String.format("%.1f", sv)} @+${selectedPredIdx * 5}min"
                canvas.drawText(label, toX(st) + 10, toY(sv) - 10, labelPaint)
            }
        }

        // 底部时间标签
        val totalH = (endTime - startTime) / 3600000.0
        val step = when { totalH <= 3 -> 30; totalH <= 6 -> 60; totalH <= 12 -> 120; else -> 240 }
        val cal = Calendar.getInstance().apply { timeInMillis = startTime; set(Calendar.MINUTE, (get(Calendar.MINUTE) / step) * step); set(Calendar.SECOND, 0) }
        while (cal.timeInMillis <= endTime) {
            canvas.drawText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(cal.timeInMillis)), toX(cal.timeInMillis), height - 6f, smallText)
            cal.add(Calendar.MINUTE, step)
        }

        // 图例
        canvas.drawRect(pad, height - 30f, pad + 20f, height - 18f, histPaint)
        canvas.drawText("实测", pad + 26f, height - 12f, smallText.apply { textAlign = Paint.Align.LEFT })
        canvas.drawRect(pad + 70f, height - 30f, pad + 90f, height - 18f, predPaint)
        canvas.drawText("预测", pad + 96f, height - 12f, smallText)
    }
}
