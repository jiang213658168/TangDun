package com.tangdun.app.ui.prediction

import androidx.compose.foundation.background
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
import com.tangdun.app.ui.components.*
import com.tangdun.app.ui.theme.*

/**
 * 血糖预测页面 (产品级重构 v2.0)
 *
 * 新布局:
 *  ┌──────────────────────────────┐
 *  │ ModernTopBar + 刷新按钮       │
 *  ├──────────────────────────────┤
 *  │ PredictionHeroCard           │  ← 大数字 + 风险徽章 + 置信度环
 *  │ (30min 预测 + 风险色背景)      │
 *  ├──────────────────────────────┤
 *  │ QuickInsightRow              │  ← IOB / 碳水 / 变异度 关键指标
 *  ├──────────────────────────────┤
 *  │ ComposePredictionChart       │  ← 三线曲线 + 选中 tooltip
 *  ├──────────────────────────────┤
 *  │ Section: 关键时间点预测        │
 *  │ 30min / 60min / 120min       │  ← 三个 stat card 横排
 *  ├──────────────────────────────┤
 *  │ ModelWeightBar               │  ← BMA 融合权重可视化
 *  └──────────────────────────────┘
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
        // ── 顶部: ModernTopBar ──
        ModernTopBar(
            title = "血糖预测",
            subtitle = uiState.modelLabel.takeIf { it.isNotEmpty() }
                ?: "AI 智能预测未来 3 小时血糖走势",
            actions = {
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
        )

        Spacer(Modifier.height(12.dp))

        if (uiState.isLoading) {
            // 加载占位
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(40.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            // ── Hero: 大预测数字 ──
            PredictionHeroCard(
                currentGlucose = uiState.currentGlucose,
                predicted30min = uiState.predicted30min,
                targetLow = uiState.targetLow,
                targetHigh = uiState.targetHigh,
                confidence = uiState.confidence
            )

            Spacer(Modifier.height(16.dp))

            // ── 关键洞察行 (横滑) ──
            SectionHeader(title = "上下文")
            QuickInsightRow(
                items = buildList {
                    add(Triple("活性胰岛素 IOB", "${"%.1f".format(uiState.activeInsulin)} U", MaterialTheme.colorScheme.tertiary))
                    add(Triple("今日碳水", "${"%.0f".format(uiState.todayCarbs)} g", MaterialTheme.colorScheme.primary))
                    add(Triple("变异度 CV", "${"%.1f".format(uiState.variability)}%", glucoseColor(uiState.variability.coerceAtLeast(3.9), 3.0, 4.5)))
                    add(Triple("空腹基线", "${"%.1f".format(uiState.fastingBaseline)}", glucoseColor(uiState.fastingBaseline)))
                    add(Triple("ISF 估算", "${"%.1f".format(uiState.isfEstimate)}", MaterialTheme.colorScheme.secondary))
                    add(Triple("CR 估算", "${"%.0f".format(uiState.crEstimate)}", MaterialTheme.colorScheme.secondary))
                }
            )

            Spacer(Modifier.height(20.dp))

            // ── 时间范围选择 ──
            SectionHeader(title = "预测曲线")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (h in listOf(1, 3, 6, 12, 24)) {
                    val selected = uiState.historyHours == h
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setHistoryHours(h) },
                        label = {
                            Text(
                                "${h}h",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 三线预测曲线图 ──
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                ComposePredictionChart(
                    history = uiState.historyData,
                    physioCurve = uiState.physioCurve,
                    incrementalCurve = uiState.incrementalCurve,
                    finalCurve = uiState.curve,
                    currentGlucose = uiState.currentGlucose ?: 5.0,
                    targetLow = uiState.targetLow,
                    targetHigh = uiState.targetHigh,
                    height = 300.dp
                )
            }

            // 图例
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LegendDot(color = Chart1, label = "预测曲线")
                LegendDot(color = Chart3, label = "DallaMan")
                LegendDot(color = Chart2, label = "增量残差")
            }

            Spacer(Modifier.height(20.dp))

            // ── 关键时间点预测 ──
            SectionHeader(title = "关键时间点")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PredictionStatCard(
                    label = "30 分钟",
                    value = uiState.predicted30min,
                    targetLow = uiState.targetLow,
                    targetHigh = uiState.targetHigh,
                    isPrimary = true,
                    modifier = Modifier.weight(1f)
                )
                PredictionStatCard(
                    label = "60 分钟",
                    value = uiState.predicted60min,
                    targetLow = uiState.targetLow,
                    targetHigh = uiState.targetHigh,
                    modifier = Modifier.weight(1f)
                )
                PredictionStatCard(
                    label = "120 分钟",
                    value = uiState.predicted120min,
                    targetLow = uiState.targetLow,
                    targetHigh = uiState.targetHigh,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── BMA 融合权重 ──
            if (uiState.tcnWeight > 0 || uiState.physioWeight > 0) {
                ModelWeightBar(
                    tcnWeight = uiState.tcnWeight,
                    physioWeight = uiState.physioWeight
                )
            }

            // ── 峰值预警 ──
            if (uiState.peakMinute > 0 && uiState.predicted120min != null) {
                Spacer(Modifier.height(16.dp))
                PeakInfoCard(
                    peakValue = uiState.peakValue,
                    peakMinute = uiState.peakMinute,
                    predicted120min = uiState.predicted120min
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .padding(2.dp)
                .background(color, RoundedCornerShape(5.dp))
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PeakInfoCard(peakValue: Double, peakMinute: Int, predicted120min: Double?) {
    val color = when {
        peakValue > 13.9 -> GlucoseSevereHigh
        peakValue > 10.0 -> GlucoseHigh
        peakValue < 3.0 -> GlucoseSevereLow
        peakValue < 3.9 -> GlucoseLow
        else -> GlucoseNormal
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.TrendingUp, contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "预计峰值",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format("%.1f", peakValue),
                        style = MaterialTheme.typography.headlineMedium,
                        color = color,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "mmol/L",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Text(
                    text = "$peakMinute 分钟后 · 2h 后: ${predicted120min?.let { String.format("%.1f", it) } ?: "--"} mmol/L",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
