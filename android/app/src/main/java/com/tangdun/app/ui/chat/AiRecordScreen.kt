package com.tangdun.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangdun.app.data.local.entity.InsulinRecord
import com.tangdun.app.data.local.entity.MealRecord
import com.tangdun.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * AI 智能记录页面 — 独立于聊天的专用记录界面
 *
 * 用户说一句话 → AI 解析 → 预览确认 → 一键记录
 * 支持: 饮食(完整字段)/胰岛素(完整字段)/运动/血糖
 */
@Composable
fun AiRecordScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var previewRecord by remember { mutableStateOf<ParsedRecord?>(null) }
    var statusMsg by remember { mutableStateOf("说出或输入你想记录的内容") }
    val recentRecords = remember { mutableStateListOf<RecordEntry>() }

    Column(Modifier.fillMaxSize().background(BgLight)) {
        // ── 顶部状态 ──
        Surface(shadowElevation = 2.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("AI 智能记录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(statusMsg, fontSize = 13.sp, color = TextHint)
            }
        }

        // ── 中间: 预览卡片或历史 ──
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            reverseLayout = true
        ) {
            // 预览卡片
            item {
                previewRecord?.let { record ->
                    RecordPreviewCard(
                        record = record,
                        onConfirm = {
                            scope.launch {
                                val (msg, saved) = AiRecordHelper.saveRecord(context, record)
                                statusMsg = msg
                                if (saved) {
                                    recentRecords.add(0, RecordEntry(record, System.currentTimeMillis()))
                                    previewRecord = null
                                    inputText = ""
                                }
                            }
                        },
                        onEdit = { field, value ->
                            previewRecord = previewRecord?.copyField(field, value)
                        },
                        onCancel = {
                            previewRecord = null
                            statusMsg = "已取消，请重新输入"
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            // 历史记录
            items(recentRecords) { entry ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = BgWhite)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(entry.icon, null, Modifier.size(20.dp), tint = Primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.summary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(entry.time, fontSize = 11.sp, color = TextHint)
                        }
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = AlertSuccess)
                    }
                }
            }
        }

        // ── 快捷标签 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for ((label, example) in listOf(
                "🍚 饮食" to "我吃了半碗米饭和青菜",
                "💉 胰岛素" to "打了3单位速效 肚子上",
                "🏃 运动" to "快走了30分钟",
                "🩸 血糖" to "指尖血5.6 空腹"
            )) {
                AssistChip(
                    onClick = {
                        if (!isProcessing) { inputText = example; statusMsg = "按回车→AI解析" }
                    },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 输入栏 ──
        Surface(shadowElevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("例如: 中午12点吃了碗面 约60g碳水", fontSize = 14.sp) },
                    textStyle = TextStyle(fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (inputText.isNotBlank() && !isProcessing) {
                            scope.launch {
                                isProcessing = true; statusMsg = "AI 解析中..."
                                val record = AiRecordHelper.parse(context, inputText)
                                isProcessing = false
                                if (record != null) {
                                    previewRecord = record
                                    statusMsg = "请确认或修改后保存"
                                } else {
                                    statusMsg = "未能识别，请换个说法试试"
                                }
                            }
                        }
                    }),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(focusedContainerColor = BgWhite, unfocusedContainerColor = BgWhite)
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        if (inputText.isNotBlank() && !isProcessing) {
                            scope.launch {
                                isProcessing = true; statusMsg = "AI 解析中..."
                                val record = AiRecordHelper.parse(context, inputText)
                                isProcessing = false
                                if (record != null) {
                                    previewRecord = record
                                    statusMsg = "请确认或修改后保存"
                                } else {
                                    statusMsg = "未能识别，请换个说法试试"
                                }
                            }
                        }
                    },
                    enabled = inputText.isNotBlank() && !isProcessing,
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    if (isProcessing)
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = BgWhite)
                    else
                        Icon(Icons.Default.Send, "发送", Modifier.size(20.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// 预览卡片
// ═══════════════════════════════════════════

@Composable
fun RecordPreviewCard(
    record: ParsedRecord,
    onConfirm: () -> Unit,
    onEdit: (String, String) -> Unit,
    onCancel: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // 类型标签
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Primary.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)) {
                    Text(" ${record.typeLabel} ", fontSize = 12.sp, color = Primary, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(record.timeDisplay, fontSize = 12.sp, color = TextHint)
            }

            Spacer(Modifier.height(10.dp))

            when (record) {
                is ParsedRecord.Meal -> {
                    FieldRow("食物", record.food)
                    FieldRow("碳水", "${record.carbs}g")
                    FieldRow("类型", record.mealType)
                    if (record.protein > 0) FieldRow("蛋白质", "${record.protein}g")
                    if (record.fat > 0) FieldRow("脂肪", "${record.fat}g")
                }
                is ParsedRecord.Insulin -> {
                    FieldRow("类型", record.doseType)
                    FieldRow("剂量", "${record.dose}U")
                    if (record.site.isNotBlank()) FieldRow("部位", record.site)
                }
                is ParsedRecord.Exercise -> {
                    FieldRow("类型", record.exType)
                    FieldRow("时长", "${record.minutes}分钟")
                }
                is ParsedRecord.Glucose -> {
                    FieldRow("血糖值", "${record.value} mmol/L")
                    FieldRow("场景", record.scene)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("取消", color = TextHint) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm) { Text("✓ 确认记录") }
            }
        }
    }
}

@Composable
fun FieldRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", fontSize = 13.sp, color = TextHint)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ═══════════════════════════════════════════
// 数据模型
// ═══════════════════════════════════════════

sealed class ParsedRecord(
    open val timeDisplay: String,
    open val timestamp: Long,
    open val typeLabel: String
) {
    data class Meal(
        val food: String, val carbs: Double, val calories: Double, val gi: Double,
        val mealType: String, val protein: Double, val fat: Double,
        override val timeDisplay: String, override val timestamp: Long
    ) : ParsedRecord(timeDisplay, timestamp, "🍚 饮食")

    data class Insulin(
        val dose: Double, val doseType: String, val site: String,
        override val timeDisplay: String, override val timestamp: Long
    ) : ParsedRecord(timeDisplay, timestamp, "💉 胰岛素")

    data class Exercise(
        val exType: String, val minutes: Int,
        override val timeDisplay: String, override val timestamp: Long
    ) : ParsedRecord(timeDisplay, timestamp, "🏃 运动")

    data class Glucose(
        val value: Double, val scene: String,
        override val timeDisplay: String, override val timestamp: Long
    ) : ParsedRecord(timeDisplay, timestamp, "🩸 血糖")

    fun copyField(field: String, value: String): ParsedRecord = when (this) {
        is Meal -> copy(
            food = if (field == "food") value else food,
            carbs = if (field == "carbs") value.toDoubleOrNull() ?: this.carbs else this.carbs,
            mealType = if (field == "mealType") value else mealType,
            protein = if (field == "protein") value.toDoubleOrNull() ?: this.protein else this.protein,
            fat = if (field == "fat") value.toDoubleOrNull() ?: this.fat else this.fat
        )
        is Insulin -> copy(
            dose = if (field == "dose") value.toDoubleOrNull() ?: this.dose else this.dose,
            doseType = if (field == "type") value else doseType,
            site = if (field == "site") value else site
        )
        is Exercise -> copy(
            exType = if (field == "type") value else exType,
            minutes = if (field == "minutes") value.toIntOrNull() ?: this.minutes else this.minutes
        )
        is Glucose -> copy(
            value = if (field == "value") value.toDoubleOrNull() ?: this.value else this.value,
            scene = if (field == "scene") value else this.scene
        )
    }
}

data class RecordEntry(
    val record: ParsedRecord,
    val savedAt: Long
) {
    val icon get() = when (record) {
        is ParsedRecord.Meal -> Icons.Default.Restaurant
        is ParsedRecord.Insulin -> Icons.Default.MedicalServices
        is ParsedRecord.Exercise -> Icons.Default.DirectionsRun
        is ParsedRecord.Glucose -> Icons.Default.MonitorHeart
    }
    val summary get() = when (record) {
        is ParsedRecord.Meal -> "${record.food} ${record.carbs}g"
        is ParsedRecord.Insulin -> "${record.dose}U ${record.doseType}"
        is ParsedRecord.Exercise -> "${record.exType} ${record.minutes}min"
        is ParsedRecord.Glucose -> "${record.value}mmol/L ${record.scene}"
    }
    val time get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(savedAt))
}
