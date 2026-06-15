package com.tangdun.app.ui.prediction

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tangdun.app.ui.theme.*
import com.tangdun.app.widget.PredictionChartView

/**
 * 血糖预测页面
 *
 * 显示：
 * - 风险等级
 * - 关键时间点预测（5/15/30/60/120分钟）
 * - 0-120分钟预测曲线
 * - 模型信息
 */
@Composable
fun PredictionScreen(
    viewModel: PredictionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 顶部标题
        TopAppBar(
            title = {
                Text(
                    text = "血糖预测",
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

        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // 风险等级卡片
            RiskLevelCard(
                riskLevel = uiState.riskLevel,
                currentGlucose = uiState.currentGlucose
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 预测引擎信息
            EngineInfoCard(
                modelLabel = uiState.modelLabel,
                confidence = uiState.confidence,
                totalRecords = uiState.totalRecords,
                fastingBaseline = uiState.fastingBaseline,
                variability = uiState.variability,
                activeInsulin = uiState.activeInsulin,
                todayCarbs = uiState.todayCarbs,
                isfEstimate = uiState.isfEstimate,
                crEstimate = uiState.crEstimate,
                tcnWeight = uiState.tcnWeight,
                physioWeight = uiState.physioWeight
            )

            // 时间范围选择
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in listOf(1, 3, 6, 12, 24)) {
                    FilterChip(
                        selected = uiState.historyHours == h,
                        onClick = { viewModel.setHistoryHours(h) },
                        label = { Text("${h}h", fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 预测曲线图（历史 + 预测叠加）
            PredictionCurveCard(
                history = uiState.historyData,
                curve = uiState.curve,
                current = uiState.currentGlucose ?: 0.0,
                targetLow = uiState.targetLow,
                targetHigh = uiState.targetHigh
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 峰值预警
            if (uiState.peakMinute > 0) {
                PeakInfoCard(
                    peakValue = uiState.peakValue,
                    peakMinute = uiState.peakMinute,
                    predicted120min = uiState.predicted120min
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun RiskLevelCard(riskLevel: String, currentGlucose: Double?) {
    val (riskText, riskColor, riskIcon) = when (riskLevel) {
        "低血糖风险" -> Triple("低血糖风险", AlertWarning, Icons.Default.Warning)
        "高血糖风险" -> Triple("高血糖风险", AlertCritical, Icons.Default.Error)
        else -> Triple("正常", AlertSuccess, Icons.Default.CheckCircle)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = riskColor.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = riskIcon,
                contentDescription = null,
                tint = riskColor,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "风险等级",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    text = riskText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = riskColor
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "当前",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
                Text(
                    text = if (currentGlucose != null) String.format("%.1f", currentGlucose) else "--",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ─────── 商用级预测组件 ───────

@Composable
fun EngineInfoCard(
    modelLabel: String, confidence: Double,
    totalRecords: Int, fastingBaseline: Double, variability: Double,
    activeInsulin: Double, todayCarbs: Double,
    isfEstimate: Double, crEstimate: Double,
    tcnWeight: Double, physioWeight: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Insights, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("预测引擎", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            // 模型权重 — 明确展示用的是什么
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (tcnWeight > 0) StatItem2("TCN (MAE 0.552)", "${String.format("%.0f%%", tcnWeight * 100)}")
                StatItem2("DallaMan(7室)", "${String.format("%.0f%%", physioWeight * 100)}")
                StatItem2("个性化", "${String.format("%.0f%%", (1 - tcnWeight - physioWeight).coerceAtLeast(0.0) * 100)}")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem2("置信度", "${String.format("%.0f", confidence)}%")
                StatItem2("记录", "${totalRecords}条")
                StatItem2("活性胰岛素", "${String.format("%.1f", activeInsulin)}U")
                StatItem2("今日碳水", "${String.format("%.0f", todayCarbs)}g")
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem2("ISF估算", "${String.format("%.1f", isfEstimate)}")
                StatItem2("CR估算", "${String.format("%.0f", crEstimate)}")
                StatItem2("变异", "${String.format("%.1f", variability)}%")
                StatItem2("基线", "${String.format("%.1f", fastingBaseline)}")
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(modelLabel, fontSize = 11.sp, color = TextHint)
        }
    }
}

@Composable
fun StatItem2(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
fun PredictionCurveCard(history: List<Pair<Long, Double>>, curve: List<Double>, current: Double, targetLow: Double, targetHigh: Double) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("血糖预测曲线", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (history.isEmpty() && curve.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text("暂无数据", color = TextHint) }
            } else {
                AndroidView(
                    factory = { ctx -> PredictionChartView(ctx).apply { setData(history, curve, current); setTargets(targetLow, targetHigh) } },
                    update = { it.setData(history, curve, current); it.setTargets(targetLow, targetHigh) },
                    modifier = Modifier.fillMaxWidth().height(240.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for ((label, idx) in listOf("当前" to 0, "30min" to 6, "60min" to 12, "120min" to 24)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = TextHint)
                            Text(String.format("%.1f", curve.getOrNull(idx) ?: (history.lastOrNull()?.second ?: 0.0)),
                                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ChartLine1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PeakInfoCard(peakValue: Double, peakMinute: Int, predicted120min: Double?) {
    val c = when { peakValue > 10.0 -> GlucoseHigh; peakValue < 3.9 -> GlucoseLow; else -> GlucoseNormal }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.08f))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.TrendingUp, null, tint = c, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("预计峰值 ${String.format("%.1f", peakValue)} mmol/L", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c)
                Text("${peakMinute}分钟后  |  2h后: ${predicted120min?.let { String.format("%.1f", it) } ?: "--"} mmol/L", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

