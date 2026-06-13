# 糖盾 - 独立Android App方案

## 一、目标

**一个App完成所有操作，无需后端服务器，完全离线运行。**

## 二、架构设计

```
┌─────────────────────────────────────────────┐
│                 Android App                  │
├─────────────────────────────────────────────┤
│  UI层: Kotlin + Material Design 3           │
├─────────────────────────────────────────────┤
│  业务层: ViewModel + Coroutines              │
├─────────────────────────────────────────────┤
│  数据层: Room本地数据库                      │
├─────────────────────────────────────────────┤
│  AI层: ONNX Runtime本地推理                 │
├─────────────────────────────────────────────┤
│  传感器层: Health Connect + CGM蓝牙         │
└─────────────────────────────────────────────┘
```

## 三、核心功能

| 功能 | 说明 | 数据存储 |
|------|------|----------|
| **血糖记录** | 手动输入/CGM蓝牙读取 | Room数据库 |
| **饮食记录** | 拍照识别(本地模型)+手动输入 | Room数据库 |
| **运动记录** | Health Connect自动同步 | Room数据库 |
| **血糖预测** | TCN模型本地推理(ONNX) | 内存 |
| **趋势分析** | 本地计算TIR/均值/标准差 | Room数据库 |
| **预警系统** | 本地规则引擎 | Room数据库 |
| **报告生成** | 日/周/月报告 | Room数据库 |
| **数据导出** | CSV/PDF导出到本地存储 | 文件系统 |

## 四、技术选型

| 组件 | 技术 | 说明 |
|------|------|------|
| **UI框架** | Kotlin + Jetpack Compose | 现代声明式UI |
| **数据库** | Room | SQLite封装，类型安全 |
| **图表** | MPAndroidChart | 成熟的图表库 |
| **AI推理** | ONNX Runtime | 本地运行TCN模型 |
| **健康数据** | Health Connect API | 读取心率/步数/运动/睡眠 |
| **图片识别** | ML Kit | 本地食物识别 |
| **依赖注入** | Hilt | 简化依赖管理 |
| **异步** | Coroutines + Flow | 响应式编程 |

## 五、数据库设计

### 5.1 表结构

```sql
-- 用户表
CREATE TABLE user (
    id INTEGER PRIMARY KEY,
    name TEXT,
    diabetes_type INTEGER,  -- 1型/2型
    target_low REAL DEFAULT 3.9,
    target_high REAL DEFAULT 10.0,
    created_at TEXT
);

-- 血糖记录表
CREATE TABLE glucose_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp TEXT NOT NULL,
    value REAL NOT NULL,
    trend TEXT,
    source TEXT DEFAULT 'manual',  -- manual/cgm/finger
    sensor_id TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

-- 饮食记录表
CREATE TABLE meal_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp TEXT NOT NULL,
    meal_type TEXT,
    image_path TEXT,
    total_carbs REAL DEFAULT 0,
    total_calories REAL DEFAULT 0,
    total_protein REAL DEFAULT 0,
    total_fat REAL DEFAULT 0,
    total_fiber REAL DEFAULT 0,
    avg_gi REAL DEFAULT 0,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

-- 饮食明细表
CREATE TABLE meal_item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    meal_id INTEGER REFERENCES meal_record(id),
    food_name TEXT NOT NULL,
    portion_grams REAL,
    carbs REAL DEFAULT 0,
    calories REAL DEFAULT 0,
    protein REAL DEFAULT 0,
    fat REAL DEFAULT 0,
    fiber REAL DEFAULT 0,
    gi REAL DEFAULT 0
);

-- 运动记录表
CREATE TABLE exercise_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    start_time TEXT NOT NULL,
    end_time TEXT,
    exercise_type TEXT,
    duration_min INTEGER,
    avg_heart_rate REAL,
    max_heart_rate REAL,
    steps INTEGER,
    calories_burned REAL,
    source TEXT DEFAULT 'manual',
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

-- 胰岛素记录表
CREATE TABLE insulin_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp TEXT NOT NULL,
    insulin_type TEXT,
    dose_units REAL NOT NULL,
    injection_site TEXT,
    meal_id INTEGER,
    notes TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

-- 预警记录表
CREATE TABLE alert_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    alert_type TEXT NOT NULL,
    severity TEXT DEFAULT 'warning',
    glucose_value REAL,
    predicted_value REAL,
    message TEXT,
    is_read INTEGER DEFAULT 0,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

-- 营养数据库（内置205种食物）
CREATE TABLE food_nutrition (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    category TEXT,
    carbs REAL,
    calories REAL,
    protein REAL,
    fat REAL,
    fiber REAL,
    gi REAL,
    gi_level TEXT
);
```

### 5.2 索引

```sql
CREATE INDEX idx_glucose_timestamp ON glucose_record(timestamp);
CREATE INDEX idx_meal_timestamp ON meal_record(timestamp);
CREATE INDEX idx_exercise_start ON exercise_record(start_time);
CREATE INDEX idx_insulin_timestamp ON insulin_record(timestamp);
CREATE INDEX idx_alert_created ON alert_record(created_at);
```

## 六、AI模型集成

### 6.1 模型转换

```
PyTorch模型(.pt) → ONNX格式(.onnx) → 打包到assets/
```

### 6.2 本地推理流程

```
1. 读取最近24小时血糖数据
2. 提取15维特征（与训练一致）
3. ONNX Runtime推理
4. 输出4个曲线参数
5. 生成0-120分钟预测曲线
```

### 6.3 模型文件

- `model_curve_v2.onnx` - TCN预测模型（~600KB）
- `food_recognition.tflite` - 食物识别模型（~5MB，可选）

## 七、页面设计

### 7.1 页面结构

```
App
├── 首页(Dashboard)
│   ├── 当前血糖值+趋势
│   ├── 今日血糖曲线图
│   ├── 预警横幅
│   └── 快捷操作按钮
├── 饮食
│   ├── 饮食记录列表
│   ├── 添加记录(拍照/手动)
│   ├── 食物搜索
│   └── What-if模拟
├── 运动
│   ├── 运动记录列表
│   ├── 运动统计
│   └── 运动处方
├── 预测
│   ├── 风险等级
│   ├── 关键时间点预测
│   ├── 0-120分钟曲线图
│   └── 模型信息
├── 报告
│   ├── 日报告
│   ├── 周报告
│   └── 月报告
└── 设置
    ├── 个人信息
    ├── 目标范围设置
    ├── 数据导出
    └── 关于
```

### 7.2 配色方案

- **主色**: #007A8C（青色）
- **辅色**: #1A3C5E（深蓝）
- **成功**: #4CAF50（绿色）
- **警告**: #FF9800（橙色）
- **错误**: #F44336（红色）

## 八、开发计划

### 阶段1: 基础框架（2天）

- [x] 项目结构搭建
- [ ] Room数据库 + DAO
- [ ] 基础UI框架
- [ ] 底部导航

### 阶段2: 核心功能（3天）

- [ ] 血糖记录（手动+列表）
- [ ] 饮食记录（手动输入）
- [ ] 运动记录（Health Connect同步）
- [ ] 基础图表显示

### 阶段3: AI功能（2天）

- [ ] TCN模型ONNX转换
- [ ] 本地推理集成
- [ ] 预测曲线显示
- [ ] 预警系统

### 阶段4: 高级功能（2天）

- [ ] 报告生成
- [ ] 数据导出
- [ ] 食物识别（可选）
- [ ] What-if模拟

### 阶段5: 优化完善（1天）

- [ ] UI美化
- [ ] 性能优化
- [ ] 测试修复
- [ ] 打包发布

**总计: 约10天**

## 九、依赖清单

```kotlin
// build.gradle.kts
dependencies {
    // Jetpack Compose
    implementation("androidx.compose.ui:ui:1.5.4")
    implementation("androidx.compose.material3:material3:1.1.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.4")
    implementation("androidx.activity:activity-compose:1.8.1")
    
    // Room数据库
    implementation("androidx.room:room-runtime:2.6.0")
    implementation("androidx.room:room-ktx:2.6.0")
    kapt("androidx.room:room-compiler:2.6.0")
    
    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")
    
    // 图表
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    
    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.0")
    
    // Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0-alpha06")
    
    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Hilt依赖注入
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
    
    // Gson
    implementation("com.google.code.gson:gson:2.10.1")
}
```

## 十、与现有后端的关系

**完全独立，无需后端。**

现有后端可作为参考：
- API接口设计 → 本地DAO设计
- 数据模型 → Room Entity
- 算法逻辑 → 直接移植到Kotlin
- 营养数据库 → 内置到App

## 十一、优势

| 优势 | 说明 |
|------|------|
| **完全离线** | 无需网络，随时随地使用 |
| **数据隐私** | 所有数据存储在本地 |
| **响应速度** | 本地推理，毫秒级响应 |
| **无服务器成本** | 不需要维护后端 |
| **独立运行** | 一个APK完成所有功能 |

## 十二、限制

| 限制 | 解决方案 |
|------|----------|
| 食物识别准确度 | 内置营养数据库+手动搜索 |
| 模型更新 | 发布新版本APK |
| 多设备同步 | 暂不支持，可导出CSV |
| 数据备份 | 导出到本地存储 |
