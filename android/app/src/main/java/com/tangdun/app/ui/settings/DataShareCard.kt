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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * 数据分享卡片 (v3.0.8)
 *
 * 4 种粒度导出:
 *  1. 今日报告 (血糖 + 饮食 + 胰岛素)
 *  2. 本周报告 (7 天汇总 + 趋势)
 *  3. 30 天医生摘要 (含 7 类健康数据: 睡眠/血压/体重/酮体/用药/症状/运动)
 *  4. 全量数据 CSV (所有 10 类, 给医生/数据分析用)
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
                text = "分享给家人或医生 (覆盖血糖/饮食/胰岛素/运动/睡眠/血压/体重/酮体/用药/症状)",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 1. 今日报告
            OutlinedButton(
                onClick = {
                    isGenerating = true
                    scope.launch {
                        try {
                            val report = generateTodayReport(context)
                            shareText(context, "糖盾 - 今日血糖报告", report)
                        } catch (_: Exception) {}
                        isGenerating = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                enabled = !isGenerating
            ) {
                Icon(Icons.Default.Today, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("分享今日报告")
            }
            Spacer(Modifier.height(8.dp))

            // 2. 本周报告
            OutlinedButton(
                onClick = {
                    isGenerating = true
                    scope.launch {
                        try {
                            val report = generateWeekReport(context)
                            shareText(context, "糖盾 - 本周血糖报告", report)
                        } catch (_: Exception) {}
                        isGenerating = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                enabled = !isGenerating
            ) {
                Icon(Icons.Default.DateRange, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("分享本周报告")
            }
            Spacer(Modifier.height(8.dp))

            // 3. 30 天医生摘要
            OutlinedButton(
                onClick = {
                    isGenerating = true
                    scope.launch {
                        try {
                            val report = generateDoctorReport(context)
                            shareText(context, "糖盾 - 30天医生摘要", report)
                        } catch (_: Exception) {}
                        isGenerating = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                enabled = !isGenerating
            ) {
                Icon(Icons.Default.MedicalServices, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("30天医生摘要")
            }
            Spacer(Modifier.height(8.dp))

            // 4. 全量 CSV
            OutlinedButton(
                onClick = {
                    isGenerating = true
                    scope.launch {
                        try {
                            val csv = exportAllCsv(context)
                            shareText(context, "糖盾 - 全量数据 CSV", csv)
                        } catch (_: Exception) {}
                        isGenerating = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                enabled = !isGenerating
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("导出全量数据 (CSV)")
            }

            if (isGenerating) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("生成报告中...", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun todayStartMs(): Long {
    val c = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

private fun nDaysAgoStartMs(n: Int): Long {
    val c = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, -n)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

// ════════════════ 1. 今日报告 ════════════════

private suspend fun generateTodayReport(context: Context): String = withContext(Dispatchers.IO) {
    val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val db = TangDunApp.getDatabase(context)
    val settings = com.tangdun.app.util.SettingsManager(context)
    val dayStart = todayStartMs()
    val userName = settings.getUserName().ifBlank { "用户" }

    val glucoseRecords = db.glucoseDao().getTodayRecords(dayStart)
    val mealRecords = db.mealDao().getTodayRecords(dayStart)
    val insulinRecords = db.insulinDao().getSince(dayStart)
    val exerciseRecords = db.exerciseDao().getTodayRecords(dayStart)

    val sb = StringBuilder()
    sb.appendLine("═══════════════════════════════")
    sb.appendLine("       糖盾 - 今日血糖报告")
    sb.appendLine("报告人: $userName")
    sb.appendLine("生成时间: ${df.format(Date())}")
    sb.appendLine("═══════════════════════════════")
    sb.appendLine()

    if (glucoseRecords.isNotEmpty()) {
        val values = glucoseRecords.map { it.value }
        val avg = values.average()
        val min = values.min()
        val max = values.max()
        val tir = values.count { it in 3.9..10.0 }.toDouble() / values.size * 100
        val lowCount = values.count { it < 3.9 }
        val highCount = values.count { it > 10.0 }
        sb.appendLine("【血糖统计】")
        sb.appendLine("  数据点: ${glucoseRecords.size}个")
        sb.appendLine("  平均血糖: ${"%.1f".format(avg)} mmol/L")
        sb.appendLine("  最低/最高: ${"%.1f".format(min)} / ${"%.1f".format(max)} mmol/L")
        sb.appendLine("  TIR: ${"%.0f".format(tir)}%")
        sb.appendLine("  低血糖次数: $lowCount / 高血糖次数: $highCount")
        sb.appendLine()
    } else {
        sb.appendLine("【今日暂无血糖数据】")
        sb.appendLine()
    }

    if (mealRecords.isNotEmpty()) {
        sb.appendLine("【饮食记录】")
        mealRecords.forEach { meal ->
            sb.appendLine("  ${df.format(Date(meal.timestamp))}  ${MealRecord.getMealTypeName(meal.mealType)}")
            sb.appendLine("    碳水 ${"%.0f".format(meal.totalCarbs)}g  热量 ${"%.0f".format(meal.totalCalories)}kcal")
        }
        sb.appendLine()
    }

    if (insulinRecords.isNotEmpty()) {
        sb.appendLine("【胰岛素记录】")
        insulinRecords.forEach { record ->
            sb.appendLine("  ${df.format(Date(record.timestamp))}  ${InsulinRecord.getInsulinTypeName(record.insulinType)} ${"%.1f".format(record.doseUnits)}U")
        }
        sb.appendLine()
    }

    if (exerciseRecords.isNotEmpty()) {
        sb.appendLine("【运动记录】")
        exerciseRecords.forEach { record ->
            sb.appendLine("  ${df.format(Date(record.startTime))}  ${record.exerciseType} ${record.durationMin}分钟")
        }
        sb.appendLine()
    }

    sb.appendLine("═══════════════════════════════")
    sb.appendLine("由糖盾App生成")
    sb.toString()
}

// ════════════════ 2. 本周报告 ════════════════

private suspend fun generateWeekReport(context: Context): String = withContext(Dispatchers.IO) {
    val df = SimpleDateFormat("MM-dd", Locale.getDefault())
    val db = TangDunApp.getDatabase(context)
    val weekStart = nDaysAgoStartMs(7)
    val now = System.currentTimeMillis()

    val glucose = db.glucoseDao().getByTimeRange(weekStart, now)
    val meals = db.mealDao().getByTimeRange(weekStart, now)
    val insulin = db.insulinDao().getByTimeRange(weekStart, now)
    val exercise = db.exerciseDao().getByTimeRange(weekStart, now)

    val sb = StringBuilder()
    val settings = com.tangdun.app.util.SettingsManager(context)
    val userName = settings.getUserName().ifBlank { "用户" }
    sb.appendLine("═══════════════════════════════")
    sb.appendLine("       糖盾 - 本周血糖报告")
    sb.appendLine("报告人: $userName")
    sb.appendLine("时间范围: ${df.format(Date(weekStart))} 至 ${df.format(Date(now))}")
    sb.appendLine("═══════════════════════════════")
    sb.appendLine()

    if (glucose.isNotEmpty()) {
        val v = glucose.map { it.value }
        sb.appendLine("【血糖 (${glucose.size}个数据点)】")
        sb.appendLine("  平均: ${"%.1f".format(v.average())} mmol/L")
        sb.appendLine("  最低/最高: ${"%.1f".format(v.min())} / ${"%.1f".format(v.max())} mmol/L")
        sb.appendLine("  TIR: ${"%.0f".format(v.count { it in 3.9..10.0 }.toDouble() / v.size * 100)}%")
        sb.appendLine("  估算 HbA1c: ${"%.1f".format((v.average() + 2.0) * 1.5)}%")
        sb.appendLine()
    }

    if (meals.isNotEmpty()) {
        val totalCarbs = meals.sumOf { it.totalCarbs }
        val totalCal = meals.sumOf { it.totalCalories }
        sb.appendLine("【饮食 (${meals.size}餐)】")
        sb.appendLine("  累计碳水: ${"%.0f".format(totalCarbs)}g")
        sb.appendLine("  累计热量: ${"%.0f".format(totalCal)} kcal")
        sb.appendLine("  日均: ${"%.0f".format(totalCarbs / 7.0)}g 碳水 / ${"%.0f".format(totalCal / 7.0)} kcal")
        sb.appendLine()
    }

    if (insulin.isNotEmpty()) {
        val totalDose = insulin.sumOf { it.doseUnits }
        sb.appendLine("【胰岛素 (${insulin.size}针)】")
        sb.appendLine("  累计剂量: ${"%.1f".format(totalDose)} U")
        sb.appendLine("  日均: ${"%.1f".format(totalDose / 7.0)} U")
        sb.appendLine()
    }

    if (exercise.isNotEmpty()) {
        val totalMin = exercise.sumOf { it.durationMin ?: 0 }
        val totalCal = exercise.sumOf { it.caloriesBurned ?: 0.0 }
        sb.appendLine("【运动 (${exercise.size}次)】")
        sb.appendLine("  累计时长: $totalMin 分钟")
        sb.appendLine("  累计消耗: ${"%.0f".format(totalCal)} kcal")
        sb.appendLine()
    }

    sb.appendLine("═══════════════════════════════")
    sb.appendLine("由糖盾App生成")
    sb.toString()
}

// ════════════════ 3. 30 天医生摘要 ════════════════

private suspend fun generateDoctorReport(context: Context): String = withContext(Dispatchers.IO) {
    val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val db = TangDunApp.getDatabase(context)
    val settings = com.tangdun.app.util.SettingsManager(context)
    val userName = settings.getUserName().ifBlank { "用户" }
    val start = nDaysAgoStartMs(30)
    val now = System.currentTimeMillis()

    val sb = StringBuilder()
    sb.appendLine("═══════════════════════════════════════════════")
    sb.appendLine("       糖盾 - 30天临床摘要")
    sb.appendLine("报告人: $userName")
    sb.appendLine("时间范围: ${df.format(Date(start))} 至 ${df.format(Date(now))}")
    sb.appendLine("═══════════════════════════════════════════════")
    sb.appendLine()

    // 血糖
    val glucose = db.glucoseDao().getByTimeRange(start, now)
    if (glucose.isNotEmpty()) {
        val v = glucose.map { it.value }
        sb.appendLine("【血糖控制 (${glucose.size}个数据点)】")
        sb.appendLine("  平均血糖: ${"%.1f".format(v.average())} mmol/L")
        sb.appendLine("  标准差: ${"%.2f".format(calStdDev(v))} mmol/L")
        sb.appendLine("  CV变异度: ${"%.1f".format(calStdDev(v) / v.average() * 100)}%")
        sb.appendLine("  TIR (3.9-10.0): ${"%.1f".format(v.count { it in 3.9..10.0 }.toDouble() / v.size * 100)}%")
        sb.appendLine("  TAR (>10.0): ${"%.1f".format(v.count { it > 10.0 }.toDouble() / v.size * 100)}%")
        sb.appendLine("  TBR (<3.9): ${"%.1f".format(v.count { it < 3.9 }.toDouble() / v.size * 100)}%")
        sb.appendLine("  估算 HbA1c: ${"%.1f".format((v.average() + 2.0) * 1.5)}%")
        sb.appendLine()
    }

    // 饮食
    val meals = db.mealDao().getByTimeRange(start, now)
    if (meals.isNotEmpty()) {
        sb.appendLine("【饮食 (${meals.size}餐)】")
        sb.appendLine("  日均碳水: ${"%.0f".format(meals.sumOf { it.totalCarbs } / 30.0)}g")
        sb.appendLine("  日均热量: ${"%.0f".format(meals.sumOf { it.totalCalories } / 30.0)} kcal")
        sb.appendLine()
    }

    // 胰岛素
    val insulin = db.insulinDao().getByTimeRange(start, now)
    if (insulin.isNotEmpty()) {
        sb.appendLine("【胰岛素 (${insulin.size}针)】")
        sb.appendLine("  日均剂量: ${"%.1f".format(insulin.sumOf { it.doseUnits } / 30.0)} U")
        sb.appendLine()
    }

    // 运动
    val exercise = db.exerciseDao().getByTimeRange(start, now)
    if (exercise.isNotEmpty()) {
        sb.appendLine("【运动 (${exercise.size}次)】")
        sb.appendLine("  周均次数: ${"%.1f".format(exercise.size / 4.3)}")
        sb.appendLine("  周均时长: ${"%.0f".format(exercise.sumOf { it.durationMin ?: 0 } / 4.3)} 分钟")
        sb.appendLine()
    }

    // ★ v3.0.8: 7 类健康数据 - 医生最关心的
    val sleep = db.sleepDao().getByTimeRange(start, now)
    if (sleep.isNotEmpty()) {
        val avgMin = sleep.map { it.durationMinutes }.average()
        sb.appendLine("【睡眠 (${sleep.size}条)】")
        sb.appendLine("  平均时长: ${"%.1f".format(avgMin / 60.0)} 小时/晚")
        sb.appendLine("  优质睡眠率: ${"%.0f".format(sleep.count { it.quality == "good" }.toDouble() / sleep.size * 100)}%")
        sb.appendLine()
    }

    val bp = db.bloodPressureDao().getByTimeRange(start, now)
    if (bp.isNotEmpty()) {
        sb.appendLine("【血压 (${bp.size}次)】")
        sb.appendLine("  平均: ${bp.map { it.systolic }.average().toInt()}/${bp.map { it.diastolic }.average().toInt()} mmHg")
        sb.appendLine("  最高: ${bp.maxOf { it.systolic }}/${bp.maxOf { it.diastolic }} mmHg")
        sb.appendLine()
    }

    val weight = db.weightDao().getByTimeRange(start, now)
    if (weight.isNotEmpty()) {
        val firstKg = weight.first().weightKg
        val lastKg = weight.last().weightKg
        val change = lastKg - firstKg
        sb.appendLine("【体重 (${weight.size}次)】")
        sb.appendLine("  起始: ${"%.1f".format(firstKg)} kg")
        sb.appendLine("  当前: ${"%.1f".format(lastKg)} kg")
        sb.appendLine("  30天变化: ${if (change >= 0) "+" else ""}${"%.1f".format(change)} kg")
        sb.appendLine()
    }

    val ketone = db.ketoneDao().getByTimeRange(start, now)
    if (ketone.isNotEmpty()) {
        sb.appendLine("【酮体 (${ketone.size}次)】")
        sb.appendLine("  平均: ${"%.2f".format(ketone.map { it.ketoneLevel }.average())} mmol/L")
        sb.appendLine("  最高: ${"%.2f".format(ketone.maxOf { it.ketoneLevel })} mmol/L")
        sb.appendLine()
    }

    val medication = db.medicationDao().getByTimeRange(start, now)
    if (medication.isNotEmpty()) {
        sb.appendLine("【口服药 (${medication.size}条)】")
        val byName = medication.groupBy { it.medicationName }
        byName.forEach { (name, list) ->
            sb.appendLine("  $name: ${list.size}次")
        }
        sb.appendLine()
    }

    val symptom = db.symptomDao().getByTimeRange(start, now)
    if (symptom.isNotEmpty()) {
        sb.appendLine("【症状 (${symptom.size}条)】")
        sb.appendLine("  重度症状: ${symptom.count { it.severity == "severe" }} 次")
        sb.appendLine("  中度症状: ${symptom.count { it.severity == "moderate" }} 次")
        sb.appendLine("  轻度症状: ${symptom.count { it.severity == "mild" }} 次")
        sb.appendLine()
    }

    sb.appendLine("═══════════════════════════════════════════════")
    sb.appendLine("由糖盾App生成 - 临床参考, 不替代医生诊断")
    sb.toString()
}

private fun calStdDev(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mean = values.average()
    return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
}

// ════════════════ 4. 全量 CSV 导出 ════════════════

private suspend fun exportAllCsv(context: Context): String = withContext(Dispatchers.IO) {
    val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val db = TangDunApp.getDatabase(context)
    val sb = StringBuilder()
    sb.appendLine("# 糖盾 TangDun - 全量数据导出 CSV")
    sb.appendLine("# 生成时间: ${df.format(Date())}")
    sb.appendLine()

    // 血糖
    sb.appendLine("# === 血糖 ===")
    sb.appendLine("id,timestamp,time,value(mmol/L),scene,source")
    db.glucoseDao().getRecent(50000).forEach {
        sb.appendLine("${it.id},${it.timestamp},${df.format(Date(it.timestamp))},${it.value},${it.scene ?: ""},${it.source ?: ""}")
    }
    sb.appendLine()

    // 饮食
    sb.appendLine("# === 饮食 ===")
    sb.appendLine("id,timestamp,time,meal_type,total_carbs(g),calories(kcal),protein(g),fat(g)")
    db.mealDao().getRecent(50000).forEach {
        sb.appendLine("${it.id},${it.timestamp},${df.format(Date(it.timestamp))},${it.mealType},${it.totalCarbs},${it.totalCalories},${it.totalProtein},${it.totalFat}")
    }
    sb.appendLine()

    // 胰岛素
    sb.appendLine("# === 胰岛素 ===")
    sb.appendLine("id,timestamp,time,type,dose(units),injection_site")
    db.insulinDao().getRecent(50000).forEach {
        sb.appendLine("${it.id},${it.timestamp},${df.format(Date(it.timestamp))},${it.insulinType},${it.doseUnits},${it.injectionSite ?: ""}")
    }
    sb.appendLine()

    // 运动
    sb.appendLine("# === 运动 ===")
    sb.appendLine("id,start_time,time,exercise_type,duration(min),intensity,steps,calories")
    db.exerciseDao().getRecent(50000).forEach {
        sb.appendLine("${it.id},${it.startTime},${df.format(Date(it.startTime))},${it.exerciseType},${it.durationMin},${it.intensity},${it.steps},${it.caloriesBurned}")
    }
    sb.appendLine()

    // 睡眠
    sb.appendLine("# === 睡眠 ===")
    sb.appendLine("id,timestamp,time,duration(min),quality")
    db.sleepDao().getRecent(50000).forEach {
        sb.appendLine("${it.id},${it.timestamp},${df.format(Date(it.timestamp))},${it.durationMinutes},${it.quality}")
    }
    sb.appendLine()

    // 血压
    sb.appendLine("# === 血压 ===")
    sb.appendLine("id,timestamp,time,systolic(mmHg),diastolic(mmHg),heart_rate")
    db.bloodPressureDao().getRecent(50000).forEach {
        sb.appendLine("${it.id},${it.timestamp},${df.format(Date(it.timestamp))},${it.systolic},${it.diastolic},${it.heartRate ?: ""}")
    }
    sb.appendLine()

    // 体重
    sb.appendLine("# === 体重 ===")
    sb.appendLine("id,timestamp,time,weight(kg)")
    db.weightDao().getRecent(50000).forEach {
        sb.appendLine("${it.id},${it.timestamp},${df.format(Date(it.timestamp))},${it.weightKg}")
    }
    sb.appendLine()

    // 酮体
    sb.appendLine("# === 酮体 ===")
    sb.appendLine("id,timestamp,time,ketone(mmol/L)")
    db.ketoneDao().getRecent(50000).forEach {
        sb.appendLine("${it.id},${it.timestamp},${df.format(Date(it.timestamp))},${it.ketoneLevel}")
    }
    sb.appendLine()

    // 用药
    sb.appendLine("# === 口服药 ===")
    sb.appendLine("id,timestamp,time,name,dose,notes")
    db.medicationDao().getRecent(50000).forEach {
        sb.appendLine("${it.id},${it.timestamp},${df.format(Date(it.timestamp))},${it.medicationName},${it.dose},${it.notes ?: ""}")
    }
    sb.appendLine()

    // 症状
    sb.appendLine("# === 症状 ===")
    sb.appendLine("id,timestamp,time,symptoms,severity,notes")
    db.symptomDao().getRecent(50000).forEach {
        sb.appendLine("${it.id},${it.timestamp},${df.format(Date(it.timestamp))},${it.symptoms},${it.severity},${it.notes ?: ""}")
    }

    sb.toString()
}

private fun shareText(context: Context, title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "分享报告"))
}

// 兜底: 让 some getByTimeRange 调用通用, 因为不同 DAO 方法名可能不同
private suspend fun com.tangdun.app.data.local.dao.SleepDao.getByTimeRange(start: Long, end: Long): List<com.tangdun.app.data.local.entity.SleepRecord> {
    return getRecent(50000).filter { it.timestamp in start..end }
}
private suspend fun com.tangdun.app.data.local.dao.BloodPressureDao.getByTimeRange(start: Long, end: Long): List<com.tangdun.app.data.local.entity.BloodPressureRecord> {
    return getRecent(50000).filter { it.timestamp in start..end }
}
private suspend fun com.tangdun.app.data.local.dao.WeightDao.getByTimeRange(start: Long, end: Long): List<com.tangdun.app.data.local.entity.WeightRecord> {
    return getRecent(50000).filter { it.timestamp in start..end }
}
private suspend fun com.tangdun.app.data.local.dao.KetoneDao.getByTimeRange(start: Long, end: Long): List<com.tangdun.app.data.local.entity.KetoneRecord> {
    return getRecent(50000).filter { it.timestamp in start..end }
}
private suspend fun com.tangdun.app.data.local.dao.MedicationDao.getByTimeRange(start: Long, end: Long): List<com.tangdun.app.data.local.entity.MedicationRecord> {
    return getRecent(50000).filter { it.timestamp in start..end }
}
private suspend fun com.tangdun.app.data.local.dao.SymptomDao.getByTimeRange(start: Long, end: Long): List<com.tangdun.app.data.local.entity.SymptomRecord> {
    return getRecent(50000).filter { it.timestamp in start..end }
}