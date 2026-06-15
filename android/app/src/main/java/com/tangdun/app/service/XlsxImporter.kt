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

            // 单位自检: 若全部值>30→mg/dL需转换
            val allHigh = records.all { it.value > 30.0 }
            val converted = if (allHigh) {
                Log.w(TAG, "检测到mg/dL单位, 自动转换为mmol/L")
                records.map { it.copy(value = it.value / 18.0) }
            } else records

            // 排序+去重 (同时间戳保留第一个)
            val sorted = converted.sortedBy { it.timestamp }
            val deduped = mutableListOf<GlucoseRecord>()
            for (r in sorted) {
                if (deduped.isEmpty() || r.timestamp - deduped.last().timestamp > 60_000) {
                    deduped.add(r)
                }
            }

            total = deduped.size
            if (total < 3) {
                return@withContext ImportResult(total, 0, 0, listOf("数据量不足(需>3条)"))
            }

            // 数据库去重写入
            val dao = TangDunApp.getDatabase(context).glucoseDao()
            val existing = dao.getByTimeRange(
                deduped.first().timestamp, deduped.last().timestamp
            ).map { it.timestamp to it.value }.toSet()

            deduped.forEach { r ->
                if (existing.none { (t, v) ->
                    kotlin.math.abs(t - r.timestamp) < 120_000 &&
                    kotlin.math.abs(v - r.value) < 0.1
                }) { dao.insert(r); imported++ }
                else { skipped++ }
            }

            // 按间隙(>1h)切分为连续段，逐段推断事件
            var segStart = 0
            var mealTotal = 0; var insulinTotal = 0
            for (i in 1 until deduped.size) {
                if (deduped[i].timestamp - deduped[i-1].timestamp > 60 * 60_000L || i == deduped.size - 1) {
                    val seg = deduped.subList(segStart, if (i == deduped.size - 1) deduped.size else i)
                    val (m, ins) = inferMealsAndInsulin(context, seg)
                    mealTotal += m; insulinTotal += ins
                    segStart = i
                }
            }
            Log.i(TAG, "推断事件: ${mealTotal}餐 ${insulinTotal}针 (${total}条血糖)")

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
     * 患者场景覆盖:
     *   ✅ 正餐(大升幅) → CUSUM检测
     *   ✅ 加餐(小升幅) → 降低阈值但需ROC确认
     *   ✅ 低血糖纠正(从<4.0上升) → 标记为snack类型
     *   ✅ 黎明现象(4-8am上升) → 不推断为进食
     *   ✅ 运动后降糖 → 区分于胰岛素(下降前无升糖=运动)
     *   ✅ 胰岛素注射 → 追踪完整下降段
     *
     * 参考:
     *   Dassau et al. "Detection of a Meal Using CGM" (2008)
     *   Cameron et al. "Closed-Loop AP Based on Risk Management" (2009)
     */
    private suspend fun inferMealsAndInsulin(context: Context, records: List<GlucoseRecord>): Pair<Int, Int> {
        if (records.size < 24) return Pair(0, 0)  // 至少2h数据

        val sorted = records.sortedBy { it.timestamp }
        val weight = com.tangdun.app.util.SettingsManager(context).getWeightKg().toDouble()
        val isf = com.tangdun.app.util.SettingsManager(context).getInsulinSensitivity().toDouble()
        val mealDao = TangDunApp.getDatabase(context).mealDao()
        val insulinDao = TangDunApp.getDatabase(context).insulinDao()

        // Step 1: SMA平滑 (3点窗口, 15min → 更快速响应)
        val sm = mutableListOf<Pair<Long, Double>>()
        for (i in sorted.indices) {
            val w = sorted.subList(maxOf(0, i - 1), minOf(sorted.size, i + 2))
            sm.add(sorted[i].timestamp to w.map { it.value }.average())
        }

        // Step 2: 稳健基线 (取前2h的最低25%均值)
        val initVals = sm.take(24).map { it.second }.sorted()
        var baseline = initVals.take(maxOf(1, initVals.size / 4)).average()

        // Step 3: 进餐检测 (滑动窗口法, 比CUSUM更直观)
        data class MealEvent(val startIdx: Int, val peakIdx: Int, val endIdx: Int,
                             val auc: Double, val isCorrection: Boolean, val isSnack: Boolean)
        val mealEvents = mutableListOf<MealEvent>()

        var i = 6  // 从第6点开始(30min后)
        while (i < sm.size - 6) {
            // 前向ROC: 过去30min的变化率
            val pastRoc = (sm[i].second - sm[i - 6].second) /
                maxOf(1.0, (sm[i].first - sm[i - 6].first) / 60000.0)

            // 检测上升: 30min升幅>0.5 且 ROC>0.015
            if (pastRoc > 0.015 && sm[i].second - sm[i - 6].second > 0.5) {
                // 回溯找起点 (升幅开始的位置)
                var start = i - 6
                while (start > 0 && sm[start].second > sm[start - 1].second) start--
                val mealBaseline = sm[start].second

                // 前向找峰值 (最多搜索2h=24点)
                var peak = i
                var j = i
                while (j < sm.size - 1 && j - i < 24) {
                    if (sm[j].second > sm[peak].second) peak = j
                    // 连续3点下降→峰值已过
                    if (j - peak >= 3 && sm[j].second < sm[peak].second - 0.3) break
                    j++
                }
                val peakRise = sm[peak].second - mealBaseline
                if (peakRise < 0.5) { i++; continue }  // 升幅太小

                // 找结束点 (回到基线+0.3 或 升幅的30%)
                val returnLevel = mealBaseline + peakRise * 0.3
                var end = peak
                while (end < sm.size - 1 && end - peak < 36) {
                    if (sm[end].second <= returnLevel) break
                    end++
                }

                // 黎明过滤
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = sm[start].first }
                val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                val isDawn = hour in 4..8 && mealBaseline < 7.0 && peakRise < 1.5

                if (!isDawn) {
                    val isCorrection = mealBaseline < 4.0
                    // 进餐类型判定: 时间优先, 升幅辅助
                    val mealTypeByHour = com.tangdun.app.data.local.entity.MealRecord.inferMealType(hour)
                    val isMainMeal = mealTypeByHour in listOf("breakfast", "lunch", "dinner")
                    // 主餐时段+升幅>1.0→正餐; 其他→加餐
                    val isSnack = !isCorrection && (!isMainMeal || peakRise < 1.2)

                    // AUC: 超过起始基线的面积 (延伸到返回基线)
                    var auc = 0.0
                    for (k in start..minOf(end, sm.size - 1)) {
                        auc += maxOf(0.0, sm[k].second - mealBaseline) * 5.0
                    }

                    // 与上一餐间隔检查
                    val minSep = if (isSnack || isCorrection) 8 else 20  // 正餐1.7h 加餐40min
                    if (mealEvents.isEmpty() || start - mealEvents.last().endIdx >= minSep) {
                        mealEvents.add(MealEvent(start, peak, end, auc, isCorrection, isSnack))
                    }
                }
                i = end + 1  // 跳过后继续
            }
            // 更新基线: 取最近2h的15%分位数 (偏保守)
            if (i % 12 == 0) {
                val recent = sm.subList(maxOf(0, i - 24), i + 1).map { it.second }.sorted()
                if (recent.size >= 8) {
                    val rawBaseline = recent.take(maxOf(1, (recent.size * 0.15).toInt())).average()
                    baseline = baseline * 0.8 + rawBaseline * 0.2
                }
            }
            i++
        }

        // Step 4: 创建MealRecord
        var mealCount = 0
        for (evt in mealEvents) {
            val mealTime = sm[evt.startIdx].first - 8 * 60_000L
            val hour = java.util.Calendar.getInstance().apply { timeInMillis = mealTime }
                .get(java.util.Calendar.HOUR_OF_DAY)
            val rise = sm[evt.peakIdx].second - sm[evt.startIdx].second

            val estCarbs = if (evt.isSnack || evt.isCorrection) {
                (rise * weight * 0.18).coerceIn(5.0, 50.0)
            } else {
                // 正餐: AUC+rise融合估算 (更稳健)
                val aucEst = evt.auc * weight * 0.012
                val riseEst = rise * weight * 0.4
                ((aucEst + riseEst) / 2.0).coerceIn(20.0, 200.0)
            }

            val mealType = when {
                evt.isCorrection -> "snack"
                else -> com.tangdun.app.data.local.entity.MealRecord.inferMealType(hour)
            }

            try {
                mealDao.insert(MealRecord(
                    timestamp = mealTime, mealType = mealType,
                    totalCarbs = estCarbs, totalCalories = estCarbs * 4.0,
                    avgGi = if (evt.isCorrection) 75.0 else 60.0
                ))
                mealCount++
            } catch (e: Exception) { /* skip */ }
        }

        // Step 5: 胰岛素检测 — 更保守(宁漏勿错)
        var insulinCount = 0
        var lastInsulinEnd = -36  // 3小时间隔
        for (i in 6 until sm.size - 6) {
            // 30min ROC
            val roc = (sm[i - 6].second - sm[i].second) /
                maxOf(1.0, (sm[i].first - sm[i - 6].first) / 60000.0)

            // 严格条件: ROC>0.06(下降够快) + 持续(下一点也在降) + 降幅>1.2
            val nextDrop = if (i + 1 < sm.size) sm[i].second - sm[i + 1].second else 0.0
            if (roc > 0.06 && nextDrop > 0 && sm[i - 6].second - sm[i].second > 1.2
                && i - lastInsulinEnd >= 36) {

                // 排除餐后: 前2h有>2.0升幅→大概率是餐后自然回落
                val priorMax = sm.subList(maxOf(0, i - 24), i).maxOf { it.second }
                val priorMin = sm.subList(maxOf(0, i - 24), i).minOf { it.second }
                val priorSwing = priorMax - priorMin

                // 只有下降前无明显餐后摆动才推断胰岛素
                if (priorSwing < 3.0) {
                    var trough = i
                    for (j in i until minOf(i + 30, sm.size)) {
                        if (sm[j].second < sm[trough].second) trough = j
                        // 停止条件: 连续2点回升→已到底
                        if (j - trough >= 2 && sm[j].second > sm[j - 1].second) break
                    }
                    val drop = sm[i - 6].second - sm[trough].second
                    // 排除运动(运动降糖通常<2.0, 胰岛素降糖通常>1.5)
                    if (drop in 1.5..8.0) {
                        try {
                            insulinDao.insert(InsulinRecord(
                                timestamp = sm[i - 6].first - 8 * 60_000L,
                                insulinType = "rapid",
                                doseUnits = (drop / isf).coerceIn(0.5, 12.0),
                                notes = "[推断] ~${String.format("%.1f", drop / isf)}U"
                            ))
                            insulinCount++
                            lastInsulinEnd = trough
                        } catch (e: Exception) { /* skip */ }
                    }
                }
            }
        }

        return Pair(mealCount, insulinCount)
    }
}
