package com.tangdun.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangdun.app.ui.theme.*
import kotlin.math.absoluteValue

/**
 * 预测页产品级 UI 组件库
 *
 * 核心组件:
 *  - PredictionHeroCard: 大预测数字 + 风险徽章 + 置信度环
 *  - PredictionStatCard: 30/60/120min 三个预测点
 *  - ModelWeightBar: BMA 权重可视化
 *  - ComposePredictionChart: Compose Canvas 预测曲线
 */

// ════════════════════════════════════════════════════════
// PredictionHeroCard: 预测页 Hero 卡
// ════════════════════════════════════════════════════════

/**
 * 预测页核心: 30 分钟预测 + 风险等级 + 置信度
 *
 * 视觉:
 *  ┌──────────────────────────────┐
 *  │ 🟢 正常                ⚙️ 92%│  ← 风险徽章 + 置信度环
 *  │                              │
 *  │        6.8                  │  ← 30min 大数字
 *  │        mmol/L              │
 *  │                              │
 *  │    预计 30 分钟后             │
 *  │    当前 5.6 → 6.8           │
 *  └──────────────────────────────┘
 */
@Composable
fun PredictionHeroCard(
    currentGlucose: Double?,
    predicted30min: Double?,
    targetLow: Double,
    targetHigh: Double,
    confidence: Double,  // 0-100
    modifier: Modifier = Modifier
) {
    // 风险等级颜色 + 文案
    val riskLevel = predicted30min?.let { g ->
        when {
            g < 3.0 -> Triple("严重低血糖", Icons.Default.Error, GlucoseSevereLow)
            g < targetLow -> Triple("低血糖风险", Icons.Default.Warning, GlucoseLow)
            g <= targetHigh -> Triple("正常", Icons.Default.CheckCircle, GlucoseNormal)
            g < targetHigh + 3.9 -> Triple("偏高", Icons.Default.Info, GlucoseHighNormal)
            g < 13.9 -> Triple("高血糖风险", Icons.Default.Warning, GlucoseHigh)
            else -> Triple("严重高血糖", Icons.Default.Error, GlucoseSevereHigh)
        }
    } ?: Triple("暂无数据", Icons.Default.Help, MaterialTheme.colorScheme.outline)

    val (riskText, riskIcon, riskColor) = riskLevel
    val animatedRiskColor by animateColorAsState(riskColor, label = "riskColor")

    // 预测渐变背景 (跟随风险色)
    val cardBrush = Brush.verticalGradient(
        colors = listOf(
            animatedRiskColor.copy(alpha = 0.08f),
            animatedRiskColor.copy(alpha = 0.02f),
            Color.Transparent,
        )
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
        // ★ 修复灰边: 显式 transparent border
        border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBrush)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                // ── 顶部: 风险徽章 + 置信度环 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 风险徽章
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(animatedRiskColor.copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            riskIcon, contentDescription = null,
                            tint = animatedRiskColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            riskText,
                            style = MaterialTheme.typography.labelLarge,
                            color = animatedRiskColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 置信度环
                    ConfidenceRing(
                        confidence = confidence,
                        size = 56.dp,
                        strokeWidth = 5.dp
                    )
                }

                Spacer(Modifier.height(24.dp))

                // ── 中部: 大预测数字 ──
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "30 分钟后",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = predicted30min?.let { String.format("%.1f", it) } ?: "--",
                            style = TangDunNumberLargeStyle,
                            color = animatedRiskColor,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "mmol/L",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 14.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 当前 → 预测 变化提示
                    if (currentGlucose != null && predicted30min != null) {
                        val delta = predicted30min - currentGlucose
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = String.format("%.1f", currentGlucose),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                if (delta >= 0) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = String.format("%+.1f", delta),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (delta.absoluteValue > 1.5) animatedRiskColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}


// ════════════════════════════════════════════════════════
// ConfidenceRing: 置信度环形进度 (动画)
// ════════════════════════════════════════════════════════

@Composable
fun ConfidenceRing(
    confidence: Double,
    size: androidx.compose.ui.unit.Dp = 64.dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 6.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val animatedConfidence by animateFloatAsState(
        targetValue = (confidence / 100).coerceIn(0.0, 1.0).toFloat(),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "confidence"
    )

    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidthPx = strokeWidth.toPx()
            val pad = strokeWidthPx / 2
            val arcSize = Size(size.toPx() - strokeWidthPx, size.toPx() - strokeWidthPx)
            val topLeft = Offset(pad, pad)

            // 背景圆
            drawArc(
                color = color.copy(alpha = 0.12f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )
            // 进度圆
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = animatedConfidence * 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )
        }
        // 中心文字
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${confidence.toInt()}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


// ════════════════════════════════════════════════════════
// PredictionStatCard: 单个时间点预测 (30/60/120min)
// ════════════════════════════════════════════════════════

@Composable
fun PredictionStatCard(
    label: String,
    value: Double?,
    targetLow: Double,
    targetHigh: Double,
    isPrimary: Boolean = false,
    modifier: Modifier = Modifier
) {
    val valueColor = value?.let { glucoseColor(it, targetLow, targetHigh) }
        ?: MaterialTheme.colorScheme.outline
    val animatedColor by animateColorAsState(valueColor, label = "valueColor")

    // ★ 修复灰边: 完全去掉手动 shadow, 用 cardElevation + border + tint 区分 primary, 视觉上不再有"灰边"
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPrimary) animatedColor.copy(alpha = 0.10f)
                            else MaterialTheme.colorScheme.surface
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(
            defaultElevation = if (isPrimary) 0.dp else 0.dp
        ),
        // ★ 关键修复: 显式设置 transparent border (防止 M3 Card 默认 1dp outline 显示成"黑框/灰边")
        border = androidx.compose.foundation.BorderStroke(
            width = if (isPrimary) 1.5.dp else 0.dp,
            color = if (isPrimary) animatedColor.copy(alpha = 0.35f) else Color.Transparent
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.height(4.dp))
            // ★ 修复数字换行: titleLarge (22sp) + fillMaxWidth, 强制单行
            Text(
                text = value?.let { String.format("%.1f", it) } ?: "--",
                style = MaterialTheme.typography.titleLarge,
                color = animatedColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "mmol/L",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}


// ════════════════════════════════════════════════════════
// ModelWeightBar: BMA 模型权重条
// ════════════════════════════════════════════════════════

/**
 * 模型权重可视化: 一根横向比例条, TCN + 生理 + 个性化三段
 *
 *  ┌──────────────────────────┐
 *  │ TCN      生理     个性化  │
 *  │ [████████░░░░░░░░░░░░░] │
 *  │   60%       30%      10% │
 *  └──────────────────────────┘
 */
@Composable
fun ModelWeightBar(
    tcnWeight: Double,
    physioWeight: Double,
    personalizationWeight: Double = 0.0,   // ★ 新增: 个性化权重 (PredictionViewModel 计算后传入)
    modifier: Modifier = Modifier
) {

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 顶部标签
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Insights, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "融合权重",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(12.dp))

            // 权重条 (三段)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                if (tcnWeight > 0) {
                    Box(
                        modifier = Modifier
                            .weight(tcnWeight.toFloat().coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .background(Chart1)
                    )
                }
                if (physioWeight > 0) {
                    Box(
                        modifier = Modifier
                            .weight(physioWeight.toFloat().coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .background(Chart3)
                    )
                }
                if (personalizationWeight > 0.001) {
                    Box(
                        modifier = Modifier
                            .weight(personalizationWeight.toFloat().coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .background(Chart2)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 图例
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LegendItem(
                    color = Chart1,
                    label = "TCN",
                    value = if (tcnWeight > 0) "${(tcnWeight * 100).toInt()}%" else "—"
                )
                LegendItem(
                    color = Chart3,
                    label = "DallaMan",
                    value = "${(physioWeight * 100).toInt()}%"
                )
                LegendItem(
                    color = Chart2,
                    label = "个性化",
                    value = if (personalizationWeight > 0.001) "${(personalizationWeight * 100).toInt()}%" else "—"
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}


// ════════════════════════════════════════════════════════
// QuickInsightRow: 关键上下文指标 (横向滑)
// ════════════════════════════════════════════════════════

@Composable
fun QuickInsightRow(
    items: List<Triple<String, String, Color>>,  // (label, value, color)
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items.forEach { (label, value, color) ->
            QuickInsightChip(label, value, color)
        }
    }
}

@Composable
private fun QuickInsightChip(label: String, value: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


// ════════════════════════════════════════════════════════
// ComposePredictionChart: Compose Canvas 预测曲线
// (替代 PredictionChartView 的传统 View)
// ════════════════════════════════════════════════════════

/**
 * Compose Canvas 实现的预测曲线:
 *  - 历史血糖 (实线 + 数据点)
 *  - DallaMan 生理曲线 (虚线)
 *  - 增量残差 (绿色点线)
 *  - 最终融合 (粗实线 + 渐变填充)
 *  - 目标范围绿色背景
 *  - 高/低血糖区域淡色提示
 *  - 触摸显示 tooltip
 */
@Composable
fun ComposePredictionChart(
    history: List<Pair<Long, Double>>,
    physioCurve: List<Double>,
    /**
     * ★ v3.0.8: 第三条线改名为 tcnCurve (原 incrementalCurve), 含义从"增量残差"改为"TCN 模型单独预测"
     * 兼容旧名: 仍接收 incrementalCurve 参数, 但推荐使用 tcnCurve
     */
    tcnCurve: List<Double> = emptyList(),
    incrementalCurve: List<Double> = emptyList(),
    finalCurve: List<Double>,
    currentGlucose: Double,
    targetLow: Double,
    targetHigh: Double,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 280.dp
) {
    // 兼容: tcnCurve 优先, 兜底用 incrementalCurve
    val thirdCurve = if (tcnCurve.isNotEmpty()) tcnCurve else incrementalCurve
    // ★ 修复精准定位: 用浮点 fractionalIndex (0..futureLen-1), 支持任意位置点击/拖动
    var fractionalIndex by remember { mutableStateOf<Float?>(null) }

    // ★ 在 Composable 作用域内取颜色 (避免 Canvas 内访问 Composable)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(20.dp))
                .background(surfaceColor)
                .pointerInput(Unit) {
                    // ★ 修复点击定位: 用 offset.x / width 计算 fractionalIndex, 任意位置都能选中
                    detectTapGestures { offset ->
                        val w = size.width.toFloat()
                        val nPoints = finalCurve.size
                        if (nPoints > 1 && w > 0) {
                            fractionalIndex = ((offset.x / w) * (nPoints - 1)).coerceIn(0f, (nPoints - 1).toFloat())
                        }
                    }
                }
                .pointerInput(Unit) {
                    // ★ 拖动也支持 - 连续滑动选点
                    detectDragGestures(
                        onDragStart = { offset ->
                            val w = size.width.toFloat()
                            val nPoints = finalCurve.size
                            if (nPoints > 1 && w > 0) {
                                fractionalIndex = ((offset.x / w) * (nPoints - 1)).coerceIn(0f, (nPoints - 1).toFloat())
                            }
                        },
                        onDrag = { change, _ ->
                            val w = size.width.toFloat()
                            val nPoints = finalCurve.size
                            if (nPoints > 1 && w > 0) {
                                fractionalIndex = ((change.position.x / w) * (nPoints - 1)).coerceIn(0f, (nPoints - 1).toFloat())
                            }
                        }
                    )
                }
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                if (finalCurve.isEmpty() && history.isEmpty()) return@Canvas

                val padLeft = 40f
                val padRight = 16f
                val padTop = 24f
                val padBottom = 32f
                val chartWidth = size.width - padLeft - padRight
                val chartHeight = size.height - padTop - padBottom

                // Y 轴范围: 自动适应数据
                val allValues = (history.map { it.second } +
                    finalCurve.filter { it > 0 } +
                    physioCurve.filter { it > 0 } +
                    thirdCurve.filter { it > 0 })
                val yMin = (allValues.minOrNull() ?: 2.0).coerceAtMost(targetLow - 1)
                val yMax = (allValues.maxOrNull() ?: 14.0).coerceAtLeast(targetHigh + 2)
                val yRange = (yMax - yMin).coerceAtLeast(2.0)

                fun toY(v: Double): Float {
                    val clamped = v.coerceIn(yMin, yMax)
                    return padTop + ((yMax - clamped) / yRange * chartHeight).toFloat()
                }

                // X 轴: 时间映射 (历史 + 预测)
                val historyLen = history.size
                val futureLen = finalCurve.size
                val totalPoints = historyLen + futureLen
                fun toX(idx: Int): Float {
                    return padLeft + (idx.toFloat() / (totalPoints - 1).coerceAtLeast(1)) * chartWidth
                }
                fun toXFloat(idxFloat: Float): Float {
                    return padLeft + (idxFloat / (totalPoints - 1).coerceAtLeast(1)) * chartWidth
                }

                // ───── 1. 目标范围背景 (绿色半透明) ─────
                val targetTop = toY(targetHigh)
                val targetBottom = toY(targetLow)
                drawRect(
                    color = GlucoseNormal.copy(alpha = 0.12f),
                    topLeft = Offset(padLeft, targetTop),
                    size = Size(chartWidth, (targetBottom - targetTop).coerceAtLeast(2f))
                )

                // ───── 2. 高/低血糖风险区背景 ─────
                if (yMax > targetHigh) {
                    drawRect(
                        color = GlucoseHigh.copy(alpha = 0.06f),
                        topLeft = Offset(padLeft, padTop),
                        size = Size(chartWidth, (targetTop - padTop).coerceAtLeast(0f))
                    )
                }
                if (yMin < targetLow) {
                    drawRect(
                        color = GlucoseLow.copy(alpha = 0.06f),
                        topLeft = Offset(padLeft, targetBottom),
                        size = Size(chartWidth, (padTop + chartHeight - targetBottom).coerceAtLeast(0f))
                    )
                }

                // ───── 3. 网格线 ─────
                val gridColor = ChartGrid.copy(alpha = 0.5f)
                for (i in 0..4) {
                    val y = padTop + (i.toFloat() / 4) * chartHeight
                    drawLine(
                        color = gridColor,
                        start = Offset(padLeft, y),
                        end = Offset(padLeft + chartWidth, y),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
                    )
                }
                for (i in 0..4) {
                    val x = padLeft + (i.toFloat() / 4) * chartWidth
                    drawLine(
                        color = gridColor,
                        start = Offset(x, padTop),
                        end = Offset(x, padTop + chartHeight),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
                    )
                }

                // ───── 4. Y 轴标签 (mmol/L) ─────
                val labelColorArgb = android.graphics.Color.argb(180, 100, 116, 139)
                val textSizePx = 11.sp.toPx()
                drawIntoCanvas { canvas ->
                    val nativeCanvas = canvas.nativeCanvas
                    val paint = android.graphics.Paint().apply {
                        color = labelColorArgb
                        textSize = textSizePx
                        textAlign = android.graphics.Paint.Align.RIGHT
                        isAntiAlias = true
                    }
                    for (i in 0..4) {
                        val v = yMax - (i.toFloat() / 4) * yRange
                        val y = padTop + (i.toFloat() / 4) * chartHeight + 4f
                        nativeCanvas.drawText(String.format("%.1f", v), padLeft - 6f, y, paint)
                    }
                }

                // ───── 5. 当前时间分割线 (虚线) ─────
                val nowX = toX(historyLen)
                drawLine(
                    color = outlineColor,
                    start = Offset(nowX, padTop),
                    end = Offset(nowX, padTop + chartHeight),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                )

                // ★ 平滑曲线 helper: Catmull-Rom 转 Bezier, 消除锐角
                fun buildSmoothPath(points: List<Offset>): Path {
                    if (points.isEmpty()) return Path()
                    val p = Path()
                    p.moveTo(points[0].x, points[0].y)
                    if (points.size < 3) {
                        for (i in 1 until points.size) p.lineTo(points[i].x, points[i].y)
                        return p
                    }
                    val tension = 0.5f  // 0=直线, 1=强平滑, 0.5=平衡
                    for (i in 0 until points.size - 1) {
                        val p0 = points[if (i == 0) 0 else i - 1]
                        val p1 = points[i]
                        val p2 = points[i + 1]
                        val p3 = points[if (i + 2 < points.size) i + 2 else points.size - 1]
                        val cp1x = p1.x + (p2.x - p0.x) / 6f * tension * 2f
                        val cp1y = p1.y + (p2.y - p0.y) / 6f * tension * 2f
                        val cp2x = p2.x - (p3.x - p1.x) / 6f * tension * 2f
                        val cp2y = p2.y - (p3.y - p1.y) / 6f * tension * 2f
                        p.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
                    }
                    return p
                }

                // ───── 6. DallaMan 生理曲线 (虚线 + 平滑) ─────
                if (physioCurve.isNotEmpty() && physioCurve.size >= futureLen) {
                    val physioPoints = (0 until futureLen).map { i ->
                        Offset(toX(historyLen + i), toY(physioCurve[i]))
                    }
                    drawPath(
                        path = buildSmoothPath(physioPoints),
                        color = Chart3.copy(alpha = 0.7f),
                        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
                    )
                }

                // ───── 7. TCN 模型曲线 (点线) ─────
                if (thirdCurve.isNotEmpty() && thirdCurve.size >= futureLen) {
                    val tcnPoints = (0 until futureLen).map { i ->
                        Offset(toX(historyLen + i), toY(thirdCurve[i]))
                    }
                    drawPath(
                        path = buildSmoothPath(tcnPoints),
                        color = Chart2.copy(alpha = 0.7f),
                        style = Stroke(width = 1.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                    )
                }

                // ───── 8. 历史血糖线 (实线 + 点 + 平滑) ─────
                if (history.isNotEmpty()) {
                    val histPoints = history.mapIndexed { i, (_, v) -> Offset(toX(i), toY(v)) }
                    drawPath(
                        path = buildSmoothPath(histPoints),
                        color = Chart1.copy(alpha = 0.85f),
                        style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                    )
                    history.forEachIndexed { i, (_, v) ->
                        if (i % 4 == 0 || i == history.size - 1) {
                            val x = toX(i)
                            val y = toY(v)
                            drawCircle(
                                color = Chart1,
                                radius = 3.5f,
                                center = Offset(x, y)
                            )
                        }
                    }
                }

                // ───── 9. 最终融合曲线 + 渐变填充 (平滑) ─────
                if (finalCurve.isNotEmpty()) {
                    val finalPoints = (0 until futureLen).map { i ->
                        Offset(toX(historyLen + i), toY(finalCurve[i]))
                    }
                    val finalPath = buildSmoothPath(finalPoints)
                    // 渐变填充 (曲线下)
                    val fillPath = Path().apply {
                        addPath(finalPath)
                        lineTo(toX(historyLen + futureLen - 1), padTop + chartHeight)
                        lineTo(toX(historyLen), padTop + chartHeight)
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Chart1.copy(alpha = 0.35f),
                                Chart1.copy(alpha = 0.05f),
                                Color.Transparent
                            )
                        )
                    )
                    drawPath(
                        path = finalPath,
                        color = Chart1,
                        style = Stroke(width = 4f, cap = StrokeCap.Round)
                    )
                    // 当前点 (高亮圆)
                    val nowX2 = toX(historyLen)
                    val nowY = toY(currentGlucose)
                    drawCircle(
                        color = Color.White,
                        radius = 8f,
                        center = Offset(nowX2, nowY)
                    )
                    drawCircle(
                        color = Chart1,
                        radius = 6f,
                        center = Offset(nowX2, nowY)
                    )
                }

                // ───── 10. 选中点 tooltip (浮点定位 + 线性插值) ─────
                fractionalIndex?.let { fIdx ->
                    val lo = fIdx.toInt().coerceIn(0, (finalCurve.size - 1).coerceAtLeast(0))
                    val hi = (lo + 1).coerceAtMost((finalCurve.size - 1).coerceAtLeast(0))
                    val frac = fIdx - lo
                    val vLo = finalCurve.getOrNull(lo)
                    val vHi = finalCurve.getOrNull(hi)
                    if (vLo != null && vHi != null) {
                        // ★ 精准: 选中值用线性插值, 不再锁在 5 分钟一个点
                        val v = vLo + (vHi - vLo) * frac
                        val x = toXFloat(historyLen + fIdx)
                        val y = toY(v)
                        drawLine(
                            color = outlineColor.copy(alpha = 0.4f),
                            start = Offset(x, padTop),
                            end = Offset(x, padTop + chartHeight),
                            strokeWidth = 1f
                        )
                        drawCircle(
                            color = primaryColor,
                            radius = 7f,
                            center = Offset(x, y)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 4f,
                            center = Offset(x, y)
                        )
                    }
                }
            }

            // Tooltip 文字 (Compose 层) - 浮点 + 线性插值
            fractionalIndex?.let { fIdx ->
                val lo = fIdx.toInt().coerceIn(0, (finalCurve.size - 1).coerceAtLeast(0))
                val hi = (lo + 1).coerceAtMost((finalCurve.size - 1).coerceAtLeast(0))
                val frac = fIdx - lo
                val vLo = finalCurve.getOrNull(lo)
                val vHi = finalCurve.getOrNull(hi)
                if (vLo != null && vHi != null) {
                    val v = vLo + (vHi - vLo) * frac
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = primaryColor,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                text = String.format("%.2f mmol/L", v),
                                style = MaterialTheme.typography.labelLarge,
                                color = onPrimaryColor,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${"%.1f".format(fIdx * 5)} 分钟",   // ★ 任意分钟数 (可小数)
                                style = MaterialTheme.typography.labelSmall,
                                color = onPrimaryColor.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }

        // ★ 滑块 - 用户可滑动控制选中的点 (不是5分钟一个点, 均匀滑动)
        if (finalCurve.size >= 2) {
            Slider(
                value = fractionalIndex ?: 0f,
                onValueChange = { fractionalIndex = it },
                valueRange = 0f..(finalCurve.size - 1).coerceAtLeast(1).toFloat(),
                steps = 0,    // ★ 0=连续平滑, 不锁定到离散点
                colors = SliderDefaults.colors(
                    thumbColor = primaryColor,
                    activeTrackColor = primaryColor,
                    inactiveTrackColor = primaryColor.copy(alpha = 0.2f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}
