package com.tangdun.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangdun.app.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * 糖盾 TangDun 产品级 UI 组件库 (v2.0)
 *
 * 设计原则:
 *  - 信息层级: Hero / Insight / Action
 *  - 视觉层级: Elevation 0-5 + 圆角梯度
 *  - 交互反馈: 按下 scale 0.97 + ripple
 *  - 状态色: 跟随血糖值动态变化
 */

// ════════════════════════════════════════════════════════
// HeroGlucoseCard: 大血糖数字 + 状态环 + 趋势信息
// (产品级, 用于首页最上方)
// ════════════════════════════════════════════════════════

/**
 * 首页核心: 大血糖展示卡
 *
 * 视觉:
 *  ┌──────────────────────────────┐
 *  │  当前血糖            🟢 良好 │
 *  │                              │
 *  │      ╭──────────────╮        │
 *  │      │   5.6        │  ↗     │
 *  │      │   mmol/L     │        │
 *  │      ╰──────────────╯        │
 *  │                              │
 *  │  ↑ 0.3 较30分钟前             │
 *  │                              │
 *  │  [目标 3.9-10.0]  [TIR 78%]  │
 *  └──────────────────────────────┘
 */
@Composable
fun HeroGlucoseCard(
    currentGlucose: Double?,
    targetLow: Double,
    targetHigh: Double,
    change30min: Double? = null,
    onAddClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 状态色: 跟随血糖值动态变化
    val statusColor = currentGlucose?.let { glucoseColor(it, targetLow, targetHigh) }
        ?: MaterialTheme.colorScheme.outline
    val animatedColor by animateColorAsState(targetValue = statusColor, label = "statusColor")

    // 状态文案
    val statusText = currentGlucose?.let {
        when {
            it < 3.0 -> "严重低血糖"
            it < targetLow -> "低血糖"
            it < targetLow + 0.6 -> "偏低"
            it <= targetHigh -> "正常"
            it < targetHigh + 3.9 -> "偏高"
            it < 13.9 -> "高血糖"
            else -> "严重高血糖"
        }
    } ?: "暂无数据"

    // 趋势 (change30min: mmol/L, 正=上升)
    val trendArrow = when {
        change30min == null -> Icons.Outlined.Remove
        change30min > 0.1 -> Icons.Outlined.TrendingUp
        change30min < -0.1 -> Icons.Outlined.TrendingDown
        else -> Icons.Outlined.TrendingFlat
    }
    val trendColor = when {
        change30min == null -> MaterialTheme.colorScheme.outline
        change30min > 0.1 -> GlucoseHigh
        change30min < -0.1 -> GlucoseLow
        else -> GlucoseNormal
    }

    // 整体渐变背景 (跟随状态色)
    val cardBrush = Brush.verticalGradient(
        colors = listOf(
            animatedColor.copy(alpha = 0.08f),
            animatedColor.copy(alpha = 0.02f),
            Color.Transparent,
        )
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(24.dp),
                clip = false
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBrush)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                // ── 顶部: 标签 + 添加按钮 ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "当前血糖",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    StatusBadge(text = statusText, color = animatedColor)
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = onAddClick,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "记录血糖",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── 中部: 大血糖数字 + 趋势箭头 ──
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 大数字 (等宽字体, 切换不抖)
                    Text(
                        text = currentGlucose?.let { String.format("%.1f", it) } ?: "--",
                        style = TangDunNumberLargeStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    // 单位
                    Text(
                        text = "mmol/L",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    // 趋势箭头
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(trendColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            trendArrow,
                            contentDescription = "趋势",
                            tint = trendColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── 变化提示 ──
                if (change30min != null) {
                    Text(
                        text = String.format(
                            "%s %.1f mmol/L 较30分钟前",
                            if (change30min >= 0) "↑" else "↓",
                            kotlin.math.abs(change30min)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(20.dp))

                // ── 底部: 目标范围进度条 ──
                TargetRangeIndicator(
                    current = currentGlucose,
                    low = targetLow,
                    high = targetHigh,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun TargetRangeIndicator(
    current: Double?, low: Double, high: Double,
    modifier: Modifier = Modifier
) {
    val range = (high - low).coerceAtLeast(0.1)
    val currentRatio = current?.let {
        ((it - low + range * 0.5) / (range * 2.5)).coerceIn(0.0, 1.0)
    }

    Column(modifier = modifier) {
        // 范围标签
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "目标范围",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = String.format("%.1f - %.1f mmol/L", low, high),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))

        // 进度条: 3 段 (低/正常/高), 当前值用圆点标记
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(0.2f).fillMaxHeight().background(GlucoseLow.copy(alpha = 0.3f)))
                Box(modifier = Modifier.weight(0.6f).fillMaxHeight().background(GlucoseNormal.copy(alpha = 0.4f)))
                Box(modifier = Modifier.weight(0.2f).fillMaxHeight().background(GlucoseHigh.copy(alpha = 0.3f)))
            }
            // 当前值标记
            if (currentRatio != null) {
                val targetColor = glucoseColor(current ?: 0.0, low, high)
                Box(
                    modifier = Modifier
                        .padding(start = (currentRatio * 100).dp.coerceAtLeast(0.dp))
                        .size(16.dp)
                        .offset(y = (-4).dp)
                        .clip(CircleShape)
                        .background(targetColor)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
    }
}


// ════════════════════════════════════════════════════════
// InsightStatCard: 关键指标卡 (紧凑, 用于洞察栏)
// ════════════════════════════════════════════════════════

/**
 * 关键指标卡
 *
 * 视觉:
 *  ┌──────────────┐
 *  │ 📊 TIR       │
 *  │              │
 *  │   78%        │
 *  │   达标时间   │
 *  └──────────────┘
 */
@Composable
fun InsightStatCard(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val source = remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, label = "cardScale")

    Card(
        modifier = modifier
            .scale(scale)
            .shadow(2.dp, RoundedCornerShape(20.dp), clip = false)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = onClick != null,
                onClick = { onClick?.invoke() }
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 图标 (柔和背景色)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            // 数值 (大)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = TangDunNumberStyle,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (unit != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 标签
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


// ════════════════════════════════════════════════════════
// SectionHeader: 区块标题
// ════════════════════════════════════════════════════════

@Composable
fun SectionHeader(
    title: String,
    action: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        if (action != null && onActionClick != null) {
            TextButton(
                onClick = onActionClick,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(action, style = MaterialTheme.typography.labelMedium)
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}


// ════════════════════════════════════════════════════════
// QuickActionRow: 快捷操作行 (横向滑动)
// ════════════════════════════════════════════════════════

@Composable
fun QuickActionRow(
    actions: List<QuickAction>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        actions.forEach { action ->
            QuickActionChip(action = action)
        }
    }
}

data class QuickAction(
    val icon: ImageVector,
    val label: String,
    val accentColor: Color,
    val onClick: () -> Unit
)

@Composable
private fun QuickActionChip(action: QuickAction) {
    val source = remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, label = "chipScale")

    Surface(
        modifier = Modifier
            .scale(scale)
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = action.onClick
            ),
        shape = RoundedCornerShape(16.dp),
        color = action.accentColor.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, action.accentColor.copy(alpha = 0.2f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(
                action.icon, contentDescription = null,
                tint = action.accentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                action.label,
                style = MaterialTheme.typography.labelLarge,
                color = action.accentColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}


// ════════════════════════════════════════════════════════
// ModernTopBar: 现代顶部栏 (沉浸式, 带毛玻璃可选)
// ════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTopBar(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    navigationIcon: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                navigationIcon()
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                actions()
            }
        }
    }
}
