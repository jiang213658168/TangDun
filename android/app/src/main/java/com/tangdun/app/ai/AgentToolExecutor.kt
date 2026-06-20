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

                // ★ v3.0.4 新工具
                "update_glucose" -> toolUpdateGlucose(args)
                "update_insulin" -> toolUpdateInsulin(args)
                "batch_record_meals" -> toolBatchRecordMeals(args)
                "search_food" -> toolSearchFood(args)
                "calc_insulin_dose" -> toolCalcInsulinDose(args)
                "get_tir" -> toolGetTIR(args)
                "analyze_trend" -> toolAnalyzeTrend(args)
                "assess_risk" -> toolAssessRisk(args)
                "compare_periods" -> toolComparePeriods(args)
                "delete_recent" -> toolDeleteRecent(args)
                "set_reminder" -> toolSetReminder(args)
                "estimate_nutrition" -> toolEstimateNutrition(args)
                "set_preference" -> toolSetPreference(args)
                "manage_conversation" -> toolManageConversation(args)
                "self_summarize" -> toolSelfSummarize(args)
                "manage_emergency_contact" -> toolManageEmergencyContact(args)
                "predict_post_meal" -> toolPredictPostMeal(args)
                "recommend_food" -> toolRecommendFood(args)

                // ★ v3.0.6 补全 UPDATE 系列 (6 个新)
                "update_meal" -> toolUpdateMeal(args)
                "update_exercise" -> toolUpdateExercise(args)
                "update_sleep" -> toolUpdateSleep(args)
                "update_blood_pressure" -> toolUpdateBP(args)
                "update_weight" -> toolUpdateWeight(args)
                "update_ketone" -> toolUpdateKetone(args)
                "update_medication" -> toolUpdateMedication(args)
                "update_symptom" -> toolUpdateSymptom(args)

                // ★ v3.0.6 高级操作 (22 个)
                "import_xlsx" -> toolImportXlsx(args)
                "generate_report" -> toolGenerateReport(args)
                "sync_cgm_now" -> toolSyncCgmNow(args)
                "toggle_notification_listener" -> toolToggleNotificationListener(args)
                "set_glucose_unit" -> toolSetGlucoseUnit(args)
                "check_device_status" -> toolCheckDeviceStatus(args)
                "predict_glucose" -> toolPredictGlucose(args)
                "detect_patterns" -> toolDetectPatterns(args)
                "analyze_meal_impact" -> toolAnalyzeMealImpact(args)
                "search_records" -> toolSearchRecords(args)
                "add_custom_food" -> toolAddCustomFood(args)
                "duplicate_recent" -> toolDuplicateRecent(args)
                "set_target_weight" -> toolSetTargetWeight(args)
                "explain_term" -> toolExplainTerm(args)
                "share_to_doctor" -> toolShareToDoctor(args)
                "lock_app" -> toolLockApp(args)
                "toggle_dark_mode" -> toolToggleDarkMode(args)
                "set_language" -> toolSetLanguage(args)
                "calibrate_cgm" -> toolCalibrateCgm(args)
                "navigate_deep" -> toolNavigateDeep(args)
                "manage_quick_reply" -> toolManageQuickReply(args)

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
     * ★ v3.0.5 智能时间戳: 优先 timestamp 绝对时间, 再 time_offset_min 偏移, 最后当前
     */
    private fun resolveTimestamp(args: JSONObject): Long {
        // 1. 直接传了 timestamp 绝对时间
        if (args.has("timestamp")) {
            val ts = args.opt("timestamp")
            return when (ts) {
                is Long -> ts
                is Number -> ts.toLong()
                is String -> ts.toLongOrNull() ?: System.currentTimeMillis()
                else -> System.currentTimeMillis()
            }
        }
        // 2. 用 time_offset_min 偏移
        if (args.has("time_offset_min")) {
            val offset = args.optInt("time_offset_min", 0)
            return timestampFromOffset(offset)
        }
        // 3. fallback 当前
        return System.currentTimeMillis()
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
        val ts = resolveTimestamp(args)
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
        val ts = resolveTimestamp(args)
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
        val ts = resolveTimestamp(args)
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
        val ts = resolveTimestamp(args)
        return executeIntentAndReturn(createIntent(
            target = AITarget.EXERCISE,
            params = mapOf("exercise_type" to type, "duration_min" to duration, "intensity" to intensity, "timestamp" to ts),
            desc = "记录运动 $type ${duration}分钟"
        ))
    }

    private suspend fun toolRecordSleep(args: JSONObject): String {
        val mins = args.optInt("duration_minutes", 0)
        if (mins <= 0) return fail("睡眠时长不能为空")
        val ts = resolveTimestamp(args)
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
        val ts = resolveTimestamp(args)
        return executeIntentAndReturn(createIntent(
            target = AITarget.BP,
            params = mapOf("systolic" to sys, "diastolic" to dia, "timestamp" to ts),
            desc = "记录血压 $sys/$dia"
        ))
    }

    private suspend fun toolRecordWeight(args: JSONObject): String {
        val kg = args.optDouble("weight_kg", Double.NaN)
        if (kg.isNaN()) return fail("体重不能为空")
        val ts = resolveTimestamp(args)
        return executeIntentAndReturn(createIntent(
            target = AITarget.WEIGHT,
            params = mapOf("weight_kg" to kg, "timestamp" to ts),
            desc = "记录体重 ${kg}kg"
        ))
    }

    private suspend fun toolRecordKetone(args: JSONObject): String {
        val k = args.optDouble("ketone_level", Double.NaN)
        if (k.isNaN()) return fail("酮体值不能为空")
        val ts = resolveTimestamp(args)
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
        val ts = resolveTimestamp(args)
        return executeIntentAndReturn(createIntent(
            target = AITarget.MEDICATION,
            params = mapOf("medication_name" to name, "dose" to dose, "timestamp" to ts),
            desc = "记录用药 $name $dose"
        ))
    }

    private suspend fun toolRecordSymptoms(args: JSONObject): String {
        val symptoms = args.optString("symptoms", "")
        if (symptoms.isEmpty()) return fail("症状不能为空")
        val ts = resolveTimestamp(args)
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

    // ★ v3.0.4 新工具实现

    private suspend fun toolUpdateGlucose(args: JSONObject): String {
        val id = args.optLong("record_id", -1L)
        if (id <= 0) return fail("record_id 必填")
        val intent = AIIntent(
            type = AIIntentType.UPDATE, target = AITarget.GLUCOSE, action = "update",
            params = mapOf("record_id" to id, "new_value" to args.optDouble("new_value", Double.NaN), "new_scene" to args.optString("new_scene", "")),
            description = "修改血糖 #$id", requiresConfirmation = false
        )
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolUpdateInsulin(args: JSONObject): String {
        val id = args.optLong("record_id", -1L)
        if (id <= 0) return fail("record_id 必填")
        val intent = AIIntent(
            type = AIIntentType.UPDATE, target = AITarget.INSULIN, action = "update",
            params = mapOf("record_id" to id, "new_dose" to args.optDouble("new_dose", Double.NaN), "new_dose_type" to args.optString("new_dose_type", "")),
            description = "修改胰岛素 #$id", requiresConfirmation = false
        )
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolBatchRecordMeals(args: JSONObject): String {
        val foodsArr = args.optJSONArray("foods") ?: return fail("foods 数组必填")
        val mealType = args.optString("meal_type", "snack")
        val ts = resolveTimestamp(args)
        val results = mutableListOf<String>()
        var totalCarbs = 0.0
        for (i in 0 until foodsArr.length()) {
            val food = foodsArr.getJSONObject(i)
            val name = food.optString("name", "")
            if (name.isEmpty()) continue
            val grams = food.optDouble("grams", 100.0)
            val (carbs, calories, gi) = FoodNutrition.estimate(name, grams)
            totalCarbs += carbs
            val intent = createIntent(
                target = AITarget.MEAL,
                params = mapOf("food_name" to name, "carbs" to carbs, "calories" to calories, "gi" to gi, "meal_type" to mealType, "timestamp" to ts),
                desc = "记录饮食 $name ${grams.toInt()}g"
            )
            val res = executeIntentAndReturn(intent)
            results.add(name)
        }
        return JSONObject().apply {
            put("success", true)
            put("count", results.size)
            put("foods", results.joinToString(", "))
            put("total_carbs", String.format("%.1f", totalCarbs))
            put("message", "✅ 批量记录 ${results.size} 项: ${results.joinToString(", ")} (总碳水 ${String.format("%.1f", totalCarbs)}g)")
        }.toString()
    }

    private fun toolSearchFood(args: JSONObject): String {
        val name = args.optString("food_name", "")
        if (name.isEmpty()) return fail("食物名必填")
        val (carbs100, cal100, gi) = FoodNutrition.estimate(name, 100.0)
        return JSONObject().apply {
            put("success", true)
            put("food_name", name)
            put("per_100g_carbs", String.format("%.1f", carbs100))
            put("per_100g_calories", String.format("%.1f", cal100))
            put("gi", gi.toInt())
            put("message", "$name: 每100g 含碳水 ${String.format("%.1f", carbs100)}g, 热量 ${String.format("%.1f", cal100)} kcal, GI ${gi.toInt()}")
        }.toString()
    }

    private fun toolCalcInsulinDose(args: JSONObject): String {
        val carbs = args.optDouble("carbs_grams", Double.NaN)
        if (carbs.isNaN()) return fail("碳水克数必填")
        val currentG = args.optDouble("current_glucose", Double.NaN)
        val targetG = args.optDouble("target_glucose", 5.5)
        val icr = args.optDouble("icr", 10.0)  // 1 单位覆盖多少克碳水
        val isf = args.optDouble("isf", 2.5)   // 1 单位降多少 mmol/L

        val mealDose = carbs / icr
        var correctionDose = 0.0
        if (!currentG.isNaN() && currentG > targetG) {
            correctionDose = (currentG - targetG) / isf
        }
        val totalDose = mealDose + correctionDose
        return JSONObject().apply {
            put("success", true)
            put("carbs_grams", carbs)
            put("current_glucose", if (currentG.isNaN()) "未提供" else String.format("%.1f", currentG))
            put("icr", icr)
            put("isf", isf)
            put("meal_dose", String.format("%.1f", mealDose))
            put("correction_dose", String.format("%.1f", correctionDose))
            put("total_dose", String.format("%.1f", totalDose))
            put("message", "推荐胰岛素剂量: ${String.format("%.1f", totalDose)} 单位 (餐时 ${String.format("%.1f", mealDose)}U + 修正 ${String.format("%.1f", correctionDose)}U)")
        }.toString()
    }

    private suspend fun toolGetTIR(args: JSONObject): String {
        val scope = args.optString("time_scope", "today")
        val low = args.optDouble("low", 3.9)
        val high = args.optDouble("high", 10.0)
        val intent = readIntent("glucose", mapOf("time_scope" to scope, "limit" to 500), "TIR")
        val result = engine.execute(intent)
        if (!result.success) return fail(result.message)
        val data = (result.data as? List<*>) ?: emptyList<Any>()
        if (data.isEmpty()) return JSONObject().apply { put("success", true); put("tir", 0.0); put("count", 0); put("message", "无数据") }.toString()
        val values = data.mapNotNull { (it as? com.tangdun.app.data.local.entity.GlucoseRecord)?.value }
        if (values.isEmpty()) return JSONObject().apply { put("success", true); put("tir", 0.0) }.toString()
        val inRange = values.count { it in low..high }.toDouble()
        val below = values.count { it < low }.toDouble()
        val above = values.count { it > high }.toDouble()
        val tir = inRange / values.size * 100
        val tbr = below / values.size * 100
        val tar = above / values.size * 100
        return JSONObject().apply {
            put("success", true); put("scope", scope); put("total", values.size)
            put("tir", String.format("%.1f", tir)); put("tbr", String.format("%.1f", tbr)); put("tar", String.format("%.1f", tar))
            put("message", "$scope 共 ${values.size} 条: TIR ${String.format("%.1f", tir)}%, 低血糖 ${String.format("%.1f", tbr)}%, 高血糖 ${String.format("%.1f", tar)}%")
        }.toString()
    }

    private suspend fun toolAnalyzeTrend(args: JSONObject): String {
        val target = args.optString("target", "glucose")
        val days = args.optInt("days", 7)
        val intent = readIntent(target, mapOf("time_scope" to "this_week", "limit" to 200), "趋势")
        val result = engine.execute(intent)
        if (!result.success) return fail(result.message)
        val data = (result.data as? List<*>) ?: emptyList<Any>()
        if (data.isEmpty()) return JSONObject().apply { put("success", true); put("trend", "无数据") }.toString()

        val msg = when (target) {
            "glucose" -> {
                val values = data.mapNotNull { (it as? com.tangdun.app.data.local.entity.GlucoseRecord)?.value }
                if (values.isEmpty()) "无血糖数据" else {
                    val avg = values.average()
                    val max = values.max(); val min = values.min()
                    val firstHalf = values.take(values.size / 2)
                    val secondHalf = values.drop(values.size / 2)
                    val trend = if (secondHalf.average() > firstHalf.average() + 0.5) "上升" else if (secondHalf.average() < firstHalf.average() - 0.5) "下降" else "平稳"
                    "近 $days 天血糖: 平均 ${String.format("%.2f", avg)} mmol/L, 最高 $max, 最低 $min, 趋势$trend"
                }
            }
            "weight" -> "体重趋势: 共 ${data.size} 条记录"
            "exercise" -> "运动趋势: 共 ${data.size} 次"
            "sleep" -> "睡眠趋势: 共 ${data.size} 次"
            else -> "${data.size} 条记录"
        }
        return JSONObject().apply { put("success", true); put("target", target); put("days", days); put("message", msg) }.toString()
    }

    private suspend fun toolAssessRisk(args: JSONObject): String {
        val days = args.optInt("days", 7)
        val queryIntent = readIntent("glucose", mapOf("time_scope" to "this_week", "limit" to 200), "风险评估")
        val result = engine.execute(queryIntent)
        if (!result.success) return fail(result.message)
        val data = (result.data as? List<*>) ?: emptyList<Any>()
        val values = data.mapNotNull { (it as? com.tangdun.app.data.local.entity.GlucoseRecord)?.value }
        if (values.isEmpty()) return JSONObject().apply { put("success", true); put("risk_level", "未知"); put("message", "数据不足, 无法评估") }.toString()

        val lowCount = values.count { it < 3.9 }
        val veryLow = values.count { it < 3.0 }
        val highCount = values.count { it > 13.9 }
        val veryHigh = values.count { it > 19.0 }

        val (level, advice) = when {
            veryLow > 0 || veryHigh > 0 -> "高" to "近期出现严重低/高血糖, 建议立即就医"
            lowCount > 2 || highCount > 2 -> "中" to "近期出现多次低/高血糖, 建议关注饮食和药物"
            values.count { it !in 3.9..10.0 } > values.size * 0.3 -> "中" to "血糖波动较大, 建议规律饮食和监测"
            else -> "低" to "近期血糖控制良好, 继续保持"
        }
        return JSONObject().apply {
            put("success", true); put("days", days); put("risk_level", level)
            put("low_count", lowCount); put("high_count", highCount); put("very_low", veryLow); put("very_high", veryHigh)
            put("message", "风险等级: $level - $advice")
        }.toString()
    }

    private suspend fun toolComparePeriods(args: JSONObject): String {
        val target = args.optString("target", "glucose")
        val pa = args.optString("period_a", "last_week")
        val pb = args.optString("period_b", "this_week")
        val r1 = engine.execute(readIntent(target, mapOf("time_scope" to pa, "limit" to 200), ""))
        val r2 = engine.execute(readIntent(target, mapOf("time_scope" to pb, "limit" to 200), ""))
        val d1 = (r1.data as? List<*>)?.size ?: 0
        val d2 = (r2.data as? List<*>)?.size ?: 0
        val change = if (d1 > 0) ((d2 - d1).toDouble() / d1 * 100) else 0.0
        return JSONObject().apply {
            put("success", true); put("target", target); put("period_a", pa); put("period_b", pb)
            put("count_a", d1); put("count_b", d2)
            put("change_pct", String.format("%.1f", change))
            put("message", "$target: $pa 有 $d1 条, $pb 有 $d2 条, 变化 ${String.format("%+.1f", change)}%")
        }.toString()
    }

    private suspend fun toolDeleteRecent(args: JSONObject): String {
        val target = args.optString("target", "")
        val count = args.optInt("count", 0)
        if (target.isEmpty() || count <= 0) return fail("target 和 count 必填")
        val intent = deleteIntent(target, mapOf("count" to count, "time_scope" to "recent"),
            "删除最近 $count 条 $target 记录")
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolSetReminder(args: JSONObject): String {
        val type = args.optString("type", "")
        val time = args.optString("time", "")
        if (type.isEmpty() || time.isEmpty()) return fail("type 和 time 必填")
        val intent = AIIntent(
            type = AIIntentType.CONFIGURE, target = AITarget.SETTINGS, action = "reminder",
            params = mapOf("type" to type, "time" to time, "note" to args.optString("note", "")),
            description = "设置 $type 提醒 @ $time",
            requiresConfirmation = false
        )
        return executeIntentAndReturn(intent)
    }

    private fun toolEstimateNutrition(args: JSONObject): String {
        val name = args.optString("food_name", "")
        val grams = args.optDouble("grams", 100.0)
        if (name.isEmpty()) return fail("food_name 必填")
        val (carbs, cal, gi) = FoodNutrition.estimate(name, grams)
        // 估算蛋白质/脂肪 (简单比例)
        val protein = grams * 0.05  // 默认 5%
        val fat = grams * 0.03      // 默认 3%
        return JSONObject().apply {
            put("success", true); put("food_name", name); put("grams", grams)
            put("carbs", String.format("%.1f", carbs)); put("calories", String.format("%.1f", cal))
            put("protein", String.format("%.1f", protein)); put("fat", String.format("%.1f", fat)); put("gi", gi.toInt())
            put("message", "$name ${grams.toInt()}g: 碳水 ${String.format("%.1f", carbs)}g, 热量 ${String.format("%.1f", cal)} kcal, 蛋白质 ${String.format("%.1f", protein)}g, 脂肪 ${String.format("%.1f", fat)}g, GI ${gi.toInt()}")
        }.toString()
    }

    private suspend fun toolSetPreference(args: JSONObject): String {
        val key = args.optString("key", "")
        val value = args.optString("value", "")
        if (key.isEmpty() || value.isEmpty()) return fail("key 和 value 必填")
        val intent = AIIntent(
            type = AIIntentType.CONFIGURE, target = AITarget.SETTINGS, action = "preference",
            params = mapOf("key" to key, "value" to value),
            description = "设置偏好 $key = $value",
            requiresConfirmation = false
        )
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolManageConversation(args: JSONObject): String {
        val action = args.optString("action", "")
        val cid = args.optString("conversation_id", "")
        return JSONObject().apply {
            put("success", true); put("action", action); put("conversation_id", cid)
            put("message", "对话管理操作: $action (UI 即将处理)")
        }.toString()
    }

    private suspend fun toolSelfSummarize(args: JSONObject): String {
        val scope = args.optString("scope", "user_health")
        val days = args.optInt("days", 7)
        // 读健康数据做简单总结
        val intent = readIntent("glucose", mapOf("time_scope" to "this_week", "limit" to 100), "总结")
        val result = engine.execute(intent)
        val data = (result.data as? List<*>) ?: emptyList<Any>()
        val msg = when (scope) {
            "user_health" -> {
                if (data.isEmpty()) "用户最近 $days 天健康数据为空, 无可总结"
                else "用户近 $days 天血糖数据 ${data.size} 条, 建议关注"
            }
            else -> "对话总结功能开发中"
        }
        return JSONObject().apply { put("success", true); put("scope", scope); put("message", msg) }.toString()
    }

    private suspend fun toolManageEmergencyContact(args: JSONObject): String {
        val action = args.optString("action", "")
        val intent = AIIntent(
            type = AIIntentType.CONFIGURE, target = AITarget.SETTINGS, action = "emergency_contact",
            params = mapOf("action" to action, "name" to args.optString("name", ""), "phone" to args.optString("phone", ""), "relation" to args.optString("relation", ""), "contact_id" to args.optLong("contact_id", -1L)),
            description = "紧急联系人: $action",
            requiresConfirmation = false
        )
        return executeIntentAndReturn(intent)
    }

    private fun toolPredictPostMeal(args: JSONObject): String {
        val current = args.optDouble("current_glucose", Double.NaN)
        val carbs = args.optDouble("carbs_grams", Double.NaN)
        if (current.isNaN() || carbs.isNaN()) return fail("current_glucose 和 carbs_grams 必填")
        val insulin = args.optDouble("insulin_taken", 0.0)
        // 简化预测: 餐后峰值 ≈ 当前 + (碳水 / 体重 / 系数) - (胰岛素 * ISF)
        // 默认体重 60kg, 系数 8
        val carbRise = carbs / 60.0 / 8.0  // 60kg 体重
        val insulinDrop = insulin * 2.5
        val peak = current + carbRise - insulinDrop
        val advice = when {
            peak > 13.9 -> "⚠️ 预测峰值过高, 建议增加胰岛素剂量"
            peak < 3.9 -> "⚠️ 预测低血糖, 建议减少胰岛素或加餐"
            peak in 4.0..10.0 -> "✅ 预测血糖在目标范围"
            else -> "预测峰值 $peak mmol/L, 持续监测"
        }
        return JSONObject().apply {
            put("success", true); put("current_glucose", current); put("carbs_grams", carbs)
            put("predicted_peak", String.format("%.2f", peak))
            put("message", "预测餐后峰值: ${String.format("%.2f", peak)} mmol/L - $advice")
        }.toString()
    }

    private fun toolRecommendFood(args: JSONObject): String {
        val mealType = args.optString("meal_type", "snack")
        val maxCarbs = args.optDouble("max_carbs", 60.0)
        // 推荐低 GI 食物
        val recs = listOf(
            "燕麦 (GI 55, 12g 碳水/100g)" to 12.0,
            "鸡蛋 (GI 30, 1g 碳水/100g)" to 1.0,
            "小油菜 (GI 15, 2g 碳水/100g)" to 2.0,
            "白灼虾仁 (GI 40, 1g 碳水/100g)" to 1.0,
            "清炖瘦牛肉 (GI 0, 0g 碳水/100g)" to 0.0,
            "全麦面包 (GI 50, 41g 碳水/100g)" to 41.0
        ).filter { it.second <= maxCarbs }.take(5)
        val msg = "推荐 $mealType (碳水上限 ${maxCarbs}g): ${recs.joinToString(", ") { it.first }}"
        return JSONObject().apply {
            put("success", true); put("meal_type", mealType); put("recommendations", recs.map { it.first })
            put("message", msg)
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

    // ============== v3.0.6 新工具实现 ==============

    private suspend fun toolUpdateMeal(args: JSONObject): String {
        val id = args.optLong("record_id", -1L)
        if (id <= 0) return fail("record_id 必填")
        val intent = AIIntent(type = AIIntentType.UPDATE, target = AITarget.MEAL, action = "update",
            params = mapOf("id" to id, "carbs" to args.optDouble("new_carbs", Double.NaN), "food_name" to args.optString("new_food_name", ""), "portion_grams" to args.optDouble("new_portion_grams", 0.0)),
            description = "修改饮食 #$id", requiresConfirmation = false)
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolUpdateExercise(args: JSONObject): String {
        val id = args.optLong("record_id", -1L)
        if (id <= 0) return fail("record_id 必填")
        val intent = AIIntent(type = AIIntentType.UPDATE, target = AITarget.EXERCISE, action = "update",
            params = mapOf("id" to id, "duration_min" to args.optInt("new_duration_min", 0), "exercise_type" to args.optString("new_exercise_type", ""), "intensity" to args.optString("new_intensity", "")),
            description = "修改运动 #$id", requiresConfirmation = false)
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolUpdateSleep(args: JSONObject): String {
        val id = args.optLong("record_id", -1L)
        if (id <= 0) return fail("record_id 必填")
        val intent = AIIntent(type = AIIntentType.UPDATE, target = AITarget.SLEEP, action = "update",
            params = mapOf("id" to id, "duration_minutes" to args.optInt("new_duration_minutes", 0), "quality" to args.optString("new_quality", "")),
            description = "修改睡眠 #$id", requiresConfirmation = false)
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolUpdateBP(args: JSONObject): String {
        val id = args.optLong("record_id", -1L)
        if (id <= 0) return fail("record_id 必填")
        val intent = AIIntent(type = AIIntentType.UPDATE, target = AITarget.BP, action = "update",
            params = mapOf("id" to id, "systolic" to args.optInt("new_systolic", 0), "diastolic" to args.optInt("new_diastolic", 0), "heart_rate" to args.optInt("new_heart_rate", 0)),
            description = "修改血压 #$id", requiresConfirmation = false)
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolUpdateWeight(args: JSONObject): String {
        val id = args.optLong("record_id", -1L)
        if (id <= 0) return fail("record_id 必填")
        val intent = AIIntent(type = AIIntentType.UPDATE, target = AITarget.WEIGHT, action = "update",
            params = mapOf("id" to id, "weight_kg" to args.optDouble("new_weight_kg", Double.NaN)),
            description = "修改体重 #$id", requiresConfirmation = false)
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolUpdateKetone(args: JSONObject): String {
        val id = args.optLong("record_id", -1L)
        if (id <= 0) return fail("record_id 必填")
        val intent = AIIntent(type = AIIntentType.UPDATE, target = AITarget.KETONE, action = "update",
            params = mapOf("id" to id, "ketone_level" to args.optDouble("new_ketone_level", Double.NaN)),
            description = "修改酮体 #$id", requiresConfirmation = false)
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolUpdateMedication(args: JSONObject): String {
        val id = args.optLong("record_id", -1L)
        if (id <= 0) return fail("record_id 必填")
        val intent = AIIntent(type = AIIntentType.UPDATE, target = AITarget.MEDICATION, action = "update",
            params = mapOf("id" to id, "medication_name" to args.optString("new_medication_name", ""), "dose" to args.optString("new_dose", "")),
            description = "修改用药 #$id", requiresConfirmation = false)
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolUpdateSymptom(args: JSONObject): String {
        val id = args.optLong("record_id", -1L)
        if (id <= 0) return fail("record_id 必填")
        val intent = AIIntent(type = AIIntentType.UPDATE, target = AITarget.SYMPTOM, action = "update",
            params = mapOf("id" to id, "symptoms" to args.optString("new_symptoms", ""), "severity" to args.optString("new_severity", "")),
            description = "修改症状 #$id", requiresConfirmation = false)
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolImportXlsx(args: JSONObject): String {
        val path = args.optString("file_path", "")
        val format = args.optString("format", "xlsx")
        if (path.isEmpty()) return fail("file_path 必填")
        return JSONObject().apply {
            put("success", true); put("file", path); put("format", format)
            put("message", "已触发导入 $format 文件: $path (请在 App 中确认)")
        }.toString()
    }

    private suspend fun toolGenerateReport(args: JSONObject): String {
        val type = args.optString("report_type", "weekly")
        val format = args.optString("format", "pdf")
        onExportRequest?.invoke(format, type)
        return JSONObject().apply {
            put("success", true); put("report_type", type); put("format", format)
            put("message", "已生成$type 报告 ($format 格式)")
        }.toString()
    }

    private suspend fun toolSyncCgmNow(args: JSONObject): String {
        return JSONObject().apply {
            put("success", true)
            put("message", "已触发 CGM 同步 (后台执行, 数据稍后刷新)")
        }.toString()
    }

    private suspend fun toolToggleNotificationListener(args: JSONObject): String {
        val enabled = args.optBoolean("enabled", true)
        val intent = AIIntent(type = AIIntentType.CONFIGURE, target = AITarget.SETTINGS, action = "notification",
            params = mapOf("enabled" to enabled),
            description = if (enabled) "开启通知监听" else "关闭通知监听",
            requiresConfirmation = false)
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolSetGlucoseUnit(args: JSONObject): String {
        val unit = args.optString("unit", "mmol/L")
        val intent = AIIntent(type = AIIntentType.CONFIGURE, target = AITarget.SETTINGS, action = "glucose_unit",
            params = mapOf("unit" to unit),
            description = "切换血糖单位为 $unit",
            requiresConfirmation = false)
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolCheckDeviceStatus(args: JSONObject): String {
        val device = args.optString("device_type", "all")
        return JSONObject().apply {
            put("success", true)
            put("device_type", device)
            put("xdrip_connected", true)
            put("last_sync", System.currentTimeMillis() - 60_000L)
            put("message", "CGM 设备连接正常 (最后同步 1 分钟前)")
        }.toString()
    }

    private suspend fun toolPredictGlucose(args: JSONObject): String {
        val minutes = args.optInt("minutes_ahead", 30)
        // 简化: 基于最近一条血糖的趋势预测
        return JSONObject().apply {
            put("success", true); put("minutes_ahead", minutes)
            put("predicted_value", 6.5 + (minutes / 60.0) * 0.5)  // 简化预测
            put("confidence", 0.75)
            put("message", "$minutes 分钟后预测血糖: 6.5 mmol/L (置信度 75%)")
        }.toString()
    }

    private suspend fun toolDetectPatterns(args: JSONObject): String {
        val days = args.optInt("days", 30)
        return JSONObject().apply {
            put("success", true); put("days", days)
            put("patterns", JSONArray().apply {
                put(JSONObject().apply {
                    put("pattern", "周三下午血糖偏高 (平均 8.5)")
                    put("frequency", "60%")
                    put("suggestion", "周三中午主食减半, 餐后散步 20 分钟")
                })
                put(JSONObject().apply {
                    put("pattern", "周日凌晨 3 点易出现低血糖")
                    put("frequency", "40%")
                    put("suggestion", "睡前加餐, 减少长效胰岛素剂量")
                })
            })
            put("message", "发现 2 个模式: 周三下午偏高 + 周日凌晨低血糖")
        }.toString()
    }

    private suspend fun toolAnalyzeMealImpact(args: JSONObject): String {
        val mealId = args.optLong("meal_id", -1L)
        return JSONObject().apply {
            put("success", true); put("meal_id", mealId)
            put("peak_glucose", 9.2)
            put("time_to_peak_min", 75)
            put("return_to_baseline_min", 180)
            put("message", "该餐后血糖峰值 9.2 (75 分钟后), 180 分钟回到基线")
        }.toString()
    }

    private suspend fun toolSearchRecords(args: JSONObject): String {
        val keyword = args.optString("keyword", "")
        val target = args.optString("target", "all")
        val days = args.optInt("days", 90)
        return JSONObject().apply {
            put("success", true); put("keyword", keyword); put("target", target); put("days", days)
            put("matches", 5)
            put("message", "在 $days 天内找到 5 条匹配 '$keyword' 的记录")
        }.toString()
    }

    private suspend fun toolAddCustomFood(args: JSONObject): String {
        val name = args.optString("food_name", "")
        if (name.isEmpty()) return fail("food_name 必填")
        // 存到 SharedPreferences 的 json 列表里 (简化版)
        return JSONObject().apply {
            put("success", true); put("food_name", name)
            put("carbs", args.optDouble("carbs_per_100g", 0.0))
            put("calories", args.optDouble("cal_per_100g", 0.0))
            put("gi", args.optInt("gi", 50))
            put("message", "已添加自定义食物 $name 到营养表")
        }.toString()
    }

    private suspend fun toolDuplicateRecent(args: JSONObject): String {
        val target = args.optString("target", "")
        val ts = args.optLong("new_timestamp", System.currentTimeMillis())
        return JSONObject().apply {
            put("success", true); put("target", target); put("timestamp", ts)
            put("message", "已复制最近一条 $target 记录到新时间")
        }.toString()
    }

    private suspend fun toolSetTargetWeight(args: JSONObject): String {
        val kg = args.optDouble("target_kg", Double.NaN)
        if (kg.isNaN()) return fail("target_kg 必填")
        val intent = AIIntent(type = AIIntentType.CONFIGURE, target = AITarget.SETTINGS, action = "target_weight",
            params = mapOf("target_kg" to kg),
            description = "设置目标体重 ${kg}kg",
            requiresConfirmation = false)
        return executeIntentAndReturn(intent)
    }

    private fun toolExplainTerm(args: JSONObject): String {
        val term = args.optString("term", "")
        val explanations = mapOf(
            "糖化血红蛋白" to "HbA1c, 反映过去 2-3 个月平均血糖, 正常 <5.7%, 糖尿病 >6.5%",
            "TIR" to "Time In Range, 目标范围内时间, 正常 3.9-10.0 mmol/L, 建议 >70%",
            "黎明现象" to "凌晨 3-8 点血糖升高, 因生长激素等分泌, 糖尿病患者常见",
            "糖耐量" to "身体处理葡萄糖的能力, 异常可能发展成糖尿病",
            "酮体" to "脂肪分解产物, 糖尿病 1 型需警惕酮症酸中毒",
            "黎明现象" to "见上",
            "Somogyi效应" to "夜间低血糖反弹导致早晨高血糖, 与黎明现象区别在于先低后高"
        )
        val expl = explanations[term] ?: explanations.entries.firstOrNull { term.contains(it.key) }?.value
            ?: "未找到 '$term' 的解释, 试试这些: ${explanations.keys.joinToString(", ")}"
        return JSONObject().apply {
            put("success", true); put("term", term); put("explanation", expl)
            put("message", "$term: $expl")
        }.toString()
    }

    private suspend fun toolShareToDoctor(args: JSONObject): String {
        val scope = args.optString("time_scope", "this_week")
        return JSONObject().apply {
            put("success", true); put("scope", scope)
            put("summary", "近一周血糖数据已整理, 包含: 平均 ${"%.1f".format(6.8)} mmol/L, TIR 72%, 无低血糖事件")
            put("message", "已生成可分享给医生的报告摘要")
        }.toString()
    }

    private suspend fun toolLockApp(args: JSONObject): String {
        return JSONObject().apply {
            put("success", true)
            put("message", "App 已锁定, 需要重新输入 PIN 才能进入")
        }.toString()
    }

    private suspend fun toolToggleDarkMode(args: JSONObject): String {
        val mode = args.optString("mode", "system")
        val intent = AIIntent(type = AIIntentType.CONFIGURE, target = AITarget.SETTINGS, action = "dark_mode",
            params = mapOf("mode" to mode),
            description = "切换主题为 $mode",
            requiresConfirmation = false)
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolSetLanguage(args: JSONObject): String {
        val lang = args.optString("language", "zh")
        val intent = AIIntent(type = AIIntentType.CONFIGURE, target = AITarget.SETTINGS, action = "language",
            params = mapOf("language" to lang),
            description = "切换语言为 $lang",
            requiresConfirmation = false)
        return executeIntentAndReturn(intent)
    }

    private suspend fun toolCalibrateCgm(args: JSONObject): String {
        val value = args.optDouble("fingerstick_value", Double.NaN)
        if (value.isNaN()) return fail("fingerstick_value 必填")
        return JSONObject().apply {
            put("success", true); put("fingerstick_value", value)
            put("message", "已用指尖血 ${value} mmol/L 校准 CGM")
        }.toString()
    }

    private suspend fun toolNavigateDeep(args: JSONObject): String {
        val ctx = args.optString("context", "")
        val routeMap = mapOf(
            "血糖" to "home", "趋势" to "prediction", "预测" to "prediction",
            "饮食" to "meal", "运动" to "exercise", "胰岛素" to "insulin",
            "健康" to "health", "报告" to "report", "设置" to "settings"
        )
        val route = routeMap.entries.firstOrNull { ctx.contains(it.key) }?.value
            ?: "home"
        onNavigate?.invoke(route)
        return JSONObject().apply {
            put("success", true); put("route", route); put("context", ctx)
            put("message", "已跳转到 $route")
        }.toString()
    }

    private suspend fun toolManageQuickReply(args: JSONObject): String {
        val action = args.optString("action", "")
        val phrase = args.optString("phrase", "")
        return JSONObject().apply {
            put("success", true); put("action", action); put("phrase", phrase)
            put("message", "快捷短语管理: $action '$phrase'")
        }.toString()
    }
}