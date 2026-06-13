package com.tangdun.app.ui.meal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tangdun.app.data.remote.FoodNutritionAi
import com.tangdun.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 食物搜索对话框（大模型版本）
 *
 * 功能：
 * 1. 用户输入食物名称
 * 2. 大模型返回营养信息
 * 3. 选择份量
 * 4. 返回食物和份量
 */
@Composable
fun FoodSearchDialog(
    onDismiss: () -> Unit,
    onConfirm: (foodName: String, carbs: Double, calories: Double, gi: Double, portionGrams: Double) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResult by remember { mutableStateOf<FoodNutritionAi.NutritionInfo?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var portionGrams by remember { mutableStateOf("100") }

    // 常见食物快捷选择
    val quickFoods = listOf(
        "白米饭", "面条", "馒头", "包子", "饺子",
        "红烧肉", "番茄炒蛋", "清蒸鱼", "炒青菜", "豆腐",
        "苹果", "香蕉", "牛奶", "鸡蛋", "面包"
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "搜索食物",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 搜索框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        searchResult = null
                        searchError = null
                    },
                    label = { Text("输入食物名称") },
                    placeholder = { Text("例如：红烧肉、番茄炒蛋") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                searchQuery = ""
                                searchResult = null
                                searchError = null
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除")
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 搜索按钮
                Button(
                    onClick = {
                        if (searchQuery.isNotBlank()) {
                            isSearching = true
                            searchError = null
                            scope.launch {
                                try {
                                    val aiService = FoodNutritionAi(context)
                                    val result = aiService.getNutritionInfo(searchQuery)
                                    if (result != null) {
                                        searchResult = result
                                        portionGrams = result.portionGrams.toInt().toString()
                                    } else {
                                        searchError = "未找到该食物的营养信息"
                                    }
                                } catch (e: Exception) {
                                    searchError = "查询失败: ${e.message}"
                                }
                                isSearching = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = searchQuery.isNotBlank() && !isSearching
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("查询中...")
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("查询营养信息")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 查询结果
                if (searchResult != null) {
                    val result = searchResult!!
                    val grams = portionGrams.toDoubleOrNull() ?: 100.0
                    val ratio = grams / 100.0

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = result.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // GI等级
                            val giColor = when {
                                result.gi < 55 -> GiLow
                                result.gi <= 70 -> GiMedium
                                else -> GiHigh
                            }
                            Text(
                                text = "GI: ${result.gi.toInt()} (${result.giLevel})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = giColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 份量选择
                    Text("份量 (克)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = portionGrams,
                        onValueChange = { portionGrams = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("50", "100", "150", "200", "250").forEach { g ->
                            FilterChip(
                                selected = portionGrams == g,
                                onClick = { portionGrams = g },
                                label = { Text("${g}g") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 营养信息
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("营养信息 (${grams.toInt()}g)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(8.dp))
                            NutrientRow("碳水化合物", "${String.format("%.1f", result.carbs * ratio)}g")
                            NutrientRow("热量", "${String.format("%.0f", result.calories * ratio)} kcal")
                            NutrientRow("蛋白质", "${String.format("%.1f", result.protein * ratio)}g")
                            NutrientRow("脂肪", "${String.format("%.1f", result.fat * ratio)}g")
                            NutrientRow("膳食纤维", "${String.format("%.1f", result.fiber * ratio)}g")
                            NutrientRow("GI值", "${result.gi.toInt()} (${result.giLevel})")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 确认按钮
                    Button(
                        onClick = {
                            onConfirm(
                                result.name,
                                result.carbs * ratio,
                                result.calories * ratio,
                                result.gi,
                                grams
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("添加到饮食记录")
                    }
                }

                // 错误信息
                if (searchError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(searchError!!, color = AlertCritical, style = MaterialTheme.typography.bodySmall)
                }

                // 快捷选择
                if (searchResult == null && !isSearching) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("常见食物", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    // 快捷按钮 - 分行显示
                    for (i in quickFoods.indices step 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (j in i until minOf(i + 3, quickFoods.size)) {
                                FilterChip(
                                    selected = false,
                                    onClick = { searchQuery = quickFoods[j] },
                                    label = { Text(quickFoods[j]) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 取消按钮
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消")
                }
            }
        }
    }
}

@Composable
fun NutrientRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
