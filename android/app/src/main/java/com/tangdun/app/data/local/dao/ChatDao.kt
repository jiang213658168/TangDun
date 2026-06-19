package com.tangdun.app.data.local.dao

import androidx.room.*
import com.tangdun.app.data.local.entity.ChatMessage
import com.tangdun.app.data.local.entity.Conversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    // ===== 会话 =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: Conversation)

    @Update
    suspend fun updateConversation(conversation: Conversation)

    @Delete
    suspend fun deleteConversation(conversation: Conversation)

    @Query("SELECT * FROM conversation ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversation ORDER BY updatedAt DESC")
    suspend fun getAllConversationsOnce(): List<Conversation>

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun getConversation(id: String): Conversation?

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun deleteConversationById(id: String)

    // ===== 消息 =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessage>)

    @Query("SELECT * FROM chat_message WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessages(conversationId: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_message WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesList(conversationId: String): List<ChatMessage>

    @Query("SELECT * FROM chat_message WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(conversationId: String, limit: Int = 20): List<ChatMessage>

    @Query("DELETE FROM chat_message WHERE conversationId = :conversationId")
    suspend fun deleteMessagesByConversation(conversationId: String)

    /** 删除会话及其消息 */
    @Transaction
    suspend fun deleteConversationWithMessages(conversationId: String) {
        deleteMessagesByConversation(conversationId)
        deleteConversationById(conversationId)
    }

    /** 更新会话消息数量 */
    @Query("UPDATE conversation SET messageCount = (SELECT COUNT(*) FROM chat_message WHERE conversationId = :conversationId), updatedAt = :now WHERE id = :conversationId")
    suspend fun updateConversationMessageCount(conversationId: String, now: Long = System.currentTimeMillis())
}
