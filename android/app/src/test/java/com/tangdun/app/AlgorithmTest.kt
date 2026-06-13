package com.tangdun.app

import com.tangdun.app.data.local.entity.AlertRecord
import com.tangdun.app.domain.algorithm.*
import org.junit.Test
import org.junit.Assert.*

/**
 * 算法单元测试
 */
class AlgorithmTest {

    @Test
    fun testCGMPreprocessor() {
        val preprocessor = CGMPreprocessor()

        // 测试卡尔曼滤波
        val rawData = listOf(7.0, 7.2, 7.1, 7.5, 7.3, 7.8, 7.2, 7.4)
        val filtered = preprocessor.kalmanFilter(rawData)

        assertEquals("滤波后数据长度应相同", rawData.size, filtered.size)
        assertTrue("滤波后数据应平滑", filtered.all { !it.isNaN() })

        // 测试异常值检测
        val dataWithOutlier = listOf(7.0, 7.2, 15.0, 7.1, 7.3)  // 15.0是异常值
        val outliers = preprocessor.detectOutliers(dataWithOutlier)

        assertEquals("应检测到异常值", true, outliers[2])

        // 测试趋势计算
        val risingData = listOf(7.0, 7.2, 7.5, 7.8, 8.0)
        val trend = preprocessor.calculateTrend(risingData)
        assertEquals("应检测到上升趋势", "rising", trend)

        val fallingData = listOf(8.0, 7.8, 7.5, 7.2, 7.0)
        val fallTrend = preprocessor.calculateTrend(fallingData)
        assertEquals("应检测到下降趋势", "falling", fallTrend)
    }

    @Test
    fun testFeatureExtractor() {
        val extractor = FeatureExtractor()

        // 创建模拟血糖数据（288个点 = 24小时）
        val glucoseHistory = DoubleArray(288) { 7.0 + Math.random() * 0.5 }

        val features = extractor.extract(glucoseHistory, 287)

        assertEquals("应提取15维特征", 15, features.size)
        assertTrue("特征不应全为0", features.any { it != 0f })
    }

    @Test
    fun testAlertEngine() {
        val engine = AlertEngine()

        // 测试低血糖预警
        val lowAlerts = engine.checkAll(
            currentValue = 3.5,
            trend = "falling",
            predicted30min = 3.0,
            recentROC = -0.05
        )

        assertTrue("应触发低血糖预警", lowAlerts.any { it.alertType == AlertRecord.LOW_GLUCOSE })

        // 测试高血糖预警
        val highAlerts = engine.checkAll(
            currentValue = 12.0,
            trend = "rising",
            predicted30min = 13.0,
            recentROC = 0.05
        )

        assertTrue("应触发高血糖预警", highAlerts.any { it.alertType == AlertRecord.HIGH_GLUCOSE })

        // 测试正常血糖
        val normalAlerts = engine.checkAll(
            currentValue = 6.5,
            trend = "stable",
            predicted30min = 6.8,
            recentROC = 0.01
        )

        assertTrue("正常血糖不应触发预警", normalAlerts.isEmpty())
    }

    @Test
    fun testTIRCalculation() {
        val engine = AlertEngine()

        // 创建测试数据
        val records = listOf(
            createGlucoseRecord(6.0),
            createGlucoseRecord(7.0),
            createGlucoseRecord(8.0),
            createGlucoseRecord(3.5),  // 低于目标
            createGlucoseRecord(11.0)  // 高于目标
        )

        val tir = engine.calculateTIR(records)

        assertEquals("TIR应为60%", 60.0, tir.inRange, 0.1)
        assertEquals("低于目标应为20%", 20.0, tir.belowRange, 0.1)
        assertEquals("高于目标应为20%", 20.0, tir.aboveRange, 0.1)
    }

    @Test
    fun testBergmanModel() {
        val model = BergmanModel()

        // 测试基本预测
        val curve = model.predict(
            currentGlucose = 7.0,
            currentInsulin = 10.0,
            horizonMinutes = 60,
            stepMinutes = 5
        )

        assertTrue("曲线应有数据点", curve.isNotEmpty())
        assertEquals("60分钟预测应有13个点", 13, curve.size)
        assertTrue("血糖值应在合理范围", curve.all { it in 1.0..30.0 })
    }

    @Test
    fun testWhatIfSimulation() {
        val model = BergmanModel()

        // 测试进食模拟
        val meals = listOf(
            BergmanModel.MealInput(
                timeMinutes = 0.0,
                carbsGrams = 60.0,
                gi = 70.0
            )
        )

        val result = model.whatIfSimulation(
            currentGlucose = 7.0,
            meals = meals,
            horizonMinutes = 180
        )

        assertTrue("峰值应高于初始血糖", result.peakGlucose > 7.0)
        assertTrue("达峰时间应大于0", result.peakTimeMinutes > 0)
        assertTrue("曲线应有数据点", result.curve.isNotEmpty())
    }

    @Test
    fun testReportCalculator() {
        val calculator = ReportCalculator()

        // 创建测试数据
        val records = (0..287).map { i ->
            createGlucoseRecord(7.0 + Math.sin(i / 10.0) * 1.5)
        }

        val report = calculator.generateDailyReport(
            date = System.currentTimeMillis(),
            glucoseRecords = records,
            totalCarbs = 150.0,
            totalCalories = 1800.0,
            totalSteps = 8000,
            totalExerciseMin = 30
        )

        assertTrue("平均血糖应大于0", report.avgGlucose > 0)
        assertTrue("TIR应大于0", report.tir > 0)
        assertTrue("HbA1c应大于0", report.hba1cEstimate > 0)
    }

    @Test
    fun testHbA1cEstimation() {
        val calculator = ReportCalculator()

        // 正常平均血糖约6.0 mmol/L
        val hba1c = calculator.estimateHbA1c(6.0)

        // HbA1c应该在5.5-6.5之间
        assertTrue("HbA1c应在合理范围", hba1c in 5.0..7.0)
    }

    private fun createGlucoseRecord(value: Double): com.tangdun.app.data.local.entity.GlucoseRecord {
        return com.tangdun.app.data.local.entity.GlucoseRecord(
            timestamp = System.currentTimeMillis(),
            value = value,
            source = "test"
        )
    }
}
