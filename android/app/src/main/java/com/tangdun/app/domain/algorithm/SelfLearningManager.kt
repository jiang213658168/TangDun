package com.tangdun.app.domain.algorithm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * 自学习管理器 — 唯一入口，统一管理所有学习逻辑
 *
 * 架构:
 *   TangDunApp.onCreate() → start() → 常驻Application Scope
 *   ↓
 *   四层学习:
 *     Layer 0 (EDOC):   每条误差→即时参数纠正 (新, ~30ms)
 *     Layer 1 (统计):   每条新血糖→OnlineLearner (轻量, ~100ms)
 *     Layer 2 (增量):   每12条→IncrementalLearner SGD (较重, ~500ms)
 *     Layer 3 (梯度):   DallaMan参数缓慢在线梯度 (~200ms, 低频)
 *
 * 数据质量系统:
 *   诚实查询mealDao/insulinDao判断数据完整度
 *
 * 预测缓存:
 *   storePrediction() → EDOC查历史预测→对新读数算误差→即时修正
 */
class SelfLearningManager(private val context: Context) {

    companion object {
        private const val TAG = "SelfLearn"
        @Volatile private var instance: SelfLearningManager? = null

        fun init(appContext: Context) {
            if (instance == null) {
                instance = SelfLearningManager(appContext)
                instance?.start()
            }
        }

        /** 读取学习状态 (供Settings/Prediction等使用) */
        fun getOnlineLearner(): OnlineLearner = instance!!.onlineLearner
        fun getIncrementalLearner(): IncrementalLearner = instance!!.incrementalLearner
        fun getEDOCCorrector(): EDOCCorrector = instance!!.edocCorrector

        /**
         * 存储一次预测结果 (供 PredictionViewModel 调用)
         * EDOC之后会拿实际血糖和这个预测对比, 算误差, 即时修正
         */
        fun storePrediction(glucose: Float, curve: FloatArray) {
            instance?.edocCorrector?.storePrediction(glucose, curve)
        }

        /**
         * 更新DallaMan基础参数 (供 PredictionViewModel 调用)
         * EDOC需要这些参数来计算灵敏度梯度
         */
        fun setBaseParams(params: DallaManModel.Parameters) {
            instance?.currentBaseParams = params
        }

        /** 获取当前DallaMan基础参数 (供 HomeViewModel 导入时使用) */
        fun getBaseParams(): DallaManModel.Parameters? = instance?.currentBaseParams

        /**
         * 外部通知: 已记录饮食 → 更新数据完整度
         */
        fun notifyMealRecorded() {
            instance?.scope?.launch {
                try {
                    val (hasMeals, hasInsulin) = instance!!.checkDataCompleteness()
                    instance!!.onlineLearner.updateDataCompleteness(true, hasInsulin)
                } catch (e: Exception) {
                    Log.w(TAG, "notifyMealRecorded失败: ${e.message}")
                }
            }
        }

        /**
         * 外部通知: 已记录胰岛素 → 更新数据完整度
         */
        fun notifyInsulinRecorded() {
            instance?.scope?.launch {
                try {
                    val (hasMeals, _) = instance!!.checkDataCompleteness()
                    instance!!.onlineLearner.updateDataCompleteness(hasMeals, true)
                } catch (e: Exception) {
                    Log.w(TAG, "notifyInsulinRecorded失败: ${e.message}")
                }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val onlineLearner = OnlineLearner(context)
    val incrementalLearner = IncrementalLearner(context)
    val edocCorrector = EDOCCorrector(context)
    private var readingCount = 0

    // 当前DallaMan基础参数缓存 (由PredictionViewModel传入)
    @Volatile var currentBaseParams: DallaManModel.Parameters? = null

    /**
     * 诚实检查: 真实查询数据库判断数据完整度
     */
    private suspend fun checkDataCompleteness(): Pair<Boolean, Boolean> {
        val db = com.tangdun.app.TangDunApp.getDatabase(context)
        val now = System.currentTimeMillis()
        val oneDayAgo = now - 24 * 3600 * 1000
        val hasMeals = db.mealDao().getCount(oneDayAgo, now) > 0
        val hasInsulin = db.insulinDao().getCount(oneDayAgo, now) > 0
        return Pair(hasMeals, hasInsulin)
    }

    private fun start() {
        Log.i(TAG, "自学习引擎启动 (含EDOC即时纠错)")

        scope.launch {
            delay(5000)
            val db = com.tangdun.app.TangDunApp.getDatabase(context)
            val dao = db.glucoseDao()

            dao.getLatestFlow().collect { latest ->
                if (latest == null) return@collect

                try {
                    readingCount++

                    // ══════ L0: EDOC即时纠错 ══════
                    val baseParams = currentBaseParams
                    if (baseParams != null) {
                        // 质量分数: CGM已校准=0.9, 指尖血=0.95, 手动=0.7, 默认=0.7
                        val quality = when (latest.source) {
                            "finger" -> 0.95
                            "cgm" -> if (latest.isCalibrated) 0.9 else 0.7
                            else -> 0.7
                        }
                        val action = edocCorrector.onNewReading(
                            latest.value, quality, baseParams
                        )
                        if (action != null && readingCount % 6 == 0) {  // 每30分钟打印一次
                            Log.d(TAG, "EDOC: ${action.timeHorizon} e=${String.format("%.1f", action.error)} " +
                                "${action.errorType} → ${action.paramDeltas.size}参数已修正")
                        }
                    }

                    // ══════ L1: 统计学习 ══════
                    if (onlineLearner.learn(dao)) {
                        if (readingCount % 12 == 0) {
                            val (hasMeals, hasInsulin) = checkDataCompleteness()
                            onlineLearner.updateDataCompleteness(hasMeals, hasInsulin)
                        }
                    }

                    // ══════ L2+L3: 增量SGD + 数据质量检查 ══════
                    if (readingCount % 12 == 0) {
                        val (hasMeals, hasInsulin) = checkDataCompleteness()
                        onlineLearner.updateDataCompleteness(hasMeals, hasInsulin)
                        incrementalLearner.periodicLearn(dao)
                        Log.d(TAG, "增量学习@${readingCount}条 | " +
                            "EDOC:${edocCorrector.getStatus().totalCorrections}次 | " +
                            "C:${String.format("%.1f", onlineLearner.getPersonalParams().dataCompleteness)}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "学习失败: ${e.message}")
                }
            }
        }
    }

    fun getStatus(): Map<String, Any> = mapOf(
        "stage" to onlineLearner.getLearningStage().name,
        "stage_desc" to onlineLearner.getStageDescription(),
        "params" to onlineLearner.getPersonalParams(),
        "incremental" to incrementalLearner.getStats(),
        "edoc" to mapOf(
            "total_corrections" to edocCorrector.getStatus().totalCorrections,
            "corrections_today" to edocCorrector.getStatus().correctionsToday,
            "error_trend" to edocCorrector.getStatus().errorTrend,
            "adjustment_rate" to edocCorrector.getStatus().adjustmentRate,
            "recent_mae" to edocCorrector.getStatus().recentMAE,
            "param_drifts" to edocCorrector.getStatus().paramDrifts
        )
    )
}
