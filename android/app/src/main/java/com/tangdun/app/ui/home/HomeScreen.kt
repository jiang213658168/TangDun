package com.tangdun.app.ui.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.tangdun.app.ui.components.DateTimePickerDialog
import com.tangdun.app.ui.components.HeroGlucoseCard
import com.tangdun.app.ui.components.InsightStatCard
import com.tangdun.app.ui.components.ModernTopBar
import com.tangdun.app.ui.components.SectionHeader
import com.tangdun.app.ui.components.QuickActionRow
import com.tangdun.app.ui.components.QuickAction
import com.tangdun.app.ui.theme.*
import java.util.Calendar

@Composable
fun CalibrationCard(currentGlucose: Double?, offset: Double, calCount: Int, confidence: String, onCalibrate: (Double) -> Unit) {
    var fingerValue by remember { mutableStateOf("") }
    var showInput by remember { mutableStateOf(false) }
    com.tangdun.app.ui.components.TangDunCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (calCount == 0) AlertWarning.copy(alpha = 0.06f)
                             else AlertSuccess.copy(alpha = 0.06f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Biotech, null, tint = if (calCount == 0) AlertWarning else AlertSuccess, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("指尖校准", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text(if (calCount >= 1) "已校准${calCount}次 | 偏移${String.format("%+.1f", offset)}" else "未校准", fontSize = 11.sp, color = if (calCount == 0) AlertWarning else TextSecondary)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CGM: ${if (currentGlucose != null) String.format("%.1f", currentGlucose) else "--"} mmol/L", fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.width(16.dp))
                if (showInput) {
                    OutlinedTextField(value = fingerValue, onValueChange = { fingerValue = it }, label = { Text("指尖值") }, singleLine = true, modifier = Modifier.width(100.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { val v = fingerValue.toDoubleOrNull(); if (v != null && v in 1.0..33.0) { onCalibrate(v); fingerValue = ""; showInput = false } }) { Text("确认") }
                    TextButton(onClick = { showInput = false }) { Text("取消") }
                } else {
                    OutlinedButton(onClick = { showInput = true }) { Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("录入指尖值校准") }
                }
            }
        }
    }
}

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
        // ★ v2.0 重构: 现代顶部栏 (沉浸式 + 跟随主题色)
        val sdf = java.text.SimpleDateFormat("MM/dd", java.util.Locale.getDefault())
        val todayStr = sdf.format(java.util.Date())
        val selStr = sdf.format(java.util.Date(uiState.selectedDate))
        val isToday = selStr == todayStr

        ModernTopBar(
            title = "糖盾",
            subtitle = if (isToday) "今天 $selStr · 守护血糖" else "$selStr · 历史数据",
            actions = {
                IconButton(onClick = { navController?.navigate("chat") }) {
                    Icon(Icons.Default.Chat, contentDescription = "AI助手")
                }
                IconButton(onClick = { showAddGlucoseDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "记录血糖")
                }
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ★ v2.8: DataSourceCard (广播/通知监听/自检/同步历史/导入xlsx) 已迁移到 SettingsScreen
        //   详见 SettingsScreen → DataSourceCard()

        // 日期选择
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.shiftDate(-1) }) { Icon(Icons.Default.ChevronLeft, "前一天") }
            Text(if (isToday) "今天 $selStr" else selStr, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (!isToday) TextButton(onClick = { viewModel.goToToday() }) { Text("今天", fontSize = 12.sp) }
            IconButton(onClick = { viewModel.shiftDate(1) }, enabled = !isToday) { Icon(Icons.Default.ChevronRight, "后一天") }
        }

        // 预警横幅
        if (uiState.alerts.isNotEmpty()) {
            AlertBanner(
                message = uiState.alerts.first().message ?: "有新的预警",
                severity = uiState.alerts.first().severity
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ★ Hero 血糖卡 (产品级新版)
        GlucoseCard(
            currentValue = uiState.currentGlucose,
            trend = uiState.trend,
            change30min = uiState.change30min,
            onAddClick = { showAddGlucoseDialog = true }
        )

        // ★ QuickAction 快捷操作行 (v2.0 新增)
        Spacer(modifier = Modifier.height(16.dp))
        QuickActionRow(
            actions = listOf(
                QuickAction(
                    icon = Icons.Default.Restaurant,
                    label = "记录饮食",
                    accentColor = MaterialTheme.colorScheme.primary,
                    onClick = { navController?.navigate("meal") }
                ),
                QuickAction(
                    icon = Icons.Default.MedicalServices,
                    label = "胰岛素",
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    onClick = { navController?.navigate("insulin") }
                ),
                QuickAction(
                    icon = Icons.Default.DirectionsRun,
                    label = "运动",
                    accentColor = MaterialTheme.colorScheme.secondary,
                    onClick = { navController?.navigate("exercise") }
                ),
                QuickAction(
                    icon = Icons.Default.AutoAwesome,
                    label = "AI 智能记录",
                    accentColor = MaterialTheme.colorScheme.primary,
                    onClick = { navController?.navigate("chat") }
                ),
            )
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ★ Insight 洞察栏 (v2.0 新增: 用 InsightStatCard)
        SectionHeader(title = "今日洞察")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InsightStatCard(
                icon = Icons.Default.PieChart,
                label = "TIR",
                value = String.format("%.0f", uiState.tir ?: 0.0),
                unit = "%",
                accentColor = if ((uiState.tir ?: 0.0) >= 70) Success else Warning,
                modifier = Modifier.weight(1f)
            )
            InsightStatCard(
                icon = Icons.Default.Analytics,
                label = "平均血糖",
                value = String.format("%.1f", uiState.avgGlucose ?: 0.0),
                unit = "mmol/L",
                accentColor = glucoseColor(uiState.avgGlucose ?: 5.0),
                modifier = Modifier.weight(1f)
            )
            InsightStatCard(
                icon = Icons.Default.Timeline,
                label = "记录数",
                value = uiState.recordCount.toString(),
                unit = "次",
                accentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 指尖校准卡片 — 醒目位置
        CalibrationCard(
            currentGlucose = uiState.currentGlucose,
            offset = uiState.calOffset,
            calCount = uiState.calCount,
            confidence = uiState.calConfidence,
            onCalibrate = { fingerValue: Double -> viewModel.calibrateNow(fingerValue) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 智能建议
        if (uiState.advices.isNotEmpty()) {
            AdviceCard(advices = uiState.advices)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 时间范围选择
        var chartHours by remember { mutableStateOf(6) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (h in listOf(1, 3, 6, 12, 24)) {
                FilterChip(
                    selected = chartHours == h,
                    onClick = { chartHours = h },
                    label = { Text("${h}h", fontSize = 11.sp) },
                    modifier = Modifier.height(28.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        // 血糖曲线 (按选择的时间范围过滤)
        GlucoseChartCard(
            data = uiState.glucoseData.takeLast(chartHours * 12),
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
                onEdit = { record, newValue -> viewModel.editGlucose(record, newValue) },
                onTimestampEdit = { record, newTimestamp -> viewModel.editGlucoseTimestamp(record, newTimestamp) }
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
    com.tangdun.app.ui.components.TangDunCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
    // ★ v2.0 重构: 委托给 ModernComponents.HeroGlucoseCard (产品级视觉)
    HeroGlucoseCard(
        currentGlucose = currentValue,
        targetLow = 3.9,
        targetHigh = 10.0,
        change30min = change30min,
        onAddClick = onAddClick
    )
}

@Composable
fun GlucoseChartCard(
    data: List<Pair<Long, Double>>,
    targetLow: Double,
    targetHigh: Double
) {
    com.tangdun.app.ui.components.TangDunCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
    com.tangdun.app.ui.components.TangDunCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
    onEdit: (com.tangdun.app.data.local.entity.GlucoseRecord, Double) -> Unit,
    onTimestampEdit: (com.tangdun.app.data.local.entity.GlucoseRecord, Long) -> Unit
) {
    // ★ 修复: 去掉 takeLast(10) 限制, 用 LazyColumn + 固定高度让用户可滚动看全部
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = androidx.compose.foundation.BorderStroke(0.dp, androidx.compose.ui.graphics.Color.Transparent),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "今日血糖记录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${records.size} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "↑↓ 上下滑动查看全部 · 点击右侧图标编辑/删除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            // ★ 修复滚动: 用 fixed height(380.dp) 确保 LazyColumn 在 verticalScroll 父级中有可用滚动空间
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(
                    items = records.reversed(),  // 最新在上
                    key = { record: com.tangdun.app.data.local.entity.GlucoseRecord -> record.id }
                ) { record ->
                    GlucoseRecordItem(
                        record = record,
                        onDelete = { onDelete(record) },
                        onEdit = { newValue, newTimestamp ->
                            onEdit(record, newValue)
                            onTimestampEdit(record, newTimestamp)
                        }
                    )
                    Divider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        thickness = 0.5.dp
                    )
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
    onEdit: (Double, Long) -> Unit
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

    // ★ 编辑对话框 (修复跨日编辑: 支持改血糖值 + 时间)
    if (showEditDialog) {
        var newValue by remember { mutableStateOf(record.value.toString()) }
        var editTime by remember { mutableStateOf(record.timestamp) }
        var showTimePicker by remember { mutableStateOf(false) }

        val editCal = Calendar.getInstance().apply { timeInMillis = editTime }
        val editHour = editCal.get(Calendar.HOUR_OF_DAY)
        val editMinute = editCal.get(Calendar.MINUTE)

        // 跨日切换: 昨天 / 今天 / 明天
        val now = System.currentTimeMillis()
        val todayMidnight = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        fun shiftEditDate(deltaDays: Int) {
            val c = Calendar.getInstance().apply { timeInMillis = editTime }
            c.add(Calendar.DAY_OF_MONTH, deltaDays)
            editTime = c.timeInMillis
        }

        Dialog(onDismissRequest = { showEditDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "编辑血糖记录",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { newValue = it },
                        label = { Text("血糖值 (mmol/L)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("日期", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("昨天" to -1, "今天" to 0, "明天" to 1).forEach { (label, delta) ->
                            val target = Calendar.getInstance().apply {
                                timeInMillis = todayMidnight.timeInMillis
                                add(Calendar.DAY_OF_MONTH, delta)
                            }
                            val sc = Calendar.getInstance().apply { timeInMillis = editTime }
                            val selected = sc.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                                           sc.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
                            FilterChip(
                                selected = selected,
                                onClick = { shiftEditDate(delta) },
                                label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.AccessTime, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        val dateTimeFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        Text(
                            text = dateTimeFmt.format(editTime),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showEditDialog = false }) { Text("取消") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            val value = newValue.toDoubleOrNull()
                            if (value != null && value in 1.0..30.0) {
                                onEdit(value, editTime)
                                showEditDialog = false
                            }
                        }) { Text("保存") }
                    }
                }
            }
        }

        if (showTimePicker) {
            // ★ v3.0.5 完整日期时间选择器
            DateTimePickerDialog(
                initialTime = editTime,
                title = "编辑血糖时间",
                onDismiss = { showTimePicker = false },
                onConfirm = { picked ->
                    editTime = picked
                    showTimePicker = false
                }
            )
        }
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

                // ★ 修复跨日选择: 加昨天/今天/明天 快捷切换
                // 23:59 打的胰岛素编辑时, 可以选"昨天"
                val now = System.currentTimeMillis()
                val today = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                fun shiftDate(deltaDays: Int) {
                    val cal = Calendar.getInstance().apply { timeInMillis = selectedDate }
                    cal.add(Calendar.DAY_OF_MONTH, deltaDays)
                    selectedDate = cal.timeInMillis
                }
                val dayLabels = listOf("昨天" to -1, "今天" to 0, "明天" to 1)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    dayLabels.forEach { (label, delta) ->
                        val targetCal = Calendar.getInstance().apply {
                            timeInMillis = today.timeInMillis
                            add(Calendar.DAY_OF_MONTH, delta)
                        }
                        val isSelected = run {
                            val sc = Calendar.getInstance().apply { timeInMillis = selectedDate }
                            sc.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) &&
                            sc.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR)
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { shiftDate(delta) },
                            label = {
                                Text(label, style = MaterialTheme.typography.bodySmall)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.AccessTime, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    // ★ v3.0.5 显示完整日期+时间 yyyy-MM-dd HH:mm
                    val dateTimeFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    Text(
                        text = dateTimeFmt.format(selectedDate),
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

    // ★ v3.0.5 完整日期时间选择器
    if (showTimePicker) {
        DateTimePickerDialog(
            initialTime = selectedDate,
            title = "选择血糖测量时间",
            onDismiss = { showTimePicker = false },
            onConfirm = { picked ->
                selectedDate = picked
                showTimePicker = false
            }
        )
    }
}
