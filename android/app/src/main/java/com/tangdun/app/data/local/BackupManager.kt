package com.tangdun.app.data.local

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.tangdun.app.data.local.dao.*
import com.tangdun.app.data.local.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 数据备份管理器
 *
 * 功能：
 * 1. 导出所有数据为JSON
 * 2. 导入JSON恢复数据
 * 3. 自动备份
 */
class BackupManager(
    private val context: Context,
    private val glucoseDao: GlucoseDao,
    private val mealDao: MealDao,
    private val insulinDao: InsulinDao,
    private val exerciseDao: ExerciseDao,
    private val alertDao: AlertDao
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * 备份数据
     */
    data class BackupData(
        val backupTime: String,
        val version: String = "1.0",
        val glucoseRecords: List<GlucoseRecord>,
        val mealRecords: List<MealRecord>,
        val mealItems: List<MealItem>,
        val insulinRecords: List<InsulinRecord>,
        val exerciseRecords: List<ExerciseRecord>,
        val alertRecords: List<AlertRecord>
    )

    /**
     * 执行备份
     *
     * @return 备份文件路径
     */
    suspend fun backup(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val glucoseRecords = glucoseDao.getRecent(10000)
            val mealRecords = mealDao.getRecent(1000)
            val insulinRecords = insulinDao.getRecent(1000)
            val exerciseRecords = exerciseDao.getRecent(1000)
            val alertRecords = alertDao.getRecent(500)

            // 获取所有饮食明细
            val mealItems = mutableListOf<MealItem>()
            for (meal in mealRecords) {
                mealItems.addAll(mealDao.getItemsByMealId(meal.id))
            }

            val backupData = BackupData(
                backupTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                glucoseRecords = glucoseRecords,
                mealRecords = mealRecords,
                mealItems = mealItems,
                insulinRecords = insulinRecords,
                exerciseRecords = exerciseRecords,
                alertRecords = alertRecords
            )

            val json = gson.toJson(backupData)
            val fileName = "tangdun_backup_${dateFormat.format(Date())}.json"
            val file = getBackupFile(fileName)

            FileWriter(file).use { writer ->
                writer.write(json)
            }

            Result.success(file.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 恢复数据
     *
     * @param filePath 备份文件路径
     * @return 恢复的记录数
     */
    suspend fun restore(filePath: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext Result.failure(Exception("备份文件不存在"))
            }

            val json = file.readText()
            val backupData = gson.fromJson(json, BackupData::class.java)

            var count = 0

            // 恢复血糖记录
            for (record in backupData.glucoseRecords) {
                glucoseDao.insert(record)
                count++
            }

            // 恢复饮食记录
            for (record in backupData.mealRecords) {
                mealDao.insert(record)
            }
            for (item in backupData.mealItems) {
                mealDao.insertItem(item)
            }

            // 恢复胰岛素记录
            for (record in backupData.insulinRecords) {
                insulinDao.insert(record)
            }

            // 恢复运动记录
            for (record in backupData.exerciseRecords) {
                exerciseDao.insert(record)
            }

            // 恢复预警记录
            for (record in backupData.alertRecords) {
                alertDao.insert(record)
            }

            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取备份文件列表
     */
    fun getBackupFiles(): List<File> {
        val dir = getBackupDir()
        return dir.listFiles { file -> file.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * 删除备份文件
     */
    fun deleteBackup(fileName: String): Boolean {
        val file = getBackupFile(fileName)
        return file.delete()
    }

    /**
     * 获取备份目录
     */
    private fun getBackupDir(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "TangDun/backup")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 获取备份文件
     */
    private fun getBackupFile(fileName: String): File {
        return File(getBackupDir(), fileName)
    }

    /**
     * 获取备份文件大小
     */
    fun getBackupSize(fileName: String): Long {
        return getBackupFile(fileName).length()
    }

    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}
