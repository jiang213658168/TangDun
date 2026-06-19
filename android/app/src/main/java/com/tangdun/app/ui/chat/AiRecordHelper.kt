package com.tangdun.app.ui.chat

import android.content.Context
import android.util.Log
import com.tangdun.app.data.remote.AiChatService
import com.tangdun.app.data.remote.ChatMessageDto
import com.tangdun.app.domain.algorithm.SelfLearningManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * AI 记录助手 — 把自然语言变为结构化记录
 *
 * 比 AiChatService.processRecordingCommands 更完整:
 *   - 支持全部手动字段 (蛋白质/脂肪/部位/备注)
 *   - 返回 ParsedRecord 而非直接写DB → 用户可编辑确认
 */
object AiRecordHelper {

    private val recordPrompt = """
你是糖盾AI记录解析器。用户用自然语言描述了一个健康事件，你需要解析为JSON。

## 输出规则
只输出一个JSON对象，不要任何其他文字。根据用户描述判断类型:

### 饮食记录
如果用户提到了食物:
{"type":"meal","food":"食物名","carbs":克数,"meal_type":"breakfast/lunch/dinner/snack","calories":热量,"gi":升糖指数,"protein":蛋白质克数,"fat":脂肪克数,"fiber":膳食纤维克数,"time":"HH:mm或now"}

### 胰岛素记录
如果用户提到了注射/打针/胰岛素:
{"type":"insulin","dose":单位,"dose_type":"rapid/short/long/mixed","site":"注射部位","time":"HH:mm或now"}

### 运动记录
如果用户提到了运动/跑步/走路:
{"type":"exercise","ex_type":"运动类型","minutes":分钟数,"time":"HH:mm或now"}

### 血糖记录
如果用户提到了血糖值/指尖血/测血糖:
{"type":"glucose","value":血糖值,"scene":"fasting/before_meal/after_meal/bedtime/other","time":"HH:mm或now"}

## 营养估算 (用户可能只说"一碗饭")
- 一碗米饭(200g)≈56g碳水, 230kcal, GI=70
- 小碗米饭(100g)≈28g碳水, 116kcal, GI=70
- 一个馒头≈45g碳水, 220kcal, GI=85
- 一碗面条≈45g碳水, 300kcal, GI=60
- 一个苹果≈28g碳水, 104kcal, GI=36
- 一根香蕉≈27g碳水, 110kcal, GI=55
- 一个鸡蛋≈1g碳水, 70kcal, 蛋白质6g
- 一盘青菜≈10g碳水, 50kcal, GI=25
- 二两肉≈0g碳水, 200kcal, 蛋白质30g, 脂肪20g
- 用户说"半碗"就除以2，"两碗"就乘以2
- 不确定的字段填0或空字符串

## time字段
- "now"=当前时刻
- "HH:mm"=今天该时间(24小时制)
- "yyyy-MM-dd HH:mm"=指定日期时间
- 从用户语义提取: "午饭"="12:00", "早饭"="07:30", "晚饭"="18:30", "刚才"="now", "8点"=""08:00""

## 示例
用户: "中午吃了碗牛肉面"
输出: {"type":"meal","food":"牛肉面","carbs":45,"meal_type":"lunch","calories":300,"gi":60,"protein":15,"fat":8,"fiber":2,"time":"12:00"}

用户: "刚打了3U速效 肚子"
输出: {"type":"insulin","dose":3,"dose_type":"rapid","site":"腹部","time":"now"}

用户: "晚上跑了30分钟步"
输出: {"type":"exercise","ex_type":"跑步","minutes":30,"time":"19:00"}
""".trimIndent()

    suspend fun parse(context: Context, userInput: String): ParsedRecord? = withContext(Dispatchers.IO) {
        try {
            val service = AiChatService(context)
            val reply = service.sendMessage(listOf(
                ChatMessageDto("system", recordPrompt),
                ChatMessageDto("user", userInput)
            ))

            if (reply.isFailure) return@withContext null
            val text = reply.getOrNull()?.trim() ?: return@withContext null

            // 提取JSON (可能是裸JSON或```json```包裹)
            val jsonStr = Regex("```json\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)?.trim() ?: text.trim()
            val json = JSONObject(jsonStr)
            val type = json.optString("type", "")
            val timeStr = json.optString("time", "now")
            val timestamp = parseTime(timeStr)
            val timeDisplay = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

            when (type) {
                "meal" -> ParsedRecord.Meal(
                    food = json.optString("food", "未知"),
                    carbs = json.optDouble("carbs", 0.0),
                    calories = json.optDouble("calories", 0.0),
                    gi = json.optDouble("gi", 60.0),
                    mealType = json.optString("meal_type", "snack"),
                    protein = json.optDouble("protein", 0.0),
                    fat = json.optDouble("fat", 0.0),
                    timeDisplay = timeDisplay, timestamp = timestamp
                )
                "insulin" -> ParsedRecord.Insulin(
                    dose = json.optDouble("dose", 1.0),
                    doseType = json.optString("dose_type", "rapid"),
                    site = json.optString("site", ""),
                    timeDisplay = timeDisplay, timestamp = timestamp
                )
                "exercise" -> ParsedRecord.Exercise(
                    exType = json.optString("ex_type", "运动"),
                    minutes = json.optInt("minutes", 30),
                    timeDisplay = timeDisplay, timestamp = timestamp
                )
                "glucose" -> ParsedRecord.Glucose(
                    value = json.optDouble("value", 5.0),
                    scene = json.optString("scene", "other"),
                    timeDisplay = timeDisplay, timestamp = timestamp
                )
                else -> null
            }
        } catch (e: Exception) {
            Log.w("AiRecord", "解析失败: ${e.message}")
            null
        }
    }

    suspend fun saveRecord(context: Context, record: ParsedRecord): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        try {
            val db = com.tangdun.app.TangDunApp.getDatabase(context)
            when (record) {
                is ParsedRecord.Meal -> {
                    val r = com.tangdun.app.data.local.entity.MealRecord(
                        timestamp = record.timestamp, mealType = record.mealType,
                        totalCarbs = record.carbs, totalCalories = record.calories,
                        totalProtein = record.protein, totalFat = record.fat,
                        avgGi = record.gi
                    )
                    val mealId = db.mealDao().insert(r)
                    db.mealDao().insertItem(com.tangdun.app.data.local.entity.MealItem(
                        mealId = mealId, foodName = record.food, carbs = record.carbs,
                        calories = record.calories, protein = record.protein,
                        fat = record.fat, gi = record.gi
                    ))
                    SelfLearningManager.notifyMealRecorded()
                    "已记录: ${record.food} ${record.carbs}g" to true
                }
                is ParsedRecord.Insulin -> {
                    db.insulinDao().insert(com.tangdun.app.data.local.entity.InsulinRecord(
                        timestamp = record.timestamp, insulinType = record.doseType,
                        doseUnits = record.dose, injectionSite = record.site
                    ))
                    SelfLearningManager.notifyInsulinRecorded()
                    "已记录: ${record.dose}U ${record.doseType}" to true
                }
                is ParsedRecord.Exercise -> {
                    db.exerciseDao().insert(com.tangdun.app.data.local.entity.ExerciseRecord(
                        startTime = record.timestamp - record.minutes * 60_000L,
                        exerciseType = record.exType, durationMin = record.minutes
                    ))
                    "已记录: ${record.exType} ${record.minutes}分钟" to true
                }
                is ParsedRecord.Glucose -> {
                    db.glucoseDao().insert(com.tangdun.app.data.local.entity.GlucoseRecord(
                        timestamp = record.timestamp, value = record.value,
                        source = "manual", scene = record.scene
                    ))
                    "已记录: ${record.value} mmol/L" to true
                }
            }
        } catch (e: Exception) {
            Log.w("AiRecord", "保存失败: ${e.message}")
            "保存失败: ${e.message}" to false
        }
    }

    private fun parseTime(timeStr: String, baseTime: Long = System.currentTimeMillis()): Long {
        if (timeStr.isBlank() || timeStr == "now") return baseTime
        Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(timeStr.trim())?.let { m ->
            val cal = Calendar.getInstance().apply { timeInMillis = baseTime }
            cal.set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
            cal.set(Calendar.MINUTE, m.groupValues[2].toInt())
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            val r = cal.timeInMillis
            return if (r > baseTime + 3600_000) r - 86400_000 else r
        }
        try { return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(timeStr.trim())?.time ?: baseTime } catch (_: Exception) {}
        timeStr.trim().toLongOrNull()?.let { return it }
        return baseTime
    }
}
