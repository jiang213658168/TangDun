package com.tangdun.app.ui.exercise

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
import com.tangdun.app.ui.theme.*

/**
 * 运动管理页面
 *
 * 显示：
 * - 今日运动统计
 * - 运动处方推荐
 */
@Composable
fun ExerciseScreen(
    viewModel: ExerciseViewModel = hiltViewModel()
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
                    text = "运动管理",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )

        // 今日运动统计
        TodayExerciseStatsCard(
            totalDuration = uiState.todayDuration,
            totalSteps = uiState.todaySteps,
            totalCalories = uiState.todayCalories,
            exerciseCount = uiState.todayExerciseCount
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 运动处方
        ExercisePrescriptionCard(
            exerciseType = uiState.recommendedType,
            duration = uiState.recommendedDuration,
            intensity = uiState.recommendedIntensity,
            expectedDrop = uiState.expectedGlucoseDrop,
            notes = uiState.prescriptionNotes
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun TodayExerciseStatsCard(
    totalDuration: Int,
    totalSteps: Int,
    totalCalories: Double,
    exerciseCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "今日运动统计",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("运动时长", "${totalDuration}分钟")
                StatItem("步数", "${totalSteps}")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("消耗热量", String.format("%.0fkcal", totalCalories))
                StatItem("运动次数", "${exerciseCount}次")
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextHint
        )
    }
}

@Composable
fun ExercisePrescriptionCard(
    exerciseType: String,
    duration: Int,
    intensity: String,
    expectedDrop: Double,
    notes: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "运动处方",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "推荐运动",
                style = MaterialTheme.typography.bodySmall,
                color = TextHint
            )
            Text(
                text = exerciseType,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row {
                Text(
                    text = "时长: ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextHint
                )
                Text(
                    text = "${duration}分钟",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = "强度: ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextHint
                )
                Text(
                    text = intensity,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = String.format("预计血糖下降 %.1f mmol/L", expectedDrop),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            if (notes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}
