package com.tangdun.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tangdun.app.TangDunApp
import com.tangdun.app.data.local.entity.InsulinRecord
import com.tangdun.app.data.local.entity.MealRecord
import com.tangdun.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 数据分享卡片
 *
 * 真正读取数据库数据，生成报告并分享
 */
@Composable
fun DataShareCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isGenerating by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "数据分享",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "分享今日血糖报告给家人或医生",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    isGenerating = true
                    scope.launch {
                        try {
                            val report = generateReport(context)
                            shareText(context, "糖盾血糖报告", report)
                        } catch (e: Exception) {
                            // 错误处理
                        }
                        isGenerating = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                enabled = !isGenerating
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("生成报告中...")
                } else {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("分享今日报告")
                }
            }
        }
    }
}

private suspend fun generateReport(context: Context): String {
    val dateFormatShort = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    val db = TangDunApp.getDatabase(context)

    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val todayStart = calendar.timeInMillis

    val glucoseRecords = db.glucoseDao().getTodayRecords(todayStart)
    val mealRecords = db.mealDao().getTodayRecords(todayStart)
    val insulinRecords = db.insulinDao().getSince(todayStart)

    val sb = StringBuilder()
    sb.appendLine("═══════════════════════════════")
    sb.appendLine("        糖盾 - 血糖报告")
    sb.appendLine("═══════════════════════════════")
    sb.appendLine()

    if (glucoseRecords.isNotEmpty()) {
        val values = glucoseRecords.map { it.value }
        val avg = values.average()
        val min = values.min()
        val max = values.max()
        val tir = values.count { it in 3.9..10.0 }.toDouble() / values.size * 100

        sb.appendLine("【血糖统计】")
        sb.appendLine("数据点: ${glucoseRecords.size}个")
        sb.appendLine("平均血糖: ${String.format("%.1f", avg)} mmol/L")
        sb.appendLine("最低/最高: ${String.format("%.1f", min)} / ${String.format("%.1f", max)} mmol/L")
        sb.appendLine("TIR: ${String.format("%.0f", tir)}%")
        sb.appendLine()

        sb.appendLine("【最近血糖】")
        glucoseRecords.takeLast(5).forEach { record ->
            sb.appendLine("  ${dateFormatShort.format(Date(record.timestamp))}  ${String.format("%.1f", record.value)} mmol/L")
        }
        sb.appendLine()
    } else {
        sb.appendLine("【今日暂无血糖数据】")
        sb.appendLine()
    }

    if (mealRecords.isNotEmpty()) {
        sb.appendLine("【饮食记录】")
        mealRecords.takeLast(5).forEach { meal ->
            sb.appendLine("  ${dateFormatShort.format(Date(meal.timestamp))}  ${MealRecord.getMealTypeName(meal.mealType)}")
            sb.appendLine("    碳水: ${String.format("%.0f", meal.totalCarbs)}g  热量: ${String.format("%.0f", meal.totalCalories)}kcal")
        }
        sb.appendLine()
    }

    if (insulinRecords.isNotEmpty()) {
        sb.appendLine("【胰岛素记录】")
        insulinRecords.takeLast(5).forEach { record ->
            sb.appendLine("  ${dateFormatShort.format(Date(record.timestamp))}  ${InsulinRecord.getInsulinTypeName(record.insulinType)} ${String.format("%.1f", record.doseUnits)}U")
        }
        sb.appendLine()
    }

    sb.appendLine("═══════════════════════════════")
    sb.appendLine("由糖盾App生成")

    return sb.toString()
}

private fun shareText(context: Context, title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享报告"))
}
