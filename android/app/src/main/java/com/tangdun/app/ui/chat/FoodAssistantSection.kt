package com.tangdun.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tangdun.app.domain.algorithm.DallaManModel
import com.tangdun.app.ui.theme.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * AI 食物助手面板 (集成在 ChatScreen 顶部)
 *
 * 3 个核心功能按钮:
 *  - 🍽 "如果吃这个会怎样" → 弹输入 → simulateMealImpact
 *  - 💡 "AI 推荐吃什么"     → 直接调 recommendMeal
 *  - 🍳 "怎么做这道菜"     → 弹输入 → discussMealRecipe
 */
@Composable
fun FoodAssistantSection(
    helper: FoodAssistantHelper,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var showImpactDialog by remember { mutableStateOf(false) }
    var showRecipeDialog by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var detailedResult by remember { mutableStateOf<MealImpactResult?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 顶部: 食物助手区
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FoodAssistantButton(
                icon = Icons.Default.RestaurantMenu,
                label = "如果吃",
                subLabel = "会升多少?",
                color = MaterialTheme.colorScheme.primary,
                onClick = { showImpactDialog = true },
                loading = isLoading,
                modifier = Modifier.weight(1f)
            )
            FoodAssistantButton(
                icon = Icons.Default.Lightbulb,
                label = "AI 推荐",
                subLabel = "吃什么好?",
                color = MaterialTheme.colorScheme.tertiary,
                onClick = {
                    scope.launch {
                        isLoading = true
                        result = "🤔 AI 正在根据你今天的状态推荐..."
                        try {
                            val rec = helper.recommendMeal()
                            result = rec.aiText
                            detailedResult = null
                        } catch (e: Exception) {
                            result = "推荐失败: ${e.message}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                loading = isLoading,
                modifier = Modifier.weight(1f)
            )
            FoodAssistantButton(
                icon = Icons.Default.MenuBook,
                label = "怎么做",
                subLabel = "做法建议",
                color = MaterialTheme.colorScheme.secondary,
                onClick = { showRecipeDialog = true },
                loading = isLoading,
                modifier = Modifier.weight(1f)
            )
        }

        // 结果展示卡
        AnimatedVisibility(visible = result != null) {
            FoodAssistantResultCard(
                result = result,
                impact = detailedResult,
                onDismiss = {
                    result = null
                    detailedResult = null
                },
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }

    // 输入对话框 (如果吃这个)
    if (showImpactDialog) {
        FoodNameInputDialog(
            title = "如果吃这个会怎样?",
            placeholder = "例如: 米饭、苹果、面条",
            onDismiss = { showImpactDialog = false },
            onConfirm = { foodName ->
                showImpactDialog = false
                scope.launch {
                    isLoading = true
                    result = "🔮 正在用 Dalla Man 模型仿真..."
                    try {
                        val impact = helper.simulateMealImpact(foodName)
                        detailedResult = impact
                        result = if (impact.success) impact.aiAdvice else impact.errorMessage
                    } catch (e: Exception) {
                        result = "预测失败: ${e.message}"
                    } finally {
                        isLoading = false
                    }
                }
            }
        )
    }

    // 输入对话框 (做法)
    if (showRecipeDialog) {
        FoodNameInputDialog(
            title = "想做这道菜?",
            placeholder = "例如: 红烧肉、西红柿炒蛋",
            onDismiss = { showRecipeDialog = false },
            onConfirm = { foodName ->
                showRecipeDialog = false
                scope.launch {
                    isLoading = true
                    result = "👨‍🍳 正在根据你的数据生成做法..."
                    try {
                        val recipe = helper.discussMealRecipe(foodName)
                        result = recipe.aiText
                        detailedResult = null
                    } catch (e: Exception) {
                        result = "生成失败: ${e.message}"
                    } finally {
                        isLoading = false
                    }
                }
            }
        )
    }
}

@Composable
private fun FoodAssistantButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subLabel: String,
    color: Color,
    onClick: () -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !loading, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = color,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    icon, contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FoodAssistantResultCard(
    result: String?,
    impact: MealImpactResult?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 顶部: 标题 + 关闭
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "AI 食物助手",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 预测详情 (如果有)
            if (impact != null && impact.success) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ImpactStat(
                        label = "当前",
                        value = String.format("%.1f", impact.currentGlucose),
                        unit = "mmol/L",
                        color = glucoseColor(impact.currentGlucose)
                    )
                    ImpactStat(
                        label = "预计峰值",
                        value = String.format("%.1f", impact.peakValue),
                        unit = "mmol/L",
                        color = glucoseColor(impact.peakValue)
                    )
                    ImpactStat(
                        label = "变化",
                        value = String.format("%+.1f", impact.delta),
                        unit = "mmol/L",
                        color = if (impact.delta > 1.5) GlucoseHigh else MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ImpactStat(
                        label = "碳水",
                        value = String.format("%.0f", impact.carbs),
                        unit = "g",
                        color = MaterialTheme.colorScheme.primary
                    )
                    ImpactStat(
                        label = "GI",
                        value = impact.gi.toInt().toString(),
                        unit = "",
                        color = when {
                            impact.gi < 55 -> GlucoseNormal
                            impact.gi < 70 -> GlucoseHighNormal
                            else -> GlucoseHigh
                        }
                    )
                    ImpactStat(
                        label = "峰值时间",
                        value = impact.peakTimeMinutes.toString(),
                        unit = "min",
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                if (impact.willExceedHigh) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = GlucoseHigh.copy(alpha = 0.12f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning, contentDescription = null,
                                tint = GlucoseHigh,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (impact.exceedsThreshold) "🚨 预计进入危险区, 建议减少份量或换低 GI 食物"
                                else "⚠️ 会超过目标上限, 建议餐后运动 20-30 分钟",
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlucoseHigh
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // AI 文字建议
            Text(
                text = result ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ImpactStat(
    label: String,
    value: String,
    unit: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun FoodNameInputDialog(
    title: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (text.isNotBlank()) {
                                onConfirm(text.trim())
                            }
                        },
                        enabled = text.isNotBlank(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }
}
