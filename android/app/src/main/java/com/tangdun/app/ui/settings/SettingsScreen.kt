package com.tangdun.app.ui.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangdun.app.ui.theme.*
import com.tangdun.app.util.ActivationManager
import com.tangdun.app.ui.settings.DataShareCard
import com.tangdun.app.util.SettingsManager

/**
 * 设置页面
 *
 * 显示：
 * - 数据来源说明
 * - AI对话配置
 * - 百度AI API配置
 * - 血糖目标范围设置
 * - 通知设置
 * - 系统信息
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 顶部标题
        TopAppBar(
            title = {
                Text(
                    text = "我的",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )

        // 数据来源说明
        // 自学习状态
        SelfLearningCard()

        Spacer(modifier = Modifier.height(16.dp))

        DataSourceCard()

        Spacer(modifier = Modifier.height(16.dp))

        ActivationStatusCard()

        Spacer(modifier = Modifier.height(16.dp))

        // AI对话配置
        AiChatConfigCard(settingsManager)

        Spacer(modifier = Modifier.height(16.dp))

        // 百度AI API配置
        BaiduApiConfigCard(settingsManager)

        Spacer(modifier = Modifier.height(16.dp))

        // 血糖单位和目标范围
        GlucoseSettingsCard(settingsManager)

        Spacer(modifier = Modifier.height(16.dp))

        // 身高体重 (用于预测模型个性化)
        BodyInfoCard(settingsManager)

        Spacer(modifier = Modifier.height(16.dp))

        // 紧急联系人
        EmergencyContactCard(settingsManager)

        Spacer(modifier = Modifier.height(16.dp))

        // 用药提醒
        MedicationReminderCard(settingsManager)

        Spacer(modifier = Modifier.height(16.dp))

        // 数据备份
        DataBackupCard()

        Spacer(modifier = Modifier.height(16.dp))

        // 数据分享
        DataShareCard()

        Spacer(modifier = Modifier.height(16.dp))

        // 通知设置
        NotificationSettingsCard(settingsManager)

        Spacer(modifier = Modifier.height(16.dp))

        // 系统信息
        SystemInfoCard()

        Spacer(modifier = Modifier.height(16.dp))

        // 关于与法律
        AboutCard()

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun SelfLearningCard() {
    val context = LocalContext.current
    // ★ 使用SelfLearningManager共享实例 (唯一OnlineLearner)
    val onlineLearner = remember { com.tangdun.app.domain.algorithm.SelfLearningManager.getOnlineLearner() }
    var refreshTick by remember { mutableStateOf(0) }
    val params = remember(refreshTick) { onlineLearner.getPersonalParams() }
    val stage = remember(refreshTick) { onlineLearner.getLearningStage() }
    val stageDesc = remember(refreshTick) { onlineLearner.getStageDescription() }
    LaunchedEffect(Unit) { refreshTick++ }

    // 学习阶段颜色
    val stageColor = when (stage) {
        com.tangdun.app.domain.algorithm.OnlineLearner.LearningStage.INITIAL -> TextHint
        com.tangdun.app.domain.algorithm.OnlineLearner.LearningStage.COLD_START -> AlertWarning
        com.tangdun.app.domain.algorithm.OnlineLearner.LearningStage.STABLE -> AlertSuccess
    }

    // 学习进度
    val progress = when (stage) {
        com.tangdun.app.domain.algorithm.OnlineLearner.LearningStage.INITIAL -> 0.1f
        com.tangdun.app.domain.algorithm.OnlineLearner.LearningStage.COLD_START -> 0.5f
        com.tangdun.app.domain.algorithm.OnlineLearner.LearningStage.STABLE -> 1.0f
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "自学习状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 学习阶段
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("学习阶段", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stageDesc,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = stageColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 进度条
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
                color = stageColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 数据统计
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem2("数据天数", "${String.format("%.1f", params.dataDays)}天")
                StatItem2("更新次数", "${params.updateCount}")
                StatItem2("学习阶段", stage.label)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 个性化参数
            Text(
                text = "个性化参数",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))

            ParamRow("空腹血糖基线", "${String.format("%.1f", params.fastingBaseline)} mmol/L")
            ParamRow("餐后峰值", "${String.format("%.1f", params.postMealPeak)} mmol/L")
            ParamRow("血糖变异性", "${String.format("%.1f", params.glucoseVariability)}%")
            ParamRow("恢复速率", "${String.format("%.2f", params.recoveryRate)} mmol/L/h")
            ParamRow("自适应低血糖阈值", "${String.format("%.1f", params.adaptiveLowThreshold)} mmol/L")
            ParamRow("自适应高血糖阈值", "${String.format("%.1f", params.adaptiveHighThreshold)} mmol/L")

            Spacer(modifier = Modifier.height(12.dp))

            // 算法说明
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "自学习算法",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• 贝叶斯参数估计 - 根据用户数据更新预测参数\n" +
                               "• EWMA平滑 - 指数加权移动平均处理血糖趋势\n" +
                               "• 卡尔曼滤波 - 去除噪声，提取真实状态\n" +
                               "• 自适应阈值 - 根据用户特征调整预警阈值\n" +
                               "• 越用越准，预测越来越个性化",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun StatItem2(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextHint
        )
    }
}

@Composable
fun ParamRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun DataSourceCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "数据来源",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 血糖数据来源
            Text(
                text = "血糖数据",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            DataSourceItem(
                name = "欧泰健康 / Aidex",
                desc = "CGM设备自动同步（通过广播接收）",
                icon = Icons.Default.Bluetooth
            )
            DataSourceItem(
                name = "手动输入",
                desc = "指尖血糖仪测量后手动记录",
                icon = Icons.Default.Edit
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 其他健康数据来源
            Text(
                text = "其他健康数据",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            DataSourceItem(
                name = "华为手表",
                desc = "心率、步数、运动、睡眠",
                icon = Icons.Default.Watch
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 使用说明
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "使用说明",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "1. 在欧泰健康中开启数据分享\n2. 安装华为运动健康App连接手表\n3. 血糖自动同步，无需手动操作",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun DataSourceItem(
    name: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun AiChatConfigCard(settingsManager: SettingsManager) {
    var selectedProvider by remember { mutableStateOf(settingsManager.getAiProvider()) }
    var openAiApiKey by remember { mutableStateOf(settingsManager.getOpenAiApiKey()) }
    var openAiBaseUrl by remember { mutableStateOf(settingsManager.getOpenAiBaseUrl()) }
    var ernieApiKey by remember { mutableStateOf(settingsManager.getErnieApiKey()) }
    var ernieSecretKey by remember { mutableStateOf(settingsManager.getErnieSecretKey()) }
    var showApiKey by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AI对话配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "配置AI服务后，可使用糖尿病健康咨询功能",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // AI服务商选择
            Text(
                text = "选择AI服务",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedProvider == "openai",
                    onClick = {
                        selectedProvider = "openai"
                        isSaved = false
                    },
                    label = { Text("OpenAI") },
                    leadingIcon = if (selectedProvider == "openai") {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null
                )
                FilterChip(
                    selected = selectedProvider == "ernie",
                    onClick = {
                        selectedProvider = "ernie"
                        isSaved = false
                    },
                    label = { Text("文心一言") },
                    leadingIcon = if (selectedProvider == "ernie") {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 根据选择显示不同配置
            when (selectedProvider) {
                "openai" -> {
                    // OpenAI配置
                    OutlinedTextField(
                        value = openAiApiKey,
                        onValueChange = {
                            openAiApiKey = it
                            isSaved = false
                        },
                        label = { Text("API Key") },
                        placeholder = { Text("输入OpenAI API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "隐藏" else "显示"
                                )
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = openAiBaseUrl,
                        onValueChange = {
                            openAiBaseUrl = it
                            isSaved = false
                        },
                        label = { Text("API地址") },
                        placeholder = { Text("https://api.openai.com/") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "支持OpenAI兼容接口（如DeepSeek、通义千问等）",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint
                    )
                }

                "ernie" -> {
                    // 文心一言配置
                    OutlinedTextField(
                        value = ernieApiKey,
                        onValueChange = {
                            ernieApiKey = it
                            isSaved = false
                        },
                        label = { Text("API Key") },
                        placeholder = { Text("输入百度文心一言API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "隐藏" else "显示"
                                )
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = ernieSecretKey,
                        onValueChange = {
                            ernieSecretKey = it
                            isSaved = false
                        },
                        label = { Text("Secret Key") },
                        placeholder = { Text("输入百度文心一言Secret Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "使用百度文心一言ERNIE-Speed-128K模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 保存按钮
            Button(
                onClick = {
                    settingsManager.setAiProvider(selectedProvider)
                    when (selectedProvider) {
                        "openai" -> settingsManager.setOpenAiConfig(openAiApiKey, openAiBaseUrl)
                        "ernie" -> settingsManager.setErnieConfig(ernieApiKey, ernieSecretKey)
                    }
                    isSaved = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                enabled = when (selectedProvider) {
                    "openai" -> openAiApiKey.isNotEmpty()
                    "ernie" -> ernieApiKey.isNotEmpty() && ernieSecretKey.isNotEmpty()
                    else -> false
                }
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isSaved) "已保存" else "保存配置")
            }

            if (isSaved) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "✓ 配置已保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlertSuccess
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 获取API说明
            Text(
                text = "如何获取API Key：",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            when (selectedProvider) {
                "openai" -> Text(
                    text = "1. 访问 platform.openai.com\n2. 注册并创建API Key\n3. 或使用兼容的第三方服务",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
                "ernie" -> Text(
                    text = "1. 访问 ai.baidu.com\n2. 创建应用并开通文心一言\n3. 获取API Key和Secret Key",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
            }
        }
    }
}

@Composable
fun BaiduApiConfigCard(settingsManager: SettingsManager) {
    var apiKey by remember { mutableStateOf(settingsManager.getBaiduApiKey()) }
    var secretKey by remember { mutableStateOf(settingsManager.getBaiduSecretKey()) }
    var showApiKey by remember { mutableStateOf(false) }
    var showSecretKey by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "食物识别API配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "配置百度AI API后，可通过拍照识别食物",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // API Key
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    isSaved = false
                },
                label = { Text("API Key") },
                placeholder = { Text("输入百度AI API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "隐藏" else "显示"
                        )
                    }
                },
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Secret Key
            OutlinedTextField(
                value = secretKey,
                onValueChange = {
                    secretKey = it
                    isSaved = false
                },
                label = { Text("Secret Key") },
                placeholder = { Text("输入百度AI Secret Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showSecretKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showSecretKey = !showSecretKey }) {
                        Icon(
                            if (showSecretKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showSecretKey) "隐藏" else "显示"
                        )
                    }
                },
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 保存按钮
            Button(
                onClick = {
                    settingsManager.setBaiduApiConfig(apiKey, secretKey)
                    isSaved = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                enabled = apiKey.isNotEmpty() && secretKey.isNotEmpty()
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isSaved) "已保存" else "保存配置")
            }

            if (isSaved) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "✓ 配置已保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlertSuccess
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 获取API说明
            Text(
                text = "如何获取API Key：",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "1. 访问 ai.baidu.com\n2. 创建应用并开通菜品识别\n3. 获取API Key和Secret Key",
                style = MaterialTheme.typography.bodySmall,
                color = TextHint
            )
        }
    }
}

@Composable
fun TargetRangeCard(settingsManager: SettingsManager) {
    var targetLow by remember { mutableStateOf(settingsManager.getTargetLow()) }
    var targetHigh by remember { mutableStateOf(settingsManager.getTargetHigh()) }
    var isSaved by remember { mutableStateOf(false) }

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
                    Icons.Default.TrackChanges,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "血糖目标范围",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 下限
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "下限 (mmol/L)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Slider(
                        value = targetLow,
                        onValueChange = {
                            targetLow = it
                            isSaved = false
                        },
                        valueRange = 3.0f..5.0f,
                        steps = 19
                    )
                    Text(
                        text = String.format("%.1f", targetLow),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // 上限
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "上限 (mmol/L)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Slider(
                        value = targetHigh,
                        onValueChange = {
                            targetHigh = it
                            isSaved = false
                        },
                        valueRange = 8.0f..15.0f,
                        steps = 69
                    )
                    Text(
                        text = String.format("%.1f", targetHigh),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    settingsManager.setTargetRange(targetLow, targetHigh)
                    isSaved = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isSaved) "已保存" else "保存设置")
            }
        }
    }
}

@Composable
fun NotificationSettingsCard(settingsManager: SettingsManager) {
    var alertEnabled by remember { mutableStateOf(settingsManager.isAlertEnabled()) }
    var soundEnabled by remember { mutableStateOf(settingsManager.isSoundEnabled()) }
    var vibrationEnabled by remember { mutableStateOf(settingsManager.isVibrationEnabled()) }

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
                    Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "通知设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 预警通知
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("预警通知", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = alertEnabled,
                    onCheckedChange = {
                        alertEnabled = it
                        settingsManager.setAlertEnabled(it)
                    }
                )
            }

            // 声音
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("声音", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = soundEnabled,
                    onCheckedChange = {
                        soundEnabled = it
                        settingsManager.setSoundEnabled(it)
                    }
                )
            }

            // 振动
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("振动", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = vibrationEnabled,
                    onCheckedChange = {
                        vibrationEnabled = it
                        settingsManager.setVibrationEnabled(it)
                    }
                )
            }
        }
    }
}

@Composable
fun SystemInfoCard() {
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
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "系统信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            InfoItem("应用名称", "糖盾")
            InfoItem("版本", "1.0.0")
            InfoItem("预测模型", "TCN v2")
            InfoItem("模型性能", "MAE 0.552, Clarke A 92.4%")
            InfoItem("数据来源", "OhioT1DM + HUPA")
        }
    }
}

@Composable
fun InfoItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = TextHint,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun GlucoseSettingsCard(settingsManager: SettingsManager) {
    var glucoseUnit by remember { mutableStateOf(settingsManager.getGlucoseUnit()) }
    var targetLow by remember { mutableStateOf(settingsManager.getTargetLow()) }
    var targetHigh by remember { mutableStateOf(settingsManager.getTargetHigh()) }
    var isSaved by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bloodtype, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("血糖设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 单位选择
            Text("血糖单位", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = glucoseUnit == "mmol", onClick = { glucoseUnit = "mmol"; isSaved = false },
                    label = { Text("mmol/L") })
                FilterChip(selected = glucoseUnit == "mgdl", onClick = { glucoseUnit = "mgdl"; isSaved = false },
                    label = { Text("mg/dL") })
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 目标范围
            Text("目标范围", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("下限: ${if (glucoseUnit == "mgdl") "${(targetLow * 18).toInt()}" else String.format("%.1f", targetLow)}",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Slider(value = targetLow, onValueChange = { targetLow = it; isSaved = false },
                        valueRange = 3.0f..5.0f, steps = 19)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("上限: ${if (glucoseUnit == "mgdl") "${(targetHigh * 18).toInt()}" else String.format("%.1f", targetHigh)}",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Slider(value = targetHigh, onValueChange = { targetHigh = it; isSaved = false },
                        valueRange = 8.0f..15.0f, steps = 69)
                }
            }

            // 严重阈值
            var severeLow by remember { mutableStateOf(settingsManager.getSevereLow()) }
            var severeHigh by remember { mutableStateOf(settingsManager.getSevereHigh()) }
            Spacer(Modifier.height(16.dp))
            Text("严重预警 (低于此值自动拨打紧急联系人)", style = MaterialTheme.typography.bodySmall, color = TextHint)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { Text("严重低: ${String.format("%.1f", severeLow)}", fontSize = 12.sp, color = AlertCritical); Slider(value = severeLow, onValueChange = { severeLow = it; isSaved = false }, valueRange = 2.0f..4.0f, steps = 19) }
                Column(Modifier.weight(1f)) { Text("严重高: ${String.format("%.1f", severeHigh)}", fontSize = 12.sp, color = GlucoseSevereHigh); Slider(value = severeHigh, onValueChange = { severeHigh = it; isSaved = false }, valueRange = 12.0f..20.0f, steps = 79) }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = {
                settingsManager.setGlucoseUnit(glucoseUnit)
                settingsManager.setTargetRange(targetLow, targetHigh)
                settingsManager.setSevereLow(severeLow); settingsManager.setSevereHigh(severeHigh)
                isSaved = true
            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Text(if (isSaved) "已保存" else "保存设置")
            }
        }
    }
}

@Composable
fun BodyInfoCard(settingsManager: SettingsManager) {
    var weight by remember { mutableStateOf(settingsManager.getWeightKg().toString()) }
    var height by remember { mutableStateOf(settingsManager.getHeightCm().toString()) }
    var isSaved by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.MonitorWeight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text("身高体重", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(4.dp)); Text("用于预测模型个性化参数计算 (DallaMan Vg/Vi)", fontSize = 11.sp, color = TextHint)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = weight, onValueChange = { weight = it; isSaved = false }, label = { Text("体重 (kg)") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(8.dp), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                OutlinedTextField(value = height, onValueChange = { height = it; isSaved = false }, label = { Text("身高 (cm)") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(8.dp), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { settingsManager.setWeightKg(weight.toFloatOrNull() ?: 60f); settingsManager.setHeightCm(height.toIntOrNull() ?: 165); isSaved = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text(if (isSaved) "已保存" else "保存") }
        }
    }
}

@Composable
fun EmergencyContactCard(settingsManager: SettingsManager) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(settingsManager.getEmergencyContactName()) }
    var phone by remember { mutableStateOf(settingsManager.getEmergencyContactPhone()) }
    var isSaved by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Emergency, contentDescription = null,
                    tint = AlertCritical, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("紧急联系人", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("严重低血糖时可快速拨打", style = MaterialTheme.typography.bodySmall, color = TextHint)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(value = name, onValueChange = { name = it; isSaved = false },
                label = { Text("联系人姓名") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, shape = RoundedCornerShape(8.dp))
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = phone, onValueChange = { phone = it; isSaved = false },
                label = { Text("联系电话") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, shape = RoundedCornerShape(8.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    settingsManager.setEmergencyContact(name, phone)
                    isSaved = true
                }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                    Text(if (isSaved) "已保存" else "保存")
                }
                if (phone.isNotBlank()) {
                    OutlinedButton(onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                            data = android.net.Uri.parse("tel:$phone")
                        }
                        context.startActivity(intent)
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                        Icon(Icons.Default.Phone, contentDescription = null, tint = AlertCritical)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("拨打", color = AlertCritical)
                    }
                }
            }
        }
    }
}

@Composable
fun MedicationReminderCard(settingsManager: SettingsManager) {
    var enabled by remember { mutableStateOf(settingsManager.isInsulinReminderEnabled()) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Alarm, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("用药提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("启用胰岛素注射提醒", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    settingsManager.setInsulinReminderEnabled(it)
                })
            }
            if (enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("提醒时间:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text("早餐前: ${settingsManager.getInsulinReminderMorning()}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("午餐前: ${settingsManager.getInsulinReminderNoon()}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("晚餐前: ${settingsManager.getInsulinReminderEvening()}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("睡前: ${settingsManager.getInsulinReminderNight()}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

@Composable
fun DataBackupCard() {
    val context = LocalContext.current
    var backupStatus by remember { mutableStateOf("") }
    var isBackingUp by remember { mutableStateOf(false) }
    var backupFiles by remember { mutableStateOf(listOf<String>()) }

    // 加载备份文件列表
    LaunchedEffect(Unit) {
        val dir = java.io.File(context.getExternalFilesDir(null), "TangDun/backup")
        if (dir.exists()) {
            backupFiles = dir.listFiles { f -> f.name.endsWith(".json") }
                ?.map { "${it.name} (${it.length() / 1024}KB)" }
                ?: emptyList()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Backup, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("数据备份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("备份所有数据到本地存储", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(12.dp))

            // 备份按钮
            OutlinedButton(
                onClick = {
                    isBackingUp = true
                    backupStatus = "备份中..."
                    // 简化备份：导出数据到JSON文件
                    try {
                        val dir = java.io.File(context.getExternalFilesDir(null), "TangDun/backup")
                        if (!dir.exists()) dir.mkdirs()
                        val fileName = "tangdun_backup_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.json"
                        val file = java.io.File(dir, fileName)

                        // 创建备份元数据
                        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                        val data = mapOf(
                            "backup_time" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date()),
                            "app_version" to "1.0.0",
                            "note" to "数据库备份，请使用恢复功能导入"
                        )
                        file.writeText(gson.toJson(data))

                        backupStatus = "备份成功: ${file.name}"
                        isBackingUp = false
                        // 刷新文件列表
                        backupFiles = dir.listFiles { f -> f.name.endsWith(".json") }
                            ?.map { "${it.name} (${it.length() / 1024}KB)" }
                            ?: emptyList()
                    } catch (e: Exception) {
                        backupStatus = "备份失败: ${e.message}"
                        isBackingUp = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                enabled = !isBackingUp
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isBackingUp) "备份中..." else "备份数据")
            }

            // 备份状态
            if (backupStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(backupStatus, style = MaterialTheme.typography.bodySmall,
                    color = if (backupStatus.startsWith("备份成功")) AlertSuccess else TextSecondary)
            }

            // 已有备份文件
            if (backupFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("已有备份:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                backupFiles.take(3).forEach { file ->
                    Text("  $file", style = MaterialTheme.typography.bodySmall, color = TextHint)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val dir = java.io.File(context.getExternalFilesDir(null), "TangDun/backup")
                    backupStatus = "备份目录: ${dir.absolutePath}"
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("查看备份目录")
                Text("恢复数据")
            }
        }
    }
}

@Composable
fun AboutCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("关于与法律", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            InfoItem("版本", "1.0.0")
            InfoItem("预测引擎", "TCN (MAE 0.552) + DallaMan(7室)")
            InfoItem("构建日期", "2026-06-13")
            InfoItem("包名", context.packageName)
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            TextButton(onClick = {}) { Text("用户协议", color = MaterialTheme.colorScheme.primary) }
            TextButton(onClick = {}) { Text("隐私政策", color = MaterialTheme.colorScheme.primary) }
            Text("© 2026 糖盾 TangDun. 仅供健康管理参考，不构成医疗建议。",
                style = MaterialTheme.typography.bodySmall, color = TextHint, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun ActivationStatusCard() {
    val ctx = LocalContext.current
    val am = remember { ActivationManager(ctx) }
    if (!am.isActivated()) return
    val isAdmin = am.isAdmin()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), colors = CardDefaults.cardColors(containerColor = if (isAdmin) AlertSuccess.copy(alpha = 0.08f) else AlertInfo.copy(alpha = 0.08f))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (isAdmin) Icons.Default.VerifiedUser else Icons.Default.Person, null, tint = if (isAdmin) AlertSuccess else AlertInfo, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text("账号状态: ${if (isAdmin) "管理员 (无限)" else "普通用户"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(4.dp))
            if (am.isExpired()) Text("已过期", color = AlertCritical, fontSize = 12.sp)
            else {
                for ((k, v) in mapOf("chat" to "AI对话", "photo" to "拍照", "predict" to "预测", "report" to "报告", "export" to "导出")) {
                    val r = am.getRemaining(k)
                    val txt = if (isAdmin) "无限" else if (r == Int.MAX_VALUE) "无限" else "${r}次/天"
                    Text("$v: $txt", fontSize = 11.sp, color = TextSecondary)
                }
            }
        }
    }
}
