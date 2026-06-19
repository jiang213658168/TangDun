package com.tangdun.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tangdun.app.sync.DataSyncWorker
import com.tangdun.app.sync.InsulinReminderWorker
import com.tangdun.app.ui.chat.ChatScreen
import com.tangdun.app.ui.health.HealthScreen
import com.tangdun.app.ui.home.HomeScreen
import com.tangdun.app.ui.meal.MealScreen
import com.tangdun.app.ui.exercise.ExerciseScreen
import com.tangdun.app.ui.insulin.InsulinScreen
import com.tangdun.app.ui.prediction.PredictionScreen
import com.tangdun.app.ui.report.ReportScreen
import com.tangdun.app.ui.settings.SettingsScreen
import com.tangdun.app.ui.splash.SplashScreen
import com.tangdun.app.ui.theme.Primary
import com.tangdun.app.ui.theme.TangDunTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        DataSyncWorker.schedulePeriodicSync(this)
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

// ── 5个主Tab，其余功能从首页/设置进入 ──
sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Home : Screen("home", "首页", Icons.Filled.Home, Icons.Outlined.Home)
    object Prediction : Screen("prediction", "预测", Icons.Filled.ShowChart, Icons.Outlined.ShowChart)
    object Record : Screen("record", "记录", Icons.Filled.EditNote, Icons.Outlined.EditNote)
    object Report : Screen("report", "报告", Icons.Filled.Assessment, Icons.Outlined.Assessment)
    object Settings : Screen("settings", "我的", Icons.Filled.Person, Icons.Outlined.Person)
}

// 子页面路由（不在底部导航栏中）
object SubScreen {
    const val MEAL = "meal"
    const val INSULIN = "insulin"
    const val HEALTH = "health"
    const val EXERCISE = "exercise"
    const val CHAT = "chat"
    // ★ AI 记录 已合并到 ChatScreen, 不再单独路由
}

val mainScreens = listOf(Screen.Home, Screen.Record, Screen.Prediction, Screen.Report, Screen.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                mainScreens.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title
                            )
                        },
                        label = { Text(screen.title, style = MaterialTheme.typography.labelSmall) },
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
            composable(Screen.Home.route) { HomeScreen(navController = navController) }
            composable(Screen.Prediction.route) { PredictionScreen() }
            composable(Screen.Record.route) { RecordScreen(navController) }
            composable(Screen.Report.route) { ReportScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }

            // 子页面
            composable(SubScreen.MEAL) { SubPageScaffold("饮食记录", navController) { MealScreen() } }
            composable(SubScreen.INSULIN) { SubPageScaffold("胰岛素记录", navController) { InsulinScreen() } }
            composable(SubScreen.HEALTH) { SubPageScaffold("健康记录", navController) { HealthScreen() } }
            composable(SubScreen.EXERCISE) { SubPageScaffold("运动管理", navController) { ExerciseScreen() } }
            composable(SubScreen.CHAT) { SubPageScaffold("AI助手", navController, showTopBar = false) { ChatScreen(navController = navController) } }
        }
    }
}

@Composable
fun RecordScreen(navController: androidx.navigation.NavController) {
    val items = listOf(
        Triple("AI助手", Icons.Default.AutoAwesome, SubScreen.CHAT),
        Triple("饮食", Icons.Default.Restaurant, SubScreen.MEAL),
        Triple("胰岛素", Icons.Default.MedicalServices, SubScreen.INSULIN),
        Triple("运动", Icons.Default.DirectionsRun, SubScreen.EXERCISE),
        Triple("健康", Icons.Default.HealthAndSafety, SubScreen.HEALTH),
    )
    Column(Modifier.fillMaxSize()) {
        // 卡片式入口，不用TabRow
        Text("健康记录", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items.size) { i ->
                val (name, icon, route) = items[i]
                Card(
                    Modifier.fillMaxWidth().padding(4.dp).height(120.dp).clickable { navController.navigate(route) },
                    shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, name, Modifier.size(36.dp), tint = Primary)
                        Spacer(Modifier.height(8.dp))
                        Text(name, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun SubPageScaffold(title: String, navController: androidx.navigation.NavController, showTopBar: Boolean = true, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (showTopBar) {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary)
            )
        }
        content()
    }
}
