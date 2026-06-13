package com.tangdun.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tangdun.app.sync.DataSyncWorker
import com.tangdun.app.sync.KeepAliveService
import com.tangdun.app.sync.InsulinReminderWorker
import com.tangdun.app.ui.chat.ChatScreen
import com.tangdun.app.ui.splash.SplashScreen
import com.tangdun.app.ui.health.HealthScreen
import com.tangdun.app.ui.home.HomeScreen
import com.tangdun.app.ui.meal.MealScreen
import com.tangdun.app.ui.exercise.ExerciseScreen
import com.tangdun.app.ui.insulin.InsulinScreen
import com.tangdun.app.ui.prediction.PredictionScreen
import com.tangdun.app.ui.report.ReportScreen
import com.tangdun.app.ui.settings.SettingsScreen
import com.tangdun.app.ui.theme.TangDunTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 后台保活
        KeepAliveService.start(this)

        // 启动数据同步
        DataSyncWorker.schedulePeriodicSync(this)

        // 启动用药提醒
        InsulinReminderWorker.schedule(this)

        setContent {
            TangDunTheme {
                var showSplash by remember { mutableStateOf(true) }
                if (showSplash) {
                    SplashScreen(onFinish = { showSplash = false })
                } else {
                    MainScreen()
                }
            }
        }
    }
}

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Home : Screen("home", "首页", Icons.Filled.Home, Icons.Outlined.Home)
    object Meal : Screen("meal", "饮食", Icons.Filled.Restaurant, Icons.Outlined.Restaurant)
    object Insulin : Screen("insulin", "胰岛素", Icons.Filled.MedicalServices, Icons.Outlined.MedicalServices)
    object Health : Screen("health", "健康", Icons.Filled.HealthAndSafety, Icons.Outlined.HealthAndSafety)
    object Exercise : Screen("exercise", "运动", Icons.Filled.DirectionsRun, Icons.Outlined.DirectionsRun)
    object Prediction : Screen("prediction", "预测", Icons.Filled.ShowChart, Icons.Outlined.ShowChart)
    object Chat : Screen("chat", "AI助手", Icons.Filled.SmartToy, Icons.Outlined.SmartToy)
    object Report : Screen("report", "报告", Icons.Filled.Assessment, Icons.Outlined.Assessment)
    object Settings : Screen("settings", "我的", Icons.Filled.Person, Icons.Outlined.Person)
}

val screens = listOf(Screen.Home, Screen.Meal, Screen.Insulin, Screen.Health, Screen.Exercise, Screen.Prediction, Screen.Chat, Screen.Report, Screen.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title
                            )
                        },
                        label = { Text(screen.title) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.Meal.route) { MealScreen() }
            composable(Screen.Insulin.route) { InsulinScreen() }
            composable(Screen.Health.route) { HealthScreen() }
            composable(Screen.Exercise.route) { ExerciseScreen() }
            composable(Screen.Prediction.route) { PredictionScreen() }
            composable(Screen.Chat.route) { ChatScreen() }
            composable(Screen.Report.route) { ReportScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
