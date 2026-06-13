package com.tangdun.app.ui.meal

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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangdun.app.domain.algorithm.BergmanModel
import com.tangdun.app.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What-if模拟页面
 *
 * 模拟进食后的血糖变化
 */
@Composable
fun WhatIfScreen(
    currentGlucose: Double,
    viewModel: WhatIfViewModel = hiltViewModel()
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
                    text = "What-if模拟",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            navigationIcon = {
                IconButton(onClick = { /* 返回 */ }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )

        // 当前血糖
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "当前血糖:",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = String.format("%.1f mmol/L", currentGlucose),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // 食物输入区
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "模拟进食",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 预设食物选择
                val presetFoods = listOf(
                    "白米饭" to 60.0,
                    "面条" to 70.0,
                    "馒头" to 50.0,
                    "苹果" to 25.0,
                    "牛奶" to 12.0
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presetFoods.take(3).forEach { (name, carbs) ->
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.addFood(name, carbs) },
                            label = { Text("$name ${carbs}g") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.simulate(currentGlucose) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("开始模拟")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 模拟结果
        if (uiState.result != null) {
            // 预测峰值
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        uiState.result!!.peakGlucose > 10.0 -> AlertWarning.copy(alpha = 0.1f)
                        uiState.result!!.peakGlucose < 3.9 -> AlertCritical.copy(alpha = 0.1f)
                        else -> AlertSuccess.copy(alpha = 0.1f)
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "预测峰值",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Text(
                        text = String.format("%.1f mmol/L", uiState.result!!.peakGlucose),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            uiState.result!!.peakGlucose > 10.0 -> AlertWarning
                            uiState.result!!.peakGlucose < 3.9 -> AlertCritical
                            else -> AlertSuccess
                        }
                    )
                    Text(
                        text = "达峰时间: ${uiState.result!!.peakTimeMinutes}分钟",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHint
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 血糖曲线图
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "血糖预测曲线",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        val curve = uiState.result!!.curve
                        val width = size.width
                        val height = size.height
                        val padding = 50f

                        val minY = 2.0
                        val maxY = 16.0

                        fun toX(index: Int) = padding + (index.toFloat() / (curve.size - 1) * (width - 2 * padding))
                        fun toY(value: Double) = padding + ((maxY - value.coerceIn(minY, maxY)) / (maxY - minY) * (height - 2 * padding)).toFloat()

                        // 网格
                        for (i in 0..6) {
                            val y = toY(minY + i * (maxY - minY) / 6)
                            drawLine(ChartGrid, Offset(padding, y), Offset(width - padding, y), 1f)
                        }

                        // 目标范围
                        val targetLowY = toY(3.9)
                        val targetHighY = toY(10.0)
                        drawLine(ChartTarget, Offset(padding, targetLowY), Offset(width - padding, targetLowY), 2f)
                        drawLine(Color(0x80FF9800), Offset(padding, targetHighY), Offset(width - padding, targetHighY), 2f)

                        // 血糖曲线
                        val path = Path()
                        path.moveTo(toX(0), toY(curve[0]))
                        for (i in 1 until curve.size) {
                            path.lineTo(toX(i), toY(curve[i]))
                        }
                        drawPath(path, ChartLine1, style = Stroke(width = 3f))

                        // 低GI替代曲线
                        val lowGiCurve = uiState.result!!.lowGiAlternative
                        val lowGiPath = Path()
                        lowGiPath.moveTo(toX(0), toY(lowGiCurve[0]))
                        for (i in 1 until lowGiCurve.size) {
                            lowGiPath.lineTo(toX(i), toY(lowGiCurve[i]))
                        }
                        drawPath(lowGiPath, ChartLine2, style = Stroke(width = 2f))
                    }

                    // 图例
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Canvas(modifier = Modifier.size(24.dp, 3.dp)) {
                            drawRect(ChartLine1)
                        }
                        Text(" 当前选择 ", style = MaterialTheme.typography.bodySmall)
                        Canvas(modifier = Modifier.size(24.dp, 3.dp)) {
                            drawRect(ChartLine2)
                        }
                        Text(" 低GI替代", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 低GI建议
            if (uiState.result!!.peakReduction > 0.5) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AlertSuccess.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Lightbulb,
                            contentDescription = null,
                            tint = AlertSuccess,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "低GI替代建议",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "选择低GI食物可使血糖峰值降低 ${String.format("%.1f", uiState.result!!.peakReduction)} mmol/L",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@HiltViewModel
class WhatIfViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(WhatIfUiState())
    val uiState: StateFlow<WhatIfUiState> = _uiState.asStateFlow()

    private val selectedFoods = mutableListOf<Pair<String, Double>>()
    private val bergmanModel = BergmanModel()

    fun addFood(name: String, carbs: Double) {
        selectedFoods.add(name to carbs)
    }

    fun simulate(currentGlucose: Double) {
        viewModelScope.launch {
            val meals = selectedFoods.map { (name, carbs) ->
                BergmanModel.MealInput(
                    timeMinutes = 0.0,
                    carbsGrams = carbs,
                    gi = 50.0
                )
            }

            val result = bergmanModel.whatIfSimulation(currentGlucose, meals)
            _uiState.value = WhatIfUiState(result = result)
        }
    }
}

data class WhatIfUiState(
    val result: BergmanModel.WhatIfResult? = null
)