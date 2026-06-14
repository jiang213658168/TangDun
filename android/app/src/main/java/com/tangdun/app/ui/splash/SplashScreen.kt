package com.tangdun.app.ui.splash

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangdun.app.util.ActivationManager
import com.tangdun.app.util.SettingsManager
import kotlinx.coroutines.delay

/**
 * 启动页 - 品牌展示 + 用户协议检查
 */
@Composable
fun SplashScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    var showAgreement by remember { mutableStateOf(false) }
    var showActivate by remember { mutableStateOf(false) }
    val activator = remember { ActivationManager(context) }
    val alpha by animateFloatAsState(targetValue = if (!showAgreement && !showActivate) 1f else 0f, animationSpec = tween(800))

    LaunchedEffect(Unit) {
        delay(1200)
        val agreed = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("agreement_accepted", false)
        if (!agreed) { showAgreement = true }
        else if (!activator.isActivated() || activator.isExpired()) { showActivate = true }
        else { onFinish() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF007A8C),
                        Color(0xFF005F6E)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(alpha)
        ) {
            Text("糖盾", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("TangDun", fontSize = 18.sp, color = Color.White.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(4.dp))
            Text("糖尿病智能健康管理系统", fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(48.dp))
            Text("v1.0.0", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
        }
    }

    if (showAgreement) {
        AgreementDialog(
            onAccept = {
                context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("agreement_accepted", true).apply()
                showAgreement = false
                if (!activator.isActivated() || activator.isExpired()) { showActivate = true }
                else { onFinish() }
            },
            onReject = {
                // 退出应用
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        )
    }

    if (showActivate) {
        var showResult by remember { mutableStateOf(false) }
        ActivationDialog(activator = activator, onSuccess = { showResult = true })
        if (showResult) {
            ActivationResultDialog(activator = activator, onFinish = { showActivate = false; showResult = false; onFinish() })
        }
    }
}

@Composable
fun ActivationDialog(activator: ActivationManager, onSuccess: () -> Unit) {
    var code by remember { mutableStateOf("") }; var msg by remember { mutableStateOf("") }; var loading by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = {}, title = { Text("请输入激活码") }, text = {
        Column { Text("请输入有效的激活码以使用糖盾。", fontSize = 13.sp); Spacer(Modifier.height(12.dp)); OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("激活码") }, singleLine = true); if (msg.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(msg, fontSize = 12.sp, color = if (msg.contains("成功") || msg.contains("管理员")) Color(0xFF4CAF50) else Color(0xFFE53935)) } }
    }, confirmButton = { TextButton(onClick = { if (code.isNotBlank()) { loading = true; val r = activator.activate(code); msg = r.msg; loading = false; if (r.ok) { code = ""; onSuccess() } } }, enabled = !loading) { Text(if (loading) "验证中..." else "激活") } }, dismissButton = { TextButton(onClick = { android.os.Process.killProcess(android.os.Process.myPid()) }) { Text("退出") } })
}

@Composable
fun ActivationResultDialog(activator: ActivationManager, onFinish: () -> Unit) {
    val isAdmin = activator.isAdmin()
    val tips = buildString {
        append(if (isAdmin) "管理员账号\n永久有效，所有功能无限制。" else "普通用户账号\n")
        if (!isAdmin) {
            for (f in listOf("chat" to "AI对话", "photo" to "拍照识别", "predict" to "血糖预测", "report" to "报告", "export" to "数据导出")) {
                val n = activator.getRemaining(f.first)
                if (n == Int.MAX_VALUE) append("${f.second}: 无限\n")
                else if (n > 0) append("${f.second}: 每日${n}次\n")
            }
        }
    }
    AlertDialog(onDismissRequest = {}, title = { Text("激活成功") }, text = { Text(tips, fontSize = 14.sp) }, confirmButton = { TextButton(onClick = onFinish) { Text("开始使用") } })
}

@Composable
fun AgreementDialog(onAccept: () -> Unit, onReject: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text("用户协议与隐私政策") },
        text = {
            Text(
                """
                欢迎使用糖盾（TangDun）糖尿病智能健康管理系统。

                在使用本应用前，请您仔细阅读以下条款：

                【免责声明】
                本应用提供的血糖预测、饮食建议、运动处方等信息仅供参考，不构成医疗诊断或治疗建议。任何医疗决策请咨询专业医生。

                【数据隐私】
                您的血糖数据、饮食记录、胰岛素注射记录等健康数据仅存储在您的设备本地，不会上传到任何服务器。您可以随时导出或删除这些数据。

                【数据来源】
                本应用通过 xDrip+ 的本地广播接口接收血糖数据，不会直接连接任何医疗设备。数据准确性取决于您使用的 CGM 设备和配套应用。

                【责任限制】
                开发者不对因使用本应用而导致的任何直接或间接损失承担责任，包括但不限于健康损害、数据丢失等。

                点击「同意」表示您已阅读并接受以上条款。
            """.trimIndent(),
                fontSize = 13.sp
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onAccept) {
                Text("同意")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onReject) {
                Text("不同意并退出", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
