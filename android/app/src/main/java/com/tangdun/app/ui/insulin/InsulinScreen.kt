package com.tangdun.app.ui.insulin

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
import com.tangdun.app.data.local.entity.InsulinRecord
import com.tangdun.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun InsulinScreen(
    viewModel: InsulinViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题
        TopAppBar(
            title = {
                Text(
                    text = "胰岛素记录",
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

        // 今日统计卡片
        InsulinStatsCard(
            todayTotalDose = uiState.todayTotalDose,
            iob = uiState.iob
        )

        // 胰岛素记录列表
        if (uiState.records.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MedicalServices,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = TextHint
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无胰岛素记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextHint
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击右上角 + 添加记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextHint
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.records) { record ->
                    InsulinCard(
                        record = record,
                        onDelete = { viewModel.deleteRecord(record) },
                        onEdit = { newDose, newTimestamp ->
                            viewModel.editDose(record, newDose)
                            viewModel.editTimestamp(record, newTimestamp)
                        }
                    )
                }
            }
        }
    }

    // 添加记录对话框
    if (showAddDialog) {
        AddInsulinDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { type, dose, site, notes, timestamp ->
                viewModel.addInsulin(type, dose, site, notes, timestamp)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun InsulinStatsCard(todayTotalDose: Double, iob: Double) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "今日总量",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
                Text(
                    text = String.format("%.1fU", todayTotalDose),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "活性胰岛素(IOB)",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
                Text(
                    text = String.format("%.1fU", iob),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (iob > 3.0) AlertWarning else MaterialTheme.colorScheme.primary
                )
            }
        }

        // IOB警告
        if (iob > 3.0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = AlertWarning,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "活性胰岛素较高，注意预防低血糖",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlertWarning
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsulinCard(
    record: InsulinRecord,
    onDelete: () -> Unit,
    onEdit: (dose: Double, timestamp: Long) -> Unit = { _, _ -> }
) {
    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(0.dp, androidx.compose.ui.graphics.Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Icon(
                Icons.Default.MedicalServices,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))

            // 信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = InsulinRecord.getInsulinTypeName(record.insulinType),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Text(
                        text = String.format("%.1fU", record.doseUnits),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = InsulinRecord.getInjectionSiteName(record.injectionSite),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                if (!record.notes.isNullOrBlank()) {
                    Text(
                        text = record.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint
                    )
                }
            }

            // 时间、编辑、删除
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = dateFormat.format(Date(record.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
                Row {
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "编辑",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = AlertCritical,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    // ★ 编辑对话框 (修复跨日编辑: 支持改剂量 + 时间)
    if (showEditDialog) {
        var newDose by remember { mutableStateOf(record.doseUnits.toString()) }
        var editTime by remember { mutableStateOf(record.timestamp) }
        var showTimePicker by remember { mutableStateOf(false) }

        val editCal = Calendar.getInstance().apply { timeInMillis = editTime }
        val editHour = editCal.get(Calendar.HOUR_OF_DAY)
        val editMinute = editCal.get(Calendar.MINUTE)

        // 跨日切换: 昨天 / 今天 / 明天 (解决 23:59 打的胰岛素进入第二天编辑时选昨天的需求)
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
                        text = "编辑胰岛素记录",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = newDose,
                        onValueChange = { newDose = it },
                        label = { Text("剂量 (单位U)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // ★ 跨日切换
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AccessTime, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        val monthDay = String.format("%02d-%02d", editCal.get(Calendar.MONTH) + 1, editCal.get(Calendar.DAY_OF_MONTH))
                        Text(
                            text = "$monthDay ${String.format("%02d:%02d", editHour, editMinute)}",
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
                            val dose = newDose.toDoubleOrNull()
                            if (dose != null && dose > 0) {
                                onEdit(dose, editTime)
                                showEditDialog = false
                            }
                        }) { Text("保存") }
                    }
                }
            }
        }

        // 时间选择器
        if (showTimePicker) {
            val timePickerState = rememberTimePickerState(
                initialHour = editHour,
                initialMinute = editMinute
            )
            AlertDialog(
                onDismissRequest = { showTimePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        val c = Calendar.getInstance().apply {
                            timeInMillis = editTime
                            set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                            set(Calendar.MINUTE, timePickerState.minute)
                        }
                        editTime = c.timeInMillis
                        showTimePicker = false
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
                text = { TimePicker(state = timePickerState) }
            )
        }
    }

    // 删除确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条胰岛素记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("删除", color = AlertCritical)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddInsulinDialog(
    onDismiss: () -> Unit,
    onConfirm: (insulinType: String, doseUnits: Double, injectionSite: String?, notes: String?, timestamp: Long) -> Unit
) {
    var insulinType by remember { mutableStateOf("rapid") }
    var dose by remember { mutableStateOf("") }
    var injectionSite by remember { mutableStateOf("abdomen") }
    var notes by remember { mutableStateOf("") }
    var selectedTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var showTimePicker by remember { mutableStateOf(false) }

    val calendar = Calendar.getInstance().apply { timeInMillis = selectedTime }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)

    val insulinTypes = listOf(
        "rapid" to "速效",
        "long" to "长效",
        "mixed" to "预混"
    )

    val injectionSites = listOf(
        "abdomen" to "腹部",
        "arm" to "手臂",
        "thigh" to "大腿",
        "buttock" to "臀部"
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
                    text = "记录胰岛素",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 胰岛素类型
                Text(
                    text = "胰岛素类型",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    insulinTypes.forEach { (type, name) ->
                        FilterChip(
                            selected = insulinType == type,
                            onClick = { insulinType = type },
                            label = { Text(name) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 注射时间
                Text(
                    text = "注射时间",
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
                    Text(String.format("%02d:%02d", hour, minute))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 剂量
                OutlinedTextField(
                    value = dose,
                    onValueChange = { dose = it },
                    label = { Text("剂量 (单位U)") },
                    placeholder = { Text("例如: 4.0") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 注射部位
                Text(
                    text = "注射部位",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    injectionSites.forEach { (site, name) ->
                        FilterChip(
                            selected = injectionSite == site,
                            onClick = { injectionSite = site },
                            label = { Text(name) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 备注
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("备注 (可选)") },
                    placeholder = { Text("例如: 餐前注射") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
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
                            val doseValue = dose.toDoubleOrNull()
                            if (doseValue != null && doseValue > 0) {
                                onConfirm(
                                    insulinType,
                                    doseValue,
                                    injectionSite,
                                    notes.ifBlank { null },
                                    selectedTime
                                )
                            }
                        },
                        enabled = (dose.toDoubleOrNull() ?: 0.0) > 0
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
                        timeInMillis = selectedTime
                        set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        set(Calendar.MINUTE, timePickerState.minute)
                    }
                    selectedTime = newCalendar.timeInMillis
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
