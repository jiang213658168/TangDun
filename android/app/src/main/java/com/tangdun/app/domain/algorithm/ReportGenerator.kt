package com.tangdun.app.domain.algorithm

import com.tangdun.app.data.local.entity.GlucoseRecord
import com.tangdun.app.data.local.entity.MealRecord
import com.tangdun.app.data.local.entity.InsulinRecord
import com.tangdun.app.data.local.entity.ExerciseRecord
import kotlin.math.sqrt

/**
 * 报告生成器
 *
 * 生成日/周/月报告，与后端ReportCalculator功能一致
 */
class ReportGenerator {

    /**
     * 日报告
     */
    data class DailyReport(
        val date: Long,
        val avgGlucose: Double = 0.0,
        val minGlucose: Double = 0.0,
        val maxGlucose: Double = 0.0,
        val stdGlucose: Double = 0.0,
        val tir: Double = 0.0,
        val tirLow: Double = 0.0,
        val tirHigh: Double = 0.0,
        val gri: Double = 0.0,
        val hba1cEstimate: Double = 0.0,
        val totalCarbs: Double = 0.0,
        val totalCalories: Double = 0.0,
        val totalInsulin: Double = 0.0,
        val totalSteps: Int = 0,
        val totalExerciseMin: Int = 0,
        val recordCount: Int = 0,
        val highlights: List<String> = emptyList(),
        val improvements: List<String> = emptyList()
    )

    /**
     * 周报告
     */
    data class WeeklyReport(
        val startDate: Long,
        val endDate: Long,
        val avgTir: Double = 0.0,
        val avgGlucose: Double = 0.0,
        val glucoseVariability: Double = 0.0,
        val totalCarbs: Double = 0.0,
        val totalInsulin: Double = 0.0,
        val totalSteps: Int = 0,
        val highlights: List<String> = emptyList(),
        val improvements: List<String> = emptyList()
    )

    /**
     * 月报告
     */
    data class MonthlyReport(
        val year: Int,
        val month: Int,
        val avgTir: Double = 0.0,
        val avgGlucose: Double = 0.0,
        val hba1cEstimate: Double = 0.0,
        val tirTrend: List<Map<String, Any>> = emptyList(),
        val recommendations: List<String> = emptyList()
    )

    /**
     * 生成日报告
     */
    fun generateDailyReport(
        date: Long,
        glucoseRecords: List<GlucoseRecord>,
        mealRecords: List<MealRecord> = emptyList(),
        insulinRecords: List<InsulinRecord> = emptyList(),
        exerciseRecords: List<ExerciseRecord> = emptyList()
    ): DailyReport {
        if (glucoseRecords.isEmpty()) {
            return DailyReport(date = date)
        }

        val values = glucoseRecords.map { it.value }
        val avg = values.average()
        val min = values.min()
        val max = values.max()
        val std = calculateStd(values)

        // TIR
        val tirResult = calculateTIR(glucoseRecords)

        // GRI
        val gri = calculateGRI(glucoseRecords)

        // HbA1c估算
        val hba1c = estimateHbA1c(avg)

        // 饮食统计
        val totalCarbs = mealRecords.sumOf { it.totalCarbs }
        val totalCalories = mealRecords.sumOf { it.totalCalories }

        // 胰岛素统计
        val totalInsulin = insulinRecords.sumOf { it.doseUnits }

        // 运动统计
        val totalSteps = exerciseRecords.sumOf { it.steps ?: 0 }
        val totalExerciseMin = exerciseRecords.sumOf { it.durationMin ?: 0 }

        // 亮点和待改进
        val highlights = mutableListOf<String>()
        val improvements = mutableListOf<String>()

        if (tirResult.inRange >= 70) highlights.add("TIR达标 (${String.format("%.0f", tirResult.inRange)}%)")
        if (avg in 3.9..10.0) highlights.add("平均血糖在目标范围内")
        if (std < 2.0) highlights.add("血糖稳定性良好")

        if (tirResult.inRange < 70) improvements.add("TIR未达标，建议加强血糖管理")
        if (avg > 10.0) improvements.add("平均血糖偏高，注意饮食和胰岛素")
        if (std > 3.0) improvements.add("血糖波动大，建议规律饮食")

        return DailyReport(
            date = date,
            avgGlucose = avg,
            minGlucose = min,
            maxGlucose = max,
            stdGlucose = std,
            tir = tirResult.inRange,
            tirLow = tirResult.belowRange,
            tirHigh = tirResult.aboveRange,
            gri = gri,
            hba1cEstimate = hba1c,
            totalCarbs = totalCarbs,
            totalCalories = totalCalories,
            totalInsulin = totalInsulin,
            totalSteps = totalSteps,
            totalExerciseMin = totalExerciseMin,
            recordCount = glucoseRecords.size,
            highlights = highlights,
            improvements = improvements
        )
    }

    /**
     * 生成周报告
     */
    fun generateWeeklyReport(
        startDate: Long,
        endDate: Long,
        dailyReports: List<DailyReport>
    ): WeeklyReport {
        if (dailyReports.isEmpty()) {
            return WeeklyReport(startDate = startDate, endDate = endDate)
        }

        val avgTIR = dailyReports.map { it.tir }.average()
        val avgGlucose = dailyReports.map { it.avgGlucose }.average()
        val glucoseVariability = dailyReports.map { it.stdGlucose }.average()
        val totalCarbs = dailyReports.sumOf { it.totalCarbs }
        val totalInsulin = dailyReports.sumOf { it.totalInsulin }
        val totalSteps = dailyReports.sumOf { it.totalSteps }

        val highlights = mutableListOf<String>()
        val improvements = mutableListOf<String>()

        if (avgTIR >= 70) highlights.add("TIR达标 (${String.format("%.0f", avgTIR)}%)")
        if (avgGlucose in 3.9..10.0) highlights.add("平均血糖在目标范围内")

        if (avgTIR < 70) improvements.add("TIR未达标，建议加强血糖管理")
        if (avgGlucose > 10.0) improvements.add("平均血糖偏高")

        return WeeklyReport(
            startDate = startDate,
            endDate = endDate,
            avgTir = avgTIR,
            avgGlucose = avgGlucose,
            glucoseVariability = glucoseVariability,
            totalCarbs = totalCarbs,
            totalInsulin = totalInsulin,
            totalSteps = totalSteps,
            highlights = highlights,
            improvements = improvements
        )
    }

    /**
     * 生成月报告
     */
    fun generateMonthlyReport(
        year: Int,
        month: Int,
        dailyReports: List<DailyReport>
    ): MonthlyReport {
        if (dailyReports.isEmpty()) {
            return MonthlyReport(year = year, month = month)
        }

        val avgTIR = dailyReports.map { it.tir }.average()
        val avgGlucose = dailyReports.map { it.avgGlucose }.average()
        val hba1c = estimateHbA1c(avgGlucose)

        val tirTrend = dailyReports.map { report ->
            mapOf("date" to report.date, "tir" to report.tir)
        }

        val recommendations = mutableListOf<String>()
        if (avgTIR < 70) recommendations.add("建议加强血糖监测频率")
        if (hba1c > 7.0) recommendations.add("HbA1c偏高，建议就医调整方案")

        return MonthlyReport(
            year = year,
            month = month,
            avgTir = avgTIR,
            avgGlucose = avgGlucose,
            hba1cEstimate = hba1c,
            tirTrend = tirTrend,
            recommendations = recommendations
        )
    }

    /**
     * 计算TIR
     */
    private fun calculateTIR(records: List<GlucoseRecord>): TIRResult {
        if (records.isEmpty()) return TIRResult()

        val total = records.size.toDouble()
        val inRange = records.count { it.value in 3.9..10.0 }
        val belowRange = records.count { it.value < 3.9 }
        val aboveRange = records.count { it.value > 10.0 }

        return TIRResult(
            inRange = inRange / total * 100,
            belowRange = belowRange / total * 100,
            aboveRange = aboveRange / total * 100
        )
    }

    /**
     * 计算GRI
     */
    private fun calculateGRI(records: List<GlucoseRecord>): Double {
        if (records.isEmpty()) return 0.0

        val total = records.size.toDouble()
        val severeLowCount = records.count { it.value < 3.0 }
        val lowCount = records.count { it.value in 3.0..3.9 }
        val highCount = records.count { it.value in 10.0..13.9 }
        val severeHighCount = records.count { it.value > 13.9 }

        return 3.0 * (severeLowCount / total * 100) +
                2.4 * (lowCount / total * 100) +
                1.6 * (highCount / total * 100) +
                0.8 * (severeHighCount / total * 100)
    }

    /**
     * 估算HbA1c
     */
    fun estimateHbA1c(avgGlucoseMmol: Double): Double {
        val avgMgDl = avgGlucoseMmol * 18.0
        return (avgMgDl + 46.7) / 28.7
    }

    /**
     * 计算标准差
     */
    private fun calculateStd(values: List<Double>): Double {
        if (values.size <= 1) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return if (variance > 0) sqrt(variance) else 0.0
    }

    data class TIRResult(
        val inRange: Double = 0.0,
        val belowRange: Double = 0.0,
        val aboveRange: Double = 0.0
    )
}
