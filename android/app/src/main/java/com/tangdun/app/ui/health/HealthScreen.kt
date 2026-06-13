package com.tangdun.app.ui.health

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.tangdun.app.data.local.entity.*
import com.tangdun.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 健康记录页面
 *
 * 功能：
 * - 睡眠记录
 * - 血压记录
 * - 体重记录
 * - 酮体检测
 * - 口服药记录
 * - 症状记录
 */
@Composable
fun HealthScreen(
    viewModel: HealthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }

    val tabs = listOf("睡眠", "血压", "体重", "酮体", "口服药", "症状")

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题
        TopAppBar(
            title = {
                Text(
                    text = "健康记录",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            actions = {
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )

        // Tab选择
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        // 内容
        when (selectedTab) {
            0 -> SleepContent(uiState, onDelete = { viewModel.deleteRecord("sleep", it) })
            1 -> BloodPressureContent(uiState, onDelete = { viewModel.deleteRecord("bp", it) })
            2 -> WeightContent(uiState, onDelete = { viewModel.deleteRecord("weight", it) })
            3 -> KetoneContent(uiState, onDelete = { viewModel.deleteRecord("ketone", it) })
            4 -> MedicationContent(uiState, onDelete = { viewModel.deleteRecord("medication", it) })
            5 -> SymptomContent(uiState, onDelete = { viewModel.deleteRecord("symptom", it) })
        }
    }

    // 添加对话框
    if (showAddDialog) {
        AddHealthRecordDialog(
            selectedTab = selectedTab,
            onDismiss = { showAddDialog = false },
            onConfirm = { type, data ->
                viewModel.addRecord(type, data)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun SleepContent(uiState: HealthUiState, onDelete: (Long) -> Unit = {}) {
    if (uiState.sleepRecords.isEmpty()) {
        EmptyState("暂无睡眠记录")
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.sleepRecords) { record ->
                SleepCard(record, onDelete = { onDelete(record.id) })
            }
        }
    }
}

@Composable
fun SleepCard(record: SleepRecord, onDelete: () -> Unit = {}) {
    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("${record.durationMinutes / 60}小时${record.durationMinutes % 60}分钟", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${dateFormat.format(Date(record.sleepTime))} - ${dateFormat.format(Date(record.wakeTime))}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(record.quality, style = MaterialTheme.typography.bodyMedium, color = when(record.quality) {
                    "good" -> AlertSuccess
                    "poor" -> AlertWarning
                    else -> TextSecondary
                })
                IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = AlertCritical, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条睡眠记录吗？") },
            confirmButton = { TextButton(onClick = { onDelete(); showDeleteDialog = false }) { Text("删除", color = AlertCritical) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
fun BloodPressureContent(uiState: HealthUiState, onDelete: (Long) -> Unit = {}) {
    if (uiState.bpRecords.isEmpty()) {
        EmptyState("暂无血压记录")
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.bpRecords) { record ->
                BloodPressureCard(record, onDelete = { onDelete(record.id) })
            }
        }
    }
}

@Composable
fun BloodPressureCard(record: BloodPressureRecord, onDelete: () -> Unit = {}) {
    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val level = BloodPressureRecord.getLevel(record.systolic, record.diastolic)
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MonitorHeart, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("${record.systolic}/${record.diastolic} mmHg", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(dateFormat.format(Date(record.timestamp)), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(BloodPressureRecord.getLevelName(level), style = MaterialTheme.typography.bodyMedium, color = when(level) {
                    "normal" -> AlertSuccess
                    "elevated" -> AlertWarning
                    else -> AlertCritical
                })
                IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = AlertCritical, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条血压记录吗？") },
            confirmButton = { TextButton(onClick = { onDelete(); showDeleteDialog = false }) { Text("删除", color = AlertCritical) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
fun WeightContent(uiState: HealthUiState, onDelete: (Long) -> Unit = {}) {
    if (uiState.weightRecords.isEmpty()) {
        EmptyState("暂无体重记录")
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.weightRecords) { record ->
                WeightCard(record, onDelete = { onDelete(record.id) })
            }
        }
    }
}

@Composable
fun WeightCard(record: WeightRecord, onDelete: () -> Unit = {}) {
    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MonitorWeight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("${String.format("%.1f", record.weightKg)} kg", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (record.bmi != null) {
                    Text("BMI: ${String.format("%.1f", record.bmi)} (${WeightRecord.getBMICategoryName(WeightRecord.getBMICategory(record.bmi))})", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Text(dateFormat.format(Date(record.timestamp)), style = MaterialTheme.typography.bodySmall, color = TextHint)
            }
            IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = AlertCritical, modifier = Modifier.size(18.dp))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条体重记录吗？") },
            confirmButton = { TextButton(onClick = { onDelete(); showDeleteDialog = false }) { Text("删除", color = AlertCritical) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
fun KetoneContent(uiState: HealthUiState, onDelete: (Long) -> Unit = {}) {
    if (uiState.ketoneRecords.isEmpty()) {
        EmptyState("暂无酮体记录")
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.ketoneRecords) { record ->
                KetoneCard(record, onDelete = { onDelete(record.id) })
            }
        }
    }
}

@Composable
fun KetoneCard(record: KetoneRecord, onDelete: () -> Unit = {}) {
    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val level = KetoneRecord.getLevel(record.ketoneLevel)
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bloodtype, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("${String.format("%.1f", record.ketoneLevel)} mmol/L", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${record.testType}检测", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(KetoneRecord.getLevelName(level), style = MaterialTheme.typography.bodyMedium, color = when(level) {
                    "normal" -> AlertSuccess
                    "elevated" -> AlertWarning
                    else -> AlertCritical
                })
                IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = AlertCritical, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条酮体记录吗？") },
            confirmButton = { TextButton(onClick = { onDelete(); showDeleteDialog = false }) { Text("删除", color = AlertCritical) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
fun MedicationContent(uiState: HealthUiState, onDelete: (Long) -> Unit = {}) {
    if (uiState.medicationRecords.isEmpty()) {
        EmptyState("暂无口服药记录")
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.medicationRecords) { record ->
                MedicationCard(record, onDelete = { onDelete(record.id) })
            }
        }
    }
}

@Composable
fun MedicationCard(record: MedicationRecord, onDelete: () -> Unit = {}) {
    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Medication, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(record.medicationName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(record.dose, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text(dateFormat.format(Date(record.timestamp)), style = MaterialTheme.typography.bodySmall, color = TextHint)
            }
            IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = AlertCritical, modifier = Modifier.size(18.dp))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条口服药记录吗？") },
            confirmButton = { TextButton(onClick = { onDelete(); showDeleteDialog = false }) { Text("删除", color = AlertCritical) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
fun SymptomContent(uiState: HealthUiState, onDelete: (Long) -> Unit = {}) {
    if (uiState.symptomRecords.isEmpty()) {
        EmptyState("暂无症状记录")
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.symptomRecords) { record ->
                SymptomCard(record, onDelete = { onDelete(record.id) })
            }
        }
    }
}

@Composable
fun SymptomCard(record: SymptomRecord, onDelete: () -> Unit = {}) {
    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Sick, contentDescription = null, tint = AlertWarning, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(when(record.symptomType) {
                    "hypo_symptom" -> "低血糖症状"
                    "hyper_symptom" -> "高血糖症状"
                    else -> "其他症状"
                }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(record.symptoms, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                if (record.glucoseValue != null) {
                    Text("血糖: ${String.format("%.1f", record.glucoseValue)} mmol/L", style = MaterialTheme.typography.bodySmall, color = TextHint)
                }
            }
            IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = AlertCritical, modifier = Modifier.size(18.dp))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条症状记录吗？") },
            confirmButton = { TextButton(onClick = { onDelete(); showDeleteDialog = false }) { Text("删除", color = AlertCritical) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.HealthAndSafety, contentDescription = null, modifier = Modifier.size(64.dp), tint = TextHint)
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = TextHint)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddHealthRecordDialog(
    selectedTab: Int,
    onDismiss: () -> Unit,
    onConfirm: (type: String, data: Map<String, Any>) -> Unit
) {
    var value1 by remember { mutableStateOf("") }
    var value2 by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var selectedTime by remember { mutableStateOf(System.currentTimeMillis()) }

    val title = when (selectedTab) {
        0 -> "添加睡眠记录"
        1 -> "添加血压记录"
        2 -> "添加体重记录"
        3 -> "添加酮体记录"
        4 -> "添加口服药记录"
        5 -> "添加症状记录"
        else -> "添加记录"
    }

    Dialog(onDismissRequest = { }) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> { // 睡眠
                        OutlinedTextField(value = value1, onValueChange = { value1 = it }, label = { Text("睡眠时长(分钟)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(8.dp))
                    }
                    1 -> { // 血压
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(value = value1, onValueChange = { value1 = it }, label = { Text("收缩压") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(8.dp))
                            OutlinedTextField(value = value2, onValueChange = { value2 = it }, label = { Text("舒张压") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(8.dp))
                        }
                    }
                    2 -> { // 体重
                        OutlinedTextField(value = value1, onValueChange = { value1 = it }, label = { Text("体重(kg)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(8.dp))
                    }
                    3 -> { // 酮体
                        OutlinedTextField(value = value1, onValueChange = { value1 = it }, label = { Text("酮体水平(mmol/L)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(8.dp))
                    }
                    4 -> { // 口服药
                        OutlinedTextField(value = value1, onValueChange = { value1 = it }, label = { Text("药物名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(8.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = value2, onValueChange = { value2 = it }, label = { Text("剂量") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(8.dp))
                    }
                    5 -> { // 症状
                        var symptomType by remember { mutableStateOf("hypo_symptom") }
                        Text("症状类型", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = symptomType == "hypo_symptom", onClick = { symptomType = "hypo_symptom" }, label = { Text("低血糖") })
                            FilterChip(selected = symptomType == "hyper_symptom", onClick = { symptomType = "hyper_symptom" }, label = { Text("高血糖") })
                            FilterChip(selected = symptomType == "other", onClick = { symptomType = "other" }, label = { Text("其他") })
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("常见症状（点击选择）", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        val symptoms = if (symptomType == "hypo_symptom") {
                            listOf("心慌", "手抖", "出汗", "饥饿感", "头晕", "乏力")
                        } else {
                            listOf("口渴", "多尿", "乏力", "视力模糊", "恶心", "腹痛")
                        }
                        var selectedSymptoms by remember { mutableStateOf(setOf<String>()) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            symptoms.take(3).forEach { symptom ->
                                FilterChip(
                                    selected = selectedSymptoms.contains(symptom),
                                    onClick = {
                                        selectedSymptoms = if (selectedSymptoms.contains(symptom)) selectedSymptoms - symptom else selectedSymptoms + symptom
                                        value1 = selectedSymptoms.joinToString(",")
                                    },
                                    label = { Text(symptom, style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            symptoms.drop(3).forEach { symptom ->
                                FilterChip(
                                    selected = selectedSymptoms.contains(symptom),
                                    onClick = {
                                        selectedSymptoms = if (selectedSymptoms.contains(symptom)) selectedSymptoms - symptom else selectedSymptoms + symptom
                                        value1 = selectedSymptoms.joinToString(",")
                                    },
                                    label = { Text(symptom, style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = value2, onValueChange = { value2 = it }, label = { Text("当时血糖 (可选)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(8.dp))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(8.dp))

                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { }) { Text("取消") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        val data = mutableMapOf<String, Any>()
                        when (selectedTab) {
                            0 -> data["duration"] = (value1.toIntOrNull() ?: 480)
                            1 -> {
                                data["systolic"] = (value1.toIntOrNull() ?: 120)
                                data["diastolic"] = (value2.toIntOrNull() ?: 80)
                            }
                            2 -> data["weight"] = (value1.toDoubleOrNull() ?: 70.0)
                            3 -> data["level"] = (value1.toDoubleOrNull() ?: 0.0)
                            4 -> {
                                data["name"] = value1
                                data["dose"] = value2
                            }
                            5 -> {
                                data["type"] = "other"
                                data["symptoms"] = value1
                                if (value2.isNotBlank()) {
                                    data["glucose"] = value2.toDoubleOrNull() ?: 0.0
                                }
                            }
                        }
                        val type = when (selectedTab) {
                            0 -> "sleep"
                            1 -> "blood_pressure"
                            2 -> "weight"
                            3 -> "ketone"
                            4 -> "medication"
                            5 -> "symptom"
                            else -> "other"
                        }
                        onConfirm(type, data)
                    }) { Text("保存") }
                }
            }
        }
    }
}
