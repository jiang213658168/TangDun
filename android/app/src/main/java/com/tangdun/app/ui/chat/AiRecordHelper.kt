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
你是糖盾AI记录解析器。用户用自然语言描述了健康事件，解析为JSON数组。

## 输出规则
输出一个JSON数组，每个元素是一条记录。用户可能一句话说多件事(如"吃了饭还打了胰岛素")。
例: [{"type":"meal",...}, {"type":"insulin",...}]
只有一件事也输出数组: [{"type":"meal",...}]

## 支持的全部记录类型

### 饮食 (meal)
{"type":"meal","food":"食物名","carbs":克数,"meal_type":"breakfast/lunch/dinner/snack","calories":热量,"gi":升糖指数(0-100),"protein":蛋白质克数,"fat":脂肪克数,"fiber":膳食纤维克数,"portion":克数,"time":"HH:mm或now"}

### 胰岛素 (insulin)
{"type":"insulin","dose":单位,"dose_type":"rapid/short/long/mixed","site":"腹部/手臂/大腿/臀部","notes":"备注","time":"HH:mm或now"}

### 运动 (exercise)
{"type":"exercise","ex_type":"跑步/快走/游泳/骑车/健身/散步/瑜伽/其他","minutes":分钟数,"intensity":"low/medium/high","notes":"备注","time":"HH:mm或now"}

### 血糖 (glucose)
{"type":"glucose","value":血糖值,"scene":"fasting/before_meal/after_meal/bedtime/other","time":"HH:mm或now"}

### 用药 (medication) — T2DM口服药
{"type":"medication","name":"二甲双胍/格列美脲/达格列净等","dose":"500mg/5mg等","med_type":"oral/injection/other","notes":"备注","time":"HH:mm或now"}
常见药物: 二甲双胍(格华止)、格列美脲(亚莫利)、达格列净(安达唐)、西格列汀(捷诺维)、阿卡波糖(拜唐苹)

### 体重 (weight)
{"type":"weight","value":公斤数,"time":"HH:mm或now"}

### 症状 (symptom) — 低血糖/高血糖不适
{"type":"symptom","symptom_type":"hypo/hyper/other","severity":"mild/moderate/severe","description":"症状描述","glucose":当时血糖值,"time":"HH:mm或now"}
低血糖症状: 心慌/手抖/出汗/饥饿感/头晕/乏力/视物模糊
高血糖症状: 口渴/多尿/乏力/视力模糊/恶心/腹痛

## 营养估算
- 一碗米饭(200g)≈56g碳水,230kcal,GI=70
- 小碗米饭(100g)≈28g碳水,116kcal,GI=70
- 一个馒头≈45g碳水,220kcal,GI=85
- 一碗面条≈45g碳水,300kcal,GI=60,蛋白质15g
- 一个苹果≈28g碳水,104kcal,GI=36,fiber=4g
- 一根香蕉≈27g碳水,110kcal,GI=55,fiber=3g
- 一个鸡蛋≈1g碳水,70kcal,蛋白质6g,脂肪5g
- 牛奶250ml≈12g碳水,160kcal,蛋白质8g,GI=30
- 青菜200g≈6g碳水,40kcal,fiber=4g,GI=25
- 瘦肉100g≈0g碳水,150kcal,蛋白质30g,脂肪8g
- 用户说"半碗"就除以2,"两碗"就乘以2; 不确定填0

## time字段
- "now"=当前时刻; "HH:mm"=今天(24h制)
- 语义: "早饭"="07:30","午饭"="12:00","晚饭"="18:30","刚才"="now","8点"="08:00"

## 示例
用户: "中午吃了碗牛肉面，还打了3U速效"
输出: [{"type":"meal","food":"牛肉面","carbs":45,"meal_type":"lunch","calories":300,"gi":60,"protein":15,"fat":8,"fiber":2,"portion":400,"time":"12:00"},{"type":"insulin","dose":3,"dose_type":"rapid","site":"","time":"12:00"}]

用户: "早上吃了二甲双胍500mg"
输出: [{"type":"medication","name":"二甲双胍","dose":"500mg","med_type":"oral","time":"07:30"}]

用户: "刚才有点心慌手抖，血糖3.2"
输出: [{"type":"symptom","symptom_type":"hypo","severity":"mild","description":"心慌手抖","glucose":3.2,"time":"now"}]

用户: "称了体重72公斤"
输出: [{"type":"weight","value":72,"time":"now"}]
""".trimIndent()

    suspend fun parse(context: Context, userInput: String): List<ParsedRecord> = withContext(Dispatchers.IO) {
        try {
            val service = AiChatService(context)
            val reply = service.sendMessage(listOf(
                ChatMessageDto("system", recordPrompt),
                ChatMessageDto("user", userInput)
            ))
            if (reply.isFailure) return@withContext emptyList()
            val text = reply.getOrNull()?.trim() ?: return@withContext emptyList()

            // 提取JSON数组 (支持裸JSON或```json```包裹)
            val jsonStr = Regex("```json\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)?.trim() ?: text.trim()
            val jsonArray = if (jsonStr.startsWith("[")) org.json.JSONArray(jsonStr)
                           else org.json.JSONArray().put(org.json.JSONObject(jsonStr))

            (0 until jsonArray.length()).mapNotNull { i ->
                val json = jsonArray.getJSONObject(i)
                val type = json.optString("type", "")
                val timeStr = json.optString("time", "now")
                val timestamp = parseTime(timeStr)
                val timeDisplay = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
                parseOne(type, json, timeDisplay, timestamp)
            }
        } catch (e: Exception) {
            Log.w("AiRecord", "解析失败: ${e.message}")
            emptyList()
        }
    }

    private fun parseOne(type: String, json: JSONObject, timeDisplay: String, timestamp: Long): ParsedRecord? = when (type) {
        "meal" -> ParsedRecord.Meal(
            food = json.optString("food", "未知"),
            carbs = json.optDouble("carbs", 0.0),
            calories = json.optDouble("calories", 0.0),
            gi = json.optDouble("gi", 60.0),
            mealType = json.optString("meal_type", "snack"),
            protein = json.optDouble("protein", 0.0),
            fat = json.optDouble("fat", 0.0),
            fiber = json.optDouble("fiber", 0.0),
            portion = json.optDouble("portion", 0.0),
            timeDisplay = timeDisplay, timestamp = timestamp
        )
        "insulin" -> ParsedRecord.Insulin(
            dose = json.optDouble("dose", 1.0),
            doseType = json.optString("dose_type", "rapid"),
            site = json.optString("site", ""),
            notes = json.optString("notes", ""),
            timeDisplay = timeDisplay, timestamp = timestamp
        )
        "exercise" -> ParsedRecord.Exercise(
            exType = json.optString("ex_type", "运动"),
            minutes = json.optInt("minutes", 30),
            intensity = json.optString("intensity", "medium"),
            notes = json.optString("notes", ""),
            timeDisplay = timeDisplay, timestamp = timestamp
        )
        "glucose" -> ParsedRecord.Glucose(
            value = json.optDouble("value", 5.0),
            scene = json.optString("scene", "other"),
            timeDisplay = timeDisplay, timestamp = timestamp
        )
        "medication" -> ParsedRecord.Medication(
            name = json.optString("name", ""),
            dose = json.optString("dose", ""),
            medType = json.optString("med_type", "oral"),
            notes = json.optString("notes", ""),
            timeDisplay = timeDisplay, timestamp = timestamp
        )
        "weight" -> ParsedRecord.Weight(
            value = json.optDouble("value", 70.0),
            timeDisplay = timeDisplay, timestamp = timestamp
        )
        "symptom" -> ParsedRecord.Symptom(
            symptomType = json.optString("symptom_type", "other"),
            severity = json.optString("severity", "mild"),
            description = json.optString("description", ""),
            glucose = if (json.has("glucose")) json.optDouble("glucose") else null,
            timeDisplay = timeDisplay, timestamp = timestamp
        )
        else -> null
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
                        totalFiber = record.fiber, avgGi = record.gi
                    )
                    val mealId = db.mealDao().insert(r)
                    db.mealDao().insertItem(com.tangdun.app.data.local.entity.MealItem(
                        mealId = mealId, foodName = record.food, carbs = record.carbs,
                        calories = record.calories, protein = record.protein,
                        fat = record.fat, fiber = record.fiber, gi = record.gi,
                        portionGrams = record.portion
                    ))
                    SelfLearningManager.notifyMealRecorded()
                    "已记录: ${record.food} ${record.carbs}g" to true
                }
                is ParsedRecord.Insulin -> {
                    db.insulinDao().insert(com.tangdun.app.data.local.entity.InsulinRecord(
                        timestamp = record.timestamp, insulinType = record.doseType,
                        doseUnits = record.dose, injectionSite = record.site,
                        notes = record.notes
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
                is ParsedRecord.Medication -> {
                    db.medicationDao().insert(com.tangdun.app.data.local.entity.MedicationRecord(
                        timestamp = record.timestamp, medicationName = record.name,
                        dose = record.dose, medicationType = record.medType, notes = record.notes
                    ))
                    "已记录: ${record.name} ${record.dose}" to true
                }
                is ParsedRecord.Weight -> {
                    db.weightDao().insert(com.tangdun.app.data.local.entity.WeightRecord(
                        timestamp = record.timestamp, weightKg = record.value
                    ))
                    "已记录: ${record.value}kg" to true
                }
                is ParsedRecord.Symptom -> {
                    db.symptomDao().insert(com.tangdun.app.data.local.entity.SymptomRecord(
                        timestamp = record.timestamp, symptomType = record.symptomType,
                        severity = record.severity, symptoms = record.description,
                        glucoseValue = record.glucose, notes = ""
                    ))
                    "已记录: ${record.description}" to true
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
