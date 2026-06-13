package com.tangdun.app.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tangdun.app.data.local.entity.GlucoseRecord
import com.tangdun.app.data.local.entity.MealRecord
import com.tangdun.app.data.local.entity.InsulinRecord
import java.text.SimpleDateFormat
import java.util.*

/**
 * 数据分享服务
 *
 * 参考xDrip+的数据分享功能：
 * - 分享血糖数据给家人/医生
 * - 生成分享文本/图片
 * - 支持多种分享方式
 */
class DataShareService(private val context: Context) {

    companion object {
        private const val TAG = "DataShare"
    }

    /**
     * 分享血糖报告
     */
    fun shareGlucoseReport(
        glucoseRecords: List<GlucoseRecord>,
        mealRecords: List<MealRecord> = emptyList(),
        insulinRecords: List<InsulinRecord> = emptyList()
    ) {
        val report = generateReport(glucoseRecords, mealRecords, insulinRecords)
        shareText("糖盾血糖报告", report)
    }

    /**
     * 生成报告文本
     */
    private fun generateReport(
        glucoseRecords: List<GlucoseRecord>,
        mealRecords: List<MealRecord>,
        insulinRecords: List<InsulinRecord>
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val dateFormatShort = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════")
        sb.appendLine("        糖盾 - 血糖报告")
        sb.appendLine("═══════════════════════════════")
        sb.appendLine()

        // 血糖统计
        if (glucoseRecords.isNotEmpty()) {
            val values = glucoseRecords.map { it.value }
            val avg = values.average()
            val min = values.min()
            val max = values.max()
            val tir = values.count { it in 3.9..10.0 }.toDouble() / values.size * 100

            sb.appendLine("【血糖统计】")
            sb.appendLine("数据点: ${glucoseRecords.size}个")
            sb.appendLine("平均血糖: ${String.format("%.1f", avg)} mmol/L")
            sb.appendLine("最低/最高: ${String.format("%.1f", min)} / ${String.format("%.1f", max)} mmol/L")
            sb.appendLine("TIR: ${String.format("%.0f", tir)}%")
            sb.appendLine()

            // 最近血糖
            sb.appendLine("【最近血糖】")
            glucoseRecords.takeLast(5).forEach { record ->
                sb.appendLine("  ${dateFormatShort.format(Date(record.timestamp))}  ${String.format("%.1f", record.value)} mmol/L")
            }
            sb.appendLine()
        }

        // 饮食记录
        if (mealRecords.isNotEmpty()) {
            sb.appendLine("【饮食记录】")
            mealRecords.takeLast(5).forEach { meal ->
                sb.appendLine("  ${dateFormatShort.format(Date(meal.timestamp))}  ${MealRecord.getMealTypeName(meal.mealType)}")
                sb.appendLine("    碳水: ${String.format("%.0f", meal.totalCarbs)}g  热量: ${String.format("%.0f", meal.totalCalories)}kcal")
            }
            sb.appendLine()
        }

        // 胰岛素记录
        if (insulinRecords.isNotEmpty()) {
            sb.appendLine("【胰岛素记录】")
            insulinRecords.takeLast(5).forEach { record ->
                sb.appendLine("  ${dateFormatShort.format(Date(record.timestamp))}  ${InsulinRecord.getInsulinTypeName(record.insulinType)} ${String.format("%.1f", record.doseUnits)}U")
            }
            sb.appendLine()
        }

        sb.appendLine("═══════════════════════════════")
        sb.appendLine("由糖盾App生成")

        return sb.toString()
    }

    /**
     * 分享文本
     */
    private fun shareText(title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "分享报告"))
    }
}
