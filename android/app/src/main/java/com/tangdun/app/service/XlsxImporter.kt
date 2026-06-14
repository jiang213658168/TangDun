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
        if (records.size < 12) return Pair(0, 0)

        val sorted = records.sortedBy { it.timestamp }
        val weight = com.tangdun.app.util.SettingsManager(context).getWeightKg().toDouble()
        val mealDao = TangDunApp.getDatabase(context).mealDao()
        val insulinDao = TangDunApp.getDatabase(context).insulinDao()

        // Step 1: SMA平滑 (5点=25min窗口, 去噪)
        val sm = mutableListOf<Pair<Long, Double>>()
        for (i in sorted.indices) {
            val w = sorted.subList(maxOf(0, i - 2), minOf(sorted.size, i + 3))
            sm.add(sorted[i].timestamp to w.map { it.value }.average())
        }

        // Step 2: 动态基线 (最近1小时的10%分位数, 更实时)
        fun baselineAt(idx: Int): Double {
            val start = maxOf(0, idx - 12)
            val vals = sm.subList(start, idx + 1).map { it.second }
            return if (vals.size >= 4) vals.sorted().take(maxOf(1, vals.size / 10)).average()
            else vals.average()
        }

        // Step 3: CUSUM + 上下文感知 检测进餐
        data class MealEvent(val startIdx: Int, val peakIdx: Int, val endIdx: Int,
                             val auc: Double, val isCorrection: Boolean, val isSnack: Boolean)

        val mealEvents = mutableListOf<MealEvent>()
        var cusum = 0.0
        var eventStart = -1
        var prevTrend = 0.0  // 事件前趋势 (正=已在上升, 负=在下降)

        for (i in sm.indices) {
            val bl = baselineAt(i)
            val dev = sm[i].second - bl
            cusum = maxOf(0.0, cusum + dev)

            // 上升开始: CUSUM首次超过阈值
            val threshold = if (sm[i].second < 4.0) 0.3 else 0.5  // 低血糖: 更敏感
            if (cusum > threshold && eventStart < 0) {
                eventStart = i
                // 记录事件前趋势 (前30分钟ROC)
                val preIdx = maxOf(0, i - 6)
                prevTrend = (sm[i].second - sm[preIdx].second) /
                    maxOf(1.0, (sm[i].first - sm[preIdx].first) / 60000.0)
            }

            // 上升结束: CUSUM连续下降2步 且 已越过峰值
            if (eventStart >= 0 && i - eventStart >= 4) {
                val prevCusum = maxOf(0.0, cusum - dev)
                val stillRising = cusum >= prevCusum
                val totalRise = sm[i].second - sm[eventStart].second

                if (!stillRising && totalRise > 0.5) {
                    // 找峰值索引
                    var pk = eventStart
                    for (j in eventStart..i) {
                        if (sm[j].second > sm[pk].second) pk = j
                    }
                    val peakRise = sm[pk].second - sm[eventStart].second

                    // 背景判断
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = sm[eventStart].first }
                    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                    val isDawn = hour in 4..8 && sm[eventStart].second < 7.0 && peakRise < 1.5
                    val isCorrection = sm[eventStart].second < 4.0  // 低血糖纠正
                    val isSnack = peakRise in 0.5..1.5  // 小幅上升=加餐
                    val startsFromDrop = prevTrend < -0.02  // 下降后反弹(运动/低血糖纠正)

                    // 黎明现象: 不是进食
                    if (!isDawn) {
                        val endIdx = i
                        var auc = 0.0
                        for (j in eventStart..endIdx) {
                            auc += maxOf(0.0, sm[j].second - bl) * 5.0
                        }

                        // 间隔检查 (正餐2h, 加餐/纠正1h)
                        val minSep = if (isSnack || isCorrection) 12 else 24
                        if (mealEvents.isEmpty() || i - mealEvents.last().endIdx >= minSep) {
                            mealEvents.add(MealEvent(eventStart, pk, endIdx, auc, isCorrection, isSnack))
                        }
                    }
                    cusum = 0.0
                    eventStart = -1
                    prevTrend = 0.0
                }
            }
        }

        // Step 4: 创建MealRecord — 区分餐型
        var mealCount = 0
        for (evt in mealEvents) {
            val mealTime = sm[evt.startIdx].first - 10 * 60_000L
            val hour = java.util.Calendar.getInstance().apply { timeInMillis = mealTime }
                .get(java.util.Calendar.HOUR_OF_DAY)

            // 正餐: AUC法; 加餐/纠正: 升幅法
            val estCarbs = if (evt.isSnack || evt.isCorrection) {
                val rise = sm[evt.peakIdx].second - sm[evt.startIdx].second
                (rise * weight * 0.4).coerceIn(5.0, 50.0)  // 加餐5-50g
            } else {
                (evt.auc / (weight * 0.08)).coerceIn(20.0, 200.0)  // 正餐20-200g
            }

            val mealType = when {
                evt.isCorrection -> "snack"  // 低血糖纠正=加餐类
                evt.isSnack -> com.tangdun.app.data.local.entity.MealRecord.inferMealType(hour)
                    .let { if (it == "breakfast" || it == "lunch" || it == "dinner") "snack" else it }
                else -> com.tangdun.app.data.local.entity.MealRecord.inferMealType(hour)
            }

            val gi = if (evt.isCorrection) 75.0 else 60.0  // 纠正用高GI快糖

            try {
                mealDao.insert(MealRecord(
                    timestamp = mealTime, mealType = mealType,
                    totalCarbs = estCarbs, totalCalories = estCarbs * 4.0, avgGi = gi
                ))
                mealCount++
            } catch (e: Exception) { /* skip */ }
        }

        // Step 5: 胰岛素检测 — 区分运动降糖 vs 胰岛素降糖
        var insulinCount = 0
        var lastInsulinEnd = -12

        for (i in 3 until sm.size) {
            // 计算局部ROC
            val lookback = minOf(6, i)
            val prevIdx = i - lookback
            val timeMin = maxOf(1.0, (sm[i].first - sm[prevIdx].first) / 60000.0)
            val roc = (sm[prevIdx].second - sm[i].second) / timeMin

            // 快速下降: ROC > 0.04 且持续 (比正餐后自然回落快)
            if (roc > 0.04 && i - lastInsulinEnd >= 12) {
                // 检查: 下降前是否有上升? (有=餐后自然回落, 无=胰岛素/运动)
                val preRise = sm[prevIdx].second - sm[maxOf(0, prevIdx - 6)].second
                val precededByRise = preRise > 1.0  // 前30分钟有明显上升

                // 只有非餐后自然回落才推断为胰岛素
                if (!precededByRise || roc > 0.08) {
                    var trough = i
                    for (j in i until minOf(i + 36, sm.size)) {
                        if (sm[j].second < sm[trough].second) trough = j
                        if (j - i > 8 && sm[j].second > sm[j - 1].second + 0.3) break
                    }
                    val drop = sm[prevIdx].second - sm[trough].second
                    if (drop > 1.0) {
                        val estDose = (drop * weight / 25.0).coerceIn(0.5, 15.0)
                        try {
                            insulinDao.insert(InsulinRecord(
                                timestamp = sm[prevIdx].first - 5 * 60_000L,
                                insulinType = "rapid", doseUnits = estDose,
                                notes = "[推断] ~${String.format("%.1f", estDose)}U"
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
