package com.tangdun.app.ui.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangdun.app.data.local.dao.ChatDao
import com.tangdun.app.data.local.entity.ChatMessage
import com.tangdun.app.data.local.entity.Conversation
import com.tangdun.app.data.remote.AiChatService
import com.tangdun.app.data.remote.ChatMessageDto
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ChatUiState(
    val isLoading: Boolean = false,
    val conversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val error: String? = null,
    // ★ AI 助手权限: 解析出的待应用记录 (用户输入触发)
    val pendingRecords: List<ParsedRecord> = emptyList(),
    // ★ 上一条用户消息原文 (用于解析记录关联到具体消息)
    val lastUserInput: String = "",
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatDao: ChatDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val aiChatService = AiChatService(context)

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
        }
    }

    /**
     * 加载会话
     */
    fun loadConversation(conversationId: String) {
        viewModelScope.launch {
            val messages = chatDao.getMessagesList(conversationId)
            _uiState.value = ChatUiState(
                conversationId = conversationId,
                messages = messages
            )
        }
    }

    /**
     * 发送消息
     *
     * AI 助手权限集成:
     *  - 用户输入 → 调 AiRecordHelper.parse 提取可执行记录 (meal/insulin/glucose/...)
     *  - 解析到的 records 存到 uiState.pendingRecords
     *  - ChatScreen 检测到 pendingRecords > 0 → 显示"应用建议"按钮
     *  - 用户点击 → 弹确认对话框 → applyPendingRecords() 保存到 DB
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

            // ★ AI 助手权限 - Step 1: 解析用户输入 (与 AI 调用并行, 不阻塞 UI)
            val parseJob = launch {
                try {
                    val records = AiRecordHelper.parse(context, content)
                    if (records.isNotEmpty()) {
                        Log.i("ChatVM", "解析到 ${records.size} 条可应用记录")
                        _uiState.value = _uiState.value.copy(pendingRecords = records)
                    }
                } catch (e: Exception) {
                    Log.w("ChatVM", "解析失败: ${e.message}")
                }
            }

            // 准备消息历史（最近20条）
            val messageHistory = chatDao.getRecentMessages(conversationId, 20)
                .reversed()
                .map { msg ->
                    ChatMessageDto(
                        role = msg.role,
                        content = msg.content
                    )
                }

            // 调用AI接口
            val result = aiChatService.sendMessage(messageHistory)

            result.fold(
                onSuccess = { aiResponse ->
                    // ★ 处理AI回复中的自然语言记录指令 (旧版逻辑, 保留兼容)
                    val (displayText, executed) = aiChatService.processRecordingCommands(context, aiResponse)

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

            // 等待解析完成 (但不等 AI)
            parseJob.join()
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
