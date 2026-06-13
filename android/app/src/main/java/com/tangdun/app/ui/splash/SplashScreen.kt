package com.tangdun.app.ui.splash

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.tangdun.app.util.SettingsManager
import kotlinx.coroutines.delay

/**
 * 启动页 - 品牌展示 + 用户协议检查
 */
@Composable
fun SplashScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    var showAgreement by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (!showAgreement) 1f else 0f,
        animationSpec = tween(800)
    )

    LaunchedEffect(Unit) {
        delay(1200)
        val agreed = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getBoolean("agreement_accepted", false)
        if (!agreed) {
            showAgreement = true
        } else {
            onFinish()
        }
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
                onFinish()
            },
            onReject = {
                // 退出应用
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        )
    }
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
