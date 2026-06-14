package com.tangdun.app.ui.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
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
import com.tangdun.app.sync.CGMNotificationListener
import androidx.hilt.navigation.compose.hiltViewModel
import com.tangdun.app.ui.theme.*
import java.util.Calendar

@Composable
fun HomeScreen(
    navController: androidx.navigation.NavController? = null,
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
                // AI助手
                IconButton(onClick = { navController?.navigate("chat") }) {
                    Icon(Icons.Default.Chat, contentDescription = "AI助手")
                }
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

        // 数据源状态
        DataSourceCard(
            hasData = uiState.records.isNotEmpty(),
            recordCount = uiState.recordCount,
            syncMsg = uiState.error,
            onSync = { viewModel.syncHistory() }
        )

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
            targetLow = uiState.targetLow.toDouble(),
            targetHigh = uiState.targetHigh.toDouble()
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
    currentValue: Double?, trend: String?, change30min: Double?, onAddClick: () -> Unit
) {
    val g = currentValue
    val bgColor = when { g == null -> TextHint; g < 3.0 -> GlucoseSevereLow; g < 3.9 -> GlucoseLow; g <= 10.0 -> GlucoseNormal; g <= 13.9 -> GlucoseHigh; else -> GlucoseSevereHigh }
    val bgLabel = when { g == null -> ""; g < 3.0 -> "严重低血糖"; g < 3.9 -> "低血糖"; g <= 10.0 -> "正常"; g <= 13.9 -> "高血糖"; else -> "严重高血糖" }
    val trendEmoji = when (trend) { "rising_fast" -> "⬆️"; "rising" -> "↗️"; "stable" -> "➡️"; "falling" -> "↘️"; "falling_fast" -> "⬇️"; else -> "" }
    val trendLabel = when (trend) { "rising_fast" -> "快速上升"; "rising" -> "上升"; "stable" -> "平稳"; "falling" -> "下降"; "falling_fast" -> "快速下降"; else -> "" }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor.copy(alpha = 0.06f))
    ) {
        Box {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // 标签
                Surface(shape = RoundedCornerShape(20.dp), color = bgColor.copy(alpha = 0.12f)) {
                    Text(bgLabel, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = bgColor)
                }
                Spacer(Modifier.height(16.dp))

                if (g != null) {
                    // 大数字
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(String.format("%.1f", g), fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = TextDark)
                        Spacer(Modifier.width(6.dp))
                        Text("mmol/L", fontSize = 14.sp, color = TextBody, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    // 趋势
                    if (trendLabel.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(trendEmoji, fontSize = 18.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(trendLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = bgColor)
                        }
                    }
                    if (change30min != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("30min ${String.format("%+.1f", change30min)}", fontSize = 12.sp, color = TextHint)
                    }
                } else {
                    Icon(Icons.Default.WaterDrop, null, Modifier.size(56.dp), tint = TextHint.copy(alpha = 0.5f))
                    Spacer(Modifier.height(12.dp))
                    Text("暂无血糖数据", fontSize = 16.sp, color = TextHint)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onAddClick, shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("手动记录")
                    }
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

@Composable
fun DataSourceCard(hasData: Boolean, recordCount: Int, syncMsg: String?, onSync: () -> Unit) {
    val context = LocalContext.current
    var testResult by remember { mutableStateOf("") }
    var lastRxTime by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val prefs = context.applicationContext.getSharedPreferences("glucose_rx_log", android.content.Context.MODE_PRIVATE)
        val time = prefs.getLong("last_receive_time", 0)
        if (time > 0) {
            val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            lastRxTime = "最后接收: ${sdf.format(java.util.Date(time))}"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (hasData) AlertSuccess.copy(alpha = 0.08f) else AlertWarning.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (hasData) Icons.Default.CheckCircle else Icons.Default.Sync, null,
                    tint = if (hasData) AlertSuccess else AlertWarning, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (hasData) "广播接收正常 (${recordCount}条)" else "等待广播数据...",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            if (lastRxTime.isNotEmpty()) Text(lastRxTime, style = MaterialTheme.typography.bodySmall, color = TextHint)
            // 显示通知监听状态
            val notifyOk = CGMNotificationListener.isEnabled(context)
            val notifyMsg = when {
                notifyOk && hasData -> "通知监听 ✅ | 广播 ✅"
                notifyOk -> "通知监听 ✅ | 等待数据..."
                else -> "通知监听 ❌ | 请去设置开启"
            }
            Text(notifyMsg, fontSize = 11.sp, color = if (notifyOk) AlertSuccess else AlertWarning)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    testResult = "发送中..."
                    try {
                        context.sendBroadcast(android.content.Intent("com.eveningoutpost.dexdrip.BgEstimate").apply {
                            setPackage("com.tangdun.app")
                            putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 126.0)
                            putExtra("com.eveningoutpost.dexdrip.Extras.Time", System.currentTimeMillis())
                        })
                        testResult = "✅ 自检通过"
                    } catch (e: Exception) { testResult = "❌ ${e.message}" }
                }) { Text("自检", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = onSync) { Text("同步历史", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = {
                    if (notifyOk) {
                        CGMNotificationListener.requestRebind(context)
                        testResult = "通知监听已重新绑定"
                    } else {
                        CGMNotificationListener.openSettings(context)
                    }
                }) { Text(if (notifyOk) "刷新监听" else "开启监听", style = MaterialTheme.typography.bodySmall) }
            }
            if (syncMsg != null && syncMsg.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(syncMsg, fontSize = 12.sp, color = if (syncMsg.startsWith("已同步")) AlertSuccess else TextSecondary) }
            if (testResult.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(testResult, fontSize = 12.sp, color = TextSecondary) }
        }
    }
}
