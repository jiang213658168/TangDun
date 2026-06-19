package com.tangdun.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tangdun.app.data.local.entity.ChatMessage
import com.tangdun.app.ui.theme.*
import kotlinx.coroutines.launch

/** ★ AI 助手权限: ParsedRecord → UI 显示辅助 */
private fun ParsedRecord.icon(): ImageVector = when (this) {
    is ParsedRecord.Meal -> Icons.Default.Restaurant
    is ParsedRecord.Insulin -> Icons.Default.MedicalServices
    is ParsedRecord.Exercise -> Icons.Default.DirectionsRun
    is ParsedRecord.Glucose -> Icons.Default.MonitorHeart
    is ParsedRecord.Medication -> Icons.Default.Medication
    is ParsedRecord.Weight -> Icons.Default.Scale
    is ParsedRecord.Symptom -> Icons.Default.Healing
}

private fun ParsedRecord.summary(): String = when (this) {
    is ParsedRecord.Meal -> "$food ${carbs.toInt()}g碳水"
    is ParsedRecord.Insulin -> "${dose}U $doseType"
    is ParsedRecord.Exercise -> "$exType ${minutes}min"
    is ParsedRecord.Glucose -> "${value}mmol/L $scene"
    is ParsedRecord.Medication -> "$name $dose"
    is ParsedRecord.Weight -> "${value}kg"
    is ParsedRecord.Symptom -> description
}

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val foodHelper = remember(context) { FoodAssistantHelper(context) }

    // ★ AI 助手权限: 应用记录弹窗状态
    var showApplyDialog by remember { mutableStateOf(false) }

    // 自动创建新会话
    LaunchedEffect(Unit) {
        if (uiState.conversationId == null) {
            viewModel.createNewConversation()
        }
    }

    // 自动滚动到底部
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "糖盾AI助手",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (uiState.isLoading) {
                        Text(
                            text = "正在思考...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextHint
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = { viewModel.createNewConversation() }) {
                    Icon(Icons.Default.Add, contentDescription = "新对话")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )

        // 消息列表
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ★ 食物助手面板 (3 个核心功能快捷入口)
            item {
                FoodAssistantSection(helper = foodHelper)
            }

            // 欢迎消息
            if (uiState.messages.isEmpty()) {
                item {
                    WelcomeMessage(onQuickQuestion = { viewModel.sendMessage(it) })
                }
            }

            // 消息列表
            items(uiState.messages) { message ->
                ChatBubble(message = message)
            }

            // 加载指示器
            if (uiState.isLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "正在思考...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 错误提示
        uiState.error?.let { error ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = AlertCritical.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = AlertCritical,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = AlertCritical
                    )
                }
            }
        }

        // ★ AI 助手权限: 解析到可应用记录 → 显示应用按钮
        if (uiState.pendingRecords.isNotEmpty() && !uiState.isLoading) {
            ApplyRecordsBar(
                count = uiState.pendingRecords.size,
                onApply = { showApplyDialog = true },
                onDismiss = { viewModel.dismissPendingRecords() }
            )
        }

        // 输入框
        ChatInputBar(
            onSendMessage = { message ->
                viewModel.sendMessage(message)
            },
            isLoading = uiState.isLoading
        )
    }

    // ★ AI 助手权限: 应用建议确认弹窗
    if (showApplyDialog && uiState.pendingRecords.isNotEmpty()) {
        ApplyRecordsDialog(
            records = uiState.pendingRecords,
            onDismiss = { showApplyDialog = false },
            onConfirm = { selected ->
                showApplyDialog = false
                viewModel.applyPendingRecords(selected) { success, total ->
                    // 用 Toast 提示结果 (简化: 也可加 SnackBar)
                    android.widget.Toast.makeText(
                        context,
                        "✅ 已保存 $success/$total 条记录",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}

@Composable
private fun ApplyRecordsBar(
    count: Int,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AutoAwesome, contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🤖 AI 解析了 $count 条可应用记录",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "点击查看并选择要保存到数据库的记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            TextButton(onClick = onDismiss) { Text("忽略") }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = onApply,
                shape = RoundedCornerShape(12.dp)
            ) { Text("查看") }
        }
    }
}

@Composable
private fun ApplyRecordsDialog(
    records: List<ParsedRecord>,
    onDismiss: () -> Unit,
    onConfirm: (List<ParsedRecord>) -> Unit
) {
    // 用户可勾选要保存哪些记录 (默认全选)
    val selected = remember { mutableStateListOf<ParsedRecord>().apply { addAll(records) } }
    var selectAll by remember { mutableStateOf(true) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "AI 解析的记录",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 全选/取消全选
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = selectAll,
                        onCheckedChange = {
                            selectAll = it
                            selected.clear()
                            if (it) selected.addAll(records)
                        }
                    )
                    Text(
                        "全选 (${selected.size}/${records.size})",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(Modifier.height(8.dp))

                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(
                        items = records,
                        key = { it.timestamp.toString() + it.typeLabel }
                    ) { record ->
                        RecordCheckItem(
                            record = record,
                            checked = record in selected,
                            onCheckedChange = { checked ->
                                if (checked) selected.add(record) else selected.remove(record)
                                selectAll = selected.size == records.size
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(selected.toList()) },
                        enabled = selected.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("保存 ${selected.size} 条")
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordCheckItem(
    record: ParsedRecord,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            record.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.summary(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${record.timeDisplay} · ${record.typeLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun WelcomeMessage(onQuickQuestion: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "你好！我是糖盾AI助手",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "我可以帮助你解答糖尿病相关问题：",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 快捷问题
            val quickQuestions = listOf(
                "血糖偏高怎么办？",
                "低血糖如何处理？",
                "糖尿病可以吃水果吗？",
                "运动对血糖有什么影响？"
            )

            quickQuestions.forEach { question ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onQuickQuestion(question) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text(
                        text = question,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.ROLE_USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = if (isUser) 16.dp else 4.dp,
                        topEnd = if (isUser) 4.dp else 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd = 16.dp
                    )
                )
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(12.dp)
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurface
            )
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun ChatInputBar(
    onSendMessage: (String) -> Unit,
    isLoading: Boolean
) {
    var text by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()           // ★ 键盘弹出时自动上移
            .navigationBarsPadding(), // ★ 同时处理底部导航栏
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入你的问题...") },
                singleLine = false,
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                enabled = !isLoading
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (text.isNotBlank() && !isLoading) {
                        onSendMessage(text.trim())
                        text = ""
                    }
                },
                enabled = text.isNotBlank() && !isLoading
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "发送",
                    tint = if (text.isNotBlank() && !isLoading)
                           MaterialTheme.colorScheme.primary
                          else TextHint
                )
            }
        }
    }
}
