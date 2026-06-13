# 糖盾 - 完整功能Android App方案

## 一、目标

**与电脑端功能完全一致，所有功能在Android App内独立完成。**

## 二、功能对照表

| 功能模块 | 电脑端(后端) | Android端实现 |
|----------|-------------|---------------|
| **血糖记录** | API接口 | Health Connect自动获取 + CGM蓝牙 + 手动输入 |
| **饮食记录** | 拍照→百度AI识别 | 拍照→百度AI API识别（在线）/ 本地搜索（离线） |
| **运动记录** | API接口 | Health Connect自动同步 |
| **胰岛素记录** | API接口 | 手动输入 |
| **睡眠记录** | API接口 | Health Connect自动同步 |
| **血糖预测** | TCN模型服务端推理 | ONNX Runtime本地推理 |
| **Bergman模型** | Python实现 | Kotlin移植 |
| **What-if模拟** | Python实现 | Kotlin移植 |
| **预警系统** | 规则引擎 | 本地规则引擎 |
| **日报告** | Python计算 | Kotlin计算 |
| **周报告** | Python计算 | Kotlin计算 |
| **月报告** | Python计算 | Kotlin计算 |
| **数据导出** | CSV/PDF生成 | 本地CSV/PDF生成 |
| **食物营养库** | 205种内置 | 内置JSON文件 |
| **GI计算** | Python实现 | Kotlin移植 |
| **趋势分析** | Python实现 | Kotlin移植 |
| **TIR计算** | Python实现 | Kotlin移植 |
| **Clarke网格** | Python实现 | Kotlin移植 |

## 三、技术架构

```
┌─────────────────────────────────────────────────────────┐
│                      Android App                         │
├─────────────────────────────────────────────────────────┤
│  UI层: Jetpack Compose + Material Design 3              │
│  ├── 首页Dashboard                                      │
│  ├── 饮食管理（拍照识别）                                │
│  ├── 运动管理                                            │
│  ├── 血糖预测（TCN曲线）                                 │
│  ├── 报告中心                                            │
│  └── 设置                                                │
├─────────────────────────────────────────────────────────┤
│  业务层: ViewModel + UseCase                             │
│  ├── 预测引擎（TCN + Bergman + BMA融合）                │
│  ├── 预警引擎（6类规则）                                 │
│  ├── 报告引擎（TIR/GRI/趋势）                           │
│  └── 营养引擎（GI/碳水/热量）                           │
├─────────────────────────────────────────────────────────┤
│  数据层: Room数据库                                      │
│  ├── glucose_record（血糖）                              │
│  ├── meal_record + meal_item（饮食）                     │
│  ├── exercise_record（运动）                             │
│  ├── insulin_record（胰岛素）                            │
│  ├── alert_record（预警）                                │
│  └── food_nutrition（营养库205种）                       │
├─────────────────────────────────────────────────────────┤
│  AI层:                                                   │
│  ├── ONNX Runtime（TCN模型推理）                         │
│  ├── 百度AI API（食物识别，在线）                        │
│  └── ML Kit（可选，离线识别）                            │
├─────────────────────────────────────────────────────────┤
│  传感器层:                                               │
│  ├── Health Connect（心率/步数/运动/睡眠）               │
│  ├── 蓝牙BLE（CGM血糖仪）                               │
│  └── 相机（拍照识别食物）                                │
└─────────────────────────────────────────────────────────┘
```

## 四、需要移植的算法

### 4.1 CGM数据预处理

```kotlin
// 从后端 app/algorithms/preprocessing/cgm_preprocessor.py 移植
class CGMPreprocessor {
    // 卡尔曼滤波
    fun kalmanFilter(data: List<Double>): List<Double>
    
    // 异常值检测（MAD方法）
    fun detectOutliers(data: List<Double>): List<Boolean>
    
    // 缺失值插补
    fun interpolateMissing(data: List<Double?>): List<Double>
    
    // 完整预处理流水线
    fun preprocess(rawData: List<Double>): List<Double>
}
```

### 4.2 特征工程（15维）

```kotlin
// 从后端 training/train_curve_v2.py 移植
class FeatureExtractor {
    fun extract(glucoseHistory: DoubleArray, idx: Int): FloatArray {
        // 特征1-9: 血糖动态
        // 特征10-11: 胰岛素
        // 特征12-13: 碳水
        // 特征14: 心率
        // 特征15: 步数
    }
}
```

### 4.3 TCN模型推理

```kotlin
// 使用ONNX Runtime加载模型
class TCNPredictor(context: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(loadModelBytes(context))
    
    fun predict(features: FloatArray): FloatArray {
        // 输入: 15维特征
        // 输出: 4个曲线参数 [a, b, c, d]
    }
    
    fun generateCurve(params: FloatArray, currentValue: Double): DoubleArray {
        // G(t) = current * (1 + a*t³ + b*t² + c*t + d)
        // 返回25个点（0-120分钟）
    }
}
```

### 4.4 Bergman最小模型

```kotlin
// 从后端 app/algorithms/bergman/ 移植
class BergmanModel {
    // ODE求解器
    fun solve(state: DoubleArray, timePoints: DoubleArray,
              meals: List<Meal>, exercises: List<Exercise>): DoubleArray
    
    // 参数估计（贝叶斯MAP）
    fun estimateParameters(history: DoubleArray): BergmanParameters
}
```

### 4.5 预警系统

```kotlin
// 从后端 app/services/alert_service.py 移植
class AlertEngine {
    // 6类预警规则
    fun checkLowGlucose(value: Double): Alert?      // 低血糖
    fun checkHighGlucose(value: Double): Alert?     // 高血糖
    fun checkRapidRise(trend: Double): Alert?       // 快速上升
    fun checkRapidFall(trend: Double): Alert?       // 快速下降
    fun checkPredictedLow(predicted: Double): Alert? // 预测低血糖
    fun checkPredictedHigh(predicted: Double): Alert? // 预测高血糖
}
```

### 4.6 报告计算

```kotlin
// 从后端 app/services/report_service.py 移植
class ReportCalculator {
    // TIR（目标范围内时间）
    fun calculateTIR(records: List<GlucoseRecord>): Double
    
    // GRI（血糖风险指数）
    fun calculateGRI(records: List<GlucoseRecord>): Double
    
    // 血糖变异性
    fun calculateVariability(records: List<GlucoseRecord>): Double
    
    // HbA1c估算
    fun estimateHbA1c(avgGlucose: Double): Double
    
    // 日报告
    fun generateDailyReport(date: LocalDate): DailyReport
    
    // 周报告
    fun generateWeeklyReport(startDate: LocalDate): WeeklyReport
    
    // 月报告
    fun generateMonthlyReport(year: Int, month: Int): MonthlyReport
}
```

### 4.7 营养计算

```kotlin
// 从后端 app/services/meal_service.py 移植
class NutritionCalculator {
    // 加权GI计算
    fun calculateWeightedGI(items: List<MealItem>): Double
    
    // 碳水总量
    fun calculateTotalCarbs(items: List<MealItem>): Double
    
    // 热量计算
    fun calculateCalories(items: List<MealItem>): Double
}
```

## 五、需要集成的外部服务

### 5.1 百度AI食物识别（在线）

```kotlin
class BaiduFoodRecognition {
    private val appId = "7825592"
    private val apiKey = "从.env读取"
    private val secretKey = "从.env读取"
    
    suspend fun recognize(imageBase64: String): List<FoodResult> {
        // 调用百度菜品识别API
        // POST https://aip.baidubce.com/rest/2.0/image-classify/v2/dish
    }
}
```

### 5.2 Health Connect数据同步

```kotlin
class HealthConnectSync {
    // 读取心率
    suspend fun readHeartRate(): List<HeartRateRecord>
    
    // 读取步数
    suspend fun readSteps(): List<StepsRecord>
    
    // 读取运动
    suspend fun readExercise(): List<ExerciseSessionRecord>
    
    // 读取睡眠
    suspend fun readSleep(): List<SleepSessionRecord>
}
```

### 5.3 CGM蓝牙连接（可选）

```kotlin
class CGMBluetoothManager {
    // 扫描CGM设备
    suspend fun scanDevices(): List<BluetoothDevice>
    
    // 连接设备
    suspend fun connect(device: BluetoothDevice)
    
    // 接收血糖数据
    fun glucoseFlow(): Flow<Double>
}
```

## 六、数据库设计（完整）

### 6.1 Room Entity

```kotlin
@Entity(tableName = "glucose_record")
data class GlucoseRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,           // 毫秒时间戳
    val value: Double,             // mmol/L
    val trend: String? = null,     // rising/falling/stable
    val source: String = "manual", // manual/cgm/finger
    val sensorId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "meal_record")
data class MealRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val mealType: String? = null,  // breakfast/lunch/dinner/snack
    val imagePath: String? = null,
    val totalCarbs: Double = 0.0,
    val totalCalories: Double = 0.0,
    val totalProtein: Double = 0.0,
    val totalFat: Double = 0.0,
    val totalFiber: Double = 0.0,
    val avgGi: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "meal_item",
    foreignKeys = [ForeignKey(entity = MealRecord::class,
        parentColumns = ["id"], childColumns = ["mealId"],
        onDelete = ForeignKey.CASCADE)])
data class MealItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mealId: Long,
    val foodName: String,
    val portionGrams: Double = 100.0,
    val carbs: Double = 0.0,
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double = 0.0,
    val gi: Double = 0.0
)

@Entity(tableName = "exercise_record")
data class ExerciseRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val exerciseType: String = "walking",
    val durationMin: Int? = null,
    val avgHeartRate: Double? = null,
    val maxHeartRate: Double? = null,
    val steps: Int? = null,
    val caloriesBurned: Double? = null,
    val source: String = "manual",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "insulin_record")
data class InsulinRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val insulinType: String,       // rapid/long/mixed
    val doseUnits: Double,
    val injectionSite: String? = null,
    val mealId: Long? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "alert_record")
data class AlertRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alertType: String,         // low_glucose/high_glucose/rapid_change
    val severity: String = "warning",
    val glucoseValue: Double? = null,
    val predictedValue: Double? = null,
    val message: String? = null,
    val isRead: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "food_nutrition")
data class FoodNutrition(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val carbs: Double,
    val calories: Double,
    val protein: Double,
    val fat: Double,
    val fiber: Double,
    val gi: Double,
    val giLevel: String            // low/medium/high
)
```

### 6.2 DAO接口

```kotlin
@Dao
interface GlucoseDao {
    @Insert
    suspend fun insert(record: GlucoseRecord): Long
    
    @Query("SELECT * FROM glucose_record ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 288): List<GlucoseRecord>
    
    @Query("SELECT * FROM glucose_record WHERE timestamp BETWEEN :start AND :end")
    suspend fun getByTimeRange(start: Long, end: Long): List<GlucoseRecord>
    
    @Query("SELECT * FROM glucose_record ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): GlucoseRecord?
}

@Dao
interface MealDao {
    @Insert
    suspend fun insert(record: MealRecord): Long
    
    @Insert
    suspend fun insertItem(item: MealItem): Long
    
    @Query("SELECT * FROM meal_record ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<MealRecord>
    
    @Query("SELECT * FROM meal_item WHERE mealId = :mealId")
    suspend fun getItemsByMealId(mealId: Long): List<MealItem>
}
```

## 七、内置数据文件

### 7.1 营养数据库

```
assets/
├── food_nutrition.json    # 205种食物营养数据
├── model_curve_v2.onnx    # TCN预测模型
└── bergman_params.json    # Bergman模型默认参数
```

### 7.2 food_nutrition.json 格式

```json
[
  {
    "id": "rice_white",
    "name": "白米饭",
    "category": "主食",
    "carbs": 25.6,
    "calories": 116,
    "protein": 2.6,
    "fat": 0.3,
    "fiber": 0.3,
    "gi": 83,
    "gi_level": "high"
  },
  // ... 共205种
]
```

## 八、页面设计（与电脑端一致）

### 8.1 首页Dashboard

```
┌─────────────────────────────────┐
│  糖盾                    [刷新] │
├─────────────────────────────────┤
│  ⚠️ 预警信息                    │
├─────────────────────────────────┤
│       当前血糖                  │
│         7.2                     │
│       mmol/L ↑                  │
│    30分钟变化: +0.3             │
├─────────────────────────────────┤
│  今日血糖曲线                   │
│  [═══════════════════════════] │
│  2:00  6:00  10:00  14:00  ... │
├─────────────────────────────────┤
│  [记录饮食]    [查看预测]       │
└─────────────────────────────────┘
```

### 8.2 饮食记录

```
┌─────────────────────────────────┐
│  饮食记录                       │
├─────────────────────────────────┤
│  早餐  08:00                    │
│  白米饭+红烧肉                  │
│  45g碳水  320kcal  GI:55        │
├─────────────────────────────────┤
│  午餐  12:00                    │
│  面条+青菜                      │
│  60g碳水  400kcal  GI:65        │
├─────────────────────────────────┤
│  [+] 添加记录                   │
└─────────────────────────────────┘
```

### 8.3 血糖预测

```
┌─────────────────────────────────┐
│  血糖预测                       │
├─────────────────────────────────┤
│  ✓ 正常          当前 7.2      │
├─────────────────────────────────┤
│  5分钟  15分钟  30分钟  60分钟  │
│  7.3    7.5     7.8     8.2    │
├─────────────────────────────────┤
│  预测曲线（0-120分钟）          │
│  [═══════════════════════════] │
│  0min    30min    60min   120  │
├─────────────────────────────────┤
│  模型: TCN v2                   │
│  MAE: 0.552  Clarke A: 92.4%   │
└─────────────────────────────────┘
```

## 九、开发计划

### 阶段1: 基础框架（3天）

| 任务 | 说明 |
|------|------|
| 项目初始化 | Jetpack Compose + Hilt + Room |
| 数据库设计 | 8个表 + DAO |
| 导航框架 | 底部导航5个Tab |
| 主题样式 | Material Design 3 |

### 阶段2: 数据层（3天）

| 任务 | 说明 |
|------|------|
| 血糖模块 | 手动输入 + 列表显示 |
| 饮食模块 | 手动输入 + 营养查询 |
| 运动模块 | Health Connect同步 |
| 胰岛素模块 | 手动输入 |
| 内置数据 | 导入205种食物营养库 |

### 阶段3: 算法移植（3天）

| 任务 | 说明 |
|------|------|
| CGM预处理 | 卡尔曼滤波 + 异常检测 |
| 特征提取 | 15维特征计算 |
| TCN推理 | ONNX Runtime集成 |
| Bergman模型 | ODE求解器 |
| 预警系统 | 6类规则引擎 |
| 报告计算 | TIR/GRI/趋势 |

### 阶段4: AI功能（2天）

| 任务 | 说明 |
|------|------|
| 百度AI集成 | 食物拍照识别 |
| 模型转换 | PyTorch → ONNX |
| 预测曲线 | 0-120分钟显示 |
| What-if模拟 | 进食预测 |

### 阶段5: 高级功能（2天）

| 任务 | 说明 |
|------|------|
| 日/周/月报告 | 完整报告生成 |
| 数据导出 | CSV/PDF |
| 预警详情 | 处理建议 |
| UI美化 | 图表动画 |

### 阶段6: 测试完善（2天）

| 任务 | 说明 |
|------|------|
| 功能测试 | 全流程测试 |
| 性能优化 | 内存/电量优化 |
| 打包发布 | 签名APK |

**总计: 约15天**

## 十、依赖清单

```kotlin
dependencies {
    // Jetpack Compose
    implementation("androidx.compose.ui:ui:1.5.4")
    implementation("androidx.compose.material3:material3:1.1.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.4")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
    
    // Room数据库
    implementation("androidx.room:room-runtime:2.6.0")
    implementation("androidx.room:room-ktx:2.6.0")
    ksp("androidx.room:room-compiler:2.6.0")
    
    // ViewModel + Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")
    
    // 图表
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    // 或使用Vico (Compose原生图表)
    implementation("com.patrykandpatrick.vico:compose-m3:1.12.0")
    
    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.0")
    
    // Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0-alpha06")
    
    // 网络（百度AI API）
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Hilt依赖注入
    implementation("com.google.dagger:hilt-android:2.48")
    ksp("com.google.dagger:hilt-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    
    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Gson
    implementation("com.google.code.gson:gson:2.10.1")
    
    // 图片加载
    implementation("io.coil-kt:coil-compose:2.5.0")
    
    // 权限
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")
}
```

## 十一、项目结构

```
app/src/main/java/com/tangdun/app/
├── TangDunApp.kt                    # Application
├── MainActivity.kt                  # 主Activity
│
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt           # Room数据库
│   │   ├── dao/                     # DAO接口
│   │   ├── entity/                  # Entity定义
│   │   └── converter/               # 类型转换器
│   ├── remote/
│   │   └── BaiduAiApi.kt            # 百度AI API
│   └── repository/                  # 数据仓库
│
├── domain/
│   ├── model/                       # 业务模型
│   ├── usecase/                     # 用例
│   │   ├── PredictGlucoseUseCase.kt
│   │   ├── CheckAlertsUseCase.kt
│   │   ├── GenerateReportUseCase.kt
│   │   └── ...
│   └── algorithm/                   # 算法实现
│       ├── CGMPreprocessor.kt
│       ├── FeatureExtractor.kt
│       ├── TCNPredictor.kt
│       ├── BergmanModel.kt
│       ├── AlertEngine.kt
│       ├── ReportCalculator.kt
│       └── NutritionCalculator.kt
│
├── ui/
│   ├── theme/                       # 主题
│   ├── navigation/                  # 导航
│   ├── home/                        # 首页
│   ├── meal/                        # 饮食
│   ├── exercise/                    # 运动
│   ├── prediction/                  # 预测
│   ├── report/                      # 报告
│   └── settings/                    # 设置
│
├── di/                              # Hilt依赖注入
│   ├── AppModule.kt
│   ├── DatabaseModule.kt
│   └── NetworkModule.kt
│
└── util/                            # 工具类
    ├── Constants.kt
    ├── Extensions.kt
    └── FileUtils.kt

app/src/main/assets/
├── food_nutrition.json              # 营养数据库
├── model_curve_v2.onnx              # TCN模型
└── bergman_params.json              # Bergman参数

app/src/main/res/
├── drawable/                        # 图标
├── values/                          # 颜色/字符串/主题
└── xml/                             # 配置
```

## 十二、与电脑端完全一致的功能清单

| 序号 | 功能 | 电脑端 | Android端 | 状态 |
|------|------|--------|-----------|------|
| 1 | 血糖记录 | API | Health Connect + 手动 | 待开发 |
| 2 | 血糖趋势 | API | 本地计算 | 待开发 |
| 3 | 血糖统计 | API | 本地计算 | 待开发 |
| 4 | 饮食记录 | API | 拍照 + 手动 | 待开发 |
| 5 | 食物识别 | 百度AI | 百度AI API | 待开发 |
| 6 | 营养查询 | 内置库 | 内置JSON | 待开发 |
| 7 | GI计算 | Python | Kotlin移植 | 待开发 |
| 8 | What-if模拟 | Python | Kotlin移植 | 待开发 |
| 9 | 运动记录 | API | Health Connect | 待开发 |
| 10 | 运动处方 | API | 本地计算 | 待开发 |
| 11 | 胰岛素记录 | API | 手动输入 | 待开发 |
| 12 | IOB计算 | Python | Kotlin移植 | 待开发 |
| 13 | TCN预测 | Python | ONNX推理 | 待开发 |
| 14 | Bergman预测 | Python | Kotlin移植 | 待开发 |
| 15 | 预警系统 | Python | Kotlin移植 | 待开发 |
| 16 | 日报告 | Python | Kotlin移植 | 待开发 |
| 17 | 周报告 | Python | Kotlin移植 | 待开发 |
| 18 | 月报告 | Python | Kotlin移植 | 待开发 |
| 19 | CSV导出 | Python | Kotlin实现 | 待开发 |
| 20 | PDF导出 | Python | Kotlin实现 | 待开发 |

**共20个功能模块，全部需要移植。**

## 十三、编译与运行

```bash
# 编译APK
cd D:\tangdun\android
export JAVA_HOME=/c/Users/21365/android-tools/jdk-17.0.19+10
export ANDROID_HOME=/c/Users/21365/android-tools/android-sdk
/c/Users/21365/android-tools/gradle-8.2/bin/gradle.bat assembleDebug

# 输出
# android/app/build/outputs/apk/debug/app-debug.apk
```

## 十四、总结

| 项目 | 说明 |
|------|------|
| **功能** | 与电脑端完全一致（20个模块） |
| **架构** | Jetpack Compose + Room + ONNX |
| **离线** | 完全离线可用（食物识别需联网） |
| **开发周期** | 约15天 |
| **输出** | 独立APK，无需后端 |

**完全移植，一个App搞定所有功能。**
