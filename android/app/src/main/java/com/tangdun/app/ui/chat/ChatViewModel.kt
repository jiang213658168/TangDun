package com.tangdun.app.ui.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangdun.app.ai.AIExecutionResult
import com.tangdun.app.ai.AIIntent
import com.tangdun.app.ai.AIIntentParser
import com.tangdun.app.ai.AIPermissionEngine
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

            // ★ v2.8 AI 助手: 优先调用大模型 API 自动解析, 失败回退本地规则
            val (aiIntents, parseSource) = AIIntentParser.parseAsync(content, aiClient)
            val sourceLabel = when (parseSource) {
                AIIntentParser.ParseSource.AI -> "🤖 AI 大模型"
                AIIntentParser.ParseSource.LOCAL -> "📋 本地规则"
            }
            Log.i("ChatVM", "[$sourceLabel] 解析: ${aiIntents.size} 条 → ${aiIntents.joinToString { it.description }}")
            if (aiIntents.isNotEmpty()) {
                Log.i("ChatVM", "AI 意图解析: ${aiIntents.size} 条 → ${aiIntents.joinToString { it.description }}")

                // 区分"需确认" vs "直接执行"
                val needsConfirm = aiIntents.filter { it.requiresConfirmation }
                val autoExecute = aiIntents.filter { !it.requiresConfirmation }

                // 自动执行: 导航 / 配置 (跳转到目标页, 不需要用户逐条确认)
                if (autoExecute.isNotEmpty()) {
                    val results = aiEngine.executeAll(autoExecute)
                    val combinedMsg = results.joinToString("\n") { it.message }
                    val nav = results.firstNotNullOfOrNull { it.navigateTo }

                    // 直接执行的提示消息
                    val autoMsg = ChatMessage(
                        conversationId = conversationId,
                        role = ChatMessage.ROLE_ASSISTANT,
                        content = combinedMsg.ifBlank { "已完成" }
                    )
                    chatDao.insertMessage(autoMsg)
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + autoMsg,
                        lastExecutionMessage = combinedMsg,
                        navigateRequest = nav,
                        isLoading = false
                    )
                    chatDao.updateConversationMessageCount(conversationId)

                    // 如果没有需要确认的 intent, 直接 return
                    if (needsConfirm.isEmpty()) return@launch
                }

                // 需要用户确认的 intent (创建/删除/更新) → 弹确认弹窗
                if (needsConfirm.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        pendingIntents = needsConfirm,
                        isLoading = false
                    )
                    val hintMessage = ChatMessage(
                        conversationId = conversationId,
                        role = ChatMessage.ROLE_ASSISTANT,
                        content = "🤖 我从你的描述中识别到 ${needsConfirm.size} 个操作:\n\n" +
                                needsConfirm.joinToString("\n") { "• ${it.description}" } +
                                "\n\n请确认是否执行。"
                    )
                    chatDao.insertMessage(hintMessage)
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + hintMessage
                    )
                    // 更新会话标题
                    val conv = chatDao.getConversation(conversationId)
                    if (conv != null && conv.title == "新对话") {
                        chatDao.updateConversation(conv.copy(title = "📝 智能记录"))
                    }
                    chatDao.updateConversationMessageCount(conversationId)
                    return@launch
                }
            }

            // ★ Step 2: AIIntentParser 没匹配到 → 走 AiRecordHelper 旧版解析 (食物)
            val records = try {
                AiRecordHelper.parse(context, content)
            } catch (e: Exception) {
                Log.w("ChatVM", "解析失败: ${e.message}")
                emptyList()
            }

            if (records.isNotEmpty()) {
                // ★ 解析到记录 → 不走 AI 聊天 (聊天 AI 不擅长结构化), 直接显示"应用建议"
                Log.i("ChatVM", "解析到 ${records.size} 条可应用记录, 跳过 AI 聊天")
                _uiState.value = _uiState.value.copy(
                    pendingRecords = records,
                    isLoading = false
                )
                // 加一条助手提示消息, 让用户知道发生了什么
                val hintMessage = ChatMessage(
                    conversationId = conversationId,
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = "🤖 我从你的描述中识别到 ${records.size} 条可记录的医疗事件。\n\n请在下方确认是否保存到数据库（点击 [查看]）。"
                )
                chatDao.insertMessage(hintMessage)
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + hintMessage
                )
                // 更新会话标题
                val conv = chatDao.getConversation(conversationId)
                if (conv != null && conv.title == "新对话") {
                    chatDao.updateConversation(conv.copy(title = "📝 智能记录"))
                }
                chatDao.updateConversationMessageCount(conversationId)
                return@launch
            }

            // ★ Step 2: 没有可记录事件 → 走 AI 聊天回复
            // ★ AI 助手读权限: 把最近 50 条数据作为 system context 注入, AI 能直接回答查询
            val userDataContext = buildUserDataContext()

            val messageHistory = chatDao.getRecentMessages(conversationId, 20)
                .reversed()
                .map { msg ->
                    ChatMessageDto(
                        role = msg.role,
                        content = msg.content
                    )
                }

            // 注入上下文: 如果用户问"今天最高血糖", AI 能基于真实数据回答
            val contextMsg = if (userDataContext.isNotBlank()) {
                listOf(ChatMessageDto("system", userDataContext)) + messageHistory
            } else messageHistory

            val result = aiChatService.sendMessage(contextMsg)

            result.fold(
                onSuccess = { aiResponse ->
                    // 处理AI回复中的自然语言记录指令 (旧版逻辑, 保留兼容)
                    val (displayText, executed) = aiChatService.processRecordingCommands(context, aiResponse)

                    // ★ AI 助手全套权限: 检测 AI 回复中的导航/删除/修改指令 → 弹确认
                    parseAndSetPendingAction(aiResponse)

                    // 添加AI回复 (已清理JSON指令块)
                    val assistantMessage = ChatMessage(
                        conversationId = conversationId,
                        role = ChatMessage.ROLE_ASSISTANT,
                        content = displayText
                    )
                    chatDao.insertMessage(assistantMessage)

                    // 如果执行了记录操作，更新会话标题
                    if (executed > 0) {
                        val conv = chatDao.getConversation(conversationId)
                        if (conv != null && conv.title == "新对话" || conv?.title?.startsWith("AI") == true) {
                            chatDao.updateConversation(conv.copy(title = "📝 记录与咨询"))
                        }
                    }

                    // 更新UI
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + assistantMessage,
                        isLoading = false
                    )

                    // 更新会话消息数量
                    chatDao.updateConversationMessageCount(conversationId)
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "请求失败"
                    )
                }
            )
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
