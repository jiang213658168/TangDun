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
 * ★ v2.9 AI 助手 - 真正的 Agent 模式
 *
 * 不再用本地 regex 匹配, 完全由大模型驱动:
 *  1. 给 AI 提供所有可调用的工具 (function calling schema)
 *  2. AI 决定调用哪个/哪些工具 + 传什么参数
 *  3. 执行工具, 把结果回传给 AI
 *  4. AI 看结果继续决定 (multi-turn agent loop)
 *  5. 最终 AI 给出自然语言回复
 *
 * 支持所有 OpenAI 兼容 API: DeepSeek / OpenAI / 小米 MiMo / 智谱 GLM / Moonshot 等
 */
class AIClient(private val settingsManager: SettingsManager) {

    companion object {
        private const val TAG = "AIAgent"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_MODEL = "mimo-v2-flash"
        private const val MAX_AGENT_TURNS = 8  // agent 循环最大轮数
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)  // agent loop 可能耗时
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = settingsManager.isAiConfigured()

    /**
     * Agent 工具调用结果
     */
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val result: String  // JSON string
    )

    /**
     * ★ Agent Loop 入口
     *
     * 完整流程:
     *  1. 构造 system prompt + tools schema + user message
     *  2. 调 API (Round 1)
     *  3. 如果 AI 返回 tool_calls → 执行工具 → 把结果回传 → Round 2
     *  4. 重复直到 AI 不再调工具, 给出自然语言回复
     *
     * @param userInput 用户输入
     * @param toolExecutor 执行 AI 工具调用的回调 (返回 JSON string)
     * @return AgentResult (最终 AI 回复 + 工具调用记录)
     */
    suspend fun runAgent(
        userInput: String,
        toolExecutor: suspend (toolName: String, arguments: JSONObject) -> String
    ): AgentResult {
        if (!isConfigured()) {
            return AgentResult(
                success = false,
                errorMessage = "AI 助手未配置。请到「我的」→「AI 对话配置」中填写 API Key 和 Base URL。",
                finalAnswer = "",
                toolCalls = emptyList()
            )
        }

        val messages = mutableListOf<JSONObject>()
        messages.add(JSONObject().put("role", "system").put("content", buildSystemPrompt()))
        messages.add(JSONObject().put("role", "user").put("content", userInput))

        val tools = buildToolsSchema()
        val toolCallLog = mutableListOf<ToolCallRecord>()
        var finalAnswer = ""
        var lastError: String? = null
        var done = false

        try {
            for (turn in 1..MAX_AGENT_TURNS) {
                if (done) break
                Log.i(TAG, "=== Agent Round $turn ===")
                val requestBody = JSONObject().apply {
                    put("model", DEFAULT_MODEL)
                    put("messages", JSONArray(messages))
                    put("tools", tools)
                    put("tool_choice", "auto")
                    put("temperature", 0.3)
                    put("max_tokens", 3000)
                }.toString()

                val request = Request.Builder()
                    .url("${settingsManager.getOpenAiBaseUrl().trimEnd('/')}/chat/completions")
                    .addHeader("Authorization", "Bearer ${settingsManager.getOpenAiApiKey()}")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody(JSON_MEDIA))
                    .build()

                val response = httpClient.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string() ?: ""
                        lastError = "AI 服务返回 ${resp.code}: ${errBody.take(300)}"
                        Log.w(TAG, lastError!!)
                        return AgentResult(
                            success = false,
                            errorMessage = lastError,
                            finalAnswer = "",
                            toolCalls = toolCallLog
                        )
                    }
                    val bodyStr = resp.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    val choice = json.getJSONArray("choices").getJSONObject(0)
                    val message = choice.getJSONObject("message")
                    val finishReason = choice.optString("finish_reason", "stop")

                    Log.i(TAG, "Round $turn finish_reason=$finishReason")

                    val content = message.optString("content", "")
                    if (content.isNotEmpty() && finishReason == "stop") {
                        finalAnswer = content
                    } else if (content.isNotEmpty()) {
                        Log.i(TAG, "中间文本: $content")
                    }

                    if (message.has("tool_calls")) {
                        val toolCallsArr = message.getJSONArray("tool_calls")
                        messages.add(message)

                        for (i in 0 until toolCallsArr.length()) {
                            val tc = toolCallsArr.getJSONObject(i)
                            val toolCallId = tc.getString("id")
                            val toolName = tc.getJSONObject("function").getString("name")
                            val argsStr = tc.getJSONObject("function").optString("arguments", "{}")
                            val argsObj = runCatching { JSONObject(argsStr) }.getOrElse { JSONObject() }

                            Log.i(TAG, "  🔧 工具调用: $toolName($argsStr)")

                            val toolResult = try {
                                toolExecutor(toolName, argsObj)
                            } catch (e: Exception) {
                                Log.w(TAG, "  ❌ 工具执行失败: ${e.message}", e)
                                JSONObject().apply {
                                    put("success", false)
                                    put("error", e.message ?: "未知错误")
                                }.toString()
                            }

                            toolCallLog.add(ToolCallRecord(toolName, argsStr, toolResult))

                            messages.add(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", toolCallId)
                                put("content", toolResult)
                            })

                            Log.i(TAG, "  ✅ 工具结果: ${toolResult.take(200)}")
                        }

                        if (turn == MAX_AGENT_TURNS) {
                            lastError = "达到 agent 最大循环次数 $MAX_AGENT_TURNS, 强制结束"
                            Log.w(TAG, lastError!!)
                            done = true
                        }
                    } else {
                        // 没有 tool_calls
                        if (finishReason == "stop" || finishReason.isEmpty()) {
                            finalAnswer = content
                            Log.i(TAG, "Agent 完成, final_answer 长度=${finalAnswer.length}")
                        } else {
                            lastError = "AI 回复被截断: $finishReason"
                            if (content.isNotEmpty()) finalAnswer = content
                        }
                        done = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Agent 异常: ${e.message}", e)
            return AgentResult(
                success = false,
                errorMessage = "AI 服务调用失败: ${e.message}",
                finalAnswer = "",
                toolCalls = toolCallLog
            )
        }

        return AgentResult(
            success = finalAnswer.isNotEmpty() || toolCallLog.isNotEmpty(),
            errorMessage = lastError,
            finalAnswer = finalAnswer,
            toolCalls = toolCallLog
        )
    }

    /**
     * 工具调用记录
     */
    data class ToolCallRecord(val name: String, val arguments: String, val result: String)

    data class AgentResult(
        val success: Boolean,
        val errorMessage: String?,
        val finalAnswer: String,
        val toolCalls: List<ToolCallRecord>
    )

    // ============== System Prompt ==============

    private fun buildSystemPrompt(): String = """
你是「糖盾 TangDun」糖尿病管理 App 的 AI 助手 - 一个真正的 Agent。
你能调用工具来代替用户操作 App。用户能做的所有事情, 你都能做。

## 核心原则
1. **必须调用工具**: 当用户说"记录血糖7.5" / "打开预测" / "今天吃了什么" 等, 必须调用对应工具, 不能只回答文字
2. **多次调用**: 一个用户输入可能包含多个动作, 调用多个工具
3. **自然回复**: 工具调用完后, 用简洁中文告诉用户结果 (如"已记录血糖 7.5 mmol/L 餐前")

## 当前时间
${java.util.Date()} (epoch ms = ${System.currentTimeMillis()})
所有时间相关参数 (如 timestamp / time_offset_min) 都以此为基准。

## 中文数字识别
"十"=10, "十五"=15, "半"=0.5, "一百二十"=120, "一万"=10000

## 时间推断
- "今早零点" = 今天 00:00 → 计算出相对当前时间的偏移 (分钟)
- "半小时前" = time_offset_min = -30
- "半小时后" = time_offset_min = 30
- 没明确时间 = time_offset_min = 0 (当前)
- time_offset_min 为负表示过去, 正表示未来

## 食物份量
- "半盘"=100g, "一整盘"=200g
- "拳头大小"=70g, "半个拳头"=35g
- "几个" 按 50g/个 估算
- "数字+克" = 直接用 (如 "30克")

## 场景识别
"空腹"/"早上没吃饭" → scene="fasting"
"餐前"/"饭前" → scene="before_meal"
"餐后"/"饭后" → scene="after_meal"
"睡前"/"睡觉前" → scene="bedtime"
没说 → scene="other"

## 用户上下文
- 用户用中文交流, 回复请用中文
- 保持简洁 (1-3 句话), 不要冗长
- 涉及隐私/敏感信息保持专业
""".trimIndent()

    // ============== Tools Schema ==============

    /**
     * 构建 OpenAI tools/function calling schema
     *
     * 25+ 个工具, 覆盖用户能做的所有操作
     */
    private fun buildToolsSchema(): JSONArray {
        val tools = mutableListOf<JSONObject>()

        // --- CREATE: 记录数据 ---

        fun recordTool(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
            return JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", desc)
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", props)
                        put("required", JSONArray(required))
                    })
                })
            }
        }

        // 1. 血糖
        tools.add(recordTool(
            "record_glucose",
            "记录一条血糖测量值。场景: 用户说'血糖7.5'、'测了6.8空腹'、'血糖9.0餐后'等",
            JSONObject().apply {
                put("value", JSONObject().apply { put("type", "number"); put("description", "血糖值 (mmol/L)") })
                put("scene", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("fasting", "before_meal", "after_meal", "bedtime", "other"))); put("description", "测量场景") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "相对当前时间的分钟偏移, 负数=过去") })
            },
            listOf("value")
        ))

        // 2. 胰岛素
        tools.add(recordTool(
            "record_insulin",
            "记录一次胰岛素注射",
            JSONObject().apply {
                put("dose", JSONObject().apply { put("type", "number"); put("description", "剂量 (单位)") })
                put("dose_type", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("rapid", "short", "long", "mixed", "basal"))); put("description", "胰岛素类型") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") })
            },
            listOf("dose")
        ))

        // 3. 饮食 (单条)
        tools.add(recordTool(
            "record_meal",
            "记录一种食物/一顿饭",
            JSONObject().apply {
                put("food_name", JSONObject().apply { put("type", "string"); put("description", "食物名称") })
                put("portion_grams", JSONObject().apply { put("type", "number"); put("description", "份量 (克)") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") })
                put("meal_type", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("breakfast", "lunch", "dinner", "snack"))); put("description", "餐次") })
            },
            listOf("food_name")
        ))

        // 4. 运动
        tools.add(recordTool(
            "record_exercise",
            "记录一次运动",
            JSONObject().apply {
                put("exercise_type", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("running", "walking", "cycling", "swimming", "strength", "yoga", "jumping_rope", "hiking", "ball_sports", "other"))); put("description", "运动类型") })
                put("duration_min", JSONObject().apply { put("type", "integer"); put("description", "持续分钟") })
                put("intensity", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("low", "medium", "high"))); put("description", "强度") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") })
            },
            listOf("duration_min")
        ))

        // 5. 睡眠
        tools.add(recordTool(
            "record_sleep",
            "记录睡眠时长",
            JSONObject().apply {
                put("duration_minutes", JSONObject().apply { put("type", "integer"); put("description", "睡眠总分钟数") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") })
            },
            listOf("duration_minutes")
        ))

        // 6. 血压
        tools.add(recordTool(
            "record_blood_pressure",
            "记录血压",
            JSONObject().apply {
                put("systolic", JSONObject().apply { put("type", "integer"); put("description", "收缩压 (高压)") })
                put("diastolic", JSONObject().apply { put("type", "integer"); put("description", "舒张压 (低压)") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") })
            },
            listOf("systolic", "diastolic")
        ))

        // 7. 体重
        tools.add(recordTool(
            "record_weight",
            "记录体重",
            JSONObject().apply {
                put("weight_kg", JSONObject().apply { put("type", "number"); put("description", "体重 (kg)") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") })
            },
            listOf("weight_kg")
        ))

        // 8. 酮体
        tools.add(recordTool(
            "record_ketone",
            "记录血酮",
            JSONObject().apply {
                put("ketone_level", JSONObject().apply { put("type", "number"); put("description", "酮体值 (mmol/L)") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") })
            },
            listOf("ketone_level")
        ))

        // 9. 用药
        tools.add(recordTool(
            "record_medication",
            "记录用药",
            JSONObject().apply {
                put("medication_name", JSONObject().apply { put("type", "string"); put("description", "药品名称") })
                put("dose", JSONObject().apply { put("type", "string"); put("description", "剂量 如 '500mg' / '1片'") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") })
            },
            listOf("medication_name")
        ))

        // 10. 症状
        tools.add(recordTool(
            "record_symptoms",
            "记录症状 (可多个)",
            JSONObject().apply {
                put("symptoms", JSONObject().apply { put("type", "string"); put("description", "症状列表, 逗号分隔, 如 '心慌,手抖,出汗'") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") })
            },
            listOf("symptoms")
        ))

        // --- READ: 查询数据 ---

        fun readTool(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
            return recordTool(name, desc, props, required)
        }

        tools.add(readTool("query_glucose", "查询血糖记录",
            JSONObject().apply {
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "last_week", "this_month", "last_month", "all"))); put("description", "时间范围") })
                put("limit", JSONObject().apply { put("type", "integer"); put("description", "最多返回条数, 默认20") })
            }, listOf("time_scope")))

        tools.add(readTool("query_insulin", "查询胰岛素记录",
            JSONObject().apply {
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "last_week", "this_month", "last_month", "all"))) })
            }, listOf("time_scope")))

        tools.add(readTool("query_meal", "查询饮食记录",
            JSONObject().apply {
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "last_week", "this_month", "last_month", "all"))) })
            }, listOf("time_scope")))

        tools.add(readTool("query_exercise", "查询运动记录",
            JSONObject().apply {
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "last_week", "this_month", "last_month", "all"))) })
            }, listOf("time_scope")))

        tools.add(readTool("query_sleep", "查询睡眠记录",
            JSONObject().apply {
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "last_week", "this_month", "last_month", "all"))) })
            }, listOf("time_scope")))

        tools.add(readTool("query_blood_pressure", "查询血压记录",
            JSONObject().apply {
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "last_week", "this_month", "last_month", "all"))) })
            }, listOf("time_scope")))

        tools.add(readTool("query_weight", "查询体重记录",
            JSONObject().apply {
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "last_week", "this_month", "last_month", "all"))) })
            }, listOf("time_scope")))

        tools.add(readTool("query_medication", "查询用药记录",
            JSONObject().apply {
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "last_week", "this_month", "last_month", "all"))) })
            }, listOf("time_scope")))

        tools.add(readTool("query_symptoms", "查询症状记录",
            JSONObject().apply {
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "last_week", "this_month", "last_month", "all"))) })
            }, listOf("time_scope")))

        tools.add(readTool("get_statistics", "获取统计数据 (血糖均值/极值/达标率/估算 HbA1c 等)",
            JSONObject().apply {
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "last_week", "this_month", "last_month", "all"))); put("description", "时间范围") })
            }, listOf("time_scope")))

        // --- DELETE: 删除记录 ---

        tools.add(recordTool("delete_glucose", "删除血糖记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer"); put("description", "记录 ID (可选, 如果不填则按 time_scope 批量删)") })
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "this_month", "all"))); put("description", "批量删除时间范围") })
            }, listOf()))

        tools.add(recordTool("delete_insulin", "删除胰岛素记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer") })
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "this_month", "all"))) })
            }, listOf()))

        tools.add(recordTool("delete_meal", "删除饮食记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer") })
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "this_month", "all"))) })
            }, listOf()))

        tools.add(recordTool("delete_exercise", "删除运动记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer") })
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "this_month", "all"))) })
            }, listOf()))

        // --- NAVIGATE: 跳转 ---

        tools.add(recordTool("navigate_to", "跳转到指定页面",
            JSONObject().apply {
                put("route", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("home", "prediction", "report", "settings", "record", "meal", "exercise", "insulin", "health", "glucose_list"))); put("description", "目标路由") })
            }, listOf("route")))

        // --- CONFIGURE: 修改设置 ---

        tools.add(recordTool("set_glucose_target", "设置血糖目标范围",
            JSONObject().apply {
                put("low", JSONObject().apply { put("type", "number"); put("description", "下限 mmol/L") })
                put("high", JSONObject().apply { put("type", "number"); put("description", "上限 mmol/L") })
            }, listOf("low", "high")))

        tools.add(recordTool("set_personal_info", "设置个人信息 (年龄/性别/身高/体重)",
            JSONObject().apply {
                put("field", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("age", "gender", "height", "weight", "diabetes_type"))) })
                put("value", JSONObject().apply { put("type", "string"); put("description", "新值") })
            }, listOf("field", "value")))

        tools.add(recordTool("enable_notification_listener", "引导用户开启通知监听 (跳转系统设置)",
            JSONObject(), listOf()))

        // --- EXPORT/IMPORT ---

        tools.add(recordTool("export_data", "导出所有数据 (xlsx/csv)",
            JSONObject().apply {
                put("format", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("xlsx", "csv", "json"))); put("description", "导出格式") })
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "this_week", "this_month", "all"))) })
            }, listOf("format")))

        return JSONArray(tools)
    }
}