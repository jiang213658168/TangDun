package com.tangdun.app.data.local.dao

import androidx.room.*
import com.tangdun.app.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SleepRecord): Long

    @Delete
    suspend fun delete(record: SleepRecord)

    @Update
    suspend fun update(record: SleepRecord)

    /** 按 ID 删除 (AI 权限引擎用) */
    @Query("DELETE FROM sleep_record WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM sleep_record WHERE id = :id")
    suspend fun getById(id: Long): SleepRecord?

    @Query("SELECT * FROM sleep_record ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 30): List<SleepRecord>

    @Query("SELECT * FROM sleep_record WHERE timestamp >= :todayStart ORDER BY timestamp DESC")
    suspend fun getTodayRecords(todayStart: Long): List<SleepRecord>

    @Query("SELECT AVG(durationMinutes) FROM sleep_record WHERE timestamp >= :weekAgo")
    suspend fun getWeeklyAverageDuration(weekAgo: Long): Int?
}

@Dao
interface BloodPressureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: BloodPressureRecord): Long

    @Delete
    suspend fun delete(record: BloodPressureRecord)

    @Update
    suspend fun update(record: BloodPressureRecord)

    /** 按 ID 删除 (AI 权限引擎用) */
    @Query("DELETE FROM blood_pressure_record WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM blood_pressure_record WHERE id = :id")
    suspend fun getById(id: Long): BloodPressureRecord?

    @Query("SELECT * FROM blood_pressure_record ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 30): List<BloodPressureRecord>

    @Query("SELECT * FROM blood_pressure_record ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): BloodPressureRecord?
}

@Dao
interface WeightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: WeightRecord): Long

    @Delete
    suspend fun delete(record: WeightRecord)

    @Update
    suspend fun update(record: WeightRecord)

    /** 按 ID 删除 (AI 权限引擎用) */
    @Query("DELETE FROM weight_record WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM weight_record WHERE id = :id")
    suspend fun getById(id: Long): WeightRecord?

    @Query("SELECT * FROM weight_record ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 30): List<WeightRecord>

    @Query("SELECT * FROM weight_record ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): WeightRecord?
}

@Dao
interface KetoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: KetoneRecord): Long

    @Delete
    suspend fun delete(record: KetoneRecord)

    @Update
    suspend fun update(record: KetoneRecord)

    /** 按 ID 删除 (AI 权限引擎用) */
    @Query("DELETE FROM ketone_record WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM ketone_record WHERE id = :id")
    suspend fun getById(id: Long): KetoneRecord?

    @Query("SELECT * FROM ketone_record ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 30): List<KetoneRecord>

    @Query("SELECT * FROM ketone_record ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): KetoneRecord?
}

@Dao
interface MedicationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: MedicationRecord): Long

    @Delete
    suspend fun delete(record: MedicationRecord)

    @Update
    suspend fun update(record: MedicationRecord)

    /** 按 ID 删除 (AI 权限引擎用) */
    @Query("DELETE FROM medication_record WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM medication_record WHERE id = :id")
    suspend fun getById(id: Long): MedicationRecord?

    @Query("SELECT * FROM medication_record ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<MedicationRecord>

    @Query("SELECT * FROM medication_record WHERE timestamp >= :todayStart ORDER BY timestamp DESC")
    suspend fun getTodayRecords(todayStart: Long): List<MedicationRecord>
}

@Dao
interface SymptomDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SymptomRecord): Long

    @Delete
    suspend fun delete(record: SymptomRecord)

    @Update
    suspend fun update(record: SymptomRecord)

    /** 按 ID 删除 (AI 权限引擎用) */
    @Query("DELETE FROM symptom_record WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM symptom_record WHERE id = :id")
    suspend fun getById(id: Long): SymptomRecord?

    @Query("SELECT * FROM symptom_record ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<SymptomRecord>
}
