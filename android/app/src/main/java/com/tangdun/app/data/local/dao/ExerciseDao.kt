package com.tangdun.app.data.local.dao

import androidx.room.*
import com.tangdun.app.data.local.entity.ExerciseRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ExerciseRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<ExerciseRecord>)

    @Update
    suspend fun update(record: ExerciseRecord)

    @Delete
    suspend fun delete(record: ExerciseRecord)

    @Query("SELECT * FROM exercise_record WHERE id = :id")
    suspend fun getById(id: Long): ExerciseRecord?

    @Query("SELECT * FROM exercise_record ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<ExerciseRecord>

    @Query("SELECT * FROM exercise_record ORDER BY startTime DESC LIMIT :limit")
    fun getRecentFlow(limit: Int = 50): Flow<List<ExerciseRecord>>

    @Query("SELECT * FROM exercise_record WHERE startTime BETWEEN :startTime AND :endTime ORDER BY startTime DESC")
    suspend fun getByTimeRange(startTime: Long, endTime: Long): List<ExerciseRecord>

    @Query("SELECT * FROM exercise_record WHERE startTime >= :todayStart ORDER BY startTime DESC")
    suspend fun getTodayRecords(todayStart: Long): List<ExerciseRecord>

    /** 今日运动总时长 */
    @Query("SELECT SUM(durationMin) FROM exercise_record WHERE startTime >= :todayStart")
    suspend fun getTodayTotalDuration(todayStart: Long): Int?

    /** 今日总步数 */
    @Query("SELECT SUM(steps) FROM exercise_record WHERE startTime >= :todayStart")
    suspend fun getTodayTotalSteps(todayStart: Long): Int?

    /** 今日总消耗热量 */
    @Query("SELECT SUM(caloriesBurned) FROM exercise_record WHERE startTime >= :todayStart")
    suspend fun getTodayTotalCalories(todayStart: Long): Double?

    /** 今日运动次数 */
    @Query("SELECT COUNT(*) FROM exercise_record WHERE startTime >= :todayStart")
    suspend fun getTodayExerciseCount(todayStart: Long): Int
}
