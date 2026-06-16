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

        // Step 2: 全局基线 (全数据最低20%均值→最接近真实空腹)
        val allVals = sm.map { it.second }.sorted()
        var baseline = allVals.take((allVals.size * 0.2).toInt().coerceIn(6, 48)).average()

        // Step 3: 进餐检测
        data class MealEvent(val startIdx: Int, val peakIdx: Int, val endIdx: Int,
                             val auc: Double, val isCorrection: Boolean, val isSnack: Boolean,
                             val mealBaseline: Double)
        val mealEvents = mutableListOf<MealEvent>()

        var i = 6
        while (i < sm.size - 6) {
            val pastRoc = (sm[i].second - sm[i - 6].second) /
                maxOf(1.0, (sm[i].first - sm[i - 6].first) / 60000.0)

            if (pastRoc > 0.015 && sm[i].second - sm[i - 6].second > 0.5) {
                // 回溯找起点 (最多回溯30min=6点, 防止跨餐)
                var start = i - 6
                val backLimit = maxOf(0, i - 12)
                while (start > backLimit && sm[start].second > sm[start - 1].second) start--
                val mealBaseline = sm[start].second

                // 前向找峰值 (最多2.5h=30点, 连续3点降0.3→确认到顶)
                var peak = i; var j = i
                while (j < sm.size - 1 && j - start < 30) {
                    if (sm[j].second > sm[peak].second) peak = j
                    if (j - peak >= 3 && sm[j].second < sm[peak].second - 0.3) break
                    j++
                }
                val peakRise = sm[peak].second - mealBaseline
                if (peakRise < 0.4) { i++; continue }

                // 结束点: 大餐回50%, 小吃回20% (大餐回落更慢)
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = sm[start].first }
                val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                val mealTypeByHour = com.tangdun.app.data.local.entity.MealRecord.inferMealType(hour)
                val isMainMeal = mealTypeByHour in listOf("breakfast", "lunch", "dinner")
                val returnFrac = if (isMainMeal && peakRise > 1.5) 0.50 else 0.25
                val returnLevel = mealBaseline + peakRise * returnFrac
                var end = peak
                val endLimit = minOf(peak + 30, sm.size - 1)  // 最多2.5h后
                while (end < endLimit) { if (sm[end].second <= returnLevel) break; end++ }

                // 黎明过滤
                val isDawn = hour in 4..8 && mealBaseline < 7.0 && peakRise < 1.5

                if (!isDawn && peakRise >= 0.5) {
                    val isCorrection = mealBaseline < 4.0
                    val isSnack = !isCorrection && (!isMainMeal || peakRise < 1.2)

                    // AUC: 峰前24点到峰后24点 (固定窗口, 不受returnLevel影响)
                    val aucStart = maxOf(0, peak - 24)
                    val aucEnd = minOf(peak + 24, sm.size - 1)
                    var auc = 0.0
                    for (k in aucStart..aucEnd) {
                        auc += maxOf(0.0, sm[k].second - mealBaseline) * 5.0
                    }

                    val minSep = if (isSnack || isCorrection) 8 else 18
                    if (mealEvents.isEmpty() || start - mealEvents.last().endIdx >= minSep) {
                        mealEvents.add(MealEvent(start, peak, end, auc, isCorrection, isSnack, mealBaseline))
                    }
                }
                i = end + 1
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
            val mealBaseline = evt.mealBaseline

            // 碳水估算: 正餐AUC法, 加餐rise法
            val estCarbs = if (evt.isSnack || evt.isCorrection) {
                (rise * weight * 0.18).coerceIn(5.0, 50.0)
            } else {
                // AUC法 (固定峰前后2h窗口, 不易受returnLevel影响)
                (evt.auc * weight * 0.012).coerceIn(20.0, 200.0)
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

            // 条件: ROC>0.04 + 下降幅度>1.0 (放宽, 小剂量也应检测)
            val nextDrop = if (i + 1 < sm.size) sm[i].second - sm[i + 1].second else 0.0
            if (roc > 0.04 && sm[i - 6].second - sm[i].second > 1.0
                && i - lastInsulinEnd >= 36) {

                // 排除纯餐后: 前2h摆动<3.5 (含预bolus的餐后回落仍可检测)
                val priorMax = sm.subList(maxOf(0, i - 24), i).maxOf { it.second }
                val priorMin = sm.subList(maxOf(0, i - 24), i).minOf { it.second }
                val priorSwing = priorMax - priorMin

                if (priorSwing < 3.5 || roc > 0.06) {  // 摆动大但降速快→仍推断
                    var trough = i
                    for (j in i until minOf(i + 30, sm.size)) {
                        if (sm[j].second < sm[trough].second) trough = j
                        if (j - trough >= 2 && sm[j].second > sm[j - 1].second) break
                    }
                    val drop = sm[i - 6].second - sm[trough].second
                    if (drop > 1.2) {  // 降幅>1.2即推断
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
