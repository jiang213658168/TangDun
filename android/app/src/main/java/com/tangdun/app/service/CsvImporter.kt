package com.tangdun.app.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tangdun.app.TangDunApp
import com.tangdun.app.data.local.entity.GlucoseRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

/**
 * xDrip+ CSV 数据导入器
 *
 * 支持格式:
 * - xDrip+ 导出 CSV: "Date,Timestamp,Reading,Time,Unit"
 * - 简化格式: "时间(HH:mm),血糖值(mmol/L)"
 * - 泛格式: 自动检测时间列和血糖列
 */
object CsvImporter {

    private const val TAG = "CsvImporter"

    data class ImportResult(
        val total: Int,
        val imported: Int,
        val skipped: Int,
        val errors: List<String>
    )

    suspend fun importFromUri(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        var total = 0
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        try {
            val records = mutableListOf<GlucoseRecord>()
            val reader = BufferedReader(InputStreamReader(context.contentResolver.openInputStream(uri) ?: return@withContext ImportResult(0, 0, 0, listOf("无法打开文件"))))

            // 读表头
            val header = reader.readLine() ?: return@withContext ImportResult(0, 0, 0, listOf("文件为空"))
            val columns = header.split(",", "\t").map { it.trim().lowercase() }
            val timeIdx = columns.indexOfFirst { it.contains("time") || it.contains("时间") || it.contains("date") || it.contains("日期") }
            val valueIdx = columns.indexOfFirst { it.contains("glucose") || it.contains("reading") || it.contains("血糖") || it.contains("sgv") || it.contains("value") || it.contains("值") }

            if (valueIdx < 0) {
                return@withContext ImportResult(0, 0, 0, listOf("找不到血糖列，表头: $header"))
            }

            reader.forEachLine { line ->
                total++
                try {
                    val parts = line.split(",", "\t").map { it.trim().filter { c -> c != '"' } }
                    val valueStr = parts.getOrNull(valueIdx) ?: return@forEachLine

                    // 解析血糖值
                    val rawValue = valueStr.toDoubleOrNull() ?: return@forEachLine
                    val glucoseMmol = if (rawValue > 30) rawValue / 18.0 else rawValue
                    if (glucoseMmol !in 1.0..33.0) return@forEachLine

                    // 解析时间
                    val timestamp = if (timeIdx >= 0) {
                        parseTimestamp(parts[timeIdx])
                    } else {
                        System.currentTimeMillis()
                    }

                    records.add(GlucoseRecord(timestamp = timestamp, value = glucoseMmol, source = "csv_import"))
                    imported++
                } catch (e: Exception) {
                    skipped++
                    if (errors.size < 10) errors.add("行$total: ${e.message}")
                }
            }
            reader.close()

            // 批量去重写入（60秒内同值跳过）
            val dao = TangDunApp.getDatabase(context).glucoseDao()
            val existing = dao.getRecent(10000).map { it.timestamp to it.value }.toSet()
            var dupes = 0
            records.sortedBy { it.timestamp }.forEach { r ->
                if (existing.none { (t, v) -> kotlin.math.abs(t - r.timestamp) < 60000 && kotlin.math.abs(v - r.value) < 0.1 }) {
                    dao.insert(r)
                } else { dupes++ }
            }

            val finalImported = imported - dupes
            val finalSkipped = skipped + dupes
            Log.i(TAG, "Import done: $finalImported imported, $finalSkipped skipped, $dupes dupes")
            ImportResult(total, finalImported, finalSkipped, errors)
        } catch (e: Exception) {
            Log.e(TAG, "导入失败: ${e.message}", e)
            ImportResult(total, imported, skipped, errors + listOf("${e.message}"))
        }
    }

    // 时间格式（带日期 vs 仅时间）
    private val dateFormats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm", "MM-dd HH:mm:ss", "MM/dd HH:mm:ss")
    private val timeFormats = listOf("HH:mm:ss", "HH:mm")

    private fun parseTimestamp(s: String): Long {
        // Unix 时间戳（毫秒，单位 >2000年)
        s.toLongOrNull()?.let { if (it > 946684800000) return it }
        // Unix 时间戳（秒，转为毫秒）
        s.toLongOrNull()?.let { if (it > 946684800) return it * 1000 }

        val now = Calendar.getInstance()

        // 尝试带日期的格式
        for (fmt in dateFormats) {
            try {
                val sf = SimpleDateFormat(fmt, Locale.US)
                return sf.parse(s)?.time ?: continue
            } catch (_: Exception) {}
        }

        // 尝试仅时间格式 → 假设是今天
        for (fmt in timeFormats) {
            try {
                val sf = SimpleDateFormat(fmt, Locale.US)
                val parsed = sf.parse(s) ?: continue
                val cal = Calendar.getInstance()
                cal.time = parsed  // 拿到 1970-01-01 HH:mm
                // 合并为今天的日期+解析的时间
                cal.set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH))
                return cal.timeInMillis
            } catch (_: Exception) {}
        }

        return System.currentTimeMillis()
    }
}
