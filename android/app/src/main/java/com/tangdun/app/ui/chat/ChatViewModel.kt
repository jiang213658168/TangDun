package com.tangdun.app.ui.chat

import android.content.Context
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
    val error: String? = null
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
     */
    fun sendMessage(content: String) {
        val conversationId = _uiState.value.conversationId ?: return

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
                error = null
            )

            // 更新会话标题（使用第一条消息）
            if (currentMessages.size == 1) {
                val conversation = chatDao.getConversation(conversationId)
                if (conversation != null) {
                    val title = if (content.length > 20) content.substring(0, 20) + "..." else content
                    chatDao.updateConversation(conversation.copy(title = title))
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
                    // 添加AI回复
                    val assistantMessage = ChatMessage(
                        conversationId = conversationId,
                        role = ChatMessage.ROLE_ASSISTANT,
                        content = aiResponse
                    )
                    chatDao.insertMessage(assistantMessage)

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
