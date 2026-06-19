package com.tangdun.app.ui.exercise

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.hilt.navigation.compose.hiltViewModel
import com.tangdun.app.data.local.entity.ExerciseRecord
import com.tangdun.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 运动管理页面
 *
 * 显示：
 * - 今日运动统计
 * - 运动处方推荐
 */
@Composable
fun ExerciseScreen(
    viewModel: ExerciseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val exerciseDao = remember(context) {
        com.tangdun.app.TangDunApp.getDatabase(context).exerciseDao()
    }
    var records by remember { mutableStateOf<List<ExerciseRecord>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        records = exerciseDao.getRecent(50)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 顶部标题
        TopAppBar(
            title = {
                Text(
                    text = "运动管理",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            actions = {
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加运动", tint = MaterialTheme.colorScheme.onPrimary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )

        // 今日运动统计
        TodayExerciseStatsCard(
            totalDuration = uiState.todayDuration,
            totalSteps = uiState.todaySteps,
            totalCalories = uiState.todayCalories,
            exerciseCount = uiState.todayExerciseCount
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 运动处方
        ExercisePrescriptionCard(
            exerciseType = uiState.recommendedType,
            duration = uiState.recommendedDuration,
            intensity = uiState.recommendedIntensity,
            expectedDrop = uiState.expectedGlucoseDrop,
            notes = uiState.prescriptionNotes
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 运动历史列表
        ExerciseHistoryList(
            records = records,
            onDelete = { record ->
                viewModel.deleteExercise(record)
                coroutineScope.launch {
                    records = exerciseDao.getRecent(50)
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showAddDialog) {
        AddExerciseDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { type, duration, intensity, steps, calBurned, timestamp ->
                viewModel.addExercise(type, duration, intensity, steps, calBurned, timestamp)
                coroutineScope.launch {
                    records = exerciseDao.getRecent(50)
                }
                showAddDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AddExerciseDialog(
    onDismiss: () -> Unit,
    onConfirm: (type: String, durationMin: Int, intensity: String, steps: Int, caloriesBurned: Double, timestamp: Long) -> Unit
) {
    var exerciseType by remember { mutableStateOf("walking") }
    var duration by remember { mutableStateOf("30") }
    var intensity by remember { mutableStateOf("moderate") }
    var steps by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var selectedTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var showTimePicker by remember { mutableStateOf(false) }

    val exerciseTypes = listOf(
        "walking" to "步行", "running" to "跑步", "cycling" to "骑行",
        "swimming" to "游泳", "yoga" to "瑜伽", "strength" to "力量",
        "aerobic" to "有氧", "other" to "其他"
    )
    val intensities = listOf("low" to "低", "moderate" to "中等", "high" to "高")

    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = selectedTime }
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = calendar.get(java.util.Calendar.MINUTE)

    Dialog(onDismissRequest = onDismiss) {
        com.tangdun.app.ui.components.TangDunCard(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("添加运动记录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                Text("运动类型", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    exerciseTypes.forEach { (key, label) ->
                        FilterChip(selected = exerciseType == key, onClick = { exerciseType = key }, label = { Text(label, style = MaterialTheme.typography.bodySmall) })
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = duration, onValueChange = { duration = it.filter { c -> c.isDigit() } },
                    label = { Text("时长 (分钟)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("强度", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    intensities.forEach { (key, label) ->
                        FilterChip(selected = intensity == key, onClick = { intensity = key }, label = { Text(label, style = MaterialTheme.typography.bodySmall) })
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = steps, onValueChange = { steps = it.filter { c -> c.isDigit() } },
                    label = { Text("步数 (可选)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = calories, onValueChange = { calories = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("消耗热量 kcal (可选)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text("日期", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                val now = System.currentTimeMillis()
                val todayMidnight = java.util.Calendar.getInstance().apply {
                    timeInMillis = now
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }
                fun shift(delta: Int) {
                    val c = java.util.Calendar.getInstance().apply { timeInMillis = selectedTime }
                    c.add(java.util.Calendar.DAY_OF_MONTH, delta)
                    selectedTime = c.timeInMillis
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("昨天" to -1, "今天" to 0, "明天" to 1).forEach { (label, delta) ->
                        val target = java.util.Calendar.getInstance().apply {
                            timeInMillis = todayMidnight.timeInMillis
                            add(java.util.Calendar.DAY_OF_MONTH, delta)
                        }
                        val sc = java.util.Calendar.getInstance().apply { timeInMillis = selectedTime }
                        val selected = sc.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
                                       sc.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
                        FilterChip(selected = selected, onClick = { shift(delta) }, label = { Text(label, style = MaterialTheme.typography.bodySmall) })
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AccessTime, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = selectedTime }
                    val md = String.format("%02d-%02d", cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
                    Text("$md ${String.format("%02d:%02d", hour, minute)}", style = MaterialTheme.typography.bodyLarge)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        val dur = duration.toIntOrNull() ?: 30
                        if (dur > 0) {
                            onConfirm(exerciseType, dur, intensity, steps.toIntOrNull() ?: 0, calories.toDoubleOrNull() ?: 0.0, selectedTime)
                        }
                    }) { Text("保存") }
                }
            }
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(initialHour = hour, initialMinute = minute)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val c = java.util.Calendar.getInstance().apply {
                        timeInMillis = selectedTime
                        set(java.util.Calendar.HOUR_OF_DAY, timePickerState.hour)
                        set(java.util.Calendar.MINUTE, timePickerState.minute)
                    }
                    selectedTime = c.timeInMillis
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
            text = { TimePicker(state = timePickerState) }
        )
    }
}

@Composable
fun ExerciseHistoryList(
    records: List<ExerciseRecord>,
    onDelete: (ExerciseRecord) -> Unit
) {
    val dateFormat = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())

    com.tangdun.app.ui.components.TangDunCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("运动历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${records.size} 条", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))

            if (records.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("暂无运动记录", style = MaterialTheme.typography.bodyMedium, color = TextHint)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(records) { record ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.DirectionsRun, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${ExerciseRecord.getExerciseTypeName(record.exerciseType)} · ${record.durationMin ?: 0}分钟 · ${record.intensity ?: "-"}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(dateFormat.format(java.util.Date(record.startTime)), style = MaterialTheme.typography.bodySmall, color = TextHint)
                            }
                            IconButton(onClick = { onDelete(record) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", tint = AlertCritical, modifier = Modifier.size(18.dp))
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun TodayExerciseStatsCard(
    totalDuration: Int,
    totalSteps: Int,
    totalCalories: Double,
    exerciseCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "今日运动统计",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("运动时长", "${totalDuration}分钟")
                StatItem("步数", "${totalSteps}")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("消耗热量", String.format("%.0fkcal", totalCalories))
                StatItem("运动次数", "${exerciseCount}次")
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextHint
        )
    }
}

@Composable
fun ExercisePrescriptionCard(
    exerciseType: String,
    duration: Int,
    intensity: String,
    expectedDrop: Double,
    notes: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "运动处方",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "推荐运动",
                style = MaterialTheme.typography.bodySmall,
                color = TextHint
            )
            Text(
                text = exerciseType,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row {
                Text(
                    text = "时长: ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextHint
                )
                Text(
                    text = "${duration}分钟",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = "强度: ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextHint
                )
                Text(
                    text = intensity,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = String.format("预计血糖下降 %.1f mmol/L", expectedDrop),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            if (notes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}
