package com.tangdun.app.ui.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangdun.app.ai.AIExecutionResult
import com.tangdun.app.ai.AIIntent
import com.tangdun.app.ai.AIIntentParser
import com.tangdun.app.ai.AIPermissionEngine
import com.tangdun.app.ai.AgentToolExecutor
import com.tangdun.app.ai.ProgressEvent
import com.tangdun.app.data.local.dao.AlertDao
import com.tangdun.app.data.local.dao.BloodPressureDao
import com.tangdun.app.data.local.dao.ChatDao
import com.tangdun.app.data.local.dao.ExerciseDao
import com.tangdun.app.data.local.dao.GlucoseDao
import com.tangdun.app.data.local.dao.InsulinDao
import com.tangdun.app.data.local.dao.KetoneDao
import com.tangdun.app.data.local.dao.MealDao
import com.tangdun.app.data.local.dao.MedicationDao
import com.tangdun.app.data.local.dao.SleepDao
import com.tangdun.app.data.local.dao.SymptomDao
import com.tangdun.app.data.local.dao.WeightDao
import com.tangdun.app.data.local.entity.ChatMessage
import com.tangdun.app.data.local.entity.Conversation
import com.tangdun.app.data.remote.AiChatService
import com.tangdun.app.data.remote.ChatMessageDto
import com.tangdun.app.util.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

data class ChatUiState(
    val isLoading: Boolean = false,
    val conversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val error: String? = null,
    // ★ AI 助手权限: 解析出的待应用记录 (用户输入触发)
    val pendingRecords: List<ParsedRecord> = emptyList(),
    // ★ AI 助手权限: 待执行的动作 (导航/查询/修改/删除) - 用户确认后执行
    val pendingAction: PendingAction? = null,
    // ★ AI 助手全套权限 (v2.7): 待执行的 AIIntent 列表 - 用户确认后批量执行
    val pendingIntents: List<AIIntent> = emptyList(),
    // ★ AI 助手权限执行反馈 (Toast 提示)
    val lastExecutionMessage: String? = null,
    // ★ AI 助手导航请求 (ChatScreen 监听这个执行跳转)
    val navigateRequest: String? = null,
    // ★ 上一条用户消息原文 (用于解析记录关联到具体消息)
    val lastUserInput: String = "",
    // ★ AI 助手历史会话列表 (用于侧边栏)
    val conversations: List<Conversation> = emptyList(),
    // ★ v3.0 Claude Code 风格: 当前正在流式显示的思考过程 + 工具调用 (用户发消息 → AI 思考时实时更新)
    val liveThinking: String? = null,
    val liveToolCalls: List<LiveToolCall> = emptyList(),
)

/** 实时工具调用 (UI 展示用) */
data class LiveToolCall(
    val name: String,
    val arguments: String,
    val result: String? = null  // null = 还在执行中
)

/** ★ AI 助手权限: 待确认动作 (兼容旧版 PendingActionDialog) */
sealed class PendingAction {
    abstract val description: String

    data class Navigate(val target: String, override val description: String) : PendingAction()
    data class DeleteRecord(val type: String, val id: Long, override val description: String) : PendingAction()
    data class UpdateRecord(val type: String, val id: Long, val newValue: Double, override val description: String) : PendingAction()
    data class BulkDelete(val type: String, val count: Int, override val description: String) : PendingAction()
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatDao: ChatDao,
    // ★ AI 助手读权限: 注入 DAO 用于构造上下文
    private val glucoseDao: GlucoseDao,
    private val insulinDao: InsulinDao,
    private val mealDao: MealDao,
    // ★ AI 助手全套权限 (v2.7): 注入所有 DAO 给 AIPermissionEngine
    private val exerciseDao: ExerciseDao,
    private val sleepDao: SleepDao,
    private val bloodPressureDao: BloodPressureDao,
    private val weightDao: WeightDao,
    private val ketoneDao: KetoneDao,
    private val medicationDao: MedicationDao,
    private val symptomDao: SymptomDao,
    private val alertDao: AlertDao,
    private val settingsManager: SettingsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val aiChatService = AiChatService(context)
    // ★ v2.8 AI 助手: 调用通用大模型 API (OpenAI 兼容) 自动解析用户输入
    private val aiClient = com.tangdun.app.ai.AIClient(settingsManager)
    // ★ AI 助手全套权限引擎
    private val aiEngine = AIPermissionEngine(
        context = context,
        glucoseDao = glucoseDao,
        insulinDao = insulinDao,
        mealDao = mealDao,
        exerciseDao = exerciseDao,
        sleepDao = sleepDao,
        bloodPressureDao = bloodPressureDao,
        weightDao = weightDao,
        ketoneDao = ketoneDao,
        medicationDao = medicationDao,
        symptomDao = symptomDao,
        alertDao = alertDao,
        settingsManager = settingsManager
    )

    /**
     * ★ AI 助手权限: 应用用户确认后的 ParsedRecord 列表
     * 返回成功保存的数量 (用于 UI 提示)
     */
    fun applyPendingRecords(records: List<ParsedRecord>, onComplete: (successCount: Int, totalCount: Int) -> Unit) {
        viewModelScope.launch {
            var success = 0
            records.forEach { record ->
                val (msg, ok) = AiRecordHelper.saveRecord(context, record)
                if (ok) success++
                else Log.w("ChatVM", "saveRecord failed: $msg")
            }
            _uiState.value = _uiState.value.copy(pendingRecords = emptyList())
            onComplete(success, records.size)
        }
    }

    /** 取消待应用的记录 */
    fun dismissPendingRecords() {
        _uiState.value = _uiState.value.copy(pendingRecords = emptyList())
    }

    /**
     * ★ AI 助手全套权限 (v2.7): 用户确认后批量执行 AIIntent 列表
     *
     * 流程:
     *  1. 遍历 intents
     *  2. 对每个调用 aiEngine.execute() (本地操作, 不依赖网络)
     *  3. 收集所有结果, 用 Toast 提示给用户
     *  4. 如果有 navigateTo, 设置 navigateRequest 触发 ChatScreen 跳转
     */
    fun applyPendingIntents() {
        val intents = _uiState.value.pendingIntents
        if (intents.isEmpty()) return

        viewModelScope.launch {
            val results = aiEngine.executeAll(intents)
            // 汇总消息
            val combinedMsg = results.joinToString("\n") { it.message }
            // 收集导航请求
            val nav = results.firstNotNullOfOrNull { it.navigateTo }

            _uiState.value = _uiState.value.copy(
                pendingIntents = emptyList(),
                lastExecutionMessage = combinedMsg,
                navigateRequest = nav
            )
        }
    }

    /** 取消待执行的 intents */
    fun dismissPendingIntents() {
        _uiState.value = _uiState.value.copy(pendingIntents = emptyList())
    }

    /** 清除 navigateRequest (ChatScreen 执行跳转后调用) */
    fun clearNavigateRequest() {
        _uiState.value = _uiState.value.copy(navigateRequest = null)
    }

    /** 清除 lastExecutionMessage (Toast 显示后调用) */
    fun clearExecutionMessage() {
        _uiState.value = _uiState.value.copy(lastExecutionMessage = null)
    }

    /**
     * ★ AI 助手读权限: 构造最近 50 条数据的上下文 (注入到 AI 系统 prompt)
     * 让 AI 能直接基于真实数据回答"今天最高血糖"、"今早吃了什么"
     */
    private suspend fun buildUserDataContext(): String {
        return try {
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val todayMidnight = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            // 最近 30 条血糖 (所有日期, 不只是今天)
            val recentGlucose = glucoseDao.getRecent(30).take(30)
            // 最近 30 条胰岛素
            val recentInsulin = insulinDao.getRecent(30).take(30)
            // 最近 30 条饮食
            val recentMeals = mealDao.getRecent(30).take(30)

            val todayGlucose = recentGlucose.filter { it.timestamp >= todayMidnight }
            val todayInsulin = recentInsulin.filter { it.timestamp >= todayMidnight }

            buildString {
                appendLine("## 用户最近数据 (用于回答查询类问题)")
                appendLine("- 今天血糖记录数: ${todayGlucose.size} 条")
                if (todayGlucose.isNotEmpty()) {
                    val avg = todayGlucose.map { it.value }.average()
                    val max = todayGlucose.maxOf { it.value }
                    val min = todayGlucose.minOf { it.value }
                    appendLine("  · 平均: ${"%.1f".format(avg)} mmol/L")
                    appendLine("  · 最高: ${"%.1f".format(max)} mmol/L")
                    appendLine("  · 最低: ${"%.1f".format(min)} mmol/L")
                    appendLine("  · 最近5条: " + todayGlucose.takeLast(5).joinToString(" | ") { "${sdf.format(Date(it.timestamp))}=${"%.1f".format(it.value)}" })
                }
                appendLine("- 今天胰岛素剂量: ${todayInsulin.sumOf { it.doseUnits }} U (${todayInsulin.size} 针)")
                appendLine("- 最近 ${recentMeals.size} 条饮食, ${recentInsulin.size} 针胰岛素, ${recentGlucose.size} 次血糖")
                appendLine()
                appendLine("## AI 可执行的指令格式 (用户输入含相关意图时, 在回复中输出 JSON 块)")
                appendLine("导航: ```json{\"action\":\"navigate\",\"target\":\"home|prediction|meal|insulin|exercise|ai_record|records\"}```")
                appendLine("修改: ```json{\"action\":\"update\",\"target\":\"glucose|insulin|meal\",\"id\":ID,\"value\":NEW_VALUE}```")
                appendLine("删除: ```json{\"action\":\"delete\",\"target\":\"glucose|insulin|meal\",\"id\":ID}``` 或 ```json{\"action\":\"delete_today\",\"target\":\"glucose|insulin|meal\"}```")
                appendLine("所有写操作必须在用户输入明确表达意图时才输出, 不要主动建议删除")
            }
        } catch (e: Exception) {
            Log.w("ChatVM", "buildUserDataContext 失败: ${e.message}")
            ""
        }
    }

    /**
     * ★ AI 助手全套权限: 检测 AI 回复中的指令 → 弹确认 → 用户确认后执行
     */
    private fun parseAndSetPendingAction(aiReply: String) {
        try {
            // 提取```json```块
            val codeBlock = Regex("```json\\s*([\\s\\S]*?)```")
            val matches = codeBlock.findAll(aiReply).toList()
            for (m in matches) {
                val jsonStr = m.groupValues[1].trim()
                val json = org.json.JSONObject(jsonStr)
                val action = json.optString("action", "")
                when (action) {
                    "navigate" -> {
                        val target = json.optString("target", "")
                        if (target.isNotEmpty()) {
                            _uiState.value = _uiState.value.copy(
                                pendingAction = PendingAction.Navigate(
                                    target = target,
                                    description = "跳转到 ${translateTarget(target)}"
                                )
                            )
                            return
                        }
                    }
                    "delete_today" -> {
                        val target = json.optString("target", "")
                        if (target.isNotEmpty()) {
                            viewModelScope.launch {
                                val count = when (target) {
                                    "glucose" -> {
                                        val todayMidnight = java.util.Calendar.getInstance().apply {
                                            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                                            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                                        }.timeInMillis
                                        val records = glucoseDao.getRecent(100).filter { it.timestamp >= todayMidnight }
                                        records.forEach { glucoseDao.delete(it) }
                                        records.size
                                    }
                                    "insulin" -> {
                                        val todayMidnight = java.util.Calendar.getInstance().apply {
                                            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                                            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                                        }.timeInMillis
                                        val records = insulinDao.getRecent(100).filter { it.timestamp >= todayMidnight }
                                        records.forEach { insulinDao.delete(it) }
                                        records.size
                                    }
                                    "meal" -> {
                                        val todayMidnight = java.util.Calendar.getInstance().apply {
                                            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                                            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                                        }.timeInMillis
                                        val records = mealDao.getRecent(100).filter { it.timestamp >= todayMidnight }
                                        records.forEach { mealDao.delete(it) }
                                        records.size
                                    }
                                    else -> 0
                                }
                                android.widget.Toast.makeText(
                                    context, "🗑️ 已删除 $count 条${translateTarget(target)}记录",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                _uiState.value = _uiState.value.copy(pendingAction = null)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ChatVM", "parseAndSetPendingAction 失败: ${e.message}")
        }
    }

    private fun translateTarget(target: String): String = when (target) {
        "home" -> "首页"
        "prediction" -> "预测页"
        "meal" -> "饮食记录页"
        "insulin" -> "胰岛素记录页"
        "exercise" -> "运动记录页"
        "ai_record" -> "AI 记录页"
        "records" -> "记录列表页"
        "glucose" -> "血糖"
        else -> target
    }

    /** 取消待执行动作 */
    fun dismissPendingAction() {
        _uiState.value = _uiState.value.copy(pendingAction = null)
    }

    /** 确认执行导航动作 (由 ChatScreen 处理跳转) */
    fun confirmPendingAction(onNavigate: (String) -> Unit) {
        val action = _uiState.value.pendingAction ?: return
        if (action is PendingAction.Navigate) {
            onNavigate(action.target)
        }
        _uiState.value = _uiState.value.copy(pendingAction = null)
    }

    /**
     * 创建新会话
     */
    fun createNewConversation() {
        val conversationId = UUID.randomUUID().toString()
        val conversation = Conversation(
            id = conversationId,
            title = "新对话"
        )

        viewModelScope.launch {
            chatDao.insertConversation(conversation)
            _uiState.value = ChatUiState(conversationId = conversationId)
            refreshConversations()
        }
    }

    /**
     * 加载会话
     */
    fun loadConversation(conversationId: String) {
        viewModelScope.launch {
            val messages = chatDao.getMessagesList(conversationId)
            _uiState.value = _uiState.value.copy(
                conversationId = conversationId,
                messages = messages
            )
            refreshConversations()
        }
    }

    /**
     * ★ AI 助手历史: 刷新会话列表 (Flow 自动更新, 这里手动 reload 一次)
     */
    fun refreshConversations() {
        viewModelScope.launch {
            val all = chatDao.getAllConversationsOnce()
            _uiState.value = _uiState.value.copy(conversations = all)
        }
    }

    /** 删除一个会话 */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            chatDao.deleteConversationWithMessages(conversationId)
            if (_uiState.value.conversationId == conversationId) {
                createNewConversation()
            } else {
                refreshConversations()
            }
        }
    }

    /**
     * 发送消息
     *
     * AI 助手权限集成:
     *  - 优先级 1: 调 AiRecordHelper.parse 解析用户输入 → 如有记录, 显示"应用建议"按钮, 跳过 AI 聊天 (避免 AI 误识别)
     *  - 优先级 2: 解析无记录 → 走 AI 聊天回复
     */
    fun sendMessage(content: String) {
        if (_uiState.value.isLoading) return  // 防止并发发送
        var conversationId = _uiState.value.conversationId

        // 如果还未创建会话，先同步创建
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString().replace("-", "")
            val conversation = Conversation(id = conversationId, title = "新对话")
            viewModelScope.launch {
                chatDao.insertConversation(conversation)
                _uiState.value = _uiState.value.copy(conversationId = conversationId)
            }
        }

        viewModelScope.launch {
            // 添加用户消息
            val userMessage = ChatMessage(
                conversationId = conversationId,
                role = ChatMessage.ROLE_USER,
                content = content
            )
            chatDao.insertMessage(userMessage)

            // 更新UI
            val currentMessages = _uiState.value.messages + userMessage
            _uiState.value = _uiState.value.copy(
                messages = currentMessages,
                isLoading = true,
                error = null,
                lastUserInput = content
            )

            // 更新会话标题（使用第一条消息）
            if (currentMessages.size == 1) {
                val conversation = chatDao.getConversation(conversationId)
                if (conversation != null) {
                    val title = if (content.length > 20) content.substring(0, 20) + "..." else content
                    chatDao.updateConversation(conversation.copy(title = title))
                }
            }

            // ★ v2.9 AI 助手 - 真正的 Agent 模式
            // 不再使用本地 regex 解析, 完全由 AI 大模型通过 function calling 决定调用哪些工具
            val agentExecutor = AgentToolExecutor(
                engine = aiEngine,
                onNavigate = { route ->
                    _uiState.value = _uiState.value.copy(navigateRequest = route)
                },
                onExportRequest = { format, scope ->
                    // 触发导出 (通过 navigateRequest 跳到记录页, 让用户手动导出)
                    _uiState.value = _uiState.value.copy(navigateRequest = "record")
                }
            )

            Log.i("ChatVM", "[Agent 模式] 调用 AI: $content")
            // ★ v3.0 实时进度: AI 思考 / 工具调用每步都推到 UI
            val liveThinkingBuilder = StringBuilder()
            val liveToolCalls = mutableListOf<LiveToolCall>()
            val agentResult = aiClient.runAgent(
                userInput = content,
                toolExecutor = { toolName, args -> agentExecutor.execute(toolName, args) },
                onProgress = { event ->
                    when (event) {
                        is ProgressEvent.Thinking -> {
                            liveThinkingBuilder.append(event.content).append("\n\n")
                            _uiState.value = _uiState.value.copy(liveThinking = liveThinkingBuilder.toString())
                        }
                        is ProgressEvent.ToolCallStart -> {
                            liveToolCalls.add(LiveToolCall(event.name, event.arguments, null))
                            _uiState.value = _uiState.value.copy(liveToolCalls = liveToolCalls.toList())
                        }
                        is ProgressEvent.ToolCallDone -> {
                            val idx = liveToolCalls.indexOfLast { it.name == event.name && it.result == null }
                            if (idx >= 0) liveToolCalls[idx] = LiveToolCall(event.name, event.arguments, event.result)
                            _uiState.value = _uiState.value.copy(liveToolCalls = liveToolCalls.toList())
                        }
                        is ProgressEvent.Text -> {}
                        is ProgressEvent.Done -> {}
                    }
                }
            )
            // 清空 live 状态
            _uiState.value = _uiState.value.copy(liveThinking = null, liveToolCalls = emptyList())

            if (!agentResult.success && agentResult.finalAnswer.isEmpty()) {
                // 失败 (API 未配置/网络错误/AI 返回空)
                val errMsg = agentResult.errorMessage ?: "AI 服务异常"
                Log.w("ChatVM", "Agent 失败: $errMsg")
                val errorAssistantMsg = ChatMessage(
                    conversationId = conversationId,
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = "⚠️ $errMsg\n\n请到「我的 → AI 对话配置」中检查 API Key 和 Base URL 是否正确。"
                )
                chatDao.insertMessage(errorAssistantMsg)
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + errorAssistantMsg,
                    isLoading = false,
                    error = errMsg
                )
                chatDao.updateConversationMessageCount(conversationId)
                return@launch
            }

            // ★ Agent 成功: 显示最终 AI 回复 + 工具调用记录
            Log.i("ChatVM", "Agent 完成: ${agentResult.toolCalls.size} 个工具调用, 回复 ${agentResult.finalAnswer.length} 字")

            // 工具调用摘要 (作为前缀, 让用户看到 AI 调用了哪些工具)
            val toolSummary = if (agentResult.toolCalls.isNotEmpty()) {
                buildString {
                    append("🔧 已执行 ${agentResult.toolCalls.size} 个操作:\n")
                    agentResult.toolCalls.forEach { call ->
                        append("• ${call.name}")
                        if (call.result.length < 100) {
                            val r = runCatching {
                                org.json.JSONObject(call.result).optString("message", "")
                            }.getOrDefault("")
                            if (r.isNotEmpty()) append(" → $r")
                        }
                        append("\n")
                    }
                    append("\n")
                }
            } else ""

            val finalContent = toolSummary + agentResult.finalAnswer.ifBlank { "已完成" }
            val assistantMessage = ChatMessage(
                conversationId = conversationId,
                role = ChatMessage.ROLE_ASSISTANT,
                content = finalContent
            )
            chatDao.insertMessage(assistantMessage)

            // 更新会话标题 (如果有工具调用)
            if (agentResult.toolCalls.isNotEmpty()) {
                val conv = chatDao.getConversation(conversationId)
                if (conv != null && conv.title == "新对话") {
                    chatDao.updateConversation(conv.copy(title = "📝 Agent 记录"))
                }
            }

            // 通知 UI: 数据有变动, 触发刷新
            if (agentResult.toolCalls.any { it.name.startsWith("record_") || it.name.startsWith("delete_") }) {
                _uiState.value = _uiState.value.copy(lastExecutionMessage = "AI 已自动添加/删除记录")
            }

            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + assistantMessage,
                isLoading = false
            )
            chatDao.updateConversationMessageCount(conversationId)
        }
    }

    /**
     * 清空当前会话
     */
    fun clearCurrentConversation() {
        val conversationId = _uiState.value.conversationId ?: return

        viewModelScope.launch {
            chatDao.deleteConversationWithMessages(conversationId)
            createNewConversation()
        }
    }

    /**
     * 获取所有会话
     */
    fun getAllConversations() = chatDao.getAllConversations()
}
