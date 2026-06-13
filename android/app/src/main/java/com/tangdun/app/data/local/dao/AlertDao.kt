package com.tangdun.app.data.local.dao

import androidx.room.*
import com.tangdun.app.data.local.entity.AlertRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: AlertRecord): Long

    @Update
    suspend fun update(record: AlertRecord)

    @Delete
    suspend fun delete(record: AlertRecord)

    @Query("SELECT * FROM alert_record WHERE id = :id")
    suspend fun getById(id: Long): AlertRecord?

    /** 获取未读预警 */
    @Query("SELECT * FROM alert_record WHERE isRead = 0 ORDER BY createdAt DESC")
    suspend fun getUnread(): List<AlertRecord>

    @Query("SELECT * FROM alert_record WHERE isRead = 0 ORDER BY createdAt DESC")
    fun getUnreadFlow(): Flow<List<AlertRecord>>

    /** 获取最近预警 */
    @Query("SELECT * FROM alert_record ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<AlertRecord>

    @Query("SELECT * FROM alert_record ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentFlow(limit: Int = 50): Flow<List<AlertRecord>>

    /** 标记为已读 */
    @Query("UPDATE alert_record SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)

    /** 标记所有为已读 */
    @Query("UPDATE alert_record SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllAsRead()

    /** 获取未读数量 */
    @Query("SELECT COUNT(*) FROM alert_record WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    /** 删除旧预警 */
    @Query("DELETE FROM alert_record WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
