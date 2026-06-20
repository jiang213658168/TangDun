package com.tangdun.app.ui.meal

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Calendar
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.tangdun.app.data.local.entity.MealRecord
import com.tangdun.app.ui.components.DateTimePickerDialog
import com.tangdun.app.ui.theme.*
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 压缩图片
 *
 * 策略：如果原图小于限制，直接返回；否则适度压缩
 */
fun compressImage(
    originalBytes: ByteArray,
    maxSizeKB: Int = 3800,
    maxDimension: Int = 2048
): ByteArray {
    val originalSizeKB = originalBytes.size / 1024

    // 如果原图已经小于限制，直接返回
    if (originalSizeKB <= maxSizeKB) {
        return originalBytes
    }

    // 解码图片尺寸
    val options = android.graphics.BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    android.graphics.BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, options)

    val width = options.outWidth
    val height = options.outHeight

    // 计算缩放比例（保持宽高比）
    val scale = minOf(
        maxDimension.toFloat() / width,
        maxDimension.toFloat() / height,
        1.0f
    )

    // 计算采样率
    var sampleSize = 1
    while (width / sampleSize > maxDimension * 1.5 || height / sampleSize > maxDimension * 1.5) {
        sampleSize *= 2
    }

    // 解码图片
    val decodeOptions = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sampleSize
    }
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, decodeOptions)
        ?: return originalBytes

    // 缩放到目标大小
    val newWidth = (bitmap.width * scale).toInt()
    val newHeight = (bitmap.height * scale).toInt()
    val scaledBitmap = if (scale < 1.0f) {
        android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    } else {
        bitmap
    }

    // 压缩为JPEG（从高质量开始）
    var quality = 90
    var outputStream = ByteArrayOutputStream()
    scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outputStream)

    // 逐步降低质量直到满足大小限制
    while (outputStream.size() > maxSizeKB * 1024 && quality > 60) {
        quality -= 5
        outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outputStream)
    }

    // 释放内存
    if (scaledBitmap != bitmap) scaledBitmap.recycle()
    bitmap.recycle()

    return outputStream.toByteArray()
}

@Composable
fun MealScreen(
    viewModel: MealViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showCameraDialog by remember { mutableStateOf(false) }
    var showFoodSearch by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 拍照相关
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var hasCameraPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    // 创建图片文件并获取URI
    fun createImageUri(): Uri {
        val imageFile = File(context.cacheDir, "meal_photo_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri != null) {
            showCameraDialog = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            photoUri = createImageUri()
            photoUri?.let { takePictureLauncher.launch(it) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题
        TopAppBar(
            title = {
                Text(
                    text = "饮食记录",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            actions = {
                // 食物搜索按钮
                IconButton(onClick = { showFoodSearch = true }) {
                    Icon(Icons.Default.Search, contentDescription = "搜索食物")
                }
                // 拍照按钮
                IconButton(onClick = {
                    if (hasCameraPermission) {
                        photoUri = createImageUri()
                        photoUri?.let { takePictureLauncher.launch(it) }
                    } else {
                        permissionLauncher.launch(android.Manifest.permission.CAMERA)
                    }
                }) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "拍照")
                }
                // 手动添加按钮
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )

        // 今日统计
        TodayStatsCard(
            totalCarbs = uiState.todayCarbs,
            totalCalories = uiState.todayCalories
        )

        // 饮食记录列表
        // ★ v3.0.7 日期切换栏
        var showDatePicker by remember { mutableStateOf(false) }
        val sdf = java.text.SimpleDateFormat("MM/dd", java.util.Locale.getDefault())
        val selStr = sdf.format(java.util.Date(uiState.selectedDate))
        val todayStr = sdf.format(java.util.Date())
        val isToday = selStr == todayStr
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.shiftDate(-1) }) { Icon(Icons.Default.ChevronLeft, "前一天") }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showDatePicker = true }.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Icon(Icons.Default.DateRange, contentDescription = "选日期", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(if (isToday) "今天 $selStr" else selStr, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (!isToday) TextButton(onClick = { viewModel.goToToday() }) { Text("今天", fontSize = 12.sp) }
            IconButton(onClick = { viewModel.shiftDate(1) }, enabled = !isToday) { Icon(Icons.Default.ChevronRight, "后一天") }
        }
        if (showDatePicker) {
            val datePickerState = androidx.compose.material3.rememberDatePickerState(
                initialSelectedDateMillis = uiState.selectedDate
            )
            androidx.compose.material3.DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { viewModel.goToDate(it) }
                        showDatePicker = false
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
            ) { androidx.compose.material3.DatePicker(state = datePickerState) }
        }

        if (uiState.meals.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Restaurant,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = TextHint
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无饮食记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextHint
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击右上角 + 添加记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextHint
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.meals) { meal ->
                    MealCard(
                        meal = meal,
                        onDelete = { viewModel.deleteMeal(meal) }
                    )
                }
            }
        }
    }

    // 添加记录对话框
    if (showAddDialog) {
        AddMealDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { mealType, foodName, carbs, calories, gi, timestamp ->
                viewModel.addMeal(mealType, foodName, carbs, calories, gi, timestamp = timestamp)
                showAddDialog = false
            }
        )
    }

    // 拍照识别对话框
    if (showCameraDialog && photoUri != null) {
        CameraRecognitionDialog(
            photoUri = photoUri!!,
            onDismiss = { showCameraDialog = false },
            onConfirm = { mealType, foodName, carbs, calories, gi, protein, fat, fiber, portionGrams ->
                viewModel.addMeal(mealType, foodName, carbs, calories, gi, protein, fat, fiber, portionGrams)
                showCameraDialog = false
            }
        )
    }

    // 食物搜索对话框（大模型版本）
    if (showFoodSearch) {
        FoodSearchDialog(
            onDismiss = { showFoodSearch = false },
            onConfirm = { foodName, carbs, calories, gi, portionGrams ->
                val mealType = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
                    in 5..9 -> "breakfast"
                    in 11..13 -> "lunch"
                    in 17..19 -> "dinner"
                    else -> "snack"
                }
                viewModel.addMeal(mealType, foodName, carbs, calories, gi, portionGrams = portionGrams)
                showFoodSearch = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMealDialog(
    onDismiss: () -> Unit,
    onConfirm: (mealType: String, foodName: String, carbs: Double, calories: Double, gi: Double, timestamp: Long) -> Unit
) {
    var selectedMealType by remember { mutableStateOf("lunch") }
    var foodName by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var gi by remember { mutableStateOf("50") }
    var selectedTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var showTimePicker by remember { mutableStateOf(false) }

    val calendar = Calendar.getInstance().apply { timeInMillis = selectedTime }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)

    val mealTypes = listOf(
        "breakfast" to "早餐",
        "lunch" to "午餐",
        "dinner" to "晚餐",
        "snack" to "加餐"
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "添加饮食记录",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 餐型选择
                Text(
                    text = "餐型",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    mealTypes.forEach { (type, name) ->
                        FilterChip(
                            selected = selectedMealType == type,
                            onClick = { selectedMealType = type },
                            label = { Text(name) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 用餐时间
                Text(
                    text = "用餐时间",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                // ★ 跨日切换: 昨天/今天/明天 (事后补记更方便)
                val now = System.currentTimeMillis()
                val todayMidnight = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                fun shiftDate(delta: Int) {
                    val c = Calendar.getInstance().apply { timeInMillis = selectedTime }
                    c.add(Calendar.DAY_OF_MONTH, delta)
                    selectedTime = c.timeInMillis
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("昨天" to -1, "今天" to 0, "明天" to 1).forEach { (label, delta) ->
                        val target = Calendar.getInstance().apply {
                            timeInMillis = todayMidnight.timeInMillis
                            add(Calendar.DAY_OF_MONTH, delta)
                        }
                        val sc = Calendar.getInstance().apply { timeInMillis = selectedTime }
                        val selected = sc.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                                       sc.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
                        FilterChip(selected = selected, onClick = { shiftDate(delta) }, label = { Text(label, style = MaterialTheme.typography.bodySmall) })
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.AccessTime, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    // ★ v3.0.5 完整日期+时间
                    val dateTimeFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    Text(dateTimeFmt.format(selectedTime))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 食物名称
                OutlinedTextField(
                    value = foodName,
                    onValueChange = { foodName = it },
                    label = { Text("食物名称") },
                    placeholder = { Text("例如：白米饭") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 碳水和热量
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = carbs,
                        onValueChange = { carbs = it },
                        label = { Text("碳水(g)") },
                        placeholder = { Text("60") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = calories,
                        onValueChange = { calories = it },
                        label = { Text("热量(kcal)") },
                        placeholder = { Text("200") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // GI值
                OutlinedTextField(
                    value = gi,
                    onValueChange = { gi = it },
                    label = { Text("GI值 (0-100)") },
                    placeholder = { Text("50") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // GI说明
                Row {
                    Text(
                        text = "低GI:<55 ",
                        style = MaterialTheme.typography.bodySmall,
                        color = GiLow
                    )
                    Text(
                        text = "中GI:55-70 ",
                        style = MaterialTheme.typography.bodySmall,
                        color = GiMedium
                    )
                    Text(
                        text = "高GI:>70",
                        style = MaterialTheme.typography.bodySmall,
                        color = GiHigh
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val carbsValue = carbs.toDoubleOrNull() ?: 0.0
                            val caloriesValue = calories.toDoubleOrNull() ?: 0.0
                            val giValue = gi.toDoubleOrNull() ?: 50.0
                            if (foodName.isNotBlank()) {
                                onConfirm(selectedMealType, foodName, carbsValue, caloriesValue, giValue, selectedTime)
                            }
                        },
                        enabled = foodName.isNotBlank()
                    ) {
                        Text("添加")
                    }
                }
            }
        }
    }

    // ★ v3.0.5 完整日期时间选择器 (替换纯 TimePicker)
    if (showTimePicker) {
        DateTimePickerDialog(
            initialTime = selectedTime,
            title = "选择用餐时间",
            onDismiss = { showTimePicker = false },
            onConfirm = { picked ->
                selectedTime = picked
                showTimePicker = false
            }
        )
    }
}

@Composable
fun CameraRecognitionDialog(
    photoUri: android.net.Uri,
    onDismiss: () -> Unit,
    onConfirm: (mealType: String, foodName: String, carbs: Double, calories: Double, gi: Double,
                protein: Double, fat: Double, fiber: Double, portionGrams: Double) -> Unit
) {
    val context = LocalContext.current
    var foodName by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    // AI查询结果: 不再丢弃蛋白质/脂肪/纤维
    var aiProtein by remember { mutableStateOf(0.0) }
    var aiFat by remember { mutableStateOf(0.0) }
    var aiFiber by remember { mutableStateOf(0.0) }
    var aiPortionGrams by remember { mutableStateOf(100.0) }
    var gi by remember { mutableStateOf("50") }
    var selectedGiLevel by remember { mutableStateOf("medium") }
    var caloriesPerCarb by remember { mutableStateOf(4.0) }  // 每克碳水对应的热量
    var isRecognizing by remember { mutableStateOf(false) }
    var recognitionResult by remember { mutableStateOf<String?>(null) }
    var debugLog by remember { mutableStateOf("") }
    var selectedMealType by remember { mutableStateOf(
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..9 -> "breakfast"
            in 11..13 -> "lunch"
            in 17..19 -> "dinner"
            else -> "snack"
        }
    ) }

    // 自动识别食物
    LaunchedEffect(photoUri) {
        isRecognizing = true
        try {
            // 读取并压缩图片
            val inputStream = context.contentResolver.openInputStream(photoUri)
            val originalBytes = inputStream?.readBytes()
            inputStream?.close()

            if (originalBytes == null) {
                recognitionResult = "无法读取图片"
                isRecognizing = false
                return@LaunchedEffect
            }

            // 压缩图片（百度API限制4MB，保持高质量以确保识别准确）
            val compressedBytes = compressImage(originalBytes, maxSizeKB = 3800)

            // Base64编码（去掉编码头）
            val base64 = android.util.Base64.encodeToString(compressedBytes, android.util.Base64.NO_WRAP)

            // 详细日志（显示在界面上）
            debugLog = "原图: ${originalBytes.size / 1024}KB\n" +
                    "压缩后: ${compressedBytes.size / 1024}KB\n" +
                    "Base64长度: ${base64.length}\n"

            // 调用百度AI识别
            val service = com.tangdun.app.data.remote.FoodRecognitionService(context)
            if (!service.isConfigured()) {
                recognitionResult = "API未配置"
                debugLog += "错误: API未配置\n"
                isRecognizing = false
                return@LaunchedEffect
            }

            debugLog += "调用百度AI识别...\n"
            val results = service.recognize(base64)
            debugLog += "识别结果: ${results.size}个\n"

            if (results.isNotEmpty()) {
                val topResult = results.first()
                foodName = topResult.name
                recognitionResult = "识别成功: ${topResult.name} (${String.format("%.0f", topResult.confidence * 100)}%)"
                debugLog += "食物: ${topResult.name}\n"

                // 直接查询大模型获取营养信息
                debugLog += "查询大模型获取营养信息...\n"
                try {
                    val aiService = com.tangdun.app.data.remote.FoodNutritionAi(context)
                    val aiResult = aiService.getNutritionInfo(topResult.name)

                    if (aiResult != null) {
                        gi = aiResult.gi.toInt().toString()
                        carbs = aiResult.carbs.toInt().toString()
                        calories = aiResult.calories.toInt().toString()
                        // 保存AI查到的蛋白质/脂肪/纤维 (不再丢弃)
                        aiProtein = aiResult.protein
                        aiFat = aiResult.fat
                        aiFiber = aiResult.fiber
                        aiPortionGrams = aiResult.portionGrams
                        selectedGiLevel = aiResult.giLevel
                        if (aiResult.carbs > 0) {
                            caloriesPerCarb = aiResult.calories / aiResult.carbs
                        }
                        debugLog += "查询成功\n"
                        debugLog += "GI=${aiResult.gi}, 碳水=${aiResult.carbs}g, 热量=${aiResult.calories}kcal\n"
                    } else {
                        debugLog += "大模型查询失败，使用默认值\n"
                        gi = "65"
                        carbs = "30"
                        calories = "200"
                        selectedGiLevel = "medium"
                        caloriesPerCarb = 6.67
                    }
                } catch (e: Exception) {
                    debugLog += "大模型异常: ${e.message}，使用默认值\n"
                    gi = "65"
                    carbs = "30"
                    calories = "200"
                    selectedGiLevel = "medium"
                    caloriesPerCarb = 6.67
                }
            } else {
                // 百度AI没有识别到食物
                recognitionResult = "未识别到食物，请手动输入"
                debugLog += "百度AI未返回结果\n"
                debugLog += "可能原因: 图片不清晰或不是食物\n"
            }
        } catch (e: Exception) {
            recognitionResult = "识别失败: ${e.message}"
            android.util.Log.e("FoodRecognition", "识别失败", e)
        }
        isRecognizing = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "拍照记录",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 识别状态
                if (isRecognizing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在识别食物...", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                } else if (recognitionResult != null) {
                    Text(
                        text = recognitionResult!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (foodName.isNotEmpty()) AlertSuccess else TextSecondary
                    )
                }

                // 调试日志（可折叠）
                if (debugLog.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    var expanded by remember { mutableStateOf(false) }
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "隐藏调试信息" else "显示调试信息", style = MaterialTheme.typography.bodySmall)
                    }
                    if (expanded) {
                        Text(
                            text = debugLog,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextHint,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(androidx.compose.ui.graphics.Color(0xFFF5F5F5))
                                .padding(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 餐型选择
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("breakfast" to "早餐", "lunch" to "午餐", "dinner" to "晚餐", "snack" to "加餐").forEach { (type, name) ->
                        FilterChip(
                            selected = selectedMealType == type,
                            onClick = { selectedMealType = type },
                            label = { Text(name) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 食物名称
                OutlinedTextField(
                    value = foodName,
                    onValueChange = { foodName = it },
                    label = { Text("食物名称") },
                    placeholder = { Text("例如：白米饭") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 碳水和热量
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = carbs,
                        onValueChange = {
                            carbs = it
                            // 自动计算热量：使用该食物的实际热量/碳水比例
                            val carbsValue = it.toDoubleOrNull()
                            if (carbsValue != null) {
                                calories = (carbsValue * caloriesPerCarb).toInt().toString()
                            }
                        },
                        label = { Text("碳水(g)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = calories,
                        onValueChange = { calories = it },
                        label = { Text("热量(kcal)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // GI值和GI等级
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = gi,
                        onValueChange = { gi = it },
                        label = { Text("GI值") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(8.dp)
                    )

                    // GI等级标签
                    val giValue = gi.toDoubleOrNull() ?: 50.0
                    val giColor = when {
                        giValue < 55 -> GiLow
                        giValue <= 70 -> GiMedium
                        else -> GiHigh
                    }
                    val giText = when {
                        giValue < 55 -> "低GI"
                        giValue <= 70 -> "中GI"
                        else -> "高GI"
                    }
                    Text(
                        text = giText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = giColor,
                        modifier = Modifier
                            .background(giColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // GI说明
                Row {
                    Text("低GI:<55 ", style = MaterialTheme.typography.bodySmall, color = GiLow)
                    Text("中GI:55-70 ", style = MaterialTheme.typography.bodySmall, color = GiMedium)
                    Text("高GI:>70", style = MaterialTheme.typography.bodySmall, color = GiHigh)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val carbsValue = carbs.toDoubleOrNull() ?: 0.0
                            val caloriesValue = calories.toDoubleOrNull() ?: 0.0
                            val giValue = gi.toDoubleOrNull() ?: 50.0
                            if (foodName.isNotBlank()) {
                                onConfirm(selectedMealType, foodName, carbsValue, caloriesValue, giValue,
                                    aiProtein, aiFat, aiFiber, aiPortionGrams)
                            }
                        },
                        enabled = foodName.isNotBlank()
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
fun TodayStatsCard(totalCarbs: Double, totalCalories: Double) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "今日碳水",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
                Text(
                    text = String.format("%.0fg", totalCarbs),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "今日热量",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextHint
                )
                Text(
                    text = String.format("%.0fkcal", totalCalories),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun MealCard(
    meal: MealRecord,
    onDelete: () -> Unit = {}
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 餐型图标
            val icon = when (meal.mealType) {
                "breakfast" -> Icons.Default.FreeBreakfast
                "lunch" -> Icons.Default.LunchDining
                "dinner" -> Icons.Default.DinnerDining
                else -> Icons.Default.Restaurant
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = MealRecord.getMealTypeName(meal.mealType),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Text(
                        text = String.format("%.0fg碳水", meal.totalCarbs),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = String.format("%.0fkcal", meal.totalCalories),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = String.format("GI: %.0f", meal.avgGi),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            meal.avgGi < 55 -> GiLow
                            meal.avgGi <= 70 -> GiMedium
                            else -> GiHigh
                        }
                    )
                }
            }

            // 时间和删除
            Column(horizontalAlignment = Alignment.End) {
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(meal.timestamp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextHint
                )
                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp), tint = AlertCritical)
                }
            }
        }
    }

    // 删除确认
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条饮食记录吗？") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text("删除", color = AlertCritical)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}
