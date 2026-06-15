package com.tangdun.app.data.remote

import android.content.Context
import com.google.gson.annotations.SerializedName
import com.tangdun.app.util.SettingsManager
import retrofit2.Response
import retrofit2.http.*

/**
 * AI对话API接口
 *
 * 支持多个AI服务商：
 * - OpenAI (GPT-3.5/4)
 * - 百度文心一言
 * - 阿里通义千问
 */
interface AiChatApi {

    // ===== OpenAI兼容接口 =====

    @POST("chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiRequest
    ): Response<OpenAiResponse>

    // ===== 百度文心一言 =====

    @POST("rpc/2.0/ai_custom/v1/wenxinworkshop/chat/ernie-speed-128k")
    suspend fun ernieChat(
        @Header("Authorization") authorization: String,
        @Body request: ErnieRequest
    ): Response<ErnieResponse>
}

// ===== OpenAI 数据模型 =====

data class OpenAiRequest(
    val model: String = "mimo-v2.5-pro",
    val messages: List<ChatMessageDto>,
    val temperature: Double = 0.7,
    @SerializedName("max_tokens")
    val maxTokens: Int = 1000
)

data class ChatMessageDto(
    val role: String,
    val content: String
)

data class OpenAiResponse(
    val id: String?,
    val choices: List<Choice>?,
    val error: ErrorDto?
)

data class Choice(
    val message: ChatMessageDto?,
    @SerializedName("finish_reason")
    val finishReason: String?
)

data class ErrorDto(
    val message: String?,
    val type: String?
)

// ===== 百度文心一言 数据模型 =====

data class ErnieRequest(
    val messages: List<ChatMessageDto>,
    val temperature: Double = 0.7,
    @SerializedName("max_output_tokens")
    val maxOutputTokens: Int = 1000
)

data class ErnieResponse(
    val result: String?,
    val error_code: Int?,
    val error_msg: String?
)

// ===== AI对话服务 =====

/**
 * AI对话服务
 *
 * 支持OpenAI和百度文心一言
 */
class AiChatService(private val context: Context) {

    private val settingsManager = SettingsManager(context)

    // 糖尿病专家系统提示词 (含自然语言记录功能)
    private val systemPrompt = """你是"糖盾AI助手"，一位专业的糖尿病健康管理顾问。

## 你的职责
1. 回答糖尿病相关的健康问题
2. 提供饮食、运动、用药建议
3. 解释血糖数据和趋势
4. **帮助用户通过自然语言记录数据** (重要!)

## 自然语言记录功能
当用户说"我吃了XX"、"我打了XX单位"、"我运动了XX"等，你需要:
1. 先确认理解正确，回复简短确认
2. 然后在回复末尾附加一个JSON指令块，格式:
```json
{"action":"record_meal","food":"食物名","carbs":克数,"meal_type":"breakfast/lunch/dinner/snack","calories":热量,"gi":升糖指数}
{"action":"record_insulin","type":"rapid/short/long","dose":单位}
{"action":"record_exercise","type":"运动类型","minutes":分钟数}
{"action":"record_glucose","value":血糖值,"scene":"fasting/before_meal/after_meal/bedtime/other"}
```

## 营养知识 (用于估算)
- 米饭100g≈28g碳水, 116kcal, GI=70
- 面条100g≈25g碳水, 110kcal, GI=60
- 馒头100g≈45g碳水, 220kcal, GI=85
- 全麦面包100g≈41g碳水, 250kcal, GI=50
- 苹果200g≈28g碳水, 104kcal, GI=36
- 香蕉120g≈27g碳水, 110kcal, GI=55
- 鸡蛋1个≈1g碳水, 70kcal
- 牛奶250ml≈12g碳水, 160kcal, GI=30
- 蔬菜200g≈6g碳水, 40kcal
- 如果用户没有说具体克数，根据常识估算

## 重要原则
- 始终建议用户咨询专业医生
- 用通俗易懂的语言解释
- 给出实用、可操作的建议
- 回复简洁明了，重点突出"""

    /**
     * 发送消息并获取回复
     */
    suspend fun sendMessage(
        messages: List<ChatMessageDto>
    ): Result<String> {
        val provider = settingsManager.getAiProvider()

        return when (provider) {
            "openai" -> sendOpenAiMessage(messages)
            "ernie" -> sendErnieMessage(messages)
            else -> Result.failure(Exception("请在设置中配置AI服务"))
        }
    }

    private suspend fun sendOpenAiMessage(
        messages: List<ChatMessageDto>
    ): Result<String> {
        val apiKey = settingsManager.getOpenAiApiKey()
        if (apiKey.isEmpty()) {
            return Result.failure(Exception("请在设置中配置OpenAI API Key"))
        }

        val baseUrl = settingsManager.getOpenAiBaseUrl()
        if (baseUrl.isEmpty()) {
            return Result.failure(Exception("请在设置中配置API地址"))
        }

        return try {
            val api = AiChatRetrofitClient.getApi(baseUrl)
            val request = OpenAiRequest(
                messages = listOf(ChatMessageDto("system", systemPrompt)) + messages,
                temperature = 0.7,
                maxTokens = 1000
            )

            val response = api.chatCompletions("Bearer $apiKey", request)

            val body = response.body()
            if (response.isSuccessful && body?.choices != null) {
                val content = body.choices!!.firstOrNull()?.message?.content
                if (content != null) {
                    Result.success(content)
                } else {
                    Result.failure(Exception("AI返回内容为空"))
                }
            } else {
                val error = body?.error?.message ?: "请求失败: ${response.code()}"
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun sendErnieMessage(
        messages: List<ChatMessageDto>
    ): Result<String> {
        val apiKey = settingsManager.getErnieApiKey()
        val secretKey = settingsManager.getErnieSecretKey()

        if (apiKey.isEmpty() || secretKey.isEmpty()) {
            return Result.failure(Exception("请在设置中配置文心一言API Key"))
        }

        return try {
            // 获取access_token
            val token = getErnieAccessToken(apiKey, secretKey)
            if (token == null) {
                return Result.failure(Exception("获取access_token失败"))
            }

            val api = AiChatRetrofitClient.getApi("https://aip.baidubce.com/")
            val request = ErnieRequest(
                messages = listOf(ChatMessageDto("user", systemPrompt + "\n\n用户问题：" + messages.last().content)),
                temperature = 0.7,
                maxOutputTokens = 1000
            )

            val response = api.ernieChat("Bearer $token", request)

            val body = response.body()
            if (response.isSuccessful && body?.result != null) {
                Result.success(body.result!!)
            } else {
                val error = body?.error_msg ?: "请求失败"
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getErnieAccessToken(apiKey: String, secretKey: String): String? {
        return try {
            val client = okhttp3.OkHttpClient()
            val body = okhttp3.FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", apiKey)
                .add("client_secret", secretKey)
                .build()
            val request = okhttp3.Request.Builder()
                .url("https://aip.baidubce.com/oauth/2.0/token")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            if (response.isSuccessful && responseBody != null) {
                org.json.JSONObject(responseBody).optString("access_token")
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取系统提示词
     */
    fun getSystemPrompt(): String = systemPrompt

    /**
     * 处理AI回复中的记录指令 — 提取JSON并执行数据库操作
     * @return Pair<显示文本, 执行结果数量>
     */
    suspend fun processRecordingCommands(context: Context, aiReply: String): Pair<String, Int> {
        val jsonPattern = Regex("""\{[^{}]*"action"\s*:\s*"record_[^"]*"[^{}]*\}""")
        val matches = jsonPattern.findAll(aiReply)
        var displayText = aiReply
        var executed = 0

        for (match in matches) {
            try {
                val json = org.json.JSONObject(match.value)
                val action = json.optString("action", "")
                val db = com.tangdun.app.TangDunApp.getDatabase(context)

                when (action) {
                    "record_meal" -> {
                        val food = json.optString("food", "未命名")
                        val carbs = json.optDouble("carbs", 30.0)
                        val calories = if (json.has("calories")) json.optDouble("calories") else carbs * 4.0
                        val gi = json.optDouble("gi", 60.0)
                        val mealType = json.optString("meal_type", "snack")

                        val record = com.tangdun.app.data.local.entity.MealRecord(
                            timestamp = System.currentTimeMillis(),
                            mealType = mealType, totalCarbs = carbs,
                            totalCalories = calories, avgGi = gi
                        )
                        val mealId = db.mealDao().insert(record)
                        db.mealDao().insertItem(com.tangdun.app.data.local.entity.MealItem(
                            mealId = mealId, foodName = food,
                            carbs = carbs, calories = calories, gi = gi
                        ))
                        displayText = displayText.replace(match.value, "")
                        executed++
                    }
                    "record_insulin" -> {
                        val type = json.optString("type", "rapid")
                        val dose = json.optDouble("dose", 1.0)
                        db.insulinDao().insert(com.tangdun.app.data.local.entity.InsulinRecord(
                            timestamp = System.currentTimeMillis(),
                            insulinType = type, doseUnits = dose
                        ))
                        displayText = displayText.replace(match.value, "")
                        executed++
                    }
                    "record_exercise" -> {
                        val exType = json.optString("type", "walking")
                        val minutes = json.optInt("minutes", 30)
                        db.exerciseDao().insert(com.tangdun.app.data.local.entity.ExerciseRecord(
                            startTime = System.currentTimeMillis() - minutes * 60_000L,
                            exerciseType = exType, durationMin = minutes
                        ))
                        displayText = displayText.replace(match.value, "")
                        executed++
                    }
                    "record_glucose" -> {
                        val value = json.optDouble("value", 5.0)
                        val scene = json.optString("scene", "other")
                        db.glucoseDao().insert(com.tangdun.app.data.local.entity.GlucoseRecord(
                            timestamp = System.currentTimeMillis(),
                            value = value, source = "manual", scene = scene
                        ))
                        displayText = displayText.replace(match.value, "")
                        executed++
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AiChat", "解析记录指令失败: ${e.message}")
            }
        }

        // 清理多余空行
        displayText = displayText.replace(Regex("\n{3,}"), "\n\n").trim()
        if (executed > 0 && displayText.isBlank()) {
            displayText = "已记录 ✅"
        }
        return Pair(displayText, executed)
    }
}

/**
 * AI聊天Retrofit客户端
 */
object AiChatRetrofitClient {
    private var currentBaseUrl = ""
    private var currentApi: AiChatApi? = null

    fun getApi(baseUrl: String): AiChatApi {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        if (normalizedUrl != currentBaseUrl || currentApi == null) {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)  // AI生成需要较长时间
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val retrofit = retrofit2.Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(client)
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                .build()
            currentApi = retrofit.create(AiChatApi::class.java)
            currentBaseUrl = normalizedUrl
        }
        return currentApi ?: throw IllegalStateException("AiChatApi未初始化，请先调用getApi()")
    }
}
