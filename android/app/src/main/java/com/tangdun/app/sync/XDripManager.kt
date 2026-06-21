package com.tangdun.app.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tangdun.app.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * xDrip+数据管理器
 *
 * 支持多种方式获取xDrip+的血糖数据：
 * 1. Content Provider - 读取xDrip+本地数据库
 * 2. REST API - 通过xDrip+内置Web服务器
 * 3. Shared Preferences - 读取最新数据
 */
class XDripManager(private val context: Context) {

    companion object {
        private const val TAG = "XDripManager"

        // xDrip+ Content Provider URI
        val CONTENT_URI: Uri = Uri.parse("content://com.eveningoutpost.dexdrip.provider")

        // xDrip+ REST API默认地址
        private const val DEFAULT_API_URL = "http://localhost:17580"

        // xDrip+ Shared Preferences
        private const val XDRI_PREFS = "com.eveningoutpost.dexdrip.prefs"
    }

    /**
     * 血糖数据
     */
    data class GlucoseReading(
        val timestamp: Long,
        val value: Double,         // mg/dL
        val valueMmol: Double,     // mmol/L
        val trend: String?,        // 趋势方向
        val source: String = "xdrip"
    )

    /**
     * 从Content Provider获取最新血糖
     */
    suspend fun getLatestFromProvider(): GlucoseReading? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse("$CONTENT_URI/sgv")
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("timestamp", "calculated_value", "trend_arrow"),
                null,
                null,
                "timestamp DESC LIMIT 1"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val timestamp = it.getLong(0)
                    val value = it.getDouble(1)
                    val trend = it.getString(2)

                    return@withContext GlucoseReading(
                        timestamp = timestamp,
                        value = value,
                        valueMmol = value / 18.0,
                        trend = parseTrendArrow(trend)
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Content Provider读取失败: ${e.message}")
            null
        }
    }

    /**
     * 从Content Provider获取历史血糖
     */
    suspend fun getHistoryFromProvider(hours: Int = 24): List<GlucoseReading> = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse("$CONTENT_URI/sgv")
            val since = System.currentTimeMillis() - hours * 3600 * 1000

            val cursor = context.contentResolver.query(
                uri,
                arrayOf("timestamp", "calculated_value", "trend_arrow"),
                "timestamp > ?",
                arrayOf(since.toString()),
                "timestamp ASC"
            )

            val readings = mutableListOf<GlucoseReading>()
            cursor?.use {
                while (it.moveToNext()) {
                    val timestamp = it.getLong(0)
                    val value = it.getDouble(1)
                    val trend = it.getString(2)

                    readings.add(
                        GlucoseReading(
                            timestamp = timestamp,
                            value = value,
                            valueMmol = value / 18.0,
                            trend = parseTrendArrow(trend)
                        )
                    )
                }
            }
            readings
        } catch (e: Exception) {
            Log.e(TAG, "Content Provider读取失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 从REST API获取最新血糖
     */
    suspend fun getLatestFromApi(apiUrl: String = DEFAULT_API_URL): GlucoseReading? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$apiUrl/sgv.json?count=1")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val jsonArray = org.json.JSONArray(response)
                if (jsonArray.length() > 0) {
                    val obj = jsonArray.getJSONObject(0)
                    return@withContext GlucoseReading(
                        timestamp = obj.getLong("date"),
                        value = obj.getDouble("sgv"),
                        valueMmol = obj.getDouble("sgv") / 18.0,
                        trend = obj.optString("direction", null)
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "REST API读取失败: ${e.message}")
            null
        }
    }

    /**
     * 从REST API获取历史血糖
     */
    suspend fun getHistoryFromApi(apiUrl: String = DEFAULT_API_URL, hours: Int = 24): List<GlucoseReading> = withContext(Dispatchers.IO) {
        try {
            val count = hours * 12  // 每5分钟一个点
            val url = URL("$apiUrl/sgv.json?count=$count")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val jsonArray = org.json.JSONArray(response)
                val readings = mutableListOf<GlucoseReading>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    readings.add(
                        GlucoseReading(
                            timestamp = obj.getLong("date"),
                            value = obj.getDouble("sgv"),
                            valueMmol = obj.getDouble("sgv") / 18.0,
                            trend = obj.optString("direction", null)
                        )
                    )
                }
                return@withContext readings.reversed()  // 按时间正序
            }
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "REST API读取失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 解析 REST API URL — 优先读设置里的 CGM 设备地址
     */
    private fun resolveApiUrl(): String {
        return try {
            val settings = SettingsManager(context)
            settings.getCgmDeviceAddress().ifBlank { DEFAULT_API_URL }
        } catch (e: Exception) {
            DEFAULT_API_URL
        }
    }

    /**
     * 自动检测并获取最新血糖
     *
     * 按优先级尝试：
     * 1. Content Provider
     * 2. REST API
     */
    suspend fun getLatestGlucose(): GlucoseReading? {
        // 尝试Content Provider
        val fromProvider = getLatestFromProvider()
        if (fromProvider != null) {
            Log.d(TAG, "从Content Provider获取血糖: ${fromProvider.valueMmol} mmol/L")
            return fromProvider
        }

        // 尝试REST API (URL 优先读设置, 否则用默认 localhost:17580)
        val apiUrl = resolveApiUrl()
        Log.d(TAG, "尝试 REST API: $apiUrl")
        val fromApi = getLatestFromApi(apiUrl)
        if (fromApi != null) {
            Log.d(TAG, "从REST API获取血糖: ${fromApi.valueMmol} mmol/L")
            return fromApi
        }

        Log.w(TAG, "无法获取xDrip+数据")
        return null
    }

    /**
     * 自动检测并获取历史血糖
     */
    suspend fun getGlucoseHistory(hours: Int = 24): List<GlucoseReading> {
        // 尝试Content Provider
        val fromProvider = getHistoryFromProvider(hours)
        if (fromProvider.isNotEmpty()) {
            Log.d(TAG, "从Content Provider获取${fromProvider.size}条历史数据")
            return fromProvider
        }

        // 尝试REST API (URL 优先读设置)
        val apiUrl = resolveApiUrl()
        val fromApi = getHistoryFromApi(apiUrl = apiUrl, hours = hours)
        if (fromApi.isNotEmpty()) {
            Log.d(TAG, "从REST API获取${fromApi.size}条历史数据")
            return fromApi
        }

        Log.w(TAG, "无法获取xDrip+历史数据")
        return emptyList()
    }

    /**
     * 检查xDrip+是否可用
     */
    suspend fun isXDripAvailable(): Boolean {
        // 检查Content Provider
        try {
            val uri = Uri.parse("$CONTENT_URI/sgv")
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.close()
            return true
        } catch (e: Exception) {
            // Content Provider不可用
        }

        // 检查REST API (URL 优先读设置)
        val apiUrl = resolveApiUrl()
        try {
            val url = URL("$apiUrl/sgv.json?count=1")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            return connection.responseCode == 200
        } catch (e: Exception) {
            // REST API不可用
        }

        return false
    }

    /**
     * 解析趋势箭头
     */
    private fun parseTrendArrow(arrow: String?): String? {
        return when (arrow) {
            "DoubleUp", "↑↑" -> "rising_fast"
            "SingleUp", "↑" -> "rising"
            "FortyFiveUp", "↗" -> "rising"
            "Flat", "→" -> "stable"
            "FortyFiveDown", "↘" -> "falling"
            "SingleDown", "↓" -> "falling"
            "DoubleDown", "↓↓" -> "falling_fast"
            else -> "stable"
        }
    }
}
