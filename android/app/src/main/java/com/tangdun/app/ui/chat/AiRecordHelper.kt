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

## 重要: 推理步骤 (按顺序执行)
1. **先识别所有时间点**: 0:00/9:30/12:30/13:00 等, 把描述按时间分段
2. **再识别每个时间段内的事件**: 饮食/胰岛素/用药 等
3. **每件事单独一条 JSON 记录**, 不要合并到一条
4. **时间继承**: 如果一句话没说时间但紧跟某个时间点, 继承该时间
5. **所有 time 字段必须是 24h 制 HH:mm 或 "now"**

## 输出规则
输出一个 JSON 数组, 每个元素是一条记录。用户可能一句话说多件事。
例: [{"type":"meal",...}, {"type":"insulin",...}]
只有一件事也输出数组: [{"type":"meal",...}]

## 支持的全部记录类型

### 饮食 (meal)
{"type":"meal","food":"食物名","carbs":克数,"meal_type":"breakfast/lunch/dinner/snack","calories":热量,"gi":升糖指数(0-100),"protein":蛋白质克数,"fat":脂肪克数,"fiber":膳食纤维克数,"portion":克数,"notes":"备注","time":"HH:mm或now"}

### 胰岛素 (insulin)
{"type":"insulin","dose":单位,"dose_type":"rapid/short/long/mixed","site":"腹部/手臂/大腿/臀部","notes":"备注","time":"HH:mm或now"}

### 运动 (exercise)
{"type":"exercise","ex_type":"跑步/快走/游泳/骑车/健身/散步/瑜伽/其他","minutes":分钟数,"intensity":"low/medium/high","notes":"备注","time":"HH:mm或now"}

### 血糖 (glucose)
{"type":"glucose","value":血糖值,"scene":"fasting/before_meal/after_meal/bedtime/other","time":"HH:mm或now"}

### 用药 (medication) — T2DM口服药
{"type":"medication","name":"二甲双胍/格列美脲/达格列净等","dose":"500mg/5mg等","med_type":"oral/injection/other","notes":"备注","time":"HH:mm或now"}

### 体重 (weight)
{"type":"weight","value":公斤数,"time":"HH:mm或now"}

### 症状 (symptom)
{"type":"symptom","symptom_type":"hypo/hyper/other","severity":"mild/moderate/severe","description":"症状描述","glucose":当时血糖值,"time":"HH:mm或now"}

## 时间解析规则 (重要!)
- "零点/0点/0:00" = "00:00"
- "早上 7 点/7 点" = "07:00" (默认早上, 除非明确"晚上 7 点" = "19:00")
- "上午 9 点" = "09:00"; "下午 1 点" = "13:00"; "晚上 8 点" = "20:00"
- "9 点半/9:30" = "09:30"; "12 点半/12:30" = "12:30"
- "早饭/早餐" = "07:30"
- "午饭/中午" = "12:00"
- "晚饭/晚上" = "18:30"
- "加餐" = "snack" 类型, time 跟随描述
- "刚才" = "now"
- "午饭前" → 触发的事件用 12:00 (或推断的午饭前 5 分钟)

## 份量估算 (重要!)
- "1 碗米饭" ≈ 200g (碳水 56g)
- "1 小碗米饭" ≈ 100g (碳水 28g)
- "半个馒头" ≈ 35g (碳水 16g)
- "1 个拳头大小馒头" ≈ 70g (碳水 32g)
- "半个拳头大小" ≈ 35g (碳水 16g)
- "1 个苹果" ≈ 200g (碳水 28g, GI 36)
- "1 个鸡蛋" ≈ 50g (碳水 1g, 蛋白质 6g)
- "1 杯牛奶 250ml" ≈ 250g (碳水 12g, 蛋白质 8g)
- "1 盘菜(200g)" ≈ 200g (碳水 6g, 纤维 4g)
- "半盘" ≈ 100g
- "几片/几个" = 50g 左右
- 用户说"25g 苏打饼干" → 直接用 25g
- 用户说"酥皮扒掉了" → notes 记录, 克数按原估算

## 营养估算 (常见食物)
- 米饭 100g = 28g 碳水, 116kcal, GI 70
- 面条 100g = 25g 碳水, 110kcal, GI 60
- 馒头 100g = 45g 碳水, 220kcal, GI 85
- 全麦面包 100g = 41g 碳水, 250kcal, GI 50
- 鸡蛋 1 个 = 1g 碳水, 70kcal, 蛋白质 6g
- 牛奶 250ml = 12g 碳水, 160kcal, 蛋白质 8g, GI 30
- 瘦肉 100g = 0g 碳水, 150kcal, 蛋白质 30g, 脂肪 8g
- 蔬菜 200g = 6g 碳水, 40kcal, GI 25
- 水果 200g = 25g 碳水, 100kcal, GI 40
- 油炸类 (炸鸡腿/薯条) 100g = 25g 碳水, 280kcal, 脂肪 15g
- 苏打饼干 25g = 18g 碳水, 110kcal, GI 70
- 葱爆羊肉 100g = 5g 碳水, 200kcal, 蛋白质 20g, 脂肪 12g
- 香椿炒鸡蛋 100g = 5g 碳水, 180kcal, 蛋白质 12g, 脂肪 13g
- 小油菜 100g = 2g 碳水, 20kcal, GI 20
- 荞麦馒头 100g = 45g 碳水, 220kcal, GI 65
- 虾仁 100g = 1g 碳水, 90kcal, 蛋白质 18g, 脂肪 1g

## 长描述完整示例 (重要! 多种事件混合)

用户输入: "今天早上零点, 打了十个单位的长效, 早饭在9点半吃的, 打了八个单位的速效, 吃的一个25克的苏打饼干, 太平品牌的, 半盘香椿炒鸡蛋, 半盘小油菜, 早餐后面还吃了个顿, 加餐是12点半吃的, 吃了一个炸鸡腿, 但是把外面的那层酥皮给扒掉了, 午饭前打了八个单位的速效是, 午饭是13点吃的, 吃了半盘葱爆羊肉, 吃了几个虾仁, 吃了一个荞麦面的馒头, 大概半个拳头大小"

正确输出 (按时间排序):
[
  {"type":"insulin","dose":10,"dose_type":"long","site":"","notes":"","time":"00:00"},
  {"type":"insulin","dose":8,"dose_type":"rapid","site":"","notes":"","time":"09:30"},
  {"type":"meal","food":"苏打饼干","carbs":18,"meal_type":"breakfast","calories":110,"gi":70,"portion":25,"notes":"太平品牌","time":"09:30"},
  {"type":"meal","food":"香椿炒鸡蛋","carbs":5,"meal_type":"breakfast","calories":180,"gi":40,"portion":100,"time":"09:30"},
  {"type":"meal","food":"小油菜","carbs":2,"meal_type":"breakfast","calories":20,"portion":100,"time":"09:30"},
  {"type":"meal","food":"炸鸡腿","carbs":25,"meal_type":"snack","calories":280,"portion":100,"notes":"酥皮已扒掉","time":"12:30"},
  {"type":"insulin","dose":8,"dose_type":"rapid","site":"","notes":"午饭前","time":"13:00"},
  {"type":"meal","food":"葱爆羊肉","carbs":5,"meal_type":"lunch","calories":200,"portion":100,"time":"13:00"},
  {"type":"meal","food":"虾仁","carbs":1,"meal_type":"lunch","calories":90,"portion":50,"notes":"几个","time":"13:00"},
  {"type":"meal","food":"荞麦馒头","carbs":16,"meal_type":"lunch","calories":110,"portion":35,"notes":"半个拳头大小","time":"13:00"}
]

## 关键原则
- 每个事件一条 JSON
- 同一时间的多个事件 → 多条 JSON, 不要合并
- 用户没提时间 → 继承上文最近的时间点
- "加餐" 是 meal_type="snack" 而非 breakfast/lunch/dinner
- 模糊份量 ("几个/几片") 按 50g 估算
- "酥皮扒掉/去皮/去骨" 等信息放 notes
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
