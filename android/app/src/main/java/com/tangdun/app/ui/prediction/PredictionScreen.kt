package com.tangdun.app.ui.prediction

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tangdun.app.ui.theme.*

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
                modelType = uiState.modelType,
                predictionTime = uiState.predictionTime,
                confidence = uiState.confidence,
                dataDays = uiState.dataDays,
                totalRecords = uiState.totalRecords,
                fastingBaseline = uiState.fastingBaseline,
                variability = uiState.variability,
                tcnWeight = uiState.tcnWeight,
                bergmanWeight = uiState.bergmanWeight
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 预测曲线图
            SimplePredictionCurve(
                curve = uiState.curve,
                currentGlucose = uiState.currentGlucose
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
        "low_risk" -> Triple("低血糖风险", AlertWarning, Icons.Default.Warning)
        "high_risk" -> Triple("高血糖风险", AlertCritical, Icons.Default.Error)
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
    modelType: String, predictionTime: String, confidence: Double,
    dataDays: Double, totalRecords: Int, fastingBaseline: Double, variability: Double,
    tcnWeight: Double, bergmanWeight: Double
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
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem2("置信度", "${String.format("%.0f", confidence)}%")
                StatItem2("数据量", "${String.format("%.1f", dataDays)}天")
                StatItem2("记录数", "${totalRecords}")
                StatItem2("空腹基线", "${String.format("%.1f", fastingBaseline)}")
            }
            if (variability > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem2("变异性", "${String.format("%.1f", variability)}%")
                    StatItem2("模型", modelType)
                    StatItem2("更新", predictionTime.takeLast(8))
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem2("TCN权重", "${String.format("%.0f%%", tcnWeight * 100)}")
                    StatItem2("Bergman权重", "${String.format("%.0f%%", bergmanWeight * 100)}")
                }
            }
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
fun SimplePredictionCurve(curve: List<Double>, currentGlucose: Double?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("血糖预测曲线 (0-120分钟)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(12.dp))
            if (curve.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Text("暂无预测数据", color = TextHint)
                }
            } else {
                Canvas(Modifier.fillMaxWidth().height(200.dp)) {
                    val w = size.width; val h = size.height; val pad = 40f
                    val minY = 2.0; val maxY = 16.0
                    fun toX(i: Int) = pad + i.toFloat() / (curve.size - 1) * (w - 2 * pad)
                    fun toY(v: Double) = pad + ((maxY - v.coerceIn(minY, maxY)) / (maxY - minY) * (h - 2 * pad)).toFloat()
                    for (i in 0..6) drawLine(ChartGrid, androidx.compose.ui.geometry.Offset(pad, toY(minY + i * 2f)), androidx.compose.ui.geometry.Offset(w - pad, toY(minY + i * 2f)), 0.5f)
                    drawLine(ChartTarget, androidx.compose.ui.geometry.Offset(pad, toY(3.9)), androidx.compose.ui.geometry.Offset(w - pad, toY(3.9)), 2f)
                    drawLine(Color(0xFFFF9800), androidx.compose.ui.geometry.Offset(pad, toY(10.0)), androidx.compose.ui.geometry.Offset(w - pad, toY(10.0)), 2f)
                    val path = Path().apply { moveTo(toX(0), toY(curve[0])); curve.drop(1).forEachIndexed { i, v -> lineTo(toX(i + 1), toY(v)) } }
                    drawPath(path, ChartLine1, style = Stroke(width = 3f))
                    drawCircle(Color.Red, 8f, androidx.compose.ui.geometry.Offset(toX(0), toY(curve[0])))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (l in listOf("0", "30min", "60min", "90min", "120min")) Text(l, style = MaterialTheme.typography.bodySmall, color = TextHint)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for ((label, idx) in listOf("当前" to 0, "30min" to 6, "60min" to 12, "120min" to 24)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = TextHint)
                            Text(String.format("%.1f", curve.getOrNull(idx) ?: 0.0), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ChartLine1)
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

