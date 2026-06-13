package com.tangdun.app.data.remote

import android.content.Context
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
 * 食物营养AI查询
 *
 * 流程：
 * 1. 百度AI识别食物名称
 * 2. 把食物名称发给大模型API
 * 3. 大模型返回详细营养信息
 */
class FoodNutritionAi(private val context: Context) {

    companion object {
        private const val TAG = "FoodNutritionAi"
    }

    private val settingsManager = SettingsManager(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 食物营养信息
     */
    data class NutritionInfo(
        val name: String,
        val carbs: Double,        // 碳水 g/100g
        val calories: Double,     // 热量 kcal/100g
        val protein: Double,      // 蛋白质 g/100g
        val fat: Double,          // 脂肪 g/100g
        val fiber: Double,        // 膳食纤维 g/100g
        val gi: Double,           // GI值
        val giLevel: String,      // GI等级
        val portionGrams: Double, // 常见份量
        val portionName: String   // 份量名称
    )

    /**
     * 通过大模型获取食物营养信息
     *
     * @param foodName 食物名称（来自百度AI识别）
     * @return 营养信息
     */
    suspend fun getNutritionInfo(foodName: String): NutritionInfo? = withContext(Dispatchers.IO) {
        try {
            val provider = settingsManager.getAiProvider()
            val result = when (provider) {
                "openai" -> queryOpenAi(foodName)
                "ernie" -> queryErnie(foodName)
                else -> null
            }

            if (result != null) {
                parseNutritionResponse(result, foodName)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询失败: ${e.message}")
            null
        }
    }

    /**
     * 查询OpenAI兼容API
     */
    private suspend fun queryOpenAi(foodName: String): String? {
        val apiKey = settingsManager.getOpenAiApiKey()
        val baseUrl = settingsManager.getOpenAiBaseUrl()

        if (apiKey.isEmpty()) return null

        val prompt = buildPrompt(foodName)

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", "mimo-v2.5-pro")
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", 1000)
        }

        // Base URL已经包含/v1/
        val url = "${baseUrl}chat/completions"

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        Log.d(TAG, "调用AI API: $url")

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        Log.d(TAG, "API响应码: ${response.code}")

        if (response.isSuccessful && responseBody != null) {
            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                val content = message?.optString("content", "")
                if (!content.isNullOrBlank()) {
                    return content
                }
                // 有些模型返回在reasoning_content里
                val reasoning = message?.optString("reasoning_content", "")
                if (!reasoning.isNullOrBlank()) {
                    return reasoning
                }
            }
        }

        Log.e(TAG, "API调用失败: $responseBody")
        return null
    }

    /**
     * 查询文心一言API
     */
    private suspend fun queryErnie(foodName: String): String? {
        val apiKey = settingsManager.getErnieApiKey()
        val secretKey = settingsManager.getErnieSecretKey()

        if (apiKey.isEmpty() || secretKey.isEmpty()) return null

        // 获取access_token
        val token = getErnieToken(apiKey, secretKey) ?: return null

        val prompt = buildPrompt(foodName)

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val body = JSONObject().apply {
            put("messages", messages)
            put("temperature", 0.3)
            put("max_output_tokens", 500)
        }

        val request = Request.Builder()
            .url("https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/ernie-speed-128k?access_token=$token")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        if (response.isSuccessful && responseBody != null) {
            val json = JSONObject(responseBody)
            return json.optString("result")
        }

        return null
    }

    /**
     * 构建提示词
     */
    private fun buildPrompt(foodName: String): String {
        return "请告诉我${foodName}每100g的营养成分，直接返回JSON格式，不要其他文字：\n{\"name\":\"${foodName}\",\"carbs\":数字,\"calories\":数字,\"protein\":数字,\"fat\":数字,\"fiber\":数字,\"gi\":数字,\"portion_grams\":数字,\"portion_name\":\"份量描述\"}"
    }

    /**
     * 解析大模型返回的营养信息
     */
    private fun parseNutritionResponse(response: String, foodName: String): NutritionInfo? {
        return try {
            // 提取JSON部分（处理可能的```json```包裹）
            var jsonStr = response.trim()

            // 去掉可能的```json```标记
            if (jsonStr.contains("```")) {
                val start = jsonStr.indexOf("{")
                val end = jsonStr.lastIndexOf("}") + 1
                if (start >= 0 && end > start) {
                    jsonStr = jsonStr.substring(start, end)
                }
            } else {
                val start = jsonStr.indexOf("{")
                val end = jsonStr.lastIndexOf("}") + 1
                if (start >= 0 && end > start) {
                    jsonStr = jsonStr.substring(start, end)
                }
            }

            val json = JSONObject(jsonStr)
            val gi = json.optDouble("gi", 50.0)

            NutritionInfo(
                name = json.optString("name", foodName),
                carbs = json.optDouble("carbs", 0.0),
                calories = json.optDouble("calories", 0.0),
                protein = json.optDouble("protein", 0.0),
                fat = json.optDouble("fat", 0.0),
                fiber = json.optDouble("fiber", 0.0),
                gi = gi,
                giLevel = when {
                    gi < 55 -> "low"
                    gi <= 70 -> "medium"
                    else -> "high"
                },
                portionGrams = json.optDouble("portion_grams", 100.0),
                portionName = json.optString("portion_name", "一份")
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析失败: ${e.message}")
            null
        }
    }

    /**
     * 获取文心一言token
     */
    private suspend fun getErnieToken(apiKey: String, secretKey: String): String? {
        val body = JSONObject().apply {
            put("grant_type", "client_credentials")
            put("client_id", apiKey)
            put("client_secret", secretKey)
        }

        val request = Request.Builder()
            .url("https://aip.baidubce.com/oauth/2.0/token")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        return if (response.isSuccessful && responseBody != null) {
            JSONObject(responseBody).optString("access_token")
        } else null
    }
}
