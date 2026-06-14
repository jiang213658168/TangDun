package com.tangdun.app.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.tangdun.app.TangDunApp
import com.tangdun.app.data.local.entity.GlucoseRecord
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 数据同步Worker
 *
 * 数据来源：
 * - 血糖：xDrip+（CGM设备）或手动输入
 * - 心率/步数/运动/睡眠：华为Health Kit（华为手表）
 *
 * 同步频率：每5分钟
 */
class DataSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "DataSyncWorker"
        private const val WORK_NAME = "health_data_sync"
        private const val ONE_TIME_WORK_NAME = "health_data_sync_one_time"

        /**
         * 启动定期同步（每5分钟）
         */
        fun schedulePeriodicSync(context: Context) {
            val syncRequest = PeriodicWorkRequestBuilder<DataSyncWorker>(
                Duration.ofMinutes(5)
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                1L,
                TimeUnit.MINUTES
            )
            .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )

            Log.i(TAG, "已启动定期同步（每5分钟）")
        }

        /**
         * 立即同步一次
         */
        fun scheduleOneTimeSync(context: Context) {
            val syncRequest = OneTimeWorkRequestBuilder<DataSyncWorker>()
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
        }

        /**
         * 取消同步
         */
        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "已取消同步")
        }
    }

    // 血糖数据源：xDrip+
    private val xDripManager = XDripManager(context)

    // 华为手表数据源
    private val huaweiHealthManager = HuaweiHealthManager(context)

    // 本地数据库
    private val database by lazy {
        try {
            TangDunApp.getDatabase(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "数据库初始化失败: ${e.message}")
            null
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "开始同步数据...")

        return try {
            // 1. 从xDrip+同步血糖
            syncGlucoseFromXDrip()

            // 2. 从华为手表同步心率、步数等
            syncFromHuaweiWatch()

            // 3. 触发自学习 (每5分钟一次，足够频率)
            syncOnlineLearning()

            Log.d(TAG, "数据同步完成")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "同步失败: ${e.message}", e)
            Result.retry()
        }
    }

    /** 触发个性化自学习 (OnlineLearner + IncrementalLearner) */
    private suspend fun syncOnlineLearning() {
        try {
            val dao = database?.glucoseDao() ?: return
            val learner = com.tangdun.app.domain.algorithm.PersonalizedPredictor(applicationContext)
            learner.initialize()
            learner.learn(dao)
            Log.d(TAG, "自学习: ${learner.getLearningStatus()}")
        } catch (e: Exception) {
            Log.w(TAG, "自学习失败: ${e.message}")
        }
    }

    /**
     * 从xDrip+同步血糖数据
     */
    private suspend fun syncGlucoseFromXDrip() {
        try {
            val readings = xDripManager.getGlucoseHistory(hours = 24)

            if (readings.isEmpty()) {
                Log.d(TAG, "xDrip+无血糖数据")
                return
            }

            Log.d(TAG, "从xDrip+获取${readings.size}条血糖数据")

            val glucoseDao = database?.glucoseDao() ?: return

            // 批量去重: 一次查询时间范围，一次批量插入
            if (readings.isEmpty()) return
            val timeRange = readings.first().timestamp..readings.last().timestamp
            val existing = glucoseDao.getByTimeRange(timeRange.start, timeRange.endInclusive)
                .map { it.timestamp }.toSet()
            val newRecords = readings
                .filter { it.timestamp !in existing }
                .map { r -> GlucoseRecord(timestamp = r.timestamp, value = r.valueMmol, source = "xdrip", trend = r.trend) }
            if (newRecords.isNotEmpty()) glucoseDao.insertAll(newRecords)
            val savedCount = newRecords.size

            if (savedCount > 0) {
                Log.d(TAG, "xDrip+同步完成，新增${savedCount}条血糖数据")
                updateSyncStatus("xdrip_glucose", savedCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "xDrip+同步失败: ${e.message}")
        }
    }

    /**
     * 从华为手表同步心率、步数、睡眠
     */
    private suspend fun syncFromHuaweiWatch() {
        if (!huaweiHealthManager.isAvailable()) {
            Log.d(TAG, "华为Health Kit不可用")
            return
        }

        try {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 3600 * 1000  // 最近1小时

            val watchData = huaweiHealthManager.getAllWatchData(startTime, endTime)

            val heartRateCount = (watchData["heart_rate"] as? List<*>)?.size ?: 0
            val stepsCount = (watchData["steps"] as? List<*>)?.size ?: 0
            val sleepCount = (watchData["sleep"] as? List<*>)?.size ?: 0

            val total = heartRateCount + stepsCount + sleepCount
            if (total > 0) {
                Log.d(TAG, "华为手表数据: 心率${heartRateCount}条, 步数${stepsCount}条, 睡眠${sleepCount}条")
                updateSyncStatus("huawei_watch", total)
            }
        } catch (e: Exception) {
            Log.e(TAG, "华为手表同步失败: ${e.message}")
        }
    }

    /**
     * 更新同步状态
     */
    private fun updateSyncStatus(source: String, count: Int) {
        val sharedPref = applicationContext.getSharedPreferences("sync_status", Context.MODE_PRIVATE)

        with(sharedPref.edit()) {
            putString("last_sync_time", java.time.Instant.now().toString())
            putString("last_sync_source", source)
            putInt("last_sync_count", count)
            putInt("total_sync_count", sharedPref.getInt("total_sync_count", 0) + count)
            apply()
        }
    }
}
