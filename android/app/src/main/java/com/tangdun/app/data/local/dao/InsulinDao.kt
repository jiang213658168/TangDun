package com.tangdun.app.data.local.dao

import androidx.room.*
import com.tangdun.app.data.local.entity.InsulinRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface InsulinDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: InsulinRecord): Long

    @Update
    suspend fun update(record: InsulinRecord)

    @Delete
    suspend fun delete(record: InsulinRecord)

    /** 按 ID 删除 (AI 权限引擎用) */
    @Query("DELETE FROM insulin_record WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM insulin_record WHERE id = :id")
    suspend fun getById(id: Long): InsulinRecord?

    @Query("SELECT * FROM insulin_record ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<InsulinRecord>

    @Query("SELECT * FROM insulin_record ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentFlow(limit: Int = 50): Flow<List<InsulinRecord>>

    @Query("SELECT * FROM insulin_record WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    suspend fun getByTimeRange(startTime: Long, endTime: Long): List<InsulinRecord>

    /** 获取指定时间后的胰岛素记录（用于IOB计算） */
    @Query("SELECT * FROM insulin_record WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getSince(since: Long): List<InsulinRecord>

    /** 今日总剂量 */
    @Query("SELECT SUM(doseUnits) FROM insulin_record WHERE timestamp >= :todayStart")
    suspend fun getTodayTotalDose(todayStart: Long): Double?

    /** 时间范围内的记录数 */
    @Query("SELECT COUNT(*) FROM insulin_record WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun getCount(startTime: Long, endTime: Long): Int
}
