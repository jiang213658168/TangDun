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
 *   两层学习:
 *     Layer 1 (统计): 每条新血糖→OnlineLearner (轻量, ~5min一次)
 *     Layer 2 (增量): 每30分钟→IncrementalLearner (较重, SGD)
 *
 * 数据质量系统:
 *   不再盲目传(false,false), 而是真实查询mealDao/insulinDao
 *   检查过去24小时内是否有饮食/胰岛素记录
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

        /**
         * 外部通知: 已记录饮食 → 更新数据完整度
         * 由 MealViewModel / ChatViewModel 在插入后调用
         */
        fun notifyMealRecorded() {
            instance?.scope?.launch {
                try {
                    val (hasMeals, hasInsulin) = instance!!.checkDataCompleteness()
                    instance!!.onlineLearner.updateDataCompleteness(true, hasInsulin)
                    Log.d(TAG, "饮食记录通知: hasMeals=true, hasInsulin=$hasInsulin → " +
                        "completeness=${instance!!.onlineLearner.getPersonalParams().dataCompleteness}")
                } catch (e: Exception) {
                    Log.w(TAG, "notifyMealRecorded失败: ${e.message}")
                }
            }
        }

        /**
         * 外部通知: 已记录胰岛素 → 更新数据完整度
         * 由 InsulinViewModel / ChatViewModel 在插入后调用
         */
        fun notifyInsulinRecorded() {
            instance?.scope?.launch {
                try {
                    val (hasMeals, _) = instance!!.checkDataCompleteness()
                    instance!!.onlineLearner.updateDataCompleteness(hasMeals, true)
                    Log.d(TAG, "胰岛素记录通知: hasMeals=$hasMeals, hasInsulin=true → " +
                        "completeness=${instance!!.onlineLearner.getPersonalParams().dataCompleteness}")
                } catch (e: Exception) {
                    Log.w(TAG, "notifyInsulinRecorded失败: ${e.message}")
                }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val onlineLearner = OnlineLearner(context)
    val incrementalLearner = IncrementalLearner(context)
    private var readingCount = 0

    /**
     * 诚实检查: 真实查询数据库判断数据完整度
     * 返回 Pair(hasMeals, hasInsulin)
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
        Log.i(TAG, "自学习引擎启动")

        scope.launch {
            delay(5000)
            val db = com.tangdun.app.TangDunApp.getDatabase(context)
            val dao = db.glucoseDao()

            dao.getLatestFlow().collect { latest ->
                if (latest == null) return@collect

                try {
                    readingCount++

                    // Layer 1: 每条新血糖 → 统计学习
                    if (onlineLearner.learn(dao)) {
                        // ★ 诚实数据质量: 真实查询数据库，每12条(≈1h)完整查询
                        val (hasMeals, hasInsulin) = if (readingCount % 12 == 0) {
                            checkDataCompleteness()
                        } else {
                            // 非查询周期: 保守使用上次状态 (不完全精确但避免频繁查询)
                            Pair(false, false)  // 占位, learn()内部已更新dataCompleteness=0.3
                        }
                        if (readingCount % 12 == 0) {
                            onlineLearner.updateDataCompleteness(hasMeals, hasInsulin)
                        }
                        Log.d(TAG, "快速学习: ${onlineLearner.getStageDescription()}")
                    }

                    // Layer 2: 每12条新数据(≈1h) → 增量SGD + 完整数据质量检查
                    if (readingCount % 12 == 0) {
                        val (hasMeals, hasInsulin) = checkDataCompleteness()
                        onlineLearner.updateDataCompleteness(hasMeals, hasInsulin)
                        incrementalLearner.periodicLearn(dao)
                        Log.d(TAG, "增量学习@${readingCount}条: ${incrementalLearner.getStats()["updates"]}次 | " +
                            "completeness=${onlineLearner.getPersonalParams().dataCompleteness}")
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
        "incremental" to incrementalLearner.getStats()
    )
}
