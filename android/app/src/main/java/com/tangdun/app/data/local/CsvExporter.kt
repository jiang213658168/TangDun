package com.tangdun.app.data.local

import android.content.Context
import android.os.Environment
import com.tangdun.app.data.local.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * CSV导出器
 *
 * 导出所有数据为CSV格式，与后端DataExporter功能一致
 */
class CsvExporter(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dateFormatFile = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /** CSV字段转义：含逗号/双引号/换行时用双引号包裹并转义内部双引号 */
    private fun csvEscape(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }

    /**
     * 导出血糖数据
     */
    suspend fun exportGlucose(records: List<GlucoseRecord>): File? = withContext(Dispatchers.IO) {
        try {
            val file = createExportFile("glucose_${dateFormatFile.format(Date())}.csv")
            FileWriter(file).use { writer ->
                writer.write("﻿")  // BOM for Excel
                writer.write("时间,血糖值(mmol/L),趋势,来源,场景\n")
                for (record in records) {
                    writer.write("${dateFormat.format(Date(record.timestamp))},${record.value},${record.trend ?: ""},${record.source},${record.scene}\n")
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 导出饮食数据
     */
    suspend fun exportMeal(records: List<MealRecord>, items: Map<Long, List<MealItem>>): File? = withContext(Dispatchers.IO) {
        try {
            val file = createExportFile("meal_${dateFormatFile.format(Date())}.csv")
            FileWriter(file).use { writer ->
                writer.write("﻿")
                writer.write("时间,餐型,食物名称,份量(g),碳水(g),热量(kcal),蛋白质(g),脂肪(g),GI\n")
                for (record in records) {
                    val mealType = MealRecord.getMealTypeName(record.mealType)
                    val mealItems = items[record.id] ?: emptyList()
                    for (item in mealItems) {
                        writer.write("${dateFormat.format(Date(record.timestamp))},$mealType,${csvEscape(item.foodName)},${item.portionGrams},${item.carbs},${item.calories},${item.protein},${item.fat},${item.gi}\n")
                    }
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 导出胰岛素数据
     */
    suspend fun exportInsulin(records: List<InsulinRecord>): File? = withContext(Dispatchers.IO) {
        try {
            val file = createExportFile("insulin_${dateFormatFile.format(Date())}.csv")
            FileWriter(file).use { writer ->
                writer.write("﻿")
                writer.write("时间,类型,剂量(U),注射部位,备注\n")
                for (record in records) {
                    writer.write("${dateFormat.format(Date(record.timestamp))},${InsulinRecord.getInsulinTypeName(record.insulinType)},${record.doseUnits},${csvEscape(InsulinRecord.getInjectionSiteName(record.injectionSite))},${csvEscape(record.notes ?: "")}\n")
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 导出完整数据
     */
    suspend fun exportAll(
        glucoseRecords: List<GlucoseRecord>,
        mealRecords: List<MealRecord>,
        mealItems: Map<Long, List<MealItem>>,
        insulinRecords: List<InsulinRecord>,
        exerciseRecords: List<ExerciseRecord>
    ): File? = withContext(Dispatchers.IO) {
        try {
            val file = createExportFile("tangdun_full_${dateFormatFile.format(Date())}.csv")
            FileWriter(file).use { writer ->
                writer.write("﻿")

                // 血糖数据
                writer.write("=== 血糖数据 ===\n")
                writer.write("时间,血糖值(mmol/L),来源\n")
                for (record in glucoseRecords) {
                    writer.write("${dateFormat.format(Date(record.timestamp))},${record.value},${record.source}\n")
                }
                writer.write("\n")

                // 饮食数据
                writer.write("=== 饮食数据 ===\n")
                writer.write("时间,餐型,食物,碳水(g),热量(kcal)\n")
                for (record in mealRecords) {
                    val items = mealItems[record.id] ?: emptyList()
                    for (item in items) {
                        writer.write("${dateFormat.format(Date(record.timestamp))},${MealRecord.getMealTypeName(record.mealType)},${item.foodName},${item.carbs},${item.calories}\n")
                    }
                }
                writer.write("\n")

                // 胰岛素数据
                writer.write("=== 胰岛素数据 ===\n")
                writer.write("时间,类型,剂量(U)\n")
                for (record in insulinRecords) {
                    writer.write("${dateFormat.format(Date(record.timestamp))},${InsulinRecord.getInsulinTypeName(record.insulinType)},${record.doseUnits}\n")
                }
                writer.write("\n")

                // 运动数据
                writer.write("=== 运动数据 ===\n")
                writer.write("时间,类型,时长(分钟),步数\n")
                for (record in exerciseRecords) {
                    writer.write("${dateFormat.format(Date(record.startTime))},${ExerciseRecord.getExerciseTypeName(record.exerciseType)},${record.durationMin ?: 0},${record.steps ?: 0}\n")
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 生成日报告文本
     */
    fun generateDailyReportText(
        date: Long,
        glucoseRecords: List<GlucoseRecord>,
        totalCarbs: Double,
        totalCalories: Double,
        totalSteps: Int,
        tir: Double,
        avgGlucose: Double,
        hba1c: Double
    ): String {
        val dateStr = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault()).format(Date(date))

        return buildString {
            appendLine("╔═══════════════════════════════════════╗")
            appendLine("║         糖盾 - 每日血糖报告          ║")
            appendLine("╚═══════════════════════════════════════╝")
            appendLine()
            appendLine("报告日期: $dateStr")
            appendLine("数据点数: ${glucoseRecords.size}个")
            appendLine()
            appendLine("┌─────────────────────────────────────┐")
            appendLine("│           血糖统计                   │")
            appendLine("├─────────────────────────────────────┤")
            appendLine("│ 平均血糖: ${String.format("%.1f", avgGlucose)} mmol/L")
            appendLine("│ HbA1c估算: ${String.format("%.1f", hba1c)}%")
            appendLine("│ TIR(目标范围内): ${String.format("%.1f", tir)}%")
            appendLine("└─────────────────────────────────────┘")
            appendLine()
            appendLine("┌─────────────────────────────────────┐")
            appendLine("│           今日摄入                   │")
            appendLine("├─────────────────────────────────────┤")
            appendLine("│ 总碳水: ${String.format("%.1f", totalCarbs)}g")
            appendLine("│ 总热量: ${String.format("%.0f", totalCalories)}kcal")
            appendLine("│ 总步数: ${totalSteps}步")
            appendLine("└─────────────────────────────────────┘")
        }
    }

    private fun createExportFile(filename: String): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "TangDun/export")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, filename)
    }
}
