package com.tangdun.app.data.local.dao

import androidx.room.*
import com.tangdun.app.data.local.entity.GlucoseRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface GlucoseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: GlucoseRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<GlucoseRecord>)

    @Update
    suspend fun update(record: GlucoseRecord)

    @Delete
    suspend fun delete(record: GlucoseRecord)

    @Query("SELECT * FROM glucose_record WHERE id = :id")
    suspend fun getById(id: Long): GlucoseRecord?

    /** 获取最新的血糖记录 */
    @Query("SELECT * FROM glucose_record ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): GlucoseRecord?

    /** 获取最新的血糖记录（Flow） */
    @Query("SELECT * FROM glucose_record ORDER BY timestamp DESC LIMIT 1")
    fun getLatestFlow(): Flow<GlucoseRecord?>

    /** 获取最近N条记录 */
    @Query("SELECT * FROM glucose_record ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 288): List<GlucoseRecord>

    /** 获取时间范围内的记录 */
    @Query("SELECT * FROM glucose_record WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    suspend fun getByTimeRange(startTime: Long, endTime: Long): List<GlucoseRecord>

    /** 获取时间范围内的记录（Flow） */
    @Query("SELECT * FROM glucose_record WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    fun getByTimeRangeFlow(startTime: Long, endTime: Long): Flow<List<GlucoseRecord>>

    /** 获取今日记录 */
    @Query("SELECT * FROM glucose_record WHERE timestamp >= :todayStart ORDER BY timestamp ASC")
    suspend fun getTodayRecords(todayStart: Long): List<GlucoseRecord>

    /** 获取记录数量 */
    @Query("SELECT COUNT(*) FROM glucose_record")
    suspend fun getCount(): Int

    /** 获取统计信息 */
    @Query("SELECT AVG(value) FROM glucose_record WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun getAverage(startTime: Long, endTime: Long): Double?

    @Query("SELECT MIN(value) FROM glucose_record WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun getMin(startTime: Long, endTime: Long): Double?

    @Query("SELECT MAX(value) FROM glucose_record WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun getMax(startTime: Long, endTime: Long): Double?

    /** 删除旧记录 */
    @Query("DELETE FROM glucose_record WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
