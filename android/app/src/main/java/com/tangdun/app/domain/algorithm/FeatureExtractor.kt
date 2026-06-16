package com.tangdun.app.domain.algorithm

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * 特征提取器
 *
 * 提取15维特征用于TCN模型预测
 * 与训练脚本 train_curve_v2.py 完全一致
 *
 * 特征维度：
 * 1-9: 血糖动态特征
 * 10-11: 胰岛素特征
 * 12-13: 碳水特征
 * 14: 心率特征
 * 15: 步数特征
 */
class FeatureExtractor {

    /**
     * 提取15维特征
     *
     * @param glucoseHistory 血糖历史（至少288点 = 24小时）
     * @param idx 当前时间点索引
     * @param bolusHistory 胰岛素大剂量历史
     * @param carbHistory 碳水摄入历史
     * @param heartRateHistory 心率历史
     * @param stepHistory 步数历史
     * @return 15维特征向量
     */
    fun extract(
        glucoseHistory: DoubleArray,
        idx: Int,
        bolusHistory: DoubleArray? = null,
        carbHistory: DoubleArray? = null,
        heartRateHistory: DoubleArray? = null,
        stepHistory: DoubleArray? = null
    ): FloatArray {
        // 计算统计量（24h滑动窗口，避免数据泄露）
        val start = maxOf(0, idx - 288)
        val history = glucoseHistory.sliceArray(start until idx)

        if (history.size < 10) {
            return FloatArray(15) { 0f }
        }

        val mean = history.average()
        val std = if (history.size > 1) {
            val variance = history.sumOf { (it - mean) * (it - mean) } / (history.size - 1)
            if (variance > 0) sqrt(variance) else 1.0
        } else 1.0

        // === 血糖特征 (1-9) ===

        // 特征1: 当前血糖值（归一化）
        val f1 = ((glucoseHistory[idx] - mean) / std).toFloat()

        // 特征2-5: 变化量
        val f2 = if (idx >= 1) ((glucoseHistory[idx] - glucoseHistory[idx - 1]) / std).toFloat() else 0f
        val f3 = if (idx >= 3) ((glucoseHistory[idx] - glucoseHistory[idx - 3]) / std).toFloat() else 0f
        val f4 = if (idx >= 6) ((glucoseHistory[idx] - glucoseHistory[idx - 6]) / std).toFloat() else 0f
        val f5 = if (idx >= 12) ((glucoseHistory[idx] - glucoseHistory[idx - 12]) / std).toFloat() else 0f

        // 特征6-7: ROC（变化率）
        val f6 = f4 / 30.0f  // 30分钟ROC
        val f7 = f5 / 60.0f  // 1小时ROC

        // 特征8-9: 统计特征
        val recent = if (history.size >= 72) history.sliceArray(history.size - 72 until history.size) else history
        val f8 = ((recent.average() - mean) / std).toFloat()
        val f9 = (recent.std() / std).toFloat()

        // === 胰岛素特征 (10-11) ===
        val bolus = bolusHistory ?: DoubleArray(288) { 0.0 }

        // 特征10: 最近4小时胰岛素总量
        val bolusStart = maxOf(0, idx - 48)
        val f10 = bolus.sliceArray(bolusStart..idx).sum().toFloat()

        // 特征11: 最近注射时间（分钟, 上限120min, 归一化到[0,1]）
        val bolusRecent = bolus.sliceArray(maxOf(0, idx - 144)..idx)
        val lastBolusIdx = bolusRecent.indexOfLast { it > 0 }
        val f11 = if (lastBolusIdx >= 0) {
            (((bolusRecent.size - lastBolusIdx) * 5).toFloat() / 120f).coerceIn(0f, 1f)
        } else 1f  // 无数据=1(很久前/无), 非999

        // === 碳水特征 (12-13) ===
        val carbs = carbHistory ?: DoubleArray(288) { 0.0 }

        // 特征12: 最近4小时碳水总量
        val carbStart = maxOf(0, idx - 48)
        val f12 = carbs.sliceArray(carbStart..idx).sum().toFloat()

        // 特征13: 最近进食时间（分钟, 上限120min, 归一化到[0,1]）
        val carbRecent = carbs.sliceArray(maxOf(0, idx - 144)..idx)
        val lastCarbIdx = carbRecent.indexOfLast { it > 0 }
        val f13 = if (lastCarbIdx >= 0) {
            (((carbRecent.size - lastCarbIdx) * 5).toFloat() / 120f).coerceIn(0f, 1f)
        } else 1f

        // === 心率特征 (14) ===
        val hr = heartRateHistory ?: DoubleArray(288) { 0.0 }
        val hrRecent = hr.sliceArray(maxOf(0, idx - 12)..idx).filter { it > 0 }
        val f14 = if (hrRecent.isNotEmpty()) hrRecent.average().toFloat() else 0f

        // === 步数特征 (15) ===
        val steps = stepHistory ?: DoubleArray(288) { 0.0 }
        val f15 = steps.sliceArray(maxOf(0, idx - 12)..idx).sum().toFloat()

        return floatArrayOf(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15)
    }

    /** 计算标准差 */
    private fun DoubleArray.std(): Double {
        if (size <= 1) return 0.0
        val mean = average()
        val variance = sumOf { (it - mean) * (it - mean) } / (size - 1)
        return if (variance > 0) sqrt(variance) else 0.0
    }
}
