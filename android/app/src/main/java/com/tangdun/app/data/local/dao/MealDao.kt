package com.tangdun.app.data.local.dao

import androidx.room.*
import com.tangdun.app.data.local.entity.MealItem
import com.tangdun.app.data.local.entity.MealRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface MealDao {

    // ===== 饮食记录 =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: MealRecord): Long

    @Update
    suspend fun update(record: MealRecord)

    @Delete
    suspend fun delete(record: MealRecord)

    @Query("SELECT * FROM meal_record WHERE id = :id")
    suspend fun getById(id: Long): MealRecord?

    @Query("SELECT * FROM meal_record ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<MealRecord>

    @Query("SELECT * FROM meal_record ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentFlow(limit: Int = 50): Flow<List<MealRecord>>

    @Query("SELECT * FROM meal_record WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    suspend fun getByTimeRange(startTime: Long, endTime: Long): List<MealRecord>

    @Query("SELECT * FROM meal_record WHERE timestamp >= :todayStart ORDER BY timestamp DESC")
    suspend fun getTodayRecords(todayStart: Long): List<MealRecord>

    /** 获取今日总碳水 */
    @Query("SELECT SUM(totalCarbs) FROM meal_record WHERE timestamp >= :todayStart")
    suspend fun getTodayTotalCarbs(todayStart: Long): Double?

    /** 获取今日总热量 */
    @Query("SELECT SUM(totalCalories) FROM meal_record WHERE timestamp >= :todayStart")
    suspend fun getTodayTotalCalories(todayStart: Long): Double?

    /** 时间范围内的记录数 */
    @Query("SELECT COUNT(*) FROM meal_record WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun getCount(startTime: Long, endTime: Long): Int

    // ===== 饮食明细 =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: MealItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<MealItem>)

    @Delete
    suspend fun deleteItem(item: MealItem)

    @Query("SELECT * FROM meal_item WHERE mealId = :mealId")
    suspend fun getItemsByMealId(mealId: Long): List<MealItem>

    @Query("SELECT * FROM meal_item WHERE mealId = :mealId")
    fun getItemsByMealIdFlow(mealId: Long): Flow<List<MealItem>>

    /** 删除记录及其明细 */
    @Transaction
    suspend fun deleteWithItems(record: MealRecord) {
        deleteItemByMealId(record.id)
        delete(record)
    }

    @Query("DELETE FROM meal_item WHERE mealId = :mealId")
    suspend fun deleteItemByMealId(mealId: Long)
}
