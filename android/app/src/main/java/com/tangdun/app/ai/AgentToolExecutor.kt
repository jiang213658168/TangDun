package com.tangdun.app.ai

import android.util.Log
import com.tangdun.app.ui.chat.AiRecordHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * ★ v2.9 Agent Tool Executor
 *
 * 把 AIClient 的 tool call (函数名 + JSON 参数) 映射到 AIPermissionEngine.execute()
 * 让 AI 大模型能像用户一样操作 App 的所有功能
 */
class AgentToolExecutor(
    private val engine: AIPermissionEngine,
    private val onIntentForConfirmation: ((List<AIIntent>) -> Unit)? = null,
    private val onNavigate: ((String) -> Unit)? = null,
    private val onExportRequest: ((String, String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "AgentExec"
    }

    /**
     * 执行一个工具调用, 返回 JSON string 结果 (作为 tool message 回传给 AI)
     */
    suspend fun execute(toolName: String, args: JSONObject): String {
        Log.i(TAG, "执行工具: $toolName($args)")
        return try {
            when (toolName) {
                // CREATE 类
                "record_glucose" -> toolRecordGlucose(args)
                "record_insulin" -> toolRecordInsulin(args)
                "record_meal" -> toolRecordMeal(args)
                "record_exercise" -> toolRecordExercise(args)
                "record_sleep" -> toolRecordSleep(args)
                "record_blood_pressure" -> toolRecordBP(args)
                "record_weight" -> toolRecordWeight(args)
                "record_ketone" -> toolRecordKetone(args)
                "record_medication" -> toolRecordMedication(args)
                "record_symptoms" -> toolRecordSymptoms(args)

                // READ 类
                "query_glucose" -> toolQuery("glucose", args)
                "query_insulin" -> toolQuery("insulin", args)
                "query_meal" -> toolQuery("meal", args)
                "query_exercise" -> toolQuery("exercise", args)
                "query_sleep" -> toolQuery("sleep", args)
                "query_blood_pressure" -> toolQuery("bp", args)
                "query_weight" -> toolQuery("weight", args)
                "query_medication" -> toolQuery("medication", args)
                "query_symptoms" -> toolQuery("symptom", args)
                "get_statistics" -> toolStatistics(args)

                // DELETE 类
                "delete_glucose" -> toolDelete("glucose", args)
                "delete_insulin" -> toolDelete("insulin", args)
                "delete_meal" -> toolDelete("meal", args)
                "delete_exercise" -> toolDelete("exercise", args)

                // NAVIGATE 类
                "navigate_to" -> toolNavigate(args)

                // CONFIGURE 类
                "set_glucose_target" -> toolSetGlucoseTarget(args)
                "set_personal_info" -> toolSetPersonalInfo(args)
                "enable_notification_listener" -> toolEnableNotificationListener()

                // EXPORT 类
                "export_data" -> toolExport(args)

                else -> JSONObject().apply {
                    put("success", false)
                    put("error", "未知工具: $toolName")
                }.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "工具执行失败: ${e.message}", e)
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "未知错误")
            }.toString()
        }
    }

    // ============== 工具实现 ==============

    private fun timestampFromOffset(offsetMin: Int?): Long {
        return System.currentTimeMillis() + (offsetMin ?: 0) * 60_000L
    }

    /**
     * 快速构造 CREATE intent 的 helper
     */
    private fun createIntent(target: String, params: Map<String, Any>, desc: String): AIIntent =
        AIIntent(type = AIIntentType.CREATE, target = target, action = "create", params = params, description = desc, requiresConfirmation = false)

    private fun readIntent(target: String, params: Map<String, Any>, desc: String): AIIntent =
        AIIntent(type = AIIntentType.READ, target = target, action = "query", params = params, description = desc, requiresConfirmation = false)

    private fun deleteIntent(target: String, params: Map<String, Any>, desc: String): AIIntent =
        AIIntent(type = AIIntentType.BULK_DELETE, target = target, action = "delete", params = params, description = desc, requiresConfirmation = false)

    private suspend fun toolRecordGlucose(args: JSONObject): String {
        val value = args.optDouble("value", Double.NaN)
        if (value.isNaN()) return fail("血糖值不能为空")
        val scene = args.optString("scene", "other")
        val ts = timestampFromOffset(args.optInt("time_offset_min", 0))
        return executeIntentAndReturn(createIntent(
            target = AITarget.GLUCOSE,
            params = mapOf("value" to value, "scene" to scene, "timestamp" to ts),
            desc = "记录血糖 ${value}mmol/L ($scene)"
        ))
    }

    private suspend fun toolRecordInsulin(args: JSONObject): String {
        val dose = args.optDouble("dose", Double.NaN)
        if (dose.isNaN()) return fail("胰岛素剂量不能为空")
        val doseType = args.optString("dose_type", "rapid")
        val ts = timestampFromOffset(args.optInt("time_offset_min", 0))
        return executeIntentAndReturn(createIntent(
            target = AITarget.INSULIN,
            params = mapOf("dose" to dose, "dose_type" to doseType, "timestamp" to ts),
            desc = "记录胰岛素 ${dose}U ($doseType)"
        ))
    }

    private suspend fun toolRecordMeal(args: JSONObject): String {
        val food = args.optString("food_name", "")
        if (food.isEmpty()) return fail("食物名称不能为空")
        val portion = args.optDouble("portion_grams", 100.0)
        val ts = timestampFromOffset(args.optInt("time_offset_min", 0))
        val mealType = args.optString("meal_type", "snack")

        val (carbs, calories, gi) = FoodNutrition.estimate(food, portion)

        return executeIntentAndReturn(createIntent(
            target = AITarget.MEAL,
            params = mapOf(
                "food_name" to food, "carbs" to carbs, "calories" to calories,
                "gi" to gi, "meal_type" to mealType, "timestamp" to ts
            ),
            desc = "记录饮食 $food ${portion.toInt()}g"
        ))
    }

    private suspend fun toolRecordExercise(args: JSONObject): String {
        val duration = args.optInt("duration_min", 0)
        if (duration <= 0) return fail("运动时长不能为空")
        val type = args.optString("exercise_type", "other")
        val intensity = args.optString("intensity", "medium")
        val ts = timestampFromOffset(args.optInt("time_offset_min", 0))
        return executeIntentAndReturn(createIntent(
            target = AITarget.EXERCISE,
            params = mapOf("exercise_type" to type, "duration_min" to duration, "intensity" to intensity, "timestamp" to ts),
            desc = "记录运动 $type ${duration}分钟"
        ))
    }

    private suspend fun toolRecordSleep(args: JSONObject): String {
        val mins = args.optInt("duration_minutes", 0)
        if (mins <= 0) return fail("睡眠时长不能为空")
        val ts = timestampFromOffset(args.optInt("time_offset_min", 0))
        return executeIntentAndReturn(createIntent(
            target = AITarget.SLEEP,
            params = mapOf("duration_minutes" to mins, "timestamp" to ts),
            desc = "记录睡眠 ${mins / 60}小时${mins % 60}分"
        ))
    }

    private suspend fun toolRecordBP(args: JSONObject): String {
        val sys = args.optInt("systolic", 0)
        val dia = args.optInt("diastolic", 0)
        if (sys <= 0 || dia <= 0) return fail("血压值不能为空")
        val ts = timestampFromOffset(args.optInt("time_offset_min", 0))
        return executeIntentAndReturn(createIntent(
            target = AITarget.BP,
            params = mapOf("systolic" to sys, "diastolic" to dia, "timestamp" to ts),
            desc = "记录血压 $sys/$dia"
        ))
    }

    private suspend fun toolRecordWeight(args: JSONObject): String {
        val kg = args.optDouble("weight_kg", Double.NaN)
        if (kg.isNaN()) return fail("体重不能为空")
        val ts = timestampFromOffset(args.optInt("time_offset_min", 0))
        return executeIntentAndReturn(createIntent(
            target = AITarget.WEIGHT,
            params = mapOf("weight_kg" to kg, "timestamp" to ts),
            desc = "记录体重 ${kg}kg"
        ))
    }

    private suspend fun toolRecordKetone(args: JSONObject): String {
        val k = args.optDouble("ketone_level", Double.NaN)
        if (k.isNaN()) return fail("酮体值不能为空")
        val ts = timestampFromOffset(args.optInt("time_offset_min", 0))
        return executeIntentAndReturn(createIntent(
            target = AITarget.KETONE,
            params = mapOf("ketone_level" to k, "timestamp" to ts),
            desc = "记录酮体 ${k}mmol/L"
        ))
    }

    private suspend fun toolRecordMedication(args: JSONObject): String {
        val name = args.optString("medication_name", "")
        if (name.isEmpty()) return fail("药品名称不能为空")
        val dose = args.optString("dose", "")
        val ts = timestampFromOffset(args.optInt("time_offset_min", 0))
        return executeIntentAndReturn(createIntent(
            target = AITarget.MEDICATION,
            params = mapOf("medication_name" to name, "dose" to dose, "timestamp" to ts),
            desc = "记录用药 $name $dose"
        ))
    }

    private suspend fun toolRecordSymptoms(args: JSONObject): String {
        val symptoms = args.optString("symptoms", "")
        if (symptoms.isEmpty()) return fail("症状不能为空")
        val ts = timestampFromOffset(args.optInt("time_offset_min", 0))
        return executeIntentAndReturn(createIntent(
            target = AITarget.SYMPTOM,
            params = mapOf("symptoms" to symptoms, "timestamp" to ts),
            desc = "记录症状: $symptoms"
        ))
    }

    private suspend fun toolQuery(target: String, args: JSONObject): String {
        val scope = args.optString("time_scope", "today")
        val limit = args.optInt("limit", 20)
        return executeIntentAndReturn(readIntent(
            target = target,
            params = mapOf("time_scope" to scope, "limit" to limit),
            desc = "查询 $scope $target"
        ))
    }

    private suspend fun toolStatistics(args: JSONObject): String {
        val scope = args.optString("time_scope", "today")
        val queryIntent = readIntent(
            target = "glucose",
            params = mapOf("time_scope" to scope, "limit" to 200),
            desc = "统计 $scope"
        )
        val result = engine.execute(queryIntent)
        if (!result.success) return fail(result.message)

        val data = result.data as? List<*> ?: emptyList<Any>()
        if (data.isEmpty()) {
            return JSONObject().apply {
                put("success", true); put("scope", scope); put("count", 0)
                put("message", "该时间段无血糖数据")
            }.toString()
        }

        val values = data.mapNotNull {
            (it as? com.tangdun.app.data.local.entity.GlucoseRecord)?.value
        }
        if (values.isEmpty()) {
            return JSONObject().apply {
                put("success", true); put("scope", scope); put("count", 0)
            }.toString()
        }

        val avg = values.average()
        val max = values.max()
        val min = values.min()
        val inRange = values.count { it in 3.9..10.0 }
        val rate = inRange * 100.0 / values.size
        val hba1c = (avg + 2.59) / 1.59

        return JSONObject().apply {
            put("success", true); put("scope", scope); put("count", values.size)
            put("avg_glucose", String.format("%.2f", avg))
            put("max_glucose", String.format("%.2f", max))
            put("min_glucose", String.format("%.2f", min))
            put("in_range_rate", String.format("%.1f", rate))
            put("estimated_hba1c", String.format("%.1f", hba1c))
            put("message", "$scope 共 ${values.size} 条血糖记录, 平均 ${String.format("%.2f", avg)} mmol/L, 达标率 ${String.format("%.1f", rate)}%, 估算 HbA1c ${String.format("%.1f", hba1c)}%")
        }.toString()
    }

    private suspend fun toolDelete(target: String, args: JSONObject): String {
        val recordId = args.optInt("record_id", -1)
        val scope = args.optString("time_scope", "")
        val params = mutableMapOf<String, Any>()
        if (recordId > 0) params["record_id"] = recordId
        if (scope.isNotEmpty()) params["time_scope"] = scope
        return executeIntentAndReturn(deleteIntent(target, params, "删除 $target (id=$recordId, scope=$scope)"))
    }

    private suspend fun toolNavigate(args: JSONObject): String {
        val route = args.optString("route", "")
        if (route.isEmpty()) return fail("路由不能为空")
        onNavigate?.invoke(route)
        return JSONObject().apply {
            put("success", true); put("route", route); put("message", "已跳转到 $route")
        }.toString()
    }

    private suspend fun toolSetGlucoseTarget(args: JSONObject): String {
        val low = args.optDouble("low", Double.NaN)
        val high = args.optDouble("high", Double.NaN)
        if (low.isNaN() || high.isNaN()) return fail("血糖目标上下限不能为空")
        return executeIntentAndReturn(AIIntent(
            type = AIIntentType.CONFIGURE, target = AITarget.SETTINGS, action = "target_range",
            params = mapOf("low" to low, "high" to high),
            description = "设置血糖目标 $low - $high mmol/L",
            requiresConfirmation = false
        ))
    }

    private suspend fun toolSetPersonalInfo(args: JSONObject): String {
        val field = args.optString("field", "")
        val value = args.optString("value", "")
        if (field.isEmpty() || value.isEmpty()) return fail("字段和值不能为空")
        return executeIntentAndReturn(AIIntent(
            type = AIIntentType.CONFIGURE, target = AITarget.SETTINGS, action = "personal_info",
            params = mapOf("field" to field, "value" to value),
            description = "设置 $field = $value",
            requiresConfirmation = false
        ))
    }

    private suspend fun toolEnableNotificationListener(): String {
        return executeIntentAndReturn(AIIntent(
            type = AIIntentType.CONFIGURE, target = AITarget.SETTINGS, action = "notification",
            params = emptyMap(),
            description = "引导开启通知监听",
            requiresConfirmation = false
        ))
    }

    private suspend fun toolExport(args: JSONObject): String {
        val format = args.optString("format", "xlsx")
        val scope = args.optString("time_scope", "all")
        onExportRequest?.invoke(format, scope)
        return JSONObject().apply {
            put("success", true); put("format", format); put("scope", scope)
            put("message", "已触发导出 $format 格式 ($scope)")
        }.toString()
    }

    // ============== 辅助 ==============

    /**
     * 调用 AIPermissionEngine.execute() 并把结果转 JSON string
     */
    private suspend fun executeIntentAndReturn(intent: AIIntent): String {
        val result = engine.execute(intent)
        return JSONObject().apply {
            put("success", result.success)
            put("message", result.message)
            if (result.data != null) put("data", JSONObject.wrap(result.data))
            if (result.navigateTo != null) put("navigate_to", result.navigateTo)
        }.toString()
    }

    private fun fail(msg: String): String = JSONObject().apply {
        put("success", false)
        put("error", msg)
    }.toString()
}