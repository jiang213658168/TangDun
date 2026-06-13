package com.tangdun.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.tangdun.app.data.local.converter.Converters
import com.tangdun.app.data.local.dao.*
import com.tangdun.app.data.local.entity.*

/**
 * 糖盾本地数据库
 *
 * 版本管理：
 * - 版本1: 初始版本
 * - 版本2: 添加AI对话表
 * - 版本3: 添加健康记录表，移除本地食物数据库（改用大模型查询）
 */
@Database(
    entities = [
        GlucoseRecord::class,
        MealRecord::class,
        MealItem::class,
        ExerciseRecord::class,
        InsulinRecord::class,
        AlertRecord::class,
        ChatMessage::class,
        Conversation::class,
        SleepRecord::class,
        BloodPressureRecord::class,
        WeightRecord::class,
        KetoneRecord::class,
        MedicationRecord::class,
        SymptomRecord::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun glucoseDao(): GlucoseDao
    abstract fun mealDao(): MealDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun insulinDao(): InsulinDao
    abstract fun alertDao(): AlertDao
    abstract fun chatDao(): ChatDao
    abstract fun sleepDao(): SleepDao
    abstract fun bloodPressureDao(): BloodPressureDao
    abstract fun weightDao(): WeightDao
    abstract fun ketoneDao(): KetoneDao
    abstract fun medicationDao(): MedicationDao
    abstract fun symptomDao(): SymptomDao

    companion object {
        const val DATABASE_NAME = "tangdun.db"
    }
}
