package com.tangdun.app.ui.report

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tangdun.app.domain.algorithm.ReportGenerator
import com.tangdun.app.ui.components.rememberGlucoseFormatter
import com.tangdun.app.ui.theme.*

/**
 * 报告页面
 *
 * 显示日/周/月报告
 */
@Composable
fun ReportScreen(
    viewModel: ReportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    // ★ v3.0.11: 把 getGlucoseUnit 真用上 (报告里的血糖按用户单位显示)
    val gFmt = rememberGlucoseFormatter()

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题
        TopAppBar(
            title = {
                Text(
                    text = "报告",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            actions = {
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

        // Tab选择
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("日报告") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("周报告") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("月报告") })
        }

        // 内容
        when (selectedTab) {
            0 -> DailyReportContent(uiState.dailyReport)
            1 -> WeeklyReportContent(uiState.weeklyReport)
            2 -> MonthlyReportContent(uiState.monthlyReport)
        }
    }
}

@Composable
fun DailyReportContent(report: ReportGenerator.DailyReport?) {
    val gFmt = rememberGlucoseFormatter()
    if (report == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无数据", color = TextHint)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 血糖统计
        ReportCard("血糖统计") {
            ReportRow("平均血糖", gFmt(report.avgGlucose))
            ReportRow("最低血糖", gFmt(report.minGlucose))
            ReportRow("最高血糖", gFmt(report.maxGlucose))
            ReportRow("血糖标准差", "${String.format("%.2f", report.stdGlucose * if (gFmt(1.0).contains("mg")) 18.0 else 1.0)}" + if (gFmt(1.0).contains("mg")) " mg/dL" else " mmol/L")
            ReportRow("HbA1c估算", "${String.format("%.1f", report.hba1cEstimate)}%")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // TIR
        ReportCard("目标范围内时间(TIR)") {
            ReportRow("TIR", "${String.format("%.1f", report.tir)}%", if (report.tir >= 70) AlertSuccess else AlertWarning)
            ReportRow("低于目标", "${String.format("%.1f", report.tirLow)}%")
            ReportRow("高于目标", "${String.format("%.1f", report.tirHigh)}%")
            ReportRow("GRI", "${String.format("%.1f", report.gri)}")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 摄入统计
        ReportCard("今日摄入") {
            ReportRow("总碳水", "${String.format("%.1f", report.totalCarbs)}g")
            ReportRow("总热量", "${String.format("%.0f", report.totalCalories)} kcal")
            ReportRow("总胰岛素", "${String.format("%.1f", report.totalInsulin)}U")
            ReportRow("总步数", "${report.totalSteps}")
            ReportRow("运动时长", "${report.totalExerciseMin}分钟")
        }

        // 亮点
        if (report.highlights.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            ReportCard("亮点") {
                report.highlights.forEach { highlight ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("✓ ", color = AlertSuccess)
                        Text(highlight, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // 待改进
        if (report.improvements.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            ReportCard("待改进") {
                report.improvements.forEach { improvement ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("• ", color = AlertWarning)
                        Text(improvement, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun WeeklyReportContent(report: ReportGenerator.WeeklyReport?) {
    val gFmt = rememberGlucoseFormatter()
    if (report == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无数据", color = TextHint)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ReportCard("本周概览") {
            ReportRow("平均TIR", "${String.format("%.1f", report.avgTir)}%")
            ReportRow("平均血糖", gFmt(report.avgGlucose))
            ReportRow("血糖变异性", "${String.format("%.2f", report.glucoseVariability)}")
        }

        Spacer(modifier = Modifier.height(12.dp))

        ReportCard("本周摄入") {
            ReportRow("总碳水", "${String.format("%.0f", report.totalCarbs)}g")
            ReportRow("总胰岛素", "${String.format("%.1f", report.totalInsulin)}U")
            ReportRow("总步数", "${report.totalSteps}")
        }

        if (report.highlights.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            ReportCard("亮点") {
                report.highlights.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall, color = AlertSuccess) }
            }
        }
    }
}

@Composable
fun MonthlyReportContent(report: ReportGenerator.MonthlyReport?) {
    val gFmt = rememberGlucoseFormatter()
    if (report == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无数据", color = TextHint)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ReportCard("${report.year}年${report.month}月报告") {
            ReportRow("平均TIR", "${String.format("%.1f", report.avgTir)}%")
            ReportRow("平均血糖", gFmt(report.avgGlucose))
            ReportRow("HbA1c估算", "${String.format("%.1f", report.hba1cEstimate)}%")
        }

        if (report.recommendations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            ReportCard("建议") {
                report.recommendations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
fun ReportCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun ReportRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}
