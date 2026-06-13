package com.tangdun.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.tangdun.app.ui.theme.*
import java.util.Calendar

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddGlucoseDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 顶部标题
        TopAppBar(
            title = {
                Text(
                    text = "糖盾",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            actions = {
                // 添加血糖按钮
                IconButton(onClick = { showAddGlucoseDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "记录血糖")
                }
                // 刷新按钮
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )

        // xDrip+转发状态
        if (!uiState.isXDripConnected) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AlertInfo.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("通过 xDrip+ 获取血糖", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "xdrip+设置 → Inter-app → ✅Broadcast locally → Identify receiver: com.tangdun.app",
                        style = MaterialTheme.typography.bodySmall, color = TextHint
                    )
                }
            }
        }

        // 预警横幅
        if (uiState.alerts.isNotEmpty()) {
            AlertBanner(
                message = uiState.alerts.first().message ?: "有新的预警",
                severity = uiState.alerts.first().severity
            )
        }

        // 当前血糖卡片
        GlucoseCard(
            currentValue = uiState.currentGlucose,
            trend = uiState.trend,
            change30min = uiState.change30min,
            onAddClick = { showAddGlucoseDialog = true }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 智能建议
        if (uiState.advices.isNotEmpty()) {
            AdviceCard(advices = uiState.advices)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 今日血糖曲线
        GlucoseChartCard(
            data = uiState.glucoseData,
            targetLow = 3.9,
            targetHigh = 10.0
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 今日统计
        TodayStatsCard(
            avgGlucose = uiState.avgGlucose,
            tir = uiState.tir,
            recordCount = uiState.recordCount
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 血糖记录列表
        if (uiState.records.isNotEmpty()) {
            GlucoseRecordsList(
                records = uiState.records,
                onDelete = { viewModel.deleteGlucose(it) },
                onEdit = { record, newValue -> viewModel.editGlucose(record, newValue) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // 添加血糖对话框
    if (showAddGlucoseDialog) {
        AddGlucoseDialog(
            onDismiss = { showAddGlucoseDialog = false },
            onConfirm = { value, source, timestamp, scene ->
                viewModel.addGlucose(value, source, timestamp, scene)
                showAddGlucoseDialog = false
            }
        )
    }
}

@Composable
fun AdviceCard(advices: List<com.tangdun.app.domain.algorithm.SmartAdvisor.Advice>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "智能建议",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            advices.forEach { advice ->
                AdviceItem(advice = advice)
                if (advice != advices.last()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun AdviceItem(advice: com.tangdun.app.domain.algorithm.SmartAdvisor.Advice) {
    val iconColor = when (advice.priority) {
        com.tangdun.app.domain.algorithm.SmartAdvisor.Priority.HIGH -> AlertCritical
        com.tangdun.app.domain.algorithm.SmartAdvisor.Priority.MEDIUM -> AlertWarning
        com.tangdun.app.domain.algorithm.SmartAdvisor.Priority.LOW -> AlertSuccess
    }

    val icon = when (advice.type) {
        com.tangdun.app.domain.algorithm.SmartAdvisor.AdviceType.INSULIN_BOLUS -> Icons.Default.MedicalServices
        com.tangdun.app.domain.algorithm.SmartAdvisor.AdviceType.EXERCISE -> Icons.Default.DirectionsRun
        com.tangdun.app.domain.algorithm.SmartAdvisor.AdviceType.CARB_INTAKE -> Icons.Default.Restaurant
        com.tangdun.app.domain.algorithm.SmartAdvisor.AdviceType.MONITOR -> Icons.Default.Visibility
        com.tangdun.app.domain.algorithm.SmartAdvisor.AdviceType.WARNING -> Icons.Default.Warning
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = advice.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = iconColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = advice.message,
                style = MaterialTheme.typography.bodyMedium
            )

            // 详细信息
            if (advice.details.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                advice.details.forEach { detail ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = "  ",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // 操作按钮
            if (advice.action != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { /* 智能建议为参考，用户自行决定是否执行 */ },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = iconColor
                    )
                ) {
                    Text(advice.action)
                }
            }
        }
    }
}

@Composable
fun XDripStatusCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = AlertWarning.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = AlertWarning,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "xDrip+ 未连接",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "安装xDrip+并连接CGM设备可自动同步血糖",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun AlertBanner(message: String, severity: String) {
    val backgroundColor = when (severity) {
        "critical" -> AlertCritical.copy(alpha = 0.1f)
        "warning" -> AlertWarning.copy(alpha = 0.1f)
        else -> AlertInfo.copy(alpha = 0.1f)
    }
    val iconColor = when (severity) {
        "critical" -> AlertCritical
        "warning" -> AlertWarning
        else -> AlertInfo
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (severity == "critical") Icons.Default.Warning else Icons.Default.Info,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun GlucoseCard(
    currentValue: Double?,
    trend: String?,
    change30min: Double?,
    onAddClick: () -> Unit
) {
    val glucoseColor = when {
        currentValue == null -> TextHint
        currentValue < 3.0 -> GlucoseSevereLow
        currentValue < 3.9 -> GlucoseLow
        currentValue <= 10.0 -> GlucoseNormal
        currentValue <= 13.9 -> GlucoseHigh
        else -> GlucoseSevereHigh
    }

    val trendText = when (trend) {
        "rising_fast" -> "⬆️ 快速上升"
        "rising" -> "↗️ 上升"
        "stable" -> "➡️ 平稳"
        "falling" -> "↘️ 下降"
        "falling_fast" -> "⬇️ 快速下降"
        else -> "❓ 未知"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "当前血糖",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (currentValue != null) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = String.format("%.1f", currentValue),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 56.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = glucoseColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "mmol/L",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(glucoseColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = trendText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = glucoseColor
                    )
                }

                if (change30min != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "30分钟变化: ${String.format("%+.1f", change30min)} mmol/L",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint
                    )
                }
            } else {
                // 无数据状态
                Spacer(modifier = Modifier.height(16.dp))
                Icon(
                    Icons.Default.Bloodtype,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = TextHint
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "暂无血糖数据",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextHint
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onAddClick,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("记录血糖")
                }
            }
        }
    }
}

@Composable
fun GlucoseChartCard(
    data: List<Pair<Long, Double>>,
    targetLow: Double,
    targetHigh: Double
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "今日血糖曲线",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (data.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.ShowChart,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = TextHint
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "记录血糖后显示曲线",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextHint
                        )
                    }
                }
            } else {
                // 使用自定义图表View
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    factory = { context ->
                        com.tangdun.app.widget.GlucoseChartView(context).apply {
                            setData(data)
                            setTargetRange(targetLow, targetHigh)
                        }
                    },
                    update = { view ->
                        view.setData(data)
                        view.setTargetRange(targetLow, targetHigh)
                    }
                )
            }
        }
    }
}

@Composable
fun TodayStatsCard(
    avgGlucose: Double?,
    tir: Double?,
    recordCount: Int
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
                text = "今日统计",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "平均血糖",
                    value = if (avgGlucose != null) String.format("%.1f", avgGlucose) else "--",
                    unit = "mmol/L"
                )
                StatItem(
                    label = "TIR",
                    value = if (tir != null) String.format("%.0f", tir) else "--",
                    unit = "%"
                )
                StatItem(
                    label = "记录数",
                    value = "$recordCount",
                    unit = "次"
                )
            }
        }
    }
}

@Composable
fun GlucoseRecordsList(
    records: List<com.tangdun.app.data.local.entity.GlucoseRecord>,
    onDelete: (com.tangdun.app.data.local.entity.GlucoseRecord) -> Unit,
    onEdit: (com.tangdun.app.data.local.entity.GlucoseRecord, Double) -> Unit
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
                text = "今日血糖记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            records.takeLast(10).reversed().forEach { record ->
                GlucoseRecordItem(
                    record = record,
                    onDelete = { onDelete(record) },
                    onEdit = { newValue -> onEdit(record, newValue) }
                )
                if (record != records.first()) {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlucoseRecordItem(
    record: com.tangdun.app.data.local.entity.GlucoseRecord,
    onDelete: () -> Unit,
    onEdit: (Double) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    val time = timeFormat.format(java.util.Date(record.timestamp))

    val sceneText = when (record.scene) {
        "fasting" -> "空腹"
        "before_meal" -> "餐前"
        "after_meal" -> "餐后"
        "bedtime" -> "睡前"
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 时间
        Text(
            text = time,
            style = MaterialTheme.typography.bodySmall,
            color = TextHint,
            modifier = Modifier.width(50.dp)
        )

        // 血糖值
        Text(
            text = String.format("%.1f", record.value),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                record.value < 3.9 -> GlucoseLow
                record.value > 10.0 -> GlucoseHigh
                else -> GlucoseNormal
            }
        )
        Text(
            text = " mmol/L",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        // 场景
        if (sceneText.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = sceneText,
                style = MaterialTheme.typography.bodySmall,
                color = TextHint
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 编辑按钮
        IconButton(
            onClick = { showEditDialog = true },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp), tint = TextSecondary)
        }

        // 删除按钮
        IconButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp), tint = AlertCritical)
        }
    }

    // 删除确认
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条血糖记录吗？") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text("删除", color = AlertCritical)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    // 编辑对话框
    if (showEditDialog) {
        var newValue by remember { mutableStateOf(record.value.toString()) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("编辑血糖值") },
            text = {
                OutlinedTextField(
                    value = newValue,
                    onValueChange = { newValue = it },
                    label = { Text("血糖值 (mmol/L)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = newValue.toDoubleOrNull()
                    if (value != null && value in 1.0..30.0) {
                        onEdit(value)
                        showEditDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
fun StatItem(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.bodySmall,
            color = TextHint
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGlucoseDialog(
    onDismiss: () -> Unit,
    onConfirm: (value: Double, source: String, timestamp: Long, scene: String) -> Unit
) {
    var glucoseValue by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("finger") }
    var scene by remember { mutableStateOf("other") }
    var selectedDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var showTimePicker by remember { mutableStateOf(false) }

    // 当前时间
    val calendar = Calendar.getInstance().apply { timeInMillis = selectedDate }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)

    val scenes = listOf(
        "fasting" to "空腹",
        "before_meal" to "餐前",
        "after_meal" to "餐后",
        "bedtime" to "睡前",
        "other" to "其他"
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "记录血糖",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 血糖值输入
                OutlinedTextField(
                    value = glucoseValue,
                    onValueChange = { glucoseValue = it },
                    label = { Text("血糖值 (mmol/L)") },
                    placeholder = { Text("例如: 6.5") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 测量时间
                Text(
                    text = "测量时间",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AccessTime, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = String.format("%02d:%02d", hour, minute),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 测量场景
                Text(
                    text = "测量场景",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    scenes.take(3).forEach { (value, label) ->
                        FilterChip(
                            selected = scene == value,
                            onClick = { scene = value },
                            label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    scenes.drop(3).forEach { (value, label) ->
                        FilterChip(
                            selected = scene == value,
                            onClick = { scene = value },
                            label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 数据来源
                Text(
                    text = "数据来源",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = source == "finger",
                        onClick = { source = "finger" },
                        label = { Text("指尖血糖仪") }
                    )
                    FilterChip(
                        selected = source == "cgm",
                        onClick = { source = "cgm" },
                        label = { Text("CGM读数") }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 参考范围
                Text(
                    text = "正常范围: 3.9-10.0 mmol/L",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val value = glucoseValue.toDoubleOrNull()
                            if (value != null && value in 1.0..30.0) {
                                onConfirm(value, source, selectedDate, scene)
                            }
                        },
                        enabled = glucoseValue.toDoubleOrNull() != null
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }

    // 时间选择器
    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val newCalendar = Calendar.getInstance().apply {
                        timeInMillis = selectedDate
                        set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        set(Calendar.MINUTE, timePickerState.minute)
                    }
                    selectedDate = newCalendar.timeInMillis
                    showTimePicker = false
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("取消")
                }
            },
            text = {
                TimePicker(state = timePickerState)
            }
        )
    }
}
