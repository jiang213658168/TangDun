package com.tangdun.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tangdun.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * ★ v3.0.5 共享日期时间选择器
 *
 * 功能:
 *  - 显示当前选定时间 (大字号)
 *  - 快捷日期: 最近 1 天 / 3 天 / 7 天 / 30 天 (自动选过去 N 天的当前时刻)
 *  - 完整 DatePicker (可任意历史日期)
 *  - 完整 TimePicker (时分)
 *  - 实时预览: "MM-dd HH:mm"
 *
 * 用法: DateTimePickerDialog(initialTime = ..., onDismiss = ..., onConfirm = { ts -> ... })
 */
@Composable
fun DateTimePickerDialog(
    initialTime: Long = System.currentTimeMillis(),
    title: String = "选择日期时间",
    minDate: Long? = null,  // 可选: 最早可选日期 (用于限制范围)
    maxDate: Long? = System.currentTimeMillis() + 60_000L,  // 默认允许未来 1 分钟
    showQuickButtons: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var selectedTime by remember { mutableStateOf(initialTime) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val cal = Calendar.getInstance().apply { timeInMillis = selectedTime }
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateTimeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(title)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // ★ 当前选定时间预览
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = dateTimeFmt.format(selectedTime),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        val now = System.currentTimeMillis()
                        val delta = selectedTime - now
                        val hint = when {
                            delta > 60_000L -> "未来 ${delta / 60_000} 分钟"
                            delta < -60_000L -> "${-delta / 60_000L} 分钟前"
                            else -> "当前时间"
                        }
                        Text(text = hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ★ 快捷日期按钮 (最近 N 天)
                if (showQuickButtons) {
                    Text("快捷日期", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            "今天" to 0,
                            "昨天" to -1,
                            "前天" to -2,
                            "3天前" to -3
                        ).forEach { (label, dayOffset) ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    val nowCal = Calendar.getInstance()
                                    val targetCal = Calendar.getInstance().apply {
                                        timeInMillis = selectedTime
                                        add(Calendar.DAY_OF_MONTH, dayOffset)
                                        set(Calendar.HOUR_OF_DAY, nowCal.get(Calendar.HOUR_OF_DAY))
                                        set(Calendar.MINUTE, nowCal.get(Calendar.MINUTE))
                                    }
                                    selectedTime = targetCal.timeInMillis
                                },
                                label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ★ 日期选择 + 时间选择按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(dateFmt.format(selectedTime), style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(timeFmt.format(selectedTime), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedTime) }) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    // ★ DatePicker 弹窗
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedTime
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { pickedDay ->
                        val pickedCal = Calendar.getInstance().apply {
                            timeInMillis = pickedDay
                            set(Calendar.HOUR_OF_DAY, cal.get(Calendar.HOUR_OF_DAY))
                            set(Calendar.MINUTE, cal.get(Calendar.MINUTE))
                            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }
                        // 应用日期范围限制
                        val finalTime = when {
                            minDate != null && pickedCal.timeInMillis < minDate -> minDate
                            maxDate != null && pickedCal.timeInMillis > maxDate -> maxDate
                            else -> pickedCal.timeInMillis
                        }
                        selectedTime = finalTime
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // ★ TimePicker 弹窗
    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择时间") },
            text = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newCal = Calendar.getInstance().apply {
                        timeInMillis = selectedTime
                        set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        set(Calendar.MINUTE, timePickerState.minute)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    selectedTime = newCal.timeInMillis
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            }
        )
    }
}