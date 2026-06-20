package com.tangdun.app.ai

import android.content.Context
import android.util.Log
import com.tangdun.app.data.local.dao.AlertDao
import com.tangdun.app.data.local.dao.BloodPressureDao
import com.tangdun.app.data.local.dao.ExerciseDao
import com.tangdun.app.data.local.dao.GlucoseDao
import com.tangdun.app.data.local.dao.InsulinDao
import com.tangdun.app.data.local.dao.KetoneDao
import com.tangdun.app.data.local.dao.MealDao
import com.tangdun.app.data.local.dao.MedicationDao
import com.tangdun.app.data.local.dao.SleepDao
import com.tangdun.app.data.local.dao.SymptomDao
import com.tangdun.app.data.local.dao.WeightDao
import com.tangdun.app.data.local.entity.BloodPressureRecord
import com.tangdun.app.data.local.entity.ExerciseRecord
import com.tangdun.app.data.local.entity.GlucoseRecord
import com.tangdun.app.data.local.entity.InsulinRecord
import com.tangdun.app.data.local.entity.KetoneRecord
import com.tangdun.app.data.local.entity.MealRecord
import com.tangdun.app.data.local.entity.MedicationRecord
import com.tangdun.app.data.local.entity.SleepRecord
import com.tangdun.app.data.local.entity.SymptomRecord
import com.tangdun.app.data.local.entity.WeightRecord
import com.tangdun.app.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * AI 权限执行引擎 - 把 AIIntent 转为实际操作
 *
 * 设计: 同步阻塞调用, 每个执行都返回 AIExecutionResult
 * 调用者负责展示结果给用户 + 用户确认后才执行
 *
 * 支持 10 种记录类型的 CRUD + 导航 + 设置 + 导入导出
 */
class AIPermissionEngine(
    private val context: Context,
    private val glucoseDao: GlucoseDao,
    private val insulinDao: InsulinDao,
    private val mealDao: MealDao,
    private val exerciseDao: ExerciseDao,
    private val sleepDao: SleepDao,
    private val bloodPressureDao: BloodPressureDao,
    private val weightDao: WeightDao,
    private val ketoneDao: KetoneDao,
    private val medicationDao: MedicationDao,
    private val symptomDao: SymptomDao,
    private val alertDao: AlertDao,
    private val settingsManager: SettingsManager
) {

    private val TAG = "AIPermissionEngine"

    // ============== 入口: 批量执行 ==============

    suspend fun execute(intent: AIIntent): AIExecutionResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "执行意图: ${intent.type} ${intent.target} ${intent.action} params=${intent.params}")
            when (intent.type) {
                AIIntentType.CREATE -> executeCreate(intent.target, intent.params, intent.description)
                AIIntentType.READ -> executeRead(intent.target, intent.params)
                AIIntentType.UPDATE -> executeUpdate(intent.target, intent.params)
                AIIntentType.DELETE -> executeDelete(intent.target, intent.params)
                AIIntentType.BULK_DELETE -> executeBulkDelete(intent.target, intent.params)
                AIIntentType.NAVIGATE -> executeNavigate(intent.target, intent.params)
                AIIntentType.CONFIGURE -> executeConfigure(intent.action, intent.params)
                AIIntentType.EXPORT -> executeExport(intent.params)
                AIIntentType.IMPORT -> executeImport(intent.params)
            }
        } catch (e: Exception) {
            Log.w(TAG, "执行失败: ${e.message}", e)
            AIExecutionResult(
                success = false,
                message = "执行失败: ${e.message ?: "未知错误"}",
                data = null
            )
        }
    }

    suspend fun executeAll(intents: List<AIIntent>): List<AIExecutionResult> {
        return intents.map { execute(it) }
    }

    // ============== CREATE ==============

    private suspend fun executeCreate(target: String, params: Map<String, Any>, desc: String): AIExecutionResult {
        return when (target) {
            AITarget.GLUCOSE -> {
                val value = (params["value"] as? Double) ?: return fail("血糖值缺失")
                val scene = (params["scene"] as? String) ?: "other"
                val timestamp = (params["timestamp"] as? Long) ?: System.currentTimeMillis()
                val source = (params["source"] as? String) ?: "ai"
                val id = glucoseDao.insert(GlucoseRecord(
                    timestamp = timestamp, value = value, source = source, scene = scene
                ))
                AIExecutionResult(
                    success = true,
                    message = "已记录: $desc",
                    affectedIds = listOf(id),
                    refreshDataSources = listOf("glucose", "home", "prediction")
                )
            }
            AITarget.INSULIN -> {
                val dose = (params["dose"] as? Double) ?: return fail("剂量缺失")
                val doseType = (params["dose_type"] as? String) ?: "rapid"
                val timestamp = (params["timestamp"] as? Long) ?: System.currentTimeMillis()
                val id = insulinDao.insert(InsulinRecord(
                    timestamp = timestamp,
                    insulinType = doseType,
                    doseUnits = dose
                ))
                AIExecutionResult(
                    success = true,
                    message = "已记录: $desc",
                    affectedIds = listOf(id),
                    refreshDataSources = listOf("insulin", "home")
                )
            }
            AITarget.MEAL -> {
                val foodName = (params["food_name"] as? String) ?: "未命名食物"
                val carbs = (params["carbs"] as? Double) ?: 0.0
                val calories = (params["calories"] as? Double) ?: 0.0
                val gi = (params["gi"] as? Double) ?: 50.0
                val mealType = (params["meal_type"] as? String) ?: "snack"
                val protein = (params["protein"] as? Double) ?: 0.0
                val fat = (params["fat"] as? Double) ?: 0.0
                val timestamp = (params["timestamp"] as? Long) ?: System.currentTimeMillis()
                val mealRecord = MealRecord(
                    timestamp = timestamp, mealType = mealType,
                    totalCarbs = carbs, totalCalories = calories,
                    totalProtein = protein, totalFat = fat, avgGi = gi
                )
                val mealId = mealDao.insert(mealRecord)
                AIExecutionResult(
                    success = true,
                    message = "已记录: $desc",
                    affectedIds = listOf(mealId),
                    refreshDataSources = listOf("meal", "home")
                )
            }
            AITarget.EXERCISE -> {
                val type = (params["exercise_type"] as? String) ?: "walking"
                val duration = (params["duration_min"] as? Int) ?: 30
                val intensity = (params["intensity"] as? String) ?: "moderate"
                val timestamp = (params["timestamp"] as? Long) ?: System.currentTimeMillis()
                val record = ExerciseRecord(
                    startTime = timestamp - duration * 60_000L,
                    exerciseType = type,
                    durationMin = duration,
                    intensity = intensity
                )
                val id = exerciseDao.insert(record)
                AIExecutionResult(
                    success = true,
                    message = "已记录: $desc",
                    affectedIds = listOf(id),
                    refreshDataSources = listOf("exercise", "home")
                )
            }
            AITarget.SLEEP -> {
                val duration = (params["duration_minutes"] as? Int) ?: 480
                val timestamp = (params["timestamp"] as? Long) ?: System.currentTimeMillis()
                val record = SleepRecord(
                    timestamp = timestamp,
                    sleepTime = timestamp - duration * 60_000L,
                    wakeTime = timestamp,
                    durationMinutes = duration,
                    quality = "normal"
                )
                val id = sleepDao.insert(record)
                AIExecutionResult(
                    success = true,
                    message = "已记录: $desc",
                    affectedIds = listOf(id),
                    refreshDataSources = listOf("health", "home")
                )
            }
            AITarget.BP -> {
                val systolic = (params["systolic"] as? Int) ?: return fail("收缩压缺失")
                val diastolic = (params["diastolic"] as? Int) ?: return fail("舒张压缺失")
                val timestamp = (params["timestamp"] as? Long) ?: System.currentTimeMillis()
                val record = BloodPressureRecord(
                    timestamp = timestamp,
                    systolic = systolic, diastolic = diastolic
                )
                val id = bloodPressureDao.insert(record)
                AIExecutionResult(
                    success = true,
                    message = "已记录: $desc",
                    affectedIds = listOf(id),
                    refreshDataSources = listOf("health", "home")
                )
            }
            AITarget.WEIGHT -> {
                val weight = (params["weight_kg"] as? Double) ?: return fail("体重值缺失")
                val timestamp = (params["timestamp"] as? Long) ?: System.currentTimeMillis()
                val record = WeightRecord(timestamp = timestamp, weightKg = weight)
                val id = weightDao.insert(record)
                AIExecutionResult(
                    success = true,
                    message = "已记录: $desc",
                    affectedIds = listOf(id),
                    refreshDataSources = listOf("health", "home")
                )
            }
            AITarget.KETONE -> {
                val level = (params["ketone_level"] as? Double) ?: return fail("酮体值缺失")
                val timestamp = (params["timestamp"] as? Long) ?: System.currentTimeMillis()
                val record = KetoneRecord(timestamp = timestamp, ketoneLevel = level)
                val id = ketoneDao.insert(record)
                AIExecutionResult(
                    success = true,
                    message = "已记录: $desc",
                    affectedIds = listOf(id),
                    refreshDataSources = listOf("health", "home")
                )
            }
            AITarget.MEDICATION -> {
                val name = (params["medication_name"] as? String) ?: return fail("药物名缺失")
                val dose = (params["dose"] as? String) ?: ""
                val timestamp = (params["timestamp"] as? Long) ?: System.currentTimeMillis()
                val record = MedicationRecord(
                    timestamp = timestamp,
                    medicationName = name, dose = dose
                )
                val id = medicationDao.insert(record)
                AIExecutionResult(
                    success = true,
                    message = "已记录: $desc",
                    affectedIds = listOf(id),
                    refreshDataSources = listOf("health", "home")
                )
            }
            AITarget.SYMPTOM -> {
                val symptoms = (params["symptoms"] as? String) ?: return fail("症状描述缺失")
                val severity = (params["severity"] as? String) ?: "mild"
                val timestamp = (params["timestamp"] as? Long) ?: System.currentTimeMillis()
                val record = SymptomRecord(
                    timestamp = timestamp,
                    symptomType = "other",
                    severity = severity,
                    symptoms = symptoms
                )
                val id = symptomDao.insert(record)
                AIExecutionResult(
                    success = true,
                    message = "已记录: $desc",
                    affectedIds = listOf(id),
                    refreshDataSources = listOf("health", "home")
                )
            }
            else -> fail("不支持的创建目标: $target")
        }
    }

    // ============== READ ==============

    private suspend fun executeRead(target: String, params: Map<String, Any>): AIExecutionResult {
        val todayMidnight = todayStartMillis()
        val timeScope = (params["time_scope"] as? String) ?: "today"
        val (startTime, endTime) = timeRange(timeScope, todayMidnight)

        return when (target) {
            AITarget.GLUCOSE -> {
                val records = glucoseDao.getByTimeRange(startTime, endTime)
                if (records.isEmpty()) {
                    AIExecutionResult(success = true, message = "$timeScope 没有血糖记录", data = emptyList<String>())
                } else {
                    val avg = records.map { it.value }.average()
                    val max = records.maxOf { it.value }
                    val min = records.minOf { it.value }
                    val summary = """
                        血糖报告 $timeScope
                        记录数: ${records.size} 条
                        平均: ${"%.1f".format(avg)} mmol/L
                        最高: ${"%.1f".format(max)} mmol/L
                        最低: ${"%.1f".format(min)} mmol/L
                    """.trimIndent()
                    AIExecutionResult(success = true, message = summary, data = records)
                }
            }
            AITarget.INSULIN -> {
                val records = insulinDao.getRecent(50).filter { it.timestamp in startTime..endTime }
                val totalDose = records.sumOf { it.doseUnits }
                AIExecutionResult(
                    success = true,
                    message = "$timeScope 共注射 ${records.size} 针, 总剂量 ${"%.1f".format(totalDose)} U",
                    data = records
                )
            }
            AITarget.MEAL -> {
                val records = mealDao.getByTimeRange(startTime, endTime)
                val totalCarbs = records.sumOf { it.totalCarbs }
                val totalCal = records.sumOf { it.totalCalories }
                AIExecutionResult(
                    success = true,
                    message = "$timeScope 共 ${records.size} 餐, 碳水 ${"%.0f".format(totalCarbs)}g, 热量 ${"%.0f".format(totalCal)} kcal",
                    data = records
                )
            }
            AITarget.EXERCISE -> {
                val records = exerciseDao.getByTimeRange(startTime, endTime)
                val totalMin = records.sumOf { it.durationMin ?: 0 }
                AIExecutionResult(
                    success = true,
                    message = "$timeScope 共 ${records.size} 次运动, 总时长 $totalMin 分钟",
                    data = records
                )
            }
            AITarget.SLEEP -> {
                val records = sleepDao.getRecent(30).filter { it.timestamp in startTime..endTime }
                val totalMin = records.sumOf { it.durationMinutes }
                AIExecutionResult(
                    success = true,
                    message = "$timeScope 共 ${records.size} 条睡眠记录, 平均 ${"%.1f".format(totalMin / 60.0)} 小时/次",
                    data = records
                )
            }
            AITarget.BP -> {
                val records = bloodPressureDao.getRecent(30).filter { it.timestamp in startTime..endTime }
                if (records.isEmpty()) {
                    AIExecutionResult(success = true, message = "$timeScope 没有血压记录", data = records)
                } else {
                    val avgSys = records.map { it.systolic }.average()
                    val avgDia = records.map { it.diastolic }.average()
                    AIExecutionResult(
                        success = true,
                        message = "$timeScope 共 ${records.size} 次血压测量\n平均: ${"%.0f".format(avgSys)}/${"%.0f".format(avgDia)} mmHg",
                        data = records
                    )
                }
            }
            AITarget.WEIGHT -> {
                val records = weightDao.getRecent(30).filter { it.timestamp in startTime..endTime }
                val latest = records.maxByOrNull { it.timestamp }
                AIExecutionResult(
                    success = true,
                    message = if (latest != null) "最新体重: ${"%.1f".format(latest.weightKg)} kg" else "$timeScope 没有体重记录",
                    data = records
                )
            }
            AITarget.KETONE -> {
                val records = ketoneDao.getRecent(30).filter { it.timestamp in startTime..endTime }
                AIExecutionResult(
                    success = true,
                    message = if (records.isEmpty()) "$timeScope 没有酮体记录" else "$timeScope 共 ${records.size} 条酮体记录",
                    data = records
                )
            }
            AITarget.MEDICATION -> {
                val records = medicationDao.getRecent(50).filter { it.timestamp in startTime..endTime }
                AIExecutionResult(
                    success = true,
                    message = if (records.isEmpty()) "$timeScope 没有用药记录" else "$timeScope 共 ${records.size} 次用药",
                    data = records
                )
            }
            AITarget.SYMPTOM -> {
                val records = symptomDao.getRecent(50).filter { it.timestamp in startTime..endTime }
                AIExecutionResult(
                    success = true,
                    message = if (records.isEmpty()) "$timeScope 没有症状记录" else "$timeScope 共 ${records.size} 条症状记录",
                    data = records
                )
            }
            else -> fail("不支持的查询目标: $target")
        }
    }

    // ============== UPDATE ==============

    private suspend fun executeUpdate(target: String, params: Map<String, Any>): AIExecutionResult {
        val id = (params["id"] as? Long) ?: return fail("记录ID缺失")
        val timestamp = params["timestamp"] as? Long

        return when (target) {
            AITarget.GLUCOSE -> {
                val record = glucoseDao.getById(id) ?: return fail("血糖记录不存在")
                val newValue = (params["value"] as? Double) ?: record.value
                val newScene = (params["scene"] as? String) ?: record.scene
                glucoseDao.update(record.copy(
                    value = newValue,
                    scene = newScene,
                    timestamp = timestamp ?: record.timestamp
                ))
                AIExecutionResult(
                    success = true,
                    message = "已修改血糖记录 #${id} = ${"%.1f".format(newValue)} mmol/L",
                    refreshDataSources = listOf("glucose", "home")
                )
            }
            AITarget.INSULIN -> {
                val record = insulinDao.getById(id) ?: return fail("胰岛素记录不存在")
                val newDose = (params["dose"] as? Double) ?: record.doseUnits
                insulinDao.update(record.copy(
                    doseUnits = newDose,
                    timestamp = timestamp ?: record.timestamp
                ))
                AIExecutionResult(
                    success = true,
                    message = "已修改胰岛素记录 #${id} = ${"%.1f".format(newDose)} U",
                    refreshDataSources = listOf("insulin", "home")
                )
            }
            AITarget.MEAL -> {
                val record = mealDao.getById(id) ?: return fail("饮食记录不存在")
                val newCarbs = (params["carbs"] as? Double) ?: record.totalCarbs
                mealDao.update(record.copy(
                    totalCarbs = newCarbs,
                    timestamp = timestamp ?: record.timestamp
                ))
                AIExecutionResult(
                    success = true,
                    message = "已修改饮食记录 #$id",
                    refreshDataSources = listOf("meal", "home")
                )
            }
            AITarget.EXERCISE -> {
                val record = exerciseDao.getById(id) ?: return fail("运动记录不存在")
                val newDuration = (params["duration_min"] as? Int) ?: record.durationMin
                exerciseDao.update(record.copy(
                    durationMin = newDuration,
                    startTime = timestamp ?: record.startTime
                ))
                AIExecutionResult(
                    success = true,
                    message = "已修改运动记录 #$id",
                    refreshDataSources = listOf("exercise", "home")
                )
            }
            AITarget.SLEEP -> {
                val record = sleepDao.getById(id) ?: return fail("睡眠记录不存在")
                val newDuration = (params["duration_minutes"] as? Int) ?: record.durationMinutes
                val newQuality = (params["quality"] as? String) ?: record.quality
                sleepDao.update(record.copy(
                    durationMinutes = newDuration,
                    quality = newQuality,
                    timestamp = timestamp ?: record.timestamp
                ))
                AIExecutionResult(
                    success = true,
                    message = "已修改睡眠记录 #$id (${newDuration}分钟, $newQuality)",
                    refreshDataSources = listOf("health")
                )
            }
            AITarget.BP -> {
                val record = bloodPressureDao.getById(id) ?: return fail("血压记录不存在")
                val newSys = (params["systolic"] as? Int) ?: record.systolic
                val newDia = (params["diastolic"] as? Int) ?: record.diastolic
                bloodPressureDao.update(record.copy(
                    systolic = newSys,
                    diastolic = newDia,
                    timestamp = timestamp ?: record.timestamp
                ))
                AIExecutionResult(
                    success = true,
                    message = "已修改血压记录 #$id = $newSys/$newDia mmHg",
                    refreshDataSources = listOf("health")
                )
            }
            AITarget.WEIGHT -> {
                val record = weightDao.getById(id) ?: return fail("体重记录不存在")
                val newKg = (params["weight_kg"] as? Double) ?: record.weightKg
                weightDao.update(record.copy(
                    weightKg = newKg,
                    timestamp = timestamp ?: record.timestamp
                ))
                AIExecutionResult(
                    success = true,
                    message = "已修改体重记录 #$id = ${"%.1f".format(newKg)} kg",
                    refreshDataSources = listOf("health")
                )
            }
            AITarget.KETONE -> {
                val record = ketoneDao.getById(id) ?: return fail("酮体记录不存在")
                val newLevel = (params["ketone_level"] as? Double) ?: record.ketoneLevel
                ketoneDao.update(record.copy(
                    ketoneLevel = newLevel,
                    timestamp = timestamp ?: record.timestamp
                ))
                AIExecutionResult(
                    success = true,
                    message = "已修改酮体记录 #$id = ${"%.2f".format(newLevel)} mmol/L",
                    refreshDataSources = listOf("health")
                )
            }
            AITarget.MEDICATION -> {
                val record = medicationDao.getById(id) ?: return fail("用药记录不存在")
                val newName = (params["medication_name"] as? String) ?: record.medicationName
                val newDose = (params["dose"] as? String) ?: record.dose
                medicationDao.update(record.copy(
                    medicationName = newName,
                    dose = newDose,
                    timestamp = timestamp ?: record.timestamp
                ))
                AIExecutionResult(
                    success = true,
                    message = "已修改用药记录 #$id = $newName $newDose",
                    refreshDataSources = listOf("health")
                )
            }
            AITarget.SYMPTOM -> {
                val record = symptomDao.getById(id) ?: return fail("症状记录不存在")
                val newSymptoms = (params["symptoms"] as? String) ?: record.symptoms
                val newSeverity = (params["severity"] as? String) ?: record.severity
                symptomDao.update(record.copy(
                    symptoms = newSymptoms,
                    severity = newSeverity,
                    timestamp = timestamp ?: record.timestamp
                ))
                AIExecutionResult(
                    success = true,
                    message = "已修改症状记录 #$id = $newSymptoms ($newSeverity)",
                    refreshDataSources = listOf("health")
                )
            }
            else -> fail("不支持的更新目标: $target")
        }
    }

    // ============== DELETE ==============

    private suspend fun executeDelete(target: String, params: Map<String, Any>): AIExecutionResult {
        val id = (params["id"] as? Long) ?: return fail("记录ID缺失")
        return when (target) {
            AITarget.GLUCOSE -> {
                glucoseDao.deleteById(id)
                AIExecutionResult(success = true, message = "已删除血糖记录 #$id", refreshDataSources = listOf("glucose"))
            }
            AITarget.INSULIN -> {
                insulinDao.deleteById(id)
                AIExecutionResult(success = true, message = "已删除胰岛素记录 #$id", refreshDataSources = listOf("insulin"))
            }
            AITarget.MEAL -> {
                mealDao.deleteById(id)
                AIExecutionResult(success = true, message = "已删除饮食记录 #$id", refreshDataSources = listOf("meal"))
            }
            AITarget.EXERCISE -> {
                exerciseDao.deleteById(id)
                AIExecutionResult(success = true, message = "已删除运动记录 #$id", refreshDataSources = listOf("exercise"))
            }
            AITarget.SLEEP -> {
                sleepDao.deleteById(id)
                AIExecutionResult(success = true, message = "已删除睡眠记录 #$id", refreshDataSources = listOf("health"))
            }
            AITarget.BP -> {
                bloodPressureDao.deleteById(id)
                AIExecutionResult(success = true, message = "已删除血压记录 #$id", refreshDataSources = listOf("health"))
            }
            AITarget.WEIGHT -> {
                weightDao.deleteById(id)
                AIExecutionResult(success = true, message = "已删除体重记录 #$id", refreshDataSources = listOf("health"))
            }
            AITarget.KETONE -> {
                ketoneDao.deleteById(id)
                AIExecutionResult(success = true, message = "已删除酮体记录 #$id", refreshDataSources = listOf("health"))
            }
            AITarget.MEDICATION -> {
                medicationDao.deleteById(id)
                AIExecutionResult(success = true, message = "已删除用药记录 #$id", refreshDataSources = listOf("health"))
            }
            AITarget.SYMPTOM -> {
                symptomDao.deleteById(id)
                AIExecutionResult(success = true, message = "已删除症状记录 #$id", refreshDataSources = listOf("health"))
            }
            else -> fail("不支持的删除目标: $target")
        }
    }

    // ============== BULK_DELETE ==============

    private suspend fun executeBulkDelete(target: String, params: Map<String, Any>): AIExecutionResult {
        val todayMidnight = todayStartMillis()
        val timeScope = (params["time_scope"] as? String) ?: "today"
        val (startTime, endTime) = timeRange(timeScope, todayMidnight)

        val deletedCount = when (target) {
            AITarget.GLUCOSE -> {
                val records = glucoseDao.getByTimeRange(startTime, endTime)
                records.forEach { glucoseDao.delete(it) }
                records.size
            }
            AITarget.INSULIN -> {
                val records = insulinDao.getRecent(100).filter { it.timestamp in startTime..endTime }
                records.forEach { insulinDao.delete(it) }
                records.size
            }
            AITarget.MEAL -> {
                val records = mealDao.getByTimeRange(startTime, endTime)
                records.forEach { mealDao.delete(it) }
                records.size
            }
            AITarget.EXERCISE -> {
                val records = exerciseDao.getByTimeRange(startTime, endTime)
                records.forEach { exerciseDao.delete(it) }
                records.size
            }
            AITarget.SLEEP -> {
                val records = sleepDao.getRecent(100).filter { it.timestamp in startTime..endTime }
                records.forEach { sleepDao.delete(it) }
                records.size
            }
            AITarget.BP -> {
                val records = bloodPressureDao.getRecent(100).filter { it.timestamp in startTime..endTime }
                records.forEach { bloodPressureDao.delete(it) }
                records.size
            }
            AITarget.WEIGHT -> {
                val records = weightDao.getRecent(100).filter { it.timestamp in startTime..endTime }
                records.forEach { weightDao.delete(it) }
                records.size
            }
            AITarget.KETONE -> {
                val records = ketoneDao.getRecent(100).filter { it.timestamp in startTime..endTime }
                records.forEach { ketoneDao.delete(it) }
                records.size
            }
            AITarget.MEDICATION -> {
                val records = medicationDao.getRecent(100).filter { it.timestamp in startTime..endTime }
                records.forEach { medicationDao.delete(it) }
                records.size
            }
            AITarget.SYMPTOM -> {
                val records = symptomDao.getRecent(100).filter { it.timestamp in startTime..endTime }
                records.forEach { symptomDao.delete(it) }
                records.size
            }
            else -> return fail("不支持的批量删除目标: $target")
        }
        val scopeText = timeScopeDisplay(timeScope)
        return AIExecutionResult(
            success = true,
            message = "已删除 $scopeText $deletedCount 条${targetDisplayName(target)}记录",
            refreshDataSources = listOf(target, "home", "prediction")
        )
    }

    // ============== NAVIGATE ==============

    private fun executeNavigate(target: String, params: Map<String, Any>): AIExecutionResult {
        val route = (params["route"] as? String) ?: return fail("目标页面缺失")
        return AIExecutionResult(
            success = true,
            message = "正在跳转到 $route",
            navigateTo = route
        )
    }

    // ============== CONFIGURE ==============

    private fun executeConfigure(action: String, params: Map<String, Any>): AIExecutionResult {
        return when (action) {
            "target_range" -> {
                AIExecutionResult(success = true, message = "目标范围设置请在设置页调整", navigateTo = "settings")
            }
            "ai_config" -> {
                AIExecutionResult(success = true, message = "AI 配置请在设置页调整", navigateTo = "settings")
            }
            "personal_info" -> {
                AIExecutionResult(success = true, message = "个人信息请在设置页调整", navigateTo = "settings")
            }
            "notification" -> {
                AIExecutionResult(success = true, message = "通知设置请在设置页调整", navigateTo = "settings")
            }
            "data_backup", "data_share" -> {
                AIExecutionResult(success = true, message = "数据备份/共享请在设置页操作", navigateTo = "settings")
            }
            else -> fail("不支持的配置项: $action")
        }
    }

    // ============== EXPORT/IMPORT ==============

    private suspend fun executeExport(params: Map<String, Any>): AIExecutionResult {
        return AIExecutionResult(success = true, message = "数据导出请在设置页操作 (支持 CSV/Excel/JSON)", navigateTo = "settings")
    }

    private suspend fun executeImport(params: Map<String, Any>): AIExecutionResult {
        return AIExecutionResult(success = true, message = "数据导入请在设置页操作 (支持 xlsx/csv)", navigateTo = "settings")
    }

    // ============== 辅助函数 ==============

    private fun fail(msg: String) = AIExecutionResult(success = false, message = msg)

    private fun todayStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun timeRange(scope: String, todayMidnight: Long): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return when (scope) {
            "今天" -> todayMidnight to now
            "昨天" -> (todayMidnight - 86400_000L) to todayMidnight
            "本周", "这周" -> (todayMidnight - 7 * 86400_000L) to now
            "上周" -> (todayMidnight - 14 * 86400_000L) to (todayMidnight - 7 * 86400_000L)
            "本月", "这个月" -> (todayMidnight - 30 * 86400_000L) to now
            "最近" -> (todayMidnight - 7 * 86400_000L) to now
            "所有", "全部" -> 0L to now
            else -> todayMidnight to now
        }
    }

    private fun timeScopeDisplay(scope: String): String = when (scope) {
        "今天" -> "今天"
        "昨天" -> "昨天"
        "本周", "这周" -> "本周"
        "上周" -> "上周"
        "本月", "这个月" -> "本月"
        "最近" -> "最近"
        "所有", "全部" -> "所有"
        else -> scope
    }

    private fun targetDisplayName(target: String): String = when (target) {
        AITarget.GLUCOSE -> "血糖"
        AITarget.INSULIN -> "胰岛素"
        AITarget.MEAL -> "饮食"
        AITarget.EXERCISE -> "运动"
        AITarget.SLEEP -> "睡眠"
        AITarget.BP -> "血压"
        AITarget.WEIGHT -> "体重"
        AITarget.KETONE -> "酮体"
        AITarget.MEDICATION -> "用药"
        AITarget.SYMPTOM -> "症状"
        else -> target
    }
}