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
import com.tangdun.app.ai.AIIntent
import com.tangdun.app.data.local.entity.ChatMessage
import com.tangdun.app.data.local.entity.Conversation
import com.tangdun.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val foodHelper = remember(context) { FoodAssistantHelper(context) }

    // ★ AI 助手权限: 应用记录弹窗状态
    var showApplyDialog by remember { mutableStateOf(false) }
    // ★ AI 助手历史: 抽屉状态
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    // 自动创建新会话 + 加载历史
    LaunchedEffect(Unit) {
        if (uiState.conversationId == null) {
            viewModel.createNewConversation()
        }
        viewModel.refreshConversations()
    }

    // 自动滚动到底部
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // ★ AI 助手全套权限 (v2.7): 监听执行结果, Toast 提示
    LaunchedEffect(uiState.lastExecutionMessage) {
        uiState.lastExecutionMessage?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearExecutionMessage()
        }
    }

    // ★ AI 助手全套权限: 监听导航请求, 触发 NavController 跳转
    LaunchedEffect(uiState.navigateRequest) {
        uiState.navigateRequest?.let { route ->
            when (route) {
                "home" -> navController?.navigate("home")
                "prediction" -> navController?.navigate("prediction")
                "meal" -> navController?.navigate("meal")
                "insulin" -> navController?.navigate("insulin")
                "exercise" -> navController?.navigate("exercise")
                "health" -> navController?.navigate("health")
                "settings" -> navController?.navigate("settings")
                "chat" -> navController?.navigate("chat")
                "report" -> navController?.navigate("report")
                else -> navController?.navigate("home")
            }
            viewModel.clearNavigateRequest()
        }
    }

    // ★ ModalNavigationDrawer: 侧边栏显示历史会话
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                conversations = uiState.conversations,
                currentId = uiState.conversationId,
                onNewConversation = {
                    viewModel.createNewConversation()
                    coroutineScope.launch { drawerState.close() }
                },
                onSelect = { id ->
                    viewModel.loadConversation(id)
                    coroutineScope.launch { drawerState.close() }
                },
                onDelete = { id ->
                    viewModel.deleteConversation(id)
                }
            )
        }
    ) {
        // ★ 修复键盘遮挡: 用 Scaffold 让 bottomBar 自动处理 imePadding
        Scaffold(
            topBar = {
                // ★ v2.9 顶栏紧凑化: 用 Surface + Row 自定义 (48dp 高), 去掉默认 TopAppBar 的 padding/insets 让顶栏不再臃肿
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "历史会话", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "AI助手",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1,
                        )
                        if (uiState.isLoading) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "思考中...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                                maxLines = 1,
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { navController?.navigate("home") }) {
                            Icon(Icons.Default.Home, contentDescription = "首页", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { navController?.navigate("prediction") }) {
                            Icon(Icons.Default.Timeline, contentDescription = "预测", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { navController?.navigate("insulin") }) {
                            Icon(Icons.Default.MedicalServices, contentDescription = "胰岛素", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { viewModel.createNewConversation() }) {
                            Icon(Icons.Default.Add, contentDescription = "新对话", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            },
        bottomBar = {
            // ★ 关键修复: Scaffold.bottomBar 自动处理 imePadding, 输入框永远在键盘上方
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                // 错误提示
                uiState.error?.let { error ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = AlertCritical.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning, contentDescription = null,
                                tint = AlertCritical,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = AlertCritical,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // ★ AI 助手权限: 解析到可应用记录 → 显示应用按钮 (在输入框上方)
                if (uiState.pendingRecords.isNotEmpty() && !uiState.isLoading) {
                    ApplyRecordsBar(
                        count = uiState.pendingRecords.size,
                        onApply = { showApplyDialog = true },
                        onDismiss = { viewModel.dismissPendingRecords() }
                    )
                }

                // 输入框 (在 bottomBar 内, Scaffold 自动处理键盘)
                ChatInputBar(
                    onSendMessage = { message -> viewModel.sendMessage(message) },
                    isLoading = uiState.isLoading
                )
            }
        }
    ) { paddingValues ->
        // 消息列表
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ★ 食物助手面板
            item { FoodAssistantSection(helper = foodHelper) }

            // 欢迎消息
            if (uiState.messages.isEmpty()) {
                item { WelcomeMessage(onQuickQuestion = { viewModel.sendMessage(it) }) }
            }

            // 消息列表
            items(uiState.messages) { message -> ChatBubble(message = message) }

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
    }

    // ★ AI 助手权限: 应用建议确认弹窗
    if (showApplyDialog && uiState.pendingRecords.isNotEmpty()) {
        ApplyRecordsDialog(
            records = uiState.pendingRecords,
            onDismiss = { showApplyDialog = false },
            onConfirm = { selected ->
                showApplyDialog = false
                viewModel.applyPendingRecords(selected) { success, total ->
                    android.widget.Toast.makeText(
                        context,
                        "✅ 已保存 $success/$total 条记录",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    // ★ AI 助手全套权限: 待执行动作 (导航/查询/修改/删除) 确认弹窗
    uiState.pendingAction?.let { action ->
        PendingActionDialog(
            action = action,
            onConfirm = {
                viewModel.confirmPendingAction { target ->
                    // ★ 执行导航: 根据 target 跳转
                    when (target) {
                        "home" -> navController?.navigate("home")
                        "prediction" -> navController?.navigate("prediction")
                        "meal" -> navController?.navigate("meal")
                        "insulin" -> navController?.navigate("insulin")
                        "exercise" -> navController?.navigate("exercise")
                        "ai_record" -> navController?.navigate("ai_record")
                        "records" -> navController?.navigate("records")
                        else -> navController?.navigate("home")
                    }
                }
            },
            onDismiss = { viewModel.dismissPendingAction() }
        )
    }

    // ★ AI 助手全套权限 (v2.7): 待执行的 AIIntent 列表 - 弹确认
    if (uiState.pendingIntents.isNotEmpty() && !uiState.isLoading) {
        IntentConfirmBar(
            intents = uiState.pendingIntents,
            onConfirm = { viewModel.applyPendingIntents() },
            onDismiss = { viewModel.dismissPendingIntents() }
        )
    }
}

// ★ AI 助手执行结果 Toast + 导航请求 LaunchedEffect
//   用 LaunchedEffect 监听 uiState.lastExecutionMessage 和 navigateRequest
//   触发 Toast 和跳转
}

@Composable
private fun ChatHistoryDrawer(
    conversations: List<Conversation>,
    currentId: String?,
    onNewConversation: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 16.dp)
        ) {
            // 标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("历史会话", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${conversations.size}", style = MaterialTheme.typography.labelMedium, color = TextHint)
            }

            // 新对话按钮
            Button(
                onClick = onNewConversation,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开始新对话")
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // 会话列表
            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Forum, contentDescription = null, tint = TextHint, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("暂无历史会话", style = MaterialTheme.typography.bodyMedium, color = TextHint)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        ChatHistoryItem(
                            conversation = conv,
                            isCurrent = conv.id == currentId,
                            dateFormat = dateFormat,
                            onClick = { onSelect(conv.id) },
                            onDelete = { onDelete(conv.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatHistoryItem(
    conversation: Conversation,
    isCurrent: Boolean,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                else androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isCurrent) Icons.Default.Chat else Icons.Default.Forum,
                contentDescription = null,
                tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "${conversation.messageCount}条 · ${dateFormat.format(Date(conversation.updatedAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = AlertCritical.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除会话?") },
            text = { Text("将删除该会话及其全部消息,无法恢复") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("删除", color = AlertCritical)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PendingActionDialog(
    action: PendingAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val icon = when (action) {
        is PendingAction.Navigate -> Icons.Default.ArrowForward
        is PendingAction.DeleteRecord -> Icons.Default.Delete
        is PendingAction.UpdateRecord -> Icons.Default.Edit
        is PendingAction.BulkDelete -> Icons.Default.DeleteSweep
    }
    val tint = when (action) {
        is PendingAction.Navigate -> MaterialTheme.colorScheme.primary
        is PendingAction.DeleteRecord, is PendingAction.BulkDelete -> AlertCritical
        is PendingAction.UpdateRecord -> MaterialTheme.colorScheme.tertiary
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("AI 助手请求执行", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(action.description, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("拒绝") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = tint)
                    ) { Text("确认") }
                }
            }
        }
    }
}

/**
 * ★ AI 助手全套权限 (v2.7): 待执行 AIIntent 列表的确认条
 * 显示在输入框上方, 让用户一目了然要执行的操作
 */
@Composable
private fun IntentConfirmBar(
    intents: List<AIIntent>,
    onConfirm: () -> Unit,
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
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "AI 助手待执行 ${intents.size} 个操作",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            intents.take(5).forEach { intent ->
                Text(
                    text = "• ${intent.description}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            if (intents.size > 5) {
                Text(
                    text = "  ...还有 ${intents.size - 5} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("执行 ${intents.size} 个操作")
                }
            }
        }
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

    // ★ 修复键盘遮挡: imePadding 由外层 Scaffold.bottomBar 处理, 这里不再重复添加
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),  // 只处理底部导航栏 (全面屏手势条)
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入你的问题或健康记录...") },
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
                enabled = text.isNotBlank() && !isLoading,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "发送",
                    tint = if (text.isNotBlank() && !isLoading)
                           MaterialTheme.colorScheme.primary
                          else TextHint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
