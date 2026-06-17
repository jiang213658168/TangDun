package com.tangdun.app.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tangdun.app.TangDunApp
import com.tangdun.app.data.local.entity.GlucoseRecord
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
                return@withContext ImportResult(0, 0, 0, listOf(
                    "未解析到血糖数据，请确认:",
                    "1. 文件是欧态健康App导出的.xlsx",
                    "2. 第1列是时间，第2列是血糖值",
                    "3. 数据从第2行开始(第1行是表头则自动跳过)"
                ))
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

            // ★ 不反推: 纯血糖导入只存数据
            // 饮食/胰岛素由用户后续手动记录 → 保证数据质量
            Log.i(TAG, "导入完成: ${imported}条 (${total}条去重后)")

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
        val allEntries = mutableListOf<String>()

        // 遍历ZIP条目，提取sharedStrings和sheet1
        var entry: ZipEntry? = zipStream.nextEntry
        while (entry != null) {
            allEntries.add(entry.name)
            when {
                entry.name.equals("xl/sharedStrings.xml", ignoreCase = true) -> {
                    sharedStringsXml = zipStream.readBytes()
                    Log.i(TAG, "sharedStrings: ${sharedStringsXml?.size ?: 0} bytes")
                }
                entry.name.equals("xl/worksheets/sheet1.xml", ignoreCase = true) -> {
                    sheetXml = zipStream.readBytes()
                    Log.i(TAG, "sheet1.xml: ${sheetXml?.size ?: 0} bytes")
                }
            }
            zipStream.closeEntry()
            entry = zipStream.nextEntry
        }
        zipStream.close()

        Log.i(TAG, "xlsx内共${allEntries.size}个条目: ${allEntries.joinToString(", ")}")

        if (sheetXml == null) {
            Log.w(TAG, "未找到sheet1.xml! 可用的sheet: ${allEntries.filter { "sheet" in it.lowercase() }}")
            // 尝试找第一个sheet
            val firstSheet = allEntries.firstOrNull { it.contains("sheet", ignoreCase = true) && it.endsWith(".xml") }
            if (firstSheet != null) {
                Log.i(TAG, "回退到: $firstSheet")
                // 重新读取
                val zs2 = ZipInputStream(inputStream)  // 需要新stream...
                // 简化: 如果有sheet但名字不是sheet1, 返回空并提示
                return emptyList()
            }
            return emptyList()
        }

        // 解析共享字符串表
        val sharedStrings = parseSharedStrings(sharedStringsXml)
        Log.i(TAG, "共享字符串表: ${sharedStrings.size}条")

        // 解析sheet数据
        val records = parseSheetData(sheetXml, sharedStrings)
        Log.i(TAG, "sheet解析完成: ${records.size}条血糖记录")
        if (records.isNotEmpty()) {
            val first = records.first()
            val last = records.last()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            Log.i(TAG, "时间范围: ${fmt.format(java.util.Date(first.timestamp))} ~ ${fmt.format(java.util.Date(last.timestamp))}")
            Log.i(TAG, "血糖范围: ${"%.1f".format(records.minOf{it.value})} ~ ${"%.1f".format(records.maxOf{it.value})} mmol/L")
        }

        return records
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

    /**
     * 解析欧态时间字符串 → 毫秒时间戳
     *
     * 欧态实际格式: "2026.6.17 下午11:29" (中文AM/PM + yyyy.M.d)
     * "下午"=PM, "上午"=AM. 12小时制需要转换为24小时制
     */
    private fun parseOttaiTime(timeStr: String): Long? {
        // 尝试1: Excel序列号 (纯数字)
        val excelSerial = timeStr.toDoubleOrNull()
        if (excelSerial != null && excelSerial in 40000.0..80000.0) {
            val excelEpoch = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("1899-12-30")?.time ?: 0L
            return excelEpoch + (excelSerial * 86400000L).toLong()
        }

        // 尝试2: 标准24小时格式 (yyyy.M.d HH:mm 等)
        val formats24h = listOf(
            "yyyy.M.d HH:mm", "yyyy.M.d H:mm", "yyyy.MM.dd HH:mm",
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm", "yyyy/MM/dd HH:mm:ss"
        )
        for (fmt in formats24h) {
            try {
                return SimpleDateFormat(fmt, Locale.getDefault()).parse(timeStr)?.time
            } catch (_: Exception) {}
        }

        // 尝试3: ★ 欧态中文格式: "2026.6.17 下午11:29" 或 "2026.6.17 上午07:09"
        // 手动解析: 分离日期部分和时间部分, 处理上午/下午
        val isPM = timeStr.contains("下午")
        val isAM = timeStr.contains("上午")
        if (isPM || isAM) {
            try {
                // 分割: "2026.6.17 下午11:29" → datePart="2026.6.17", timePart="11:29"
                val cleaned = timeStr.replace("下午", " ").replace("上午", " ").trim()
                val parts = cleaned.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    // cleaned="2026.6.17  11:29" → split → ["2026.6.17","11:29"]
                    val datePart = parts[0]
                    val timePart = parts[1]
                    val timeBits = timePart.split(":")
                    if (timeBits.size >= 2) {
                        var hour = timeBits[0].toIntOrNull() ?: return null
                        val minute = timeBits[1].toIntOrNull() ?: return null
                        // 12→24小时转换
                        if (isPM && hour != 12) hour += 12
                        if (isAM && hour == 12) hour = 0
                        val fixedTime = "${datePart} ${"%02d".format(hour)}:${"%02d".format(minute)}"
                        return SimpleDateFormat("yyyy.M.d HH:mm", Locale.getDefault()).parse(fixedTime)?.time
                    }
                }
            } catch (_: Exception) {}
        }

        // 尝试4: 标准12小时格式 (英文本地化)
        try {
            return SimpleDateFormat("yyyy.M.d ah:mm", Locale.ENGLISH).parse(timeStr)?.time
        } catch (_: Exception) {}

        return null
    }

    private fun parseSheetData(xml: ByteArray, sharedStrings: List<String>): List<GlucoseRecord> {
        val records = mutableListOf<GlucoseRecord>()
        var parseErrors = 0

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
                                    if (glucose != null && glucose in 0.5..35.0) {
                                        val timestamp = parseOttaiTime(timeStr)

                                        if (timestamp != null && timestamp > 0 && timestamp < System.currentTimeMillis() + 86400000L) {
                                            records.add(GlucoseRecord(
                                                timestamp = timestamp,
                                                value = glucose,
                                                source = "xlsx_import"
                                            ))
                                        } else {
                                            parseErrors++
                                            if (parseErrors <= 3) {
                                                Log.w(TAG, "无法解析行: time='$timeStr' val='$valueStr'")
                                            }
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

        if (parseErrors > 0) Log.w(TAG, "共${parseErrors}行解析失败")
        Log.i(TAG, "解析到${records.size}条血糖记录")
        return records
    }

}
