package com.tangdun.app.ai

import android.util.Log
import com.tangdun.app.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI 大模型客户端 - 调用通用 OpenAI 兼容 chat/completions API
 *
 * 支持：DeepSeek / OpenAI / 小米 MiMo / 智谱 GLM / Moonshot 等所有兼容 OpenAI 协议的接口
 *
 * 主要用途：让 AI 助手利用大模型能力自动理解用户意图并添加记录
 */
class AIClient(private val settingsManager: SettingsManager) {

    companion object {
        private const val TAG = "AIClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_MODEL = "mimo-v2-flash"  // 小米 MiMo 默认模型
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 是否已配置 API Key
     */
    fun isConfigured(): Boolean = settingsManager.isAiConfigured()

    /**
     * 调用大模型解析用户输入为意图 JSON 数组
     *
     * 返回 [{"type":"CREATE","target":"glucose","params":{"value":6.5,"scene":"fasting"}}, ...]
     * 失败/超时返回 null
     */
    suspend fun parseIntentsWithAI(userInput: String): List<AIIntent>? = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            Log.i(TAG, "AI 未配置, 跳过 API 调用")
            return@withContext null
        }

        try {
            val systemPrompt = buildSystemPrompt()
            val userPrompt = "请解析以下用户输入并返回 JSON 数组:\n\n用户输入: \"$userInput\""

            val requestBody = buildRequestBody(systemPrompt, userPrompt)
            val baseUrl = settingsManager.getOpenAiBaseUrl().trimEnd('/')
            val apiKey = settingsManager.getOpenAiApiKey()

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody(JSON_MEDIA))
                .build()

            Log.i(TAG, "调用 AI API: $baseUrl/chat/completions (input: ${userInput.take(50)}...)")
            val response = httpClient.newCall(request).execute()

            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "AI API 失败: ${resp.code} ${resp.message}")
                    return@withContext null
                }
                val bodyStr = resp.body?.string() ?: ""
                Log.i(TAG, "AI API 响应: ${bodyStr.take(200)}...")
                val json = JSONObject(bodyStr)
                val content = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                Log.i(TAG, "AI 返回 content: ${content.take(500)}")
                return@withContext parseAIResponseToIntents(content)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI API 调用异常: ${e.message}", e)
            return@withContext null
        }
    }

    /**
     * 构建系统 prompt - 教会大模型解析用户输入为 intent JSON
     */
    private fun buildSystemPrompt(): String = """
你是"糖盾"糖尿病管理 App 的 AI 助手。你需要把用户的自然语言输入解析为结构化 intent JSON 数组。

## 输出格式
严格输出 JSON 数组 (不要有 markdown ```json``` 标记, 不要有解释文字):
[
  {"type":"CREATE","target":"glucose","params":{"value":6.5,"scene":"fasting","time_offset_min":-60}},
  {"type":"CREATE","target":"insulin","params":{"dose":10,"dose_type":"long","time_offset_min":0}}
]

## 支持的 type
- CREATE: 创建一条记录
- READ: 查询数据
- UPDATE: 修改记录
- DELETE: 删除单条记录
- BULK_DELETE: 批量删除
- NAVIGATE: 跳转到页面
- CONFIGURE: 修改设置
- EXPORT: 导出数据
- IMPORT: 导入数据

## 支持的 target + params 字段
1. glucose (血糖): value (Double mmol/L, 必填), scene (餐前/餐后/空腹/睡前/其他, 默认"其他"), time_offset_min (相对于当前时间的分钟偏移, 如"10分钟前"=-10)
2. insulin (胰岛素): dose (Double 单位, 必填), dose_type (长效/速效/短效/预混/基础, 默认速效), time_offset_min
3. meal (饮食): food_name (String 必填), portion (Double 克 默认100), carbs (Double 碳水克 可选, 若不填估算), time_offset_min
4. exercise (运动): duration_min (Int 分钟 必填), exercise_type (跑步/快走/散步/骑车/游泳/健身/瑜伽/跳绳/爬山/打球/其他), intensity (low/medium/high 默认medium), time_offset_min
5. sleep (睡眠): duration_minutes (Int 总分钟数 必填), time_offset_min
6. bp (血压): systolic (Int 收缩压 必填), diastolic (Int 舒张压 必填), time_offset_min
7. weight (体重): weight_kg (Double 必填), time_offset_min
8. ketone (酮体): ketone_level (Double mmol/L 必填), time_offset_min
9. medication (用药): medication_name (String 必填), dose (String 如"500mg"), time_offset_min
10. symptom (症状): symptoms (String 用逗号分隔多个症状: 心慌/手抖/出汗/饥饿感/头晕/乏力/视物模糊/口渴/多尿/恶心/腹痛/呼吸急促/意识模糊), time_offset_min

## 时间推断规则
- "今早零点"/"今天0点" → time_offset_min = -现在时间(分钟)
- "半小时前" → time_offset_min = -30
- "午饭后"/"9点半吃的" → time_offset_min = 估算
- 没说时间 → time_offset_min = 0 (当前)

## 中文数字转换
"十"=10, "十五"=15, "半"=0.5, "一百二十"=120, "一万"=10000

## 食物份量估算
- "半盘"=100g, "一盘"=200g
- "拳头大小"=70g, "半个拳头"=35g
- "几个"按 50g/个 估算
- 数字+克 = 直接用

## 例子
输入: "血糖7.5 餐前"
输出: [{"type":"CREATE","target":"glucose","params":{"value":7.5,"scene":"before_meal"}}]

输入: "打了十个单位的长效, 散步了1小时"
输出: [
  {"type":"CREATE","target":"insulin","params":{"dose":10,"dose_type":"long"}},
  {"type":"CREATE","target":"exercise","params":{"duration_min":60,"exercise_type":"散步"}}
]

输入: "今天早上吃了 25克苏打饼干, 半盘香椿炒鸡蛋"
输出: [
  {"type":"CREATE","target":"meal","params":{"food_name":"苏打饼干","portion":25}},
  {"type":"CREATE","target":"meal","params":{"food_name":"香椿炒鸡蛋","portion":100}}
]

输入: "今天血糖怎么样"
输出: [{"type":"READ","target":"glucose","params":{"time_scope":"today"}}]

输入: "打开预测页"
输出: [{"type":"NAVIGATE","target":"page","params":{"route":"prediction"}}]

## 重要
- 只输出 JSON 数组, 不要其他文字
- 数组可为空 (如果无法解析)
- 数字必须是 JSON number (不带引号)
""".trimIndent()

    private fun buildRequestBody(systemPrompt: String, userPrompt: String): String {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", userPrompt))
        }
        return JSONObject().apply {
            put("model", DEFAULT_MODEL)
            put("messages", messages)
            put("temperature", 0.1)  // 低温度保证稳定 JSON
            put("max_tokens", 2000)
        }.toString()
    }

    /**
     * 解析 AI 返回的 JSON 内容为 List<AIIntent>
     */
    private fun parseAIResponseToIntents(content: String): List<AIIntent>? {
        return try {
            // 清理可能的 markdown 标记
            val cleaned = content
                .replace("```json", "")
                .replace("```", "")
                .trim()
            val arr = JSONArray(cleaned)
            val result = mutableListOf<AIIntent>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val intent = obj.toAIIntent() ?: continue
                result.add(intent)
            }
            Log.i(TAG, "AI 解析成功: ${result.size} 条意图")
            result
        } catch (e: Exception) {
            Log.w(TAG, "解析 AI 响应失败: ${e.message}", e)
            null
        }
    }

    /**
     * 把 JSONObject 转为 AIIntent
     */
    private fun JSONObject.toAIIntent(): AIIntent? {
        return try {
            val type = getString("type")
            val target = getString("target")
            val paramsObj = optJSONObject("params") ?: JSONObject()
            val params = mutableMapOf<String, Any>()
            paramsObj.keys().forEach { key ->
                val v = paramsObj.get(key)
                params[key] = v
            }
            // 把 params 里的 time_offset_min 转为 timestamp
            if (params.containsKey("time_offset_min")) {
                val offset = (params["time_offset_min"] as Number).toLong()
                val ts = System.currentTimeMillis() + offset * 60_000L
                params["timestamp"] = ts
                params.remove("time_offset_min")
            }

            val typeEnum = runCatching { AIIntentType.valueOf(type) }.getOrNull() ?: return null
            // AITarget 是 string 常量不是 enum, 直接使用
            val targetStr = target.lowercase()

            val description = buildDescription(typeEnum, targetStr, params)

            AIIntent(
                type = typeEnum,
                target = targetStr,
                action = "",
                params = params,
                description = description,
                requiresConfirmation = typeEnum.requiresConfirmationByDefault()
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析单条 intent 失败: ${e.message}", e)
            null
        }
    }

    private fun buildDescription(type: AIIntentType, target: String, params: Map<String, Any>): String {
        return when (type) {
            AIIntentType.CREATE -> {
                when (target) {
                    AITarget.GLUCOSE -> "记录血糖 ${params["value"]} mmol/L"
                    AITarget.INSULIN -> "记录胰岛素 ${params["dose"]} 单位 ${params["dose_type"] ?: "速效"}"
                    AITarget.MEAL -> "记录饮食 ${params["food_name"]} ${params["portion"] ?: 100}g"
                    AITarget.EXERCISE -> "记录运动 ${params["exercise_type"] ?: ""} ${params["duration_min"]} 分钟"
                    AITarget.SLEEP -> "记录睡眠 ${params["duration_minutes"]} 分钟"
                    AITarget.BP -> "记录血压 ${params["systolic"]}/${params["diastolic"]}"
                    AITarget.WEIGHT -> "记录体重 ${params["weight_kg"]} kg"
                    AITarget.KETONE -> "记录酮体 ${params["ketone_level"]} mmol/L"
                    AITarget.MEDICATION -> "记录用药 ${params["medication_name"]} ${params["dose"] ?: ""}"
                    AITarget.SYMPTOM -> "记录症状 ${params["symptoms"]}"
                    else -> "创建 $target 记录"
                }
            }
            AIIntentType.READ -> "查询 ${params["time_scope"] ?: "today"} $target 数据"
            AIIntentType.NAVIGATE -> "跳转到 ${params["route"]}"
            AIIntentType.CONFIGURE -> "修改设置 ${params["action"]}"
            AIIntentType.BULK_DELETE -> "删除 ${params["time_scope"]} $target 记录"
            AIIntentType.DELETE -> "删除 $target 记录"
            AIIntentType.UPDATE -> "修改 $target 记录"
            AIIntentType.EXPORT -> "导出 $target 数据"
            AIIntentType.IMPORT -> "导入 $target 数据"
        }
    }
}

/**
 * AIIntentType 是否需要用户确认
 */
fun AIIntentType.requiresConfirmationByDefault(): Boolean = when (this) {
    AIIntentType.NAVIGATE, AIIntentType.CONFIGURE -> false
    AIIntentType.READ -> false
    else -> true  // CREATE/UPDATE/DELETE/BULK_DELETE/EXPORT/IMPORT 都要确认
}