package com.tangdun.app.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tangdun.app.TangDunApp
import com.tangdun.app.data.local.entity.GlucoseRecord
import com.tangdun.app.data.local.entity.InsulinRecord
import com.tangdun.app.data.local.entity.MealRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 欧态CGM Excel(.xlsx)导入器
 *
 * 支持格式: 欧态健康App导出Excel
 *   列A: 时间 (YYYY.M.D HH:MM)
 *   列B: 血糖值 (mmol/L)
 *
 * 实现: 直接解析xlsx内部XML (ZIP→sharedStrings.xml + sheet1.xml)
 *   无需Apache POI等第三方库
 */
object XlsxImporter {

    private const val TAG = "XlsxImporter"

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
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult(0, 0, 0, listOf("无法打开文件"))
            val records = parseXlsx(inputStream)
            inputStream.close()

            if (records.isEmpty()) {
                return@withContext ImportResult(0, 0, 0, listOf("未解析到血糖数据"))
            }

            total = records.size

            // 批量去重写入
            val dao = TangDunApp.getDatabase(context).glucoseDao()
            val existing = dao.getByTimeRange(
                records.first().timestamp, records.last().timestamp
            ).map { it.timestamp to it.value }.toSet()

            records.forEach { r ->
                if (existing.none { (t, v) ->
                    kotlin.math.abs(t - r.timestamp) < 120_000 &&
                    kotlin.math.abs(v - r.value) < 0.1
                }) {
                    dao.insert(r)
                    imported++
                } else {
                    skipped++
                }
            }

            // 从血糖曲线反推饮食和胰岛素事件
            val inferred = inferMealsAndInsulin(context, records)
            Log.i(TAG, "推断事件: ${inferred.first}餐 ${inferred.second}针")

            ImportResult(total, imported, skipped, errors)
        } catch (e: Exception) {
            Log.e(TAG, "导入失败: ${e.message}", e)
            ImportResult(total, imported, skipped, errors + listOf("${e.message}"))
        }
    }

    private fun parseXlsx(inputStream: InputStream): List<GlucoseRecord> {
        val zipStream = ZipInputStream(inputStream)
        var sharedStringsXml: ByteArray? = null
        var sheetXml: ByteArray? = null

        // 遍历ZIP条目，提取sharedStrings和sheet1
        var entry: ZipEntry? = zipStream.nextEntry
        while (entry != null) {
            when {
                entry.name.equals("xl/sharedStrings.xml", ignoreCase = true) ->
                    sharedStringsXml = zipStream.readBytes()
                entry.name.equals("xl/worksheets/sheet1.xml", ignoreCase = true) ->
                    sheetXml = zipStream.readBytes()
            }
            zipStream.closeEntry()
            entry = zipStream.nextEntry
        }
        zipStream.close()

        if (sheetXml == null) return emptyList()

        // 解析共享字符串表
        val sharedStrings = parseSharedStrings(sharedStringsXml)

        // 解析sheet数据
        return parseSheetData(sheetXml, sharedStrings)
    }

    private fun parseSharedStrings(xml: ByteArray?): List<String> {
        if (xml == null) return emptyList()
        val result = mutableListOf<String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(xml.inputStream(), "UTF-8")

            var event = parser.eventType
            var currentText = StringBuilder()
            var inSi = false
            var inT = false

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "si" -> { inSi = true; currentText = StringBuilder() }
                            "t" -> inT = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inT) currentText.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "t" -> inT = false
                            "si" -> {
                                inSi = false
                                result.add(currentText.toString())
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析sharedStrings失败: ${e.message}")
        }
        return result
    }

    private fun parseSheetData(xml: ByteArray, sharedStrings: List<String>): List<GlucoseRecord> {
        val records = mutableListOf<GlucoseRecord>()
        // 时间格式: 2026.6.15 07:09 (M.d可能是一位或两位)
        val dateFormat = SimpleDateFormat("yyyy.M.d HH:mm", Locale.getDefault())

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(xml.inputStream(), "UTF-8")

            var event = parser.eventType
            var currentRow = mutableListOf<String>()
            var inRow = false
            var inCell = false
            var cellType = ""  // "s"=sharedString, "n"=number, ""=inline
            var cellText = StringBuilder()

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "row" -> { inRow = true; currentRow = mutableListOf() }
                            "c" -> { inCell = true; cellText = StringBuilder()
                                cellType = parser.getAttributeValue(null, "t") ?: "" }
                            "v" -> { /* value element, text follows */ }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inCell) cellText.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "v" -> { /* done reading value */ }
                            "c" -> {
                                inCell = false
                                val raw = cellText.toString()
                                // 如果单元格类型是"shared string", 用索引查表
                                val value = if (cellType == "s") {
                                    val idx = raw.toIntOrNull()
                                    if (idx != null && idx < sharedStrings.size) sharedStrings[idx] else raw
                                } else raw
                                currentRow.add(value)
                            }
                            "row" -> {
                                inRow = false
                                // 解析行数据: col0=时间, col1=血糖值
                                if (currentRow.size >= 2) {
                                    val timeStr = currentRow[0]
                                    val valueStr = currentRow[1]
                                    val glucose = valueStr.toDoubleOrNull()
                                    if (glucose != null && glucose in 1.0..33.0) {
                                        try {
                                            val timestamp = dateFormat.parse(timeStr)?.time
                                            if (timestamp != null) {
                                                records.add(GlucoseRecord(
                                                    timestamp = timestamp,
                                                    value = glucose,
                                                    source = "xlsx_import"
                                                ))
                                            }
                                        } catch (e: Exception) {
                                            // 跳过无法解析的时间
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析sheet失败: ${e.message}", e)
        }

        return records
    }

    /**
     * 从血糖曲线反推饮食和胰岛素事件
     *
     * 原理:
     *   持续上升(>1.0 mmol/L in 30min) → 推断进食
     *   快速下降(<-1.5 mmol/L in 30min) → 推断胰岛素注射
     *
     * 估算公式:
     *   碳水(g) ≈ 升幅(mmol/L) × 体重(kg) × 0.5
     *              (约每10g碳水升0.3-0.5 mmol/L, 取决于体重)
     *   胰岛素(U) ≈ 降幅(mmol/L) × 体重(kg) / 30
     *              (约1U降1.5-3.0 mmol/L, 取决于敏感度)
     */
    private suspend fun inferMealsAndInsulin(context: Context, records: List<GlucoseRecord>): Pair<Int, Int> {
        if (records.size < 12) return Pair(0, 0)  // 至少1小时数据

        val sorted = records.sortedBy { it.timestamp }
        val settings = com.tangdun.app.util.SettingsManager(context)
        val weight = settings.getWeightKg().toDouble()

        val mealDao = TangDunApp.getDatabase(context).mealDao()
        val insulinDao = TangDunApp.getDatabase(context).insulinDao()

        var mealCount = 0
        var insulinCount = 0

        // 滑动窗口检测 (窗口大小6点=30min, 步长1点=5min)
        val windowSize = 6
        var i = 0
        while (i + windowSize < sorted.size) {
            val start = sorted[i]
            val end = sorted[i + windowSize]
            val timeDiff = (end.timestamp - start.timestamp) / 60000.0  // 分钟
            if (timeDiff <= 0) { i++; continue }
            val change = end.value - start.value
            val roc = change / timeDiff  // mmol/L/min

            when {
                // 持续上升 → 推断进食
                change > 1.2 && roc > 0.03 -> {
                    // 找上升段的峰值和起点的差值
                    var peak = end
                    var j = i + windowSize
                    while (j + 3 < sorted.size) {
                        val next = sorted[j + 3]
                        if (next.value < sorted[j].value - 0.2) break
                        if (next.value > peak.value) peak = next
                        j += 3
                    }
                    val totalRise = peak.value - start.value
                    val estCarbs = (totalRise * weight * 0.5).coerceIn(10.0, 200.0)

                    // 推断进餐时间为上升起点前15分钟
                    val mealTime = start.timestamp - 15 * 60_000L
                    val hour = java.util.Calendar.getInstance().apply { timeInMillis = mealTime }
                        .get(java.util.Calendar.HOUR_OF_DAY)
                    val mealType = com.tangdun.app.data.local.entity.MealRecord.inferMealType(hour)

                    try {
                        mealDao.insert(MealRecord(
                            timestamp = mealTime,
                            mealType = mealType,
                            totalCarbs = estCarbs,
                            totalCalories = estCarbs * 4.0,  // 碳水×4kcal
                            avgGi = 60.0
                        ))
                        mealCount++
                    } catch (e: Exception) { /* skip */ }

                    i = j  // 跳过已处理的区间
                }

                // 快速下降 → 推断胰岛素注射
                change < -1.5 && roc < -0.03 -> {
                    var trough = end
                    var j = i + windowSize
                    while (j + 3 < sorted.size) {
                        val next = sorted[j + 3]
                        if (next.value > sorted[j].value + 0.2) break
                        if (next.value < trough.value) trough = next
                        j += 3
                    }
                    val totalDrop = start.value - trough.value
                    val estDose = (totalDrop * weight / 30).coerceIn(0.5, 20.0)

                    // 推断注射时间为下降起点前10分钟
                    val insulinTime = start.timestamp - 10 * 60_000L

                    try {
                        insulinDao.insert(InsulinRecord(
                            timestamp = insulinTime,
                            insulinType = "rapid",
                            doseUnits = estDose
                        ))
                        insulinCount++
                    } catch (e: Exception) { /* skip */ }

                    i = j
                }

                else -> i++
            }
        }

        return Pair(mealCount, insulinCount)
    }
}
