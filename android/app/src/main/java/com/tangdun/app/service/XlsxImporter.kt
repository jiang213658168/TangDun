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

}
