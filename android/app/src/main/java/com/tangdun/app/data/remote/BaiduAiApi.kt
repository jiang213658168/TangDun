package com.tangdun.app.data.remote

import android.content.Context
import android.util.Log
import com.tangdun.app.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 百度AI食物识别服务
 *
 * 使用百度菜品识别接口
 * https://aip.baidubce.com/rest/2.0/image-classify/v2/dish
 */
class FoodRecognitionService(private val context: Context) {

    companion object {
        private const val TAG = "FoodRecognition"
        private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        private const val DISH_URL = "https://aip.baidubce.com/rest/2.0/image-classify/v2/dish"
    }

    private val settingsManager = SettingsManager(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var accessToken: String? = null
    private var tokenExpiry: Long = 0

    /**
     * 检查API是否已配置
     */
    fun isConfigured(): Boolean {
        return settingsManager.isBaiduApiConfigured()
    }

    /**
     * 识别食物
     *
     * @param imageBase64 图片的Base64编码（已去掉编码头）
     * @return 识别结果列表
     */
    suspend fun recognize(imageBase64: String): List<FoodRecognitionResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            Log.w(TAG, "API未配置")
            return@withContext emptyList()
        }

        // 获取access_token
        val token = getAccessToken()
        if (token == null) {
            Log.e(TAG, "获取access_token失败")
            return@withContext emptyList()
        }

        try {
            // 构建请求
            // 注意：OkHttp的FormBody会自动urlencode，所以直接传base64即可
            val body = FormBody.Builder()
                .add("image", imageBase64)  // 不需要手动urlencode
                .add("top_num", "5")
                .build()

            val request = Request.Builder()
                .url("$DISH_URL?access_token=$token")
                .post(body)
                .build()

            Log.d(TAG, "调用菜品识别API...")
            Log.d(TAG, "URL: $DISH_URL?access_token=${token.take(20)}...")
            Log.d(TAG, "图片Base64长度: ${imageBase64.length}")
            Log.d(TAG, "Body大小: ${body.contentLength()} bytes")

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            Log.d(TAG, "响应码: ${response.code}")
            Log.d(TAG, "响应体: ${responseBody?.take(500)}")

            if (response.isSuccessful && responseBody != null) {
                val json = JSONObject(responseBody)
                val resultArray = json.optJSONArray("result")

                if (resultArray != null && resultArray.length() > 0) {
                    val results = mutableListOf<FoodRecognitionResult>()
                    for (i in 0 until resultArray.length()) {
                        val item = resultArray.getJSONObject(i)
                        results.add(FoodRecognitionResult(
                            name = item.optString("name", ""),
                            confidence = item.optDouble("probability", 0.0),
                            caloriesPer100g = item.optDouble("calorie", 0.0)
                        ))
                    }
                    Log.d(TAG, "识别成功: ${results.size}个结果")
                    results
                } else {
                    Log.w(TAG, "未识别到食物")
                    emptyList()
                }
            } else {
                Log.e(TAG, "请求失败: ${response.code}")
                Log.e(TAG, "错误信息: $responseBody")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "识别异常: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 获取access_token
     */
    private suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        // 检查缓存
        if (accessToken != null && System.currentTimeMillis() < tokenExpiry) {
            return@withContext accessToken
        }

        val apiKey = settingsManager.getBaiduApiKey()
        val secretKey = settingsManager.getBaiduSecretKey()

        if (apiKey.isEmpty() || secretKey.isEmpty()) {
            Log.w(TAG, "API Key未配置")
            return@withContext null
        }

        try {
            val body = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", apiKey)
                .add("client_secret", secretKey)
                .build()

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(body)
                .build()

            Log.d(TAG, "获取access_token...")
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val json = JSONObject(responseBody)
                val token = json.optString("access_token")
                val expiresIn = json.optInt("expires_in", 2592000)

                if (token.isNotEmpty()) {
                    accessToken = token
                    tokenExpiry = System.currentTimeMillis() + (expiresIn - 60) * 1000
                    Log.d(TAG, "获取token成功: ${token.take(20)}...")
                    return@withContext token
                }
            }

            Log.e(TAG, "获取token失败: ${response.code}")
            Log.e(TAG, "响应: $responseBody")
            null
        } catch (e: Exception) {
            Log.e(TAG, "获取token异常: ${e.message}")
            null
        }
    }
}

data class FoodRecognitionResult(
    val name: String,
    val confidence: Double,
    val caloriesPer100g: Double?
)
