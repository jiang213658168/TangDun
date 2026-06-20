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

/**
 * v3.0 Agent 实时进度事件 (推给 UI 显示)
 */
sealed class ProgressEvent {
    data class Thinking(val turn: Int, val content: String) : ProgressEvent()
    data class ToolCallStart(val name: String, val arguments: String) : ProgressEvent()
    data class ToolCallDone(val name: String, val arguments: String, val result: String) : ProgressEvent()
    data class Text(val content: String) : ProgressEvent()
    data class Done(val finalAnswer: String) : ProgressEvent()
}

class AIClient(private val settingsManager: SettingsManager) {

    companion object {
        private const val TAG = "AIAgent"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_MODEL = "deepseek-v4-flash"  // v3.0 默认 DeepSeek v4-flash
        private const val MAX_AGENT_TURNS = 8  // agent 循环最大轮数
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)  // agent loop 可能耗时
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 真实使用的模型名 (用户可在设置里改, 默认 MiMo-V2.5-Pro 支持 function calling) */
    private val modelName: String get() = runCatching { settingsManager.getAiModel() }.getOrDefault("deepseek-v4-flash")

    fun isConfigured(): Boolean = settingsManager.isAiConfigured()

    /**
     * v3.0.1 测试连接: 简单调一次 chat/completions, 不带 tools, 用来诊断 API Key/网络/模型问题
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext "❌ AI 未配置: 请先在「我的 → AI 对话配置」填入 API Key"
        return@withContext try {
            val requestBody = JSONObject().apply {
                put("model", modelName)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content", "ping"))
                })
                put("max_tokens", 20)
            }.toString()
            val request = Request.Builder()
                .url("${settingsManager.getOpenAiBaseUrl().trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer ${settingsManager.getOpenAiApiKey()}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody(JSON_MEDIA))
                .build()
            Log.i(TAG, "测试连接: ${settingsManager.getOpenAiBaseUrl().trimEnd('/')}/chat/completions, model=$modelName")
            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    "✅ 连接成功!\nBase URL: ${settingsManager.getOpenAiBaseUrl()}\nModel: $modelName\nHTTP ${resp.code}\n\n${body.take(500)}"
                } else {
                    "❌ HTTP ${resp.code} ${resp.message}\n\n$body"
                }
            }
        } catch (e: Exception) {
            val cls = e.javaClass.simpleName
            val msg = e.message ?: "(无消息)"
            "❌ 异常 [$cls]: $msg\n\n可能原因:\n- 手机无法访问 ${settingsManager.getOpenAiBaseUrl()}\n- DNS 解析失败 / 防火墙拦截 / SSL 握手失败\n- 缺少网络权限 (manifest)"
        }
    }

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
        toolExecutor: suspend (toolName: String, arguments: JSONObject) -> String,
        onProgress: (suspend (ProgressEvent) -> Unit)? = null
    ): AgentResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext AgentResult(
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
        val thinkingLog = mutableListOf<ThinkingRecord>()
        var finalAnswer = ""
        var lastError: String? = null
        var done = false

        try {
            for (turn in 1..MAX_AGENT_TURNS) {
                if (done) break
                Log.i(TAG, "=== Agent Round $turn (model=$modelName, stream=true) ===")
                val requestBody = JSONObject().apply {
                    put("model", modelName)
                    put("messages", JSONArray(messages))
                    put("tools", tools)
                    put("tool_choice", "auto")
                    put("max_tokens", 8000)
                    put("stream", true)  // ★ v3.0.4 启用流式, 让 thinking 实时推送
                    // ★ DeepSeek 思考模式
                    put("reasoning_effort", "max")
                    put("extra_body", JSONObject().apply {
                        put("thinking", JSONObject().apply { put("type", "enabled") })
                    })
                }.toString()

                val request = Request.Builder()
                    .url("${settingsManager.getOpenAiBaseUrl().trimEnd('/')}/chat/completions")
                    .addHeader("Authorization", "Bearer ${settingsManager.getOpenAiApiKey()}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .post(requestBody.toRequestBody(JSON_MEDIA))
                    .build()

                val response = httpClient.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string() ?: ""
                        lastError = "AI 服务返回 HTTP ${resp.code} ${resp.message}\n${errBody.take(500)}"
                        Log.w(TAG, lastError!!)
                        return@withContext AgentResult(
                            success = false,
                            errorMessage = lastError,
                            finalAnswer = "",
                            toolCalls = toolCallLog
                        )
                    }

                    // ★ v3.0.4 流式 SSE 解析
                    var content = ""
                    var reasoningContent = ""
                    var finishReason = ""
                    val toolCallsMap = mutableMapOf<Int, JSONObject>()  // index -> 累积的 tool_call

                    val source = resp.body?.charStream()
                    if (source == null) {
                        lastError = "AI 响应为空"
                        done = true
                        return@use
                    }

                    var dataBuilder = StringBuilder()
                    val reader = java.io.BufferedReader(source)
                    var line = reader.readLine()
                    while (line != null) {
                        when {
                            line.isBlank() -> {
                                if (dataBuilder.isNotEmpty()) {
                                    val data = dataBuilder.toString().trim()
                                    dataBuilder.clear()
                                    if (data == "[DONE]") {
                                        line = reader.readLine()
                                        continue
                                    }
                                    try {
                                        val json = JSONObject(data)
                                        val choice = json.optJSONArray("choices")?.optJSONObject(0)
                                        if (choice != null) {
                                            finishReason = choice.optString("finish_reason", finishReason)
                                            if (finishReason.isEmpty()) finishReason = choice.optString("finish_reason", "")
                                            val delta = choice.optJSONObject("delta")
                                            if (delta != null) {
                                                // 思考增量 (DeepSeek stream 把 reasoning_content 放 delta 里)
                                                val rc = delta.optString("reasoning_content", "")
                                                if (rc.isNotEmpty()) {
                                                    reasoningContent += rc
                                                    onProgress?.invoke(ProgressEvent.Thinking(turn, rc))
                                                }
                                                // 内容增量
                                                val c = delta.optString("content", "")
                                                if (c.isNotEmpty()) content += c
                                                // 工具调用增量 (按 index 累积)
                                                val tcArr = delta.optJSONArray("tool_calls")
                                                if (tcArr != null) {
                                                    for (i in 0 until tcArr.length()) {
                                                        val tcItem = tcArr.getJSONObject(i)
                                                        val idx = tcItem.optInt("index", 0)
                                                        val existing = toolCallsMap[idx] ?: JSONObject()
                                                        if (tcItem.has("id")) existing.put("id", tcItem.getString("id"))
                                                        if (tcItem.has("type")) existing.put("type", tcItem.getString("type"))
                                                        val fn = tcItem.optJSONObject("function") ?: JSONObject()
                                                        val existingFn = existing.optJSONObject("function") ?: JSONObject()
                                                        if (fn.has("name")) existingFn.put("name", fn.getString("name"))
                                                        if (fn.has("arguments")) {
                                                            val args = fn.getString("arguments")
                                                            val existingArgs = existingFn.optString("arguments", "")
                                                            existingFn.put("arguments", existingArgs + args)
                                                        }
                                                        existing.put("function", existingFn)
                                                        toolCallsMap[idx] = existing
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "SSE parse error: ${e.message}")
                                    }
                                }
                            }
                            line.startsWith("data:") -> {
                                dataBuilder.append(line.removePrefix("data:"))
                            }
                        }
                        line = reader.readLine()
                    }

                    Log.i(TAG, "Round $turn finish_reason=$finishReason, content_len=${content.length}, reasoning_len=${reasoningContent.length}, tools=${toolCallsMap.size}")

                    if (reasoningContent.isNotEmpty()) {
                        thinkingLog.add(ThinkingRecord(turn, reasoningContent))
                    }

                    // 处理工具调用
                    if (toolCallsMap.isNotEmpty()) {
                        // 构造完整的 assistant message 加入对话历史 (含 reasoning_content)
                        val assistantMsg = JSONObject().apply {
                            put("role", "assistant")
                            put("content", content)
                            if (reasoningContent.isNotEmpty()) put("reasoning_content", reasoningContent)
                            val tcArr = JSONArray()
                            toolCallsMap.values.forEach { tcArr.put(it) }
                            put("tool_calls", tcArr)
                        }
                        messages.add(assistantMsg)

                        // 执行每个工具
                        for (tc in toolCallsMap.values) {
                            val toolCallId = tc.getString("id")
                            val toolName = tc.getJSONObject("function").getString("name")
                            val argsStr = tc.getJSONObject("function").optString("arguments", "{}")
                            val argsObj = runCatching { JSONObject(argsStr) }.getOrElse { JSONObject() }

                            Log.i(TAG, "  🔧 工具调用: $toolName($argsStr)")
                            onProgress?.invoke(ProgressEvent.ToolCallStart(toolName, argsStr))

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
                            onProgress?.invoke(ProgressEvent.ToolCallDone(toolName, argsStr, toolResult))

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
                        // 没有工具调用 → 最终回复
                        if (finishReason == "stop" || finishReason.isEmpty() || finishReason == "length") {
                            finalAnswer = content
                            if (finishReason == "length") {
                                lastError = "AI 回复因长度限制被截断 (可能需要调高 max_tokens)"
                            }
                            Log.i(TAG, "Agent 完成, final_answer 长度=${finalAnswer.length}")
                            onProgress?.invoke(ProgressEvent.Done(finalAnswer))
                        } else {
                            lastError = "AI 回复异常: $finishReason"
                            if (content.isNotEmpty()) finalAnswer = content
                        }
                        done = true
                    }
                }
            }
        } catch (e: Exception) {
            val exClass = e.javaClass.simpleName
            val exMsg = e.message ?: "(无消息, 可能是 SSL/网络/DNS 异常)"
            Log.w(TAG, "Agent 异常: $exClass: $exMsg", e)
            return@withContext AgentResult(
                success = false,
                errorMessage = "AI 服务调用失败 [$exClass]: $exMsg",
                finalAnswer = "",
                toolCalls = toolCallLog
            )
        }

        return@withContext AgentResult(
            success = finalAnswer.isNotEmpty() || toolCallLog.isNotEmpty(),
            errorMessage = lastError,
            finalAnswer = finalAnswer,
            toolCalls = toolCallLog,
            thinking = thinkingLog
        )
    }

    /**
     * 工具调用记录
     */
    data class ToolCallRecord(val name: String, val arguments: String, val result: String)

    /** 思维链记录 (Claude Code / OpenClaw 风格, 把 AI 的思考过程暴露给用户) */
    data class ThinkingRecord(val turn: Int, val content: String)

    data class AgentResult(
        val success: Boolean,
        val errorMessage: String?,
        val finalAnswer: String,
        val toolCalls: List<ToolCallRecord>,
        val thinking: List<ThinkingRecord> = emptyList()
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
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "相对当前时间的分钟偏移, 负数=过去") });
                put("timestamp", JSONObject().apply { put("type", "integer"); put("description", "★ 绝对毫秒时间戳 (直接传时间, 比如 1718850000000 表示 2024-06-15 10:00). 与 time_offset_min 二选一") })
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
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") });
                put("timestamp", JSONObject().apply { put("type", "integer"); put("description", "★ 绝对毫秒时间戳 (直接传时间, 比如 1718850000000 表示 2024-06-15 10:00). 与 time_offset_min 二选一") })
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
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") });
                put("timestamp", JSONObject().apply { put("type", "integer"); put("description", "★ 绝对毫秒时间戳 (直接传时间, 比如 1718850000000 表示 2024-06-15 10:00). 与 time_offset_min 二选一") })
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
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") });
                put("timestamp", JSONObject().apply { put("type", "integer"); put("description", "★ 绝对毫秒时间戳 (直接传时间, 比如 1718850000000 表示 2024-06-15 10:00). 与 time_offset_min 二选一") })
            },
            listOf("duration_min")
        ))

        // 5. 睡眠
        tools.add(recordTool(
            "record_sleep",
            "记录睡眠时长",
            JSONObject().apply {
                put("duration_minutes", JSONObject().apply { put("type", "integer"); put("description", "睡眠总分钟数") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") });
                put("timestamp", JSONObject().apply { put("type", "integer"); put("description", "★ 绝对毫秒时间戳 (直接传时间, 比如 1718850000000 表示 2024-06-15 10:00). 与 time_offset_min 二选一") })
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
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") });
                put("timestamp", JSONObject().apply { put("type", "integer"); put("description", "★ 绝对毫秒时间戳 (直接传时间, 比如 1718850000000 表示 2024-06-15 10:00). 与 time_offset_min 二选一") })
            },
            listOf("systolic", "diastolic")
        ))

        // 7. 体重
        tools.add(recordTool(
            "record_weight",
            "记录体重",
            JSONObject().apply {
                put("weight_kg", JSONObject().apply { put("type", "number"); put("description", "体重 (kg)") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") });
                put("timestamp", JSONObject().apply { put("type", "integer"); put("description", "★ 绝对毫秒时间戳 (直接传时间, 比如 1718850000000 表示 2024-06-15 10:00). 与 time_offset_min 二选一") })
            },
            listOf("weight_kg")
        ))

        // 8. 酮体
        tools.add(recordTool(
            "record_ketone",
            "记录血酮",
            JSONObject().apply {
                put("ketone_level", JSONObject().apply { put("type", "number"); put("description", "酮体值 (mmol/L)") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") });
                put("timestamp", JSONObject().apply { put("type", "integer"); put("description", "★ 绝对毫秒时间戳 (直接传时间, 比如 1718850000000 表示 2024-06-15 10:00). 与 time_offset_min 二选一") })
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
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") });
                put("timestamp", JSONObject().apply { put("type", "integer"); put("description", "★ 绝对毫秒时间戳 (直接传时间, 比如 1718850000000 表示 2024-06-15 10:00). 与 time_offset_min 二选一") })
            },
            listOf("medication_name")
        ))

        // 10. 症状
        tools.add(recordTool(
            "record_symptoms",
            "记录症状 (可多个)",
            JSONObject().apply {
                put("symptoms", JSONObject().apply { put("type", "string"); put("description", "症状列表, 逗号分隔, 如 '心慌,手抖,出汗'") })
                put("time_offset_min", JSONObject().apply { put("type", "integer"); put("description", "时间偏移") });
                put("timestamp", JSONObject().apply { put("type", "integer"); put("description", "★ 绝对毫秒时间戳 (直接传时间, 比如 1718850000000 表示 2024-06-15 10:00). 与 time_offset_min 二选一") })
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

        // 22. 导入 xlsx (用户能导入历史 CGM 数据)
        tools.add(recordTool("import_xlsx", "导入 xlsx/csv 文件 (历史 CGM 数据)",
            JSONObject().apply {
                put("file_path", JSONObject().apply { put("type", "string"); put("description", "文件绝对路径") })
                put("format", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("xlsx", "csv"))); put("description", "文件格式") })
            }, listOf("file_path")))

        // 23. 生成健康报告
        tools.add(recordTool("generate_report", "生成健康报告 (周报/月报/自定义时段)",
            JSONObject().apply {
                put("report_type", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("weekly", "monthly", "quarterly", "custom"))); put("description", "报告类型") })
                put("start_date", JSONObject().apply { put("type", "string"); put("description", "起始日期 yyyy-MM-dd (custom 时必填)") })
                put("end_date", JSONObject().apply { put("type", "string"); put("description", "结束日期 yyyy-MM-dd (custom 时必填)") })
                put("format", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("pdf", "html", "markdown"))); put("description", "输出格式") })
            }, listOf("report_type")))

        // 24. 立即同步 CGM 数据
        tools.add(recordTool("sync_cgm_now", "立即同步 xDrip/CGM 设备的数据 (不用等自动同步)",
            JSONObject().apply {
                put("force_full", JSONObject().apply { put("type", "boolean"); put("description", "是否强制全量同步 (默认增量)") })
            }, listOf()))

        // 25. 开关通知监听
        tools.add(recordTool("toggle_notification_listener", "开关 CGM 通知监听服务",
            JSONObject().apply {
                put("enabled", JSONObject().apply { put("type", "boolean"); put("description", "true 开启 / false 关闭") })
            }, listOf("enabled")))

        // 26. 切换血糖单位
        tools.add(recordTool("set_glucose_unit", "切换血糖单位 mmol/L ↔ mg/dL",
            JSONObject().apply {
                put("unit", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("mmol/L", "mg/dL"))) })
            }, listOf("unit")))

        // 27. 查询设备状态
        tools.add(recordTool("check_device_status", "查询 xDrip/CGM 设备连接状态",
            JSONObject().apply {
                put("device_type", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("xdrip", "aidex", "ot_dynamic", "all"))); put("description", "设备类型, 默认 all") })
            }, listOf()))

        // 28. 30分钟血糖预测 (基于 DallaMan 算法)
        tools.add(recordTool("predict_glucose", "预测未来 30/60/120 分钟血糖 (基于 DallaMan 算法)",
            JSONObject().apply {
                put("minutes_ahead", JSONObject().apply { put("type", "integer"); put("enum", JSONArray(listOf(30, 60, 120, 180))); put("description", "预测多少分钟后, 默认 30") })
            }, listOf()))

        // 29. 检测血糖模式
        tools.add(recordTool("detect_patterns", "分析近期血糖数据, 自动发现异常模式 (如'周三下午总偏高'/'餐后峰值过高')",
            JSONObject().apply {
                put("days", JSONObject().apply { put("type", "integer"); put("description", "分析最近几天, 默认 30") })
            }, listOf()))

        // 30. 分析某餐对血糖的影响
        tools.add(recordTool("analyze_meal_impact", "分析某餐/某时间段的血糖影响 (餐后峰值/上升幅度/回到目标范围时间)",
            JSONObject().apply {
                put("meal_id", JSONObject().apply { put("type", "integer"); put("description", "饮食记录 ID") })
                put("time_window_hours", JSONObject().apply { put("type", "integer"); put("description", "分析餐后几小时, 默认 4") })
            }, listOf()))

        // 31. 应用内搜索记录
        tools.add(recordTool("search_records", "在所有记录中搜索 (按关键词/日期范围)",
            JSONObject().apply {
                put("keyword", JSONObject().apply { put("type", "string"); put("description", "搜索关键词 (食物名/症状/备注等)") })
                put("target", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("all", "meal", "glucose", "exercise", "symptom", "medication", "notes"))); put("description", "搜索范围, 默认 all") })
                put("days", JSONObject().apply { put("type", "integer"); put("description", "最近几天, 默认 90") })
            }, listOf("keyword")))

        // 32. 添加自定义食物 (扩展食物营养表)
        tools.add(recordTool("add_custom_food", "添加用户自定义食物到营养表 (供以后查询)",
            JSONObject().apply {
                put("food_name", JSONObject().apply { put("type", "string") })
                put("carbs_per_100g", JSONObject().apply { put("type", "number"); put("description", "每 100g 碳水 g") })
                put("cal_per_100g", JSONObject().apply { put("type", "number"); put("description", "每 100g 热量 kcal") })
                put("gi", JSONObject().apply { put("type", "integer"); put("description", "升糖指数 GI 0-100") })
            }, listOf("food_name", "carbs_per_100g", "cal_per_100g")))

        // 33. 复制记录 (复用最近一条作为模板)
        tools.add(recordTool("duplicate_recent", "复制最近一条同类记录 (用于快速记第二次)",
            JSONObject().apply {
                put("target", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("meal", "insulin", "exercise", "medication"))) })
                put("new_timestamp", JSONObject().apply { put("type", "integer"); put("description", "新记录的时间 (epoch ms)") })
            }, listOf("target", "new_timestamp")))

        // 34. 设置目标体重
        tools.add(recordTool("set_target_weight", "设置用户目标体重",
            JSONObject().apply {
                put("target_kg", JSONObject().apply { put("type", "number") })
            }, listOf("target_kg")))

        // 35. 解释医学术语
        tools.add(recordTool("explain_term", "解释糖尿病/医学术语 (如'糖化血红蛋白'/'TIR'/'黎明现象')",
            JSONObject().apply {
                put("term", JSONObject().apply { put("type", "string") })
            }, listOf("term")))

        // 36. 给医生分享报告 (生成可分享的纯文本/Markdown 摘要)
        tools.add(recordTool("share_to_doctor", "生成可分享给医生的报告摘要 (微信/邮件可粘贴)",
            JSONObject().apply {
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "this_week", "this_month", "last_month"))) })
                put("format", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("markdown", "plain_text"))) })
            }, listOf("time_scope")))

        // 37. 应用锁
        tools.add(recordTool("lock_app", "锁定 App (需要重新输入密码/生物识别才能打开)",
            JSONObject(), listOf()))

        // 38. 切换暗黑模式
        tools.add(recordTool("toggle_dark_mode", "切换暗黑模式 / 浅色模式 / 跟随系统",
            JSONObject().apply {
                put("mode", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("light", "dark", "system"))) })
            }, listOf("mode")))

        // 39. 切换语言
        tools.add(recordTool("set_language", "切换 App 语言",
            JSONObject().apply {
                put("language", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("zh", "en"))) })
            }, listOf("language")))

        // 40. 校准指尖血 (录入指尖血校准 CGM)
        tools.add(recordTool("calibrate_cgm", "录入指尖血校准 CGM 传感器",
            JSONObject().apply {
                put("fingerstick_value", JSONObject().apply { put("type", "number"); put("description", "指尖血血糖 mmol/L") })
                put("timestamp", JSONObject().apply { put("type", "integer"); put("description", "测量时间 epoch ms") })
            }, listOf("fingerstick_value")))

        // 41. 应用内导航深层链接
        tools.add(recordTool("navigate_deep", "AI 智能跳转到相关页面 (根据上下文)",
            JSONObject().apply {
                put("context", JSONObject().apply { put("type", "string"); put("description", "上下文关键词, 比如血糖趋势或饮食或设置") })
            }, listOf("context")))

        // 42. 添加/编辑快捷短语 (用于 AI 助手快速回复)
        tools.add(recordTool("manage_quick_reply", "管理 AI 助手快捷短语 (常用输入)",
            JSONObject().apply {
                put("action", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("add", "list", "delete"))) })
                put("phrase", JSONObject().apply { put("type", "string") })
            }, listOf("action")))

        // ★ v3.0.4 更多 AI 工具 (用户能做的所有事, AI 都能做)

        // 21. UPDATE 类
        tools.add(recordTool("update_glucose", "修改血糖记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer"); put("description", "要修改的记录 ID") })
                put("new_value", JSONObject().apply { put("type", "number"); put("description", "新血糖值 (mmol/L)") })
                put("new_scene", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("fasting", "before_meal", "after_meal", "bedtime", "other"))) })
            }, listOf("record_id")))

        tools.add(recordTool("update_insulin", "修改胰岛素记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer") })
                put("new_dose", JSONObject().apply { put("type", "number"); put("description", "新剂量") })
                put("new_dose_type", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("rapid", "short", "long", "mixed"))) })
            }, listOf("record_id")))

        // ★ v3.0.6 补全 6 类 update 工具
        tools.add(recordTool("update_meal", "修改饮食记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer") })
                put("new_carbs", JSONObject().apply { put("type", "number"); put("description", "新碳水 g") })
                put("new_calories", JSONObject().apply { put("type", "number"); put("description", "新热量 kcal") })
                put("new_food_name", JSONObject().apply { put("type", "string"); put("description", "新食物名") })
                put("new_portion_grams", JSONObject().apply { put("type", "number"); put("description", "新份量 g") })
            }, listOf("record_id")))

        tools.add(recordTool("update_exercise", "修改运动记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer") })
                put("new_duration_min", JSONObject().apply { put("type", "integer"); put("description", "新时长 分钟") })
                put("new_exercise_type", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("running", "walking", "cycling", "swimming", "strength", "yoga", "jumping_rope", "hiking", "ball_sports", "other"))) })
                put("new_intensity", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("low", "medium", "high"))) })
            }, listOf("record_id")))

        tools.add(recordTool("update_sleep", "修改睡眠记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer") })
                put("new_duration_minutes", JSONObject().apply { put("type", "integer"); put("description", "新睡眠时长 分钟") })
                put("new_quality", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("good", "normal", "poor"))) })
            }, listOf("record_id")))

        tools.add(recordTool("update_blood_pressure", "修改血压记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer") })
                put("new_systolic", JSONObject().apply { put("type", "integer"); put("description", "新收缩压 mmHg") })
                put("new_diastolic", JSONObject().apply { put("type", "integer"); put("description", "新舒张压 mmHg") })
                put("new_heart_rate", JSONObject().apply { put("type", "integer"); put("description", "新心率") })
            }, listOf("record_id")))

        tools.add(recordTool("update_weight", "修改体重记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer") })
                put("new_weight_kg", JSONObject().apply { put("type", "number"); put("description", "新体重 kg") })
            }, listOf("record_id")))

        tools.add(recordTool("update_ketone", "修改酮体记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer") })
                put("new_ketone_level", JSONObject().apply { put("type", "number"); put("description", "新酮体值 mmol/L") })
            }, listOf("record_id")))

        tools.add(recordTool("update_medication", "修改用药记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer") })
                put("new_medication_name", JSONObject().apply { put("type", "string"); put("description", "新药品名") })
                put("new_dose", JSONObject().apply { put("type", "string"); put("description", "新剂量 如 '500mg'") })
            }, listOf("record_id")))

        tools.add(recordTool("update_symptom", "修改症状记录",
            JSONObject().apply {
                put("record_id", JSONObject().apply { put("type", "integer") })
                put("new_symptoms", JSONObject().apply { put("type", "string"); put("description", "新症状描述, 逗号分隔") })
                put("new_severity", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("mild", "moderate", "severe"))) })
            }, listOf("record_id")))

        // 22. 批量记录多餐
        tools.add(recordTool("batch_record_meals", "一次性记录多种食物 (一顿饭的多个菜)",
            JSONObject().apply {
                put("foods", JSONObject().apply {
                    put("type", "array")
                    put("description", "食物列表, 每项是 {name: '米饭', grams: 100}")
                    put("items", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("name", JSONObject().apply { put("type", "string"); put("description", "食物名") })
                            put("grams", JSONObject().apply { put("type", "number"); put("description", "克数") })
                        })
                    })
                })
                put("meal_type", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("breakfast", "lunch", "dinner", "snack"))) })
                put("time_offset_min", JSONObject().apply { put("type", "integer") })
            }, listOf("foods")))

        // 23. 食物搜索/查询
        tools.add(recordTool("search_food", "查询食物的碳水/热量/GI",
            JSONObject().apply {
                put("food_name", JSONObject().apply { put("type", "string"); put("description", "食物名") })
            }, listOf("food_name")))

        // 24. 胰岛素剂量计算器 (基于碳水 + 个性化 ICR)
        tools.add(recordTool("calc_insulin_dose", "估算胰岛素剂量 (碳水比例法)",
            JSONObject().apply {
                put("carbs_grams", JSONObject().apply { put("type", "number"); put("description", "碳水克数") })
                put("current_glucose", JSONObject().apply { put("type", "number"); put("description", "当前血糖 (mmol/L), 用于计算修正剂量") })
                put("target_glucose", JSONObject().apply { put("type", "number"); put("description", "目标血糖 (mmol/L), 默认 5.5") })
                put("icr", JSONObject().apply { put("type", "number"); put("description", "碳水比 ICR (1 单位胰岛素覆盖多少克碳水), 默认 10") })
                put("isf", JSONObject().apply { put("type", "number"); put("description", "胰岛素敏感系数 ISF (1 单位降多少 mmol/L), 默认 2.5") })
            }, listOf("carbs_grams")))

        // 25. TIR 达标率 (Time In Range)
        tools.add(recordTool("get_tir", "获取 TIR 血糖目标范围内时间百分比",
            JSONObject().apply {
                put("time_scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("today", "yesterday", "this_week", "last_week", "this_month", "last_month"))) })
                put("low", JSONObject().apply { put("type", "number"); put("description", "下限, 默认 3.9") })
                put("high", JSONObject().apply { put("type", "number"); put("description", "上限, 默认 10.0") })
            }, listOf("time_scope")))

        // 26. 趋势分析 (近期血糖趋势)
        tools.add(recordTool("analyze_trend", "分析近期血糖/体重/运动趋势",
            JSONObject().apply {
                put("target", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("glucose", "weight", "exercise", "sleep"))); put("description", "分析目标") })
                put("days", JSONObject().apply { put("type", "integer"); put("description", "分析天数, 默认 7") })
            }, listOf("target")))

        // 27. 风险评估 (高低血糖风险)
        tools.add(recordTool("assess_risk", "基于近期数据评估低血糖/高血糖风险",
            JSONObject().apply {
                put("days", JSONObject().apply { put("type", "integer"); put("description", "评估最近几天数据, 默认 7") })
            }, listOf()))

        // 28. 对比两个时间段
        tools.add(recordTool("compare_periods", "对比两个时间段的数据 (上周 vs 本周 / 上月 vs 本月)",
            JSONObject().apply {
                put("target", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("glucose", "meal", "exercise", "weight", "insulin"))) })
                put("period_a", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("last_week", "last_month"))) })
                put("period_b", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("this_week", "this_month"))) })
            }, listOf("target")))

        // 29. 删除最近 N 条记录
        tools.add(recordTool("delete_recent", "删除某个类型的最近 N 条记录",
            JSONObject().apply {
                put("target", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("glucose", "insulin", "meal", "exercise", "weight", "bp"))) })
                put("count", JSONObject().apply { put("type", "integer"); put("description", "删除最近多少条") })
            }, listOf("target", "count")))

        // 30. 设置提醒 (基于时间)
        tools.add(recordTool("set_reminder", "设置用药/测血糖/运动的提醒",
            JSONObject().apply {
                put("type", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("medication", "glucose", "exercise", "meal"))); put("description", "提醒类型") })
                put("time", JSONObject().apply { put("type", "string"); put("description", "时间 HH:mm 如 '08:00'") })
                put("note", JSONObject().apply { put("type", "string"); put("description", "备注 (可选)") })
            }, listOf("type", "time")))

        // 31. 食物营养估算 (基于克数和食物库)
        tools.add(recordTool("estimate_nutrition", "估算食物的营养 (碳水/热量/蛋白质/脂肪)",
            JSONObject().apply {
                put("food_name", JSONObject().apply { put("type", "string") })
                put("grams", JSONObject().apply { put("type", "number"); put("description", "份量 (克)") })
            }, listOf("food_name", "grams")))

        // 32. AI 设置暗黑模式/字体大小
        tools.add(recordTool("set_preference", "设置应用偏好 (暗黑模式/字体大小/通知)",
            JSONObject().apply {
                put("key", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("dark_mode", "font_size", "notification_enabled", "language"))) })
                put("value", JSONObject().apply { put("type", "string"); put("description", "新值 (true/false/small/medium/large/zh/en)") })
            }, listOf("key", "value")))

        // 33. AI 切换对话 (新建/加载历史)
        tools.add(recordTool("manage_conversation", "AI 主动管理对话 (新建/列出/切换/删除)",
            JSONObject().apply {
                put("action", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("create", "list", "switch", "delete"))) })
                put("conversation_id", JSONObject().apply { put("type", "string"); put("description", "对话 ID (switch/delete 时必填)") })
            }, listOf("action")))

        // 34. AI 自我总结 (总结对话/总结用户近况)
        tools.add(recordTool("self_summarize", "总结对话内容 或 总结用户近期健康情况",
            JSONObject().apply {
                put("scope", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("conversation", "user_health"))); put("description", "总结范围") })
                put("conversation_id", JSONObject().apply { put("type", "string"); put("description", "对话 ID (scope=conversation 时必填)") })
                put("days", JSONObject().apply { put("type", "integer"); put("description", "健康数据天数 (scope=user_health 时)") })
            }, listOf("scope")))

        // 35. AI 紧急联系人管理
        tools.add(recordTool("manage_emergency_contact", "管理紧急联系人 (增删改查)",
            JSONObject().apply {
                put("action", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("add", "list", "update", "delete"))) })
                put("name", JSONObject().apply { put("type", "string"); put("description", "姓名 (add/update 时)") })
                put("phone", JSONObject().apply { put("type", "string"); put("description", "电话 (add/update 时)") })
                put("relation", JSONObject().apply { put("type", "string"); put("description", "关系 (add/update 时)") })
                put("contact_id", JSONObject().apply { put("type", "integer"); put("description", "联系人 ID (update/delete 时)") })
            }, listOf("action")))

        // 36. AI 预测餐后血糖峰值
        tools.add(recordTool("predict_post_meal", "基于当前血糖 + 即将摄入的碳水预测餐后血糖峰值",
            JSONObject().apply {
                put("current_glucose", JSONObject().apply { put("type", "number"); put("description", "餐前血糖 mmol/L") })
                put("carbs_grams", JSONObject().apply { put("type", "number"); put("description", "即将摄入的碳水克数") })
                put("insulin_taken", JSONObject().apply { put("type", "number"); put("description", "已注射的胰岛素单位 (可选)") })
            }, listOf("current_glucose", "carbs_grams")))

        // 37. AI 食物推荐 (基于血糖历史)
        tools.add(recordTool("recommend_food", "基于用户近期血糖/偏好 推荐食物",
            JSONObject().apply {
                put("meal_type", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("breakfast", "lunch", "dinner", "snack"))) })
                put("max_carbs", JSONObject().apply { put("type", "number"); put("description", "最大碳水克数 (可选)") })
            }, listOf("meal_type")))

        return JSONArray(tools)
    }
}