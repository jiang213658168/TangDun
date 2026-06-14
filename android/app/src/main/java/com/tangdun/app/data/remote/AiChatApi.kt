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

    // 糖尿病专家系统提示词
    private val systemPrompt = """你是"糖盾AI助手"，一位专业的糖尿病健康管理顾问。

你的职责：
1. 回答糖尿病相关的健康问题
2. 提供饮食、运动、用药建议
3. 解释血糖数据和趋势
4. 提醒注意事项和风险
5. 心理支持和鼓励

你的知识范围：
- 1型和2型糖尿病管理
- 血糖监测和胰岛素使用
- 碳水化合物计数和GI值
- 运动对血糖的影响
- 糖尿病并发症预防
- 低血糖和高血糖处理

重要原则：
- 始终建议用户咨询专业医生
- 不提供具体的药物剂量建议
- 强调个体化管理的重要性
- 用通俗易懂的语言解释
- 给出实用、可操作的建议

回复风格：
- 简洁明了，重点突出
- 使用表情符号增加亲和力
- 必要时使用列表和要点
- 关注用户的情绪状态"""

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
