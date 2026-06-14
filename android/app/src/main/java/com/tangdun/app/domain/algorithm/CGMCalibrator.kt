package com.tangdun.app.domain.algorithm

import android.content.Context
import android.content.SharedPreferences
import com.tangdun.app.data.local.entity.GlucoseRecord
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * CGM 指尖血校准器 — 移植自 xDrip+ 校准算法
 *
 * xDrip+ 校准原理:
 *   1. 用户用指尖血糖仪测一次 → 输入校准值
 *   2. 系统比较指尖值与CGM原始值 → 计算补偿量
 *   3. 后续CGM读数 = 原始值 + 补偿量
 *
 * 本实现使用加权移动平均校准:
 *   offset = EWMA(指尖值 - CGM值)
 *   权重随校准次数增加而增大(0.7→0.3)
 *
 * xDrip+ 参考: BloodTest.java, Calibration.java, BgReading.java
 */
class CGMCalibrator(context: Context) {

    companion object {
        private const val PREFS = "cgm_calibration"
        private const val KEY_OFFSET = "cal_offset"
        private const val KEY_COUNT = "cal_count"
        private const val KEY_LAST_CAL = "last_calibration_time"
        private const val MAX_OFFSET = 5.0   // mmol/L 最大修正量
        private const val MIN_SAMPLES = 3     // 至少3次校准才启用
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class CalResult(
        val correctedValue: Double,     // 校准后的血糖值
        val offset: Double,             // 当前偏移量
        val confidence: String,         // 可信度
        val calibrationCount: Int
    )

    /** 获取当前偏移量 */
    fun getOffset(): Double = prefs.getFloat(KEY_OFFSET, 0f).toDouble()

    /** 校准次数 */
    fun getCount(): Int = prefs.getInt(KEY_COUNT, 0)

    /**
     * 添加一次指尖血校准
     *
     * @param fingerValue 指尖血糖仪读数 (mmol/L)
     * @param cgmValue 同时刻的CGM读数 (mmol/L)
     */
    fun calibrate(fingerValue: Double, cgmValue: Double): CalResult {
        val count = getCount() + 1
        val rawOffset = fingerValue - cgmValue
        val clampedOffset = rawOffset.coerceIn(-MAX_OFFSET, MAX_OFFSET)

        // EWMA平滑 (数据越多越稳定)
        val oldOffset = getOffset()
        val alpha = if (count <= 3) 0.5 else 0.3  // 前三次数权重高
        val newOffset = oldOffset * (1 - alpha) + clampedOffset * alpha

        prefs.edit()
            .putFloat(KEY_OFFSET, newOffset.toFloat())
            .putInt(KEY_COUNT, count)
            .putLong(KEY_LAST_CAL, System.currentTimeMillis())
            .apply()

        val confidence = when {
            count >= 10 -> "高 (已校准${count}次)"
            count >= MIN_SAMPLES -> "中 (已校准${count}次)"
            else -> "低 (需${MIN_SAMPLES - count}次以上)"
        }

        return CalResult(
            correctedValue = cgmValue + newOffset,
            offset = newOffset,
            confidence = confidence,
            calibrationCount = count
        )
    }

    /**
     * 应用校准到CGM读数
     */
    fun applyCalibration(cgmValue: Double): Double {
        val offset = getOffset()
        if (getCount() < MIN_SAMPLES) return cgmValue  // 不够3次不校准
        return (cgmValue + offset).coerceIn(1.0, 33.0)
    }

    /**
     * 检查是否应该提示校准 (超过12小时未校准)
     */
    fun needsCalibration(): Boolean {
        val lastCal = prefs.getLong(KEY_LAST_CAL, 0)
        return System.currentTimeMillis() - lastCal > 12 * 3600 * 1000
    }

    /** 重置校准数据 */
    fun reset() { prefs.edit().clear().apply() }
}
