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
 * 不再散落在HomeVM/PredVM/DataSyncWorker中
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
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val onlineLearner = OnlineLearner(context)
    val incrementalLearner = IncrementalLearner(context)
    private var readingCount = 0

    private fun start() {
        Log.i(TAG, "自学习引擎启动")

        scope.launch {
            delay(5000)
            val db = com.tangdun.app.TangDunApp.getDatabase(context)
            val dao = db.glucoseDao()

            dao.getLatestFlow().collect { latest ->
                if (latest == null) return@collect

                try {
                    // Layer 1: 每条新血糖 → 统计学习
                    if (onlineLearner.learn(dao)) {
                        onlineLearner.updateDataCompleteness(false, false)  // 标记: 有血糖数据
                        Log.d(TAG, "快速学习: ${onlineLearner.getStageDescription()}")
                    }

                    // Layer 2: 每12条新数据(≈1h) → 增量SGD
                    readingCount++
                    if (readingCount % 12 == 0) {
                        incrementalLearner.periodicLearn(dao)
                        Log.d(TAG, "增量学习@${readingCount}条: ${incrementalLearner.getStats()["updates"]}次")
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
