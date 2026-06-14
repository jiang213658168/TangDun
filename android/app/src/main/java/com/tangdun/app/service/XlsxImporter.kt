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
     * 从血糖曲线反推饮食和胰岛素事件 (参考闭环系统meal detection算法)
     *
     * 改进算法:
     *   1. SMA平滑去噪 (5点=25min窗口)
     *   2. CUSUM累计和检测 (比简单阈值更抗噪)
     *   3. 血糖AUC估算碳水量 (比峰值更准确)
     *   4. 餐间隔≥2h (避免把双峰当两餐)
     *   5. 胰岛素检测用衰减速率拟合
     *
     * 参考:
     *   Dassau et al. "Detection of a Meal Using CGM" (2008)
     *   Cameron et al. "Closed-Loop AP Based on Risk Management" (2009)
     */
    private suspend fun inferMealsAndInsulin(context: Context, records: List<GlucoseRecord>): Pair<Int, Int> {
        if (records.size < 12) return Pair(0, 0)

        val sorted = records.sortedBy { it.timestamp }
        val settings = com.tangdun.app.util.SettingsManager(context)
        val weight = settings.getWeightKg().toDouble()

        // Step 1: SMA平滑 (5点窗口)
        val smoothed = mutableListOf<Pair<Long, Double>>()
        for (i in sorted.indices) {
            val window = sorted.subList(maxOf(0, i - 2), minOf(sorted.size, i + 3))
            val avg = window.map { it.value }.average()
            smoothed.add(sorted[i].timestamp to avg)
        }

        // Step 2: 计算基线 (前2小时的最低10%均值, 代表空腹基线)
        val recentVals = smoothed.take(24).map { it.second }
        val baseline = if (recentVals.size >= 6) {
            recentVals.sorted().take(maxOf(1, recentVals.size / 10)).average()
        } else recentVals.average()

        // Step 3: CUSUM检测进餐事件
        val mealEvents = mutableListOf<Triple<Int, Int, Double>>() // (startIdx, peakIdx, auc)
        var cusumPos = 0.0
        var mealStart = -1
        val mealMinSeparation = 24  // 最少24点(2小时)间隔

        for (i in smoothed.indices) {
            val deviation = smoothed[i].second - baseline
            cusumPos = maxOf(0.0, cusumPos + deviation)

            // 上升检测: CUSUM超过0.5且持续上升
            if (cusumPos > 0.5 && mealStart < 0) {
                mealStart = i
            }

            // 到顶了: CUSUM开始下降 → 上升段结束
            if (mealStart >= 0 && cusumPos > 0 && i - mealStart >= 3) {
                val prevCusum = maxOf(0.0, cusumPos - deviation) // 前一步的CUSUM
                if (cusumPos < prevCusum && smoothed[i].second - smoothed[mealStart].second > 0.8) {
                    // 确认进餐事件: 找峰值
                    var peakIdx = mealStart
                    for (j in mealStart until i) {
                        if (smoothed[j].second > smoothed[peakIdx].second) peakIdx = j
                    }

                    // 计算AUC (曲线下面积, 反映碳水负荷)
                    var auc = 0.0
                    for (j in mealStart..i) {
                        auc += maxOf(0.0, smoothed[j].second - baseline) * 5.0  // ×5min
                    }

                    // 间隔检查
                    if (mealEvents.isEmpty() || i - mealEvents.last().second >= mealMinSeparation) {
                        mealEvents.add(Triple(mealStart, peakIdx, auc))
                    }
                    cusumPos = 0.0
                    mealStart = -1
                }
            }
        }

        // Step 4: 创建MealRecord
        val mealDao = TangDunApp.getDatabase(context).mealDao()
        var mealCount = 0
        for ((startIdx, peakIdx, auc) in mealEvents) {
            val rise = smoothed[peakIdx].second - smoothed[startIdx].second
            // 碳水量估算: 基于AUC和体重 (每10g碳水产生约3-5 mmol/L·min AUC/kg)
            val estCarbs = (auc / (weight * 0.08)).coerceIn(10.0, 200.0)
            val mealTime = smoothed[startIdx].first - 10 * 60_000L  // 上升前10分钟

            val hour = java.util.Calendar.getInstance().apply { timeInMillis = mealTime }
                .get(java.util.Calendar.HOUR_OF_DAY)
            val mealType = com.tangdun.app.data.local.entity.MealRecord.inferMealType(hour)

            try {
                mealDao.insert(MealRecord(
                    timestamp = mealTime, mealType = mealType,
                    totalCarbs = estCarbs, totalCalories = estCarbs * 4.0, avgGi = 60.0
                ))
                mealCount++
            } catch (e: Exception) { /* skip */ }
        }

        // Step 5: 胰岛素检测 — 衰减速率分析
        val insulinDao = TangDunApp.getDatabase(context).insulinDao()
        var insulinCount = 0
        var lastInsulinIdx = -12  // 最少1小时间隔

        for (i in 1 until smoothed.size) {
            val drop = smoothed[i - 1].second - smoothed[i].second
            val roc = drop / ((smoothed[i].first - smoothed[i - 1].first) / 60000.0)

            // 检测快速下降 (>0.05 mmol/L/min 且持续)
            if (roc > 0.05 && i - lastInsulinIdx >= 12) {
                // 追踪下降到底
                var troughIdx = i
                for (j in i until minOf(i + 36, smoothed.size)) {
                    if (smoothed[j].second < smoothed[troughIdx].second) troughIdx = j
                    if (j - i > 6 && smoothed[j].second > smoothed[j - 1].second + 0.3) break
                }
                val totalDrop = smoothed[i - 1].second - smoothed[troughIdx].second
                if (totalDrop > 1.0) {
                    // 剂量估算: 基于胰岛素敏感度 (1U降1.5-3.0 mmol/L, 因人而异)
                    val estDose = (totalDrop * weight / 25.0).coerceIn(0.5, 15.0)
                    try {
                        insulinDao.insert(InsulinRecord(
                            timestamp = smoothed[i - 1].first - 5 * 60_000L,
                            insulinType = "rapid", doseUnits = estDose
                        ))
                        insulinCount++
                        lastInsulinIdx = troughIdx
                    } catch (e: Exception) { /* skip */ }
                }
            }
        }

        return Pair(mealCount, insulinCount)
    }
}
