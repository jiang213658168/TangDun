package com.tangdun.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI对话消息实体
 */
@Entity(
    tableName = "chat_message",
    indices = [Index(value = ["conversationId", "timestamp"])]
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 会话ID */
    val conversationId: String,

    /** 角色: user/assistant/system */
    val role: String,

    /** 消息内容 */
    val content: String,

    /** 时间戳 */
    val timestamp: Long = System.currentTimeMillis(),

    /** 是否已读 */
    val isRead: Boolean = true
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
    }
}

/**
 * 会话记录
 */
@Entity(
    tableName = "conversation",
    indices = [Index(value = ["updatedAt"])]
)
data class Conversation(
    @PrimaryKey
    val id: String,

    /** 会话标题（自动生成） */
    val title: String,

    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis(),

    /** 更新时间 */
    val updatedAt: Long = System.currentTimeMillis(),

    /** 消息数量 */
    val messageCount: Int = 0
)
