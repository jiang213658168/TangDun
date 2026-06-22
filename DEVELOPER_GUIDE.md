# 糖盾 (TangDun) 开发者指南

> 写于 2026-06-19，更新于 2026-06-22 (v3.0.21)。给接手的开发者。本文档目标是：任何人只看这一份文档就能理解全部代码、继续开发。

---

## 目录

1. [项目概览](#1-项目概览)
2. [环境搭建](#2-环境搭建)
3. [架构总览](#3-架构总览)
4. [核心算法详解](#4-核心算法详解)
5. [代码走读](#5-代码走读)
6. [预测全链条](#6-预测全链条)
7. [自学习全链条](#7-自学习全链条)
8. [数据层](#8-数据层)
9. [UI 层](#9-ui-层)
10. [调试与排障](#10-调试与排障)
11. [架构决策记录](#11-架构决策记录)
12. [性能基准](#12-性能基准)
13. [已知局限与优化路线](#13-已知局限与优化路线)
14. [如何新增功能](#14-如何新增功能)
15. [SharedPreferences 速查](#15-sharedpreferences-速查)
16. [术语表](#16-术语表)

---

## 1. 项目概览

### 1.1 这是什么

糖盾是一个跑在 Android 手机上的糖尿病血糖预测与管理系统。它从 40+ 个品牌的 CGM（持续血糖监测）App 的通知栏自动采集血糖数据，然后用三层混合模型预测未来 180 分钟（3 小时）的血糖走势。

### 1.2 核心能力

| 功能 | 实现 | 文件 |
|-----|------|------|
| CGM 实时采集 | 通知栏监听 40+ 品牌 | `CGMNotificationListener.kt` |
| 血糖预测 (180min) | TCN v3 + DallaMan + BMA 混合 | `PredictionViewModel.kt` |
| 在线自学习 | EDOC即时 + 统计 + 增量残差 (4层) | `SelfLearningManager.kt` |
| Excel 数据导入 | 轻量 xlsx 解析 (欧态/多品牌) | `XlsxImporter.kt` |
| AI 对话记录 | DeepSeek API 兼容 (65 tools) | `AgentToolExecutor.kt` |
| 多级预警 | 预测性 + 即时 + SOS自动拨号 | `AlertEngine.kt` |
| 前台保活 | ForegroundService + RESTART_STICKY | `GlucoseForegroundService.kt` |

### 1.3 技术栈

```
语言:      Kotlin 1.9.20 + Java 17
UI:        Jetpack Compose 1.5.4 + Material Design 3
数据库:    Room 2.6.1 (15 表)
推理引擎:  ONNX Runtime 1.16.0
依赖注入:  Hilt 2.48
网络:      Retrofit 2.9.0 + OkHttp 4.12.0
构建:      Gradle 8.2 + AGP 8.1.0
最低 SDK:  API 26 (Android 8.0)
目标 SDK:  API 34 (Android 14)
版本号:    v3.0.20 (versionCode 320) — 构建时动态注入 BuildConfig
```

### 1.4 仓库

```
GitHub: https://github.com/jiang213658168/TangDun
本地:   D:\tangdun\
```

---

## 2. 环境搭建

### 2.1 必需软件

| 软件 | 版本 | 用途 |
|-----|------|------|
| JDK | 17 | 编译 |
| Android SDK | 34 (platform) + 34.0.0 (build-tools) | 编译+打包 |
| Gradle | 8.2 | 自动下载（gradlew） |
| Android Studio | Hedgehog 2023.1+ | 推荐 IDE |

### 2.2 环境变量设置

```bash
# Windows (Git Bash)
export JAVA_HOME="C:\Users\你的用户名\android-tools\jdk-17.0.19+10"
export ANDROID_HOME="C:\Users\你的用户名\android-tools\android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

### 2.3 构建命令

```bash
# 编译检查（不打包）
cd D:\tangdun\android
./gradlew compileDebugKotlin

# 打包 Debug APK
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk

# 打包 Release APK（需要签名配置）
./gradlew assembleRelease

# 安装到已连接设备
adb install app/build/outputs/apk/debug/app-debug.apk

# 查看日志
adb logcat | grep -E "EDOC|SelfLearn|PredVM|XlsxImporter"
```

### 2.4 目录结构

```
D:\tangdun\
├── DEVELOPER_GUIDE.md          # ★ 本文档
├── README.md                   # GitHub 首页
├── docs/                       # 文档
│   ├── graduate_thesis.md      # 研究生论文 (中文)
│   ├── paper_tangdun.md        # 学术论文
│   ├── user_guide.md           # 用户手册
│   ├── ad_copy.md              # 营销文案
│   └── tangjian_design.md      # 糖剑远程监护设计
└── android/                    # ★ Android 工程
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── gradle.properties
    └── app/
        ├── build.gradle.kts
        └── src/main/
            ├── AndroidManifest.xml
            ├── assets/              # TCN ONNX 模型
            ├── java/com/tangdun/app/
            │   ├── TangDunApp.kt
            │   ├── MainActivity.kt
            │   ├── domain/algorithm/  # ★ 20 个算法类
            │   ├── ui/               # Compose UI + ViewModels
            │   ├── service/          # 后台服务
            │   ├── data/local/       # Room DB
            │   ├── di/               # Hilt DI
            │   └── widget/           # 自定义 View
            └── res/                  # 资源文件
```

---

## 3. 架构总览

### 3.1 五层架构

```
┌──────────────────────────────────────────────────────────────┐
│ UI Layer (Jetpack Compose)                                    │
│ HomeScreen · PredictionScreen · SettingsScreen · RecordScreen │
│ ChatScreen · ReportScreen                                     │
├──────────────────────────────────────────────────────────────┤
│ ViewModel Layer (6 ViewModels + Hilt DI)                      │
│ HomeVM · PredictionVM · RecordVM · SettingsVM · ChatVM · ReportVM │
├──────────────────────────────────────────────────────────────┤
│ Domain Layer (20 Algorithm Classes)                           │
│ ┌─ 预测链 ────────────────────────────────────────────────┐  │
│ │ FeatureExtractor → TCN(ONNX) → ┐                        │  │
│ │ DallaMan(RK4,7室,EDOC修正)  → ├→ BMA → 增量残差 → 输出 │  │
│ │ OnlineLearner(个性化)        → ┘                        │  │
│ ├─ 自学习链 ────────────────────────────────────────────┤  │
│ │ L0-EDOC(每读数,SignSGD+RLS)                             │  │
│ │ L1-OnlineLearner(每读数,EWMA+Kalman)                     │  │
│ │ L2-IncrementalLearner(累积50条,SGD+momentum,304参数)              │  │
│ │ L3-数据完整度(累积100条,诚实查mealDao/insulinDao)                      │  │
│ ├─ 辅助 ────────────────────────────────────────────────┤  │
│ │ SmartAdvisor · AlertEngine · CGMCalibrator · TrendCalc  │  │
│ │ InsulinCalculator · AutoParamEstimator · ReportGenerator│  │
│ └────────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│ Data Layer (Room, 15 tables + DAOs)                           │
│ glucose_record · meal_record · insulin_record · exercise_record│
│ user_profile · prediction_cache · alert_config · ...          │
├──────────────────────────────────────────────────────────────┤
│ Collection Layer (Android Services)                           │
│ CGMNotificationListener · GlucoseForegroundService            │
│ XlsxImporter · DirectGlucoseBroadcastReceiver                 │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 数据流（完整路径）

```
┌─ 数据进入 ──────────────────────────────────────────────────┐
│                                                              │
│ CGM传感器 → 品牌App通知栏                                    │
│   → CGMNotificationListener.onNotificationPosted()           │
│     → tryExtract(notificationText)                           │
│       → 5种格式识别:                                         │
│         1. "5.6 mmol/L"    小数格式                          │
│         2. "LOW" / "HIGH"  极限标记                          │
│         3. "126 mg/dL"     整数mg/dL→÷18                     │
│         4. "血糖 6"        整数mmol/L(国产App)               │
│         5. 混合文本                                           │
│       → unitDetection → 范围校验(2.0-35.0)                  │
│         → GlucoseRecord(timestamp, value, source, trend)     │
│           → glucoseDao.insert() → Room DB                    │
│                                                              │
│ 备选通道: XlsxImporter (导入欧态xlsx)                         │
│         DirectGlucoseBroadcastReceiver (其他App广播)          │
└──────────────────────────────────────────────────────────────┘
                            ↓
┌─ 后台处理 (SelfLearningManager, 每条新读数) ─────────────────┐
│                                                              │
│ glucoseDao.getLatestFlow().collect { reading ->              │
│                                                              │
│   // L0: EDOC 即时纠错 (~30ms)                               │
│   val baseParams = ensureBaseParams()                        │
│   val context = SnapContext(                                 │
│     currentGlucose, glucoseROC, recentCarbs,                 │
│     minutesSinceMeal, iob, minutesSinceBolus, hourOfDay)     │
│   edocCorrector.onNewReading(glucose, quality,               │
│                               baseParams, context)           │
│                                                              │
│   // L1: 统计学习 (~100ms)                                   │
│   onlineLearner.learn(dao)                                   │
│                                                              │
│   // L2: 增量残差学习 (每12条, ~500ms)                        │
│   if (pendingNewReadings >= 50)                                │
│     incrementalLearner.periodicLearn(dao)                    │
│ }                                                            │
└──────────────────────────────────────────────────────────────┘
                            ↓
┌─ 预测生成 (PredictionViewModel, 新读数/饮食/胰岛素/设置变化) ─┐
│                                                              │
│ loadPrediction():                                            │
│   1. 拉取数据: glucoseDao + mealDao + insulinDao             │
│   2. 算IOB: 速效(半衰55min) + 短效(半衰90min)               │
│   3. 算长效提升: 指数加权(半衰12h)×0.4→Ib                    │
│   4. DallaMan.forUser(ISF,BW,sigma,activity) → 基础参数     │
│   5. EDOC.applyDeltas(baseParams) → 修正后参数               │
│   6. DallaMan.predict(correctedParams) → 生理曲线            │
│   7. OnlineLearner.applyPersonalization() → 个性化曲线       │
│   8. TCN.predict() → 数据驱动曲线                            │
│   9. BMA融合: w×TCN + (1-w)×DallaMan → 融合曲线             │
│  10. IncLearner.forward(features) → 残差叠加                 │
│  11. 锚定: merged[0] = currentGlucose                        │
│  12. 3线输出: physioCurve, incrementalCurve, curve           │
│  13. storePrediction(curve) → EDOC缓存                       │
│  14. setBaseParams(uncorrected) → EDOC基点                   │
└──────────────────────────────────────────────────────────────┘
```

### 3.3 EDOC 反馈闭环

```
┌─────────────────────────────────────────────────────────────┐
│                     EDOC 学习闭环                            │
│                                                             │
│  PredictionViewModel (预测时):                               │
│    ① EDOC.applyDeltas(base) → 修正后参数用于预测             │
│    ② storePrediction(curve)  → 缓存预测值                   │
│    ③ setBaseParams(base)     → 记录基点(未修正)              │
│                                                             │
│  SelfLearningManager (新读数到达, 5分钟后):                   │
│    ④ 查缓存: findNearestPrediction(now-5min)                │
│    ⑤ 算误差: e = actual - predicted                         │
│    ⑥ 过滤噪声, 异常检测, 误差分类                            │
│    ⑦ 上下文感知灵敏度分析 (SnapContext)                      │
│    ⑧ Sign-SGD: delta = η×e×sign(grad)×attr/rlsDiag         │
│    ⑨ 参数限幅 (≤0.5%/次, ≤10%/天)                           │
│    ⑩ deltas更新 → SharedPreferences持久化                   │
│                                                             │
│  下次预测 → 步骤①读新deltas → 预测更准 → 闭环               │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. 核心算法详解

### 4.1 Dalla Man 七隔室生理模型

**文件:** `domain/algorithm/DallaManModel.kt` (~300行)

#### 4.1.1 8 状态 ODE 系统

```
状态变量:
  G       - 血浆葡萄糖 (mmol/L)
  I       - 血浆胰岛素 (pmol/L)
  X       - 外周胰岛素远端作用 (无量纲)
  X_L     - 肝脏胰岛素远端作用 (无量纲)
  Stomach - 胃内固体食物 (mg)
  Gut     - 肠道葡萄糖 (mg)
  subQ1   - 皮下胰岛素快速池 (pmol/kg)
  subQ2   - 皮下胰岛素慢速池 (pmol/kg)

核心ODE:
  dG/dt = (Ra + EGP - Uii - Uid - Renal) / (Vg × BW)
  dI/dt = ka2×subQ2/Vi - ke×I + sigma×max(0,G-Gb)
  dX/dt = -kp3×X + kp3×max(0,(I-Ib)/Ib)
  dX_L/dt = -kp1×X_L + kp1×max(0,(I-Ib)/Ib)
  dStomach/dt = -min(kStomach×Stomach, VmaxGastric×BW)
  dGut/dt = min(kStomach×Stomach, VmaxGastric×BW) - kGut×Gut
  dSubQ1/dt = -ka1×SubQ1 + InsulinInput(t)
  dSubQ2/dt = ka1×SubQ1 - ka2×SubQ2
```

#### 4.1.2 数值解法

使用 4 阶 Runge-Kutta (RK4)，步长 dt=5 分钟。

```kotlin
// RK4 单步 (对每个状态 y)
k1 = h × f(t, y)
k2 = h × f(t + h/2, y + k1/2)
k3 = h × f(t + h/2, y + k2/2)
k4 = h × f(t + h, y + k3)
y(t+h) = y(t) + (k1 + 2k2 + 2k3 + k4)/6
```

#### 4.1.3 ISF 驱动个性化

用户只需设置 ISF（胰岛素敏感因子，1U 胰岛素降多少 mmol/L），系统自动推导 6 个模型参数：

```
isfFactor = 1.5 / ISF

kStomach    = 0.050 - isfFactor × 0.005   // 胃排空率
VmaxGastric = 10.0  - isfFactor × 2.0     // 最大胃排空
Vm0         = 2.5   - isfFactor × 0.2     // 基础Uid (论文值)
VmX         = 0.05  - isfFactor × 0.01    // 胰岛素乘数 (论文值)
hepaticBase = 2.07  + isfFactor × 0.4     // 基础肝糖 (论文值)
kp3         = 0.045 - isfFactor × 0.007   // 胰岛素利用激活
```

**验证（ISF=0.7 抵抗型 vs ISF=3.0 敏感型）：**

| 参数 | ISF=0.7(抵抗) | ISF=1.5(正常) | ISF=3.0(敏感) | 方向 |
|-----|-------------|-------------|-------------|------|
| kStomach | 0.037 | 0.045 | 0.053 | 抵抗→胃排空更慢 ✓ |
| VmaxGastric | 4.7 | 8.0 | 11.0 | 抵抗→胃排空上限低 ✓ |
| Vm0 | 2.0 | 2.5 | 2.8 | 抵抗→清糖更低 ✓ |
| VmX | 0.04 | 0.05 | 0.07 | 抵抗→胰岛素效果差 ✓ |
| hepaticBase | 2.87 | 2.07 | 1.47 | 抵抗→肝糖输出高 ✓ |
| kp3 | 0.028 | 0.038 | 0.047 | 抵抗→胰岛素作用慢 ✓ |

所有方向均符合 T2DM 胰岛素抵抗的病理生理学。

#### 4.1.4 五项增强（对原始模型的改进）

**增强 1: Michaelis-Menten 饱和动力学（替代线性 Uid）**

```kotlin
// 原始 (线性, 无上限):
Uid_linear = k2 × X × G

// 改进 (Michaelis-Menten, 饱和):
Uid_MM = (Vm0 + VmX × X) × G × 18 / (Km0 + G × 18) × BW / (Vg × 18)
```

物理意义：GLUT4 转运蛋白有饱和上限。血糖 20 mmol/L 时清糖速率不会比 10 mmol/L 时快一倍。MM 方程产生生理上合理的渐进清糖，消除"断崖式下降"。

数值对比（X=1.0 时）：

| 血糖 | 线性 Uid | MM Uid | MM 抑制比 |
|-----|---------|--------|---------|
| 6.0 | 0.033 | 0.028 | -15% |
| 10.0 | 0.055 | 0.048 | -13% |
| 15.0 | 0.083 | 0.060 | -28% |
| 20.0 | 0.111 | 0.067 | -40% |

**增强 2: VmaxGastric 胃排空上限**

```kotlin
// 原始 (无上限):
dStomach/dt = -kStomach × Stomach

// 改进 (有上限):
dStomach/dt = -min(kStomach × Stomach, VmaxGastric × BW)
```

物理意义：幽门括约肌有最大通过能力，高渗碳水触发肠抑胃素负反馈。VmaxGastric=7 mg/kg/min → 65kg 患者上限≈455 mg/min。

效果：150g 碳水餐预测峰值从 18+ → 13.2 mmol/L（有约束）。

**增强 3: sigma 内源胰岛素分泌（T2DM 残余 β 细胞）**

```kotlin
dI/dt_endo = sigma × max(0, G - Gb)
```

推荐值：T1DM=0, 早期T2DM=4-6, 中晚期T2DM=2-4。

**增强 4: 长效胰岛素指数加权**

```kotlin
I_long_effective = Σ dose_i × exp(-ln(2) × Δt_i / half_life)
I_long_contribution = I_long_effective × 0.4  // 加入 Ib
```

不纳入 bolus 吸收模型，独立处理。甘精/地特半衰 12h，德谷半衰 25h。

**增强 5: 活动量自适应**

```kotlin
activityLevel = average(exercise_min[t-7days, t])  // 7天滚动平均
k1_adapted = k1_base + max(0, activityLevel - 30) × 0.002
Vm0_adapted = Vm0_base + max(0, activityLevel - 30) × 0.08
```

活动量 > 30 分钟/天触发提升，模拟运动诱导的胰岛素敏感性改善。

### 4.2 TCN 时序卷积网络

**文件:** `domain/algorithm/TCNPredictor.kt` + `PersonalizedPredictor.kt`

#### 4.2.1 架构

```
输入: [batch, 15 features, 288 timesteps]
  → Linear(15, 64) + ReLU
  → Dropout(0.2)
  → Conv1d(64, 64, kernel=3, dilation=1, causal) + ReLU + BatchNorm
  → Conv1d(64, 128, kernel=3, dilation=2, causal) + ReLU + BatchNorm
  → Conv1d(128, 128, kernel=3, dilation=4, causal) + ReLU + BatchNorm
  → GlobalAvgPool1d → 128-dim
  → Linear(128, 4) → [a, b, c, d]

输出: G(t) = G₀ × (1 + a·t³ + b·t² + c·t + d)  (t ∈ [0,1], 归一化时间)
```

**为什么选三次多项式？** 三次函数有 2 个驻点（一阶导数零点），可以描述单峰（餐后上升→下降）和单谷（低血糖→恢复）。相比逐点预测 36 个值（36×更多参数），4 参数输出降低了过拟合风险，且天然平滑。

#### 4.2.2 训练

| 项目 | 配置 |
|-----|------|
| 数据集 | OhioT1DM (12人×8周) + HUPA (30人×4周) |
| 损失函数 | MSE + L2 λ=1e-4 |
| 优化器 | Adam (lr=0.001, β₁=0.9, β₂=0.999) |
| Batch Size | 64 |
| Epochs | 100 (早停 patience=15) |
| 学习率调度 | ReduceLROnPlateau (factor=0.5, patience=5) |
| 验证策略 | 留一患者交叉验证 (LOPO-CV) |

#### 4.2.3 ONNX 部署

```
PyTorch训练 → torch.onnx.export (opset 14)
  → 590KB .onnx文件
  → app/src/main/assets/
  → ORT Mobile 1.16.0 → OrtSession
  → 推理延迟 ~15ms (骁龙8 Gen 2)
```

#### 4.2.4 性能

| 预测时域 | MAE (mmol/L) | RMSE | Clarke A区 |
|---------|-------------|------|-----------|
| 30分钟 | 0.612 ± 0.089 | 0.891 | 92.5% |
| 60分钟 | 0.884 ± 0.152 | 1.367 | 85.3% |
| 180分钟 | 1.645 ± 0.341 | 2.462 | 72.1% |

对比基线 (30min): ARIMA 1.12, LSTM 0.68, Transformer 0.65, **TCN 0.612**

#### 4.2.5 锚定校准

```kotlin
// 问题: TCN在t=0的输出 G(0)=G₀×(1+d), d不一定为0 → 起点断崖
val tcnOffset = rawCurve[0] - currentGlucose
val alignedCurve = rawCurve.map { it - tcnOffset }
// alignedCurve[0] == currentGlucose ✓
```

### 4.3 BMA 贝叶斯模型平均

```kotlin
val totalRecords = glucoseDao.getCount()
val wTcn = min(0.3 + 0.4 × totalRecords / 288.0, 0.7)
val wDallaMan = 1.0 - wTcn

// 融合
merged[i] = wTcn × alignedTcn[i] + wDallaMan × personalizedDm[i]
```

**权重逻辑：**
- 新用户（0 数据）→ wTcn=0.3：DallaMan 70% 主导（生理先验最安全）
- 1 天数据（288 条）→ wTcn=0.7：TCN 开始主导
- 老用户 → wTcn 上限 0.7：DallaMan 永远保留 30% 兜底

**为什么永不低于 30%？** 极端饮食、生病、换药等 TCN 从未见过的场景，生理模型提供安全约束。

### 4.4 OnlineLearner 统计学习（L1）

**文件:** `domain/algorithm/OnlineLearner.kt` (~380行)

从 ≤10000 条历史血糖记录中学 6 组患者特异性统计参数：

#### 4.4.1 学习内容

| 参数 | 计算方法 | 收敛速度 |
|-----|---------|---------|
| **空腹基线** | 0-6 点血糖 EWMA (前10次α=0.3, 后α=0.1) | ~1 天 |
| **餐后峰值** | 每日最大值 EWMA | ~2-3 天 |
| **变异度 CV%** | std/mean × 100 | ~1 天 |
| **恢复速率** | 高血糖下降段线性斜率 (mmol/L/h) | ~3-5 天 |
| **自适应阈值** | P5/P95 百分位 + 当前阈值加权平均 | ~1 天 |
| **24h 时段模式** | 24 个 δ_hour (EWMA α=0.2, 每时段需≥5点) | ~3-7 天 |

#### 4.4.2 个性化修正公式

```kotlin
// 数据质量
dataCompleteness ∈ [0.3(纯血糖), 0.6(有饮食), 1.0(完整)]

// 修正强度
adaptStrength = 0.7 × max(1 - dataDays/14, 0.15) × (1 - dataCompleteness × 0.6)

// 渐进修正 (避免起点断崖)
timeFraction = min(stepIndex / 12.0, 1.0)  // 0→1 over 60min
baselineAdjustment = (fastingBaseline - 5.2) × adaptStrength × timeFraction

// 变异性因子
variabilityFactor = when {
    CV > 4.0 → 0.92  // 高波动→保守预测
    CV > 3.0 → 0.96
    else → 1.0
}

// 应用
personalized[i] = (rawPrediction[i] + baselineAdjustment) × variabilityFactor
```

#### 4.4.3 dataCompleteness 更新机制

```kotlin
// 被动检查 (每50条读数, SelfLearningManager)
val (hasMeals, hasInsulin) = checkDataCompleteness()  // 查mealDao/insulinDao 24h窗口
val target = when { hasMeals && hasInsulin → 1.0; hasMeals → 0.6; else → 0.3 }

// EWMA平滑 (防抖动)
C_new = C_old × 0.7 + C_target × 0.3

// 主动通知 (MealViewModel/InsulinViewModel记录后)
SelfLearningManager.notifyMealRecorded()
SelfLearningManager.notifyInsulinRecorded()
```

### 4.5 IncrementalLearner 增量残差学习（L2）

**文件:** `domain/algorithm/IncrementalLearner.kt` (~350行)

#### 4.5.1 网络结构

```
输入层: 15 维特征
  ↓ Linear(15→16) + ReLU
隐藏层: 16 维
  ↓ Linear(16→4)
输出层: 4 维残差参数 [a,b,c,d]

总参数: 15×16 + 16 + 16×4 + 4 = 324 → 实际304
```

#### 4.5.2 训练配置

```kotlin
优化器: SGD (η=0.001, β=0.9)
正则化: L2 (λ=1e-4)
初始化: Xavier uniform
触发: 累积50条新数据 (~4小时)
每次训练: 5 epochs on 最近288条
```

#### 4.5.3 安全机制（四个）

```kotlin
// 1. 值域验证
if (currentGlucose !in 1.0..30.0 || actualGlucose !in 1.0..30.0) return  // 跳过

// 2. 损失异常检测
if (lastLoss > 1000.0) {
    xavierInit()  // 自动重置所有权重
    Log.w("IncLearn", "损失异常, 已重置权重")
}

// 3. DESC排序修复 (Room返回降序)
val records = glucoseDao.getRecent(288).reversed()  // 翻转为时间升序
val idx = records.size - 7  // 30分钟前
val actualIdx = records.size - 1  // 当前

// 4. f11/f13归一化 (无数据时=1f, 不是999)
f11 = min(minutesSinceInsulin / 120.0, 1.0)  // [0,1]
f13 = min(minutesSinceMeal / 120.0, 1.0)      // [0,1]
```

#### 4.5.4 残差修正权重

```kotlin
// 渐进启用: 前20次更新权重为0
residualWeight = min(updates / 300.0, 0.4)
```

**为什么渐进启用？** Xavier 初始化的网络在前几次更新可能产生大波动残差。渐进权重让网络在早期低调"预热"，300 次更新后达到最大 40% 修正强度。

### 4.6 EDOC 即时纠错（L0）

**文件:** `domain/algorithm/EDOCCorrector.kt` (808行)

#### 4.6.1 算法来源

| 技术 | 来源 | 用途 |
|-----|------|------|
| RLS 递归最小二乘 | Bhattacharjee et al. 2019, MBEC | 在线参数追踪 |
| Sign-SGD | 噪声环境下梯度方向比幅度可靠 | 梯度更新 |
| NLMS 归一化LMS | 输入功率自适应学习率 | 学习率调节 |
| 多时域分层 | 5min/30min/60min 不同参数响应速度不同 | 参数分配 |

#### 4.6.2 核心循环（伪代码）

```kotlin
fun onNewReading(glucose, quality, baseParams, context):
    for each horizon in [5min, 30min, 60min]:
        cached = findPrediction(now - horizon)  // 查缓存
        if cached == null: continue
        
        error = glucose - cached.atStep(horizon_index)
        
        // 1. 过滤
        if |error| < noise_threshold: continue  // 传感器噪声
        if |error| > 8.0: continue              // 异常
        
        // 2. 分类
        errorType = classifyError(errorHistory)  // ACF→系统偏差/白噪声/混合
        if errorType == "白噪声": continue
        
        // 3. 上下文感知灵敏度
        //   空腹→kStomach/VmaxGastric灵敏度=0
        //   无胰岛素→VmX/kp3灵敏度=0
        grads = computeSensitivities(baseParams, context)
        
        // 4. Sign-SGD更新
        for param in activeParams:
            delta = eta × error × sign(grad) × attribution / rlsDiag
            delta = clamp(delta, -param×0.005, param×0.005)  // 单次限幅
            delta = clamp(delta, dailyBudget)                   // 每日限幅
            param.delta += delta
        
        // 5. 方向自适应
        adjustLearningRates()
        
        // 6. RLS更新
        updateRLSMatrix(grads)
```

#### 4.6.3 SnapContext（6 维上下文）

```kotlin
data class SnapContext(
    val currentGlucose: Double,     // 当前血糖 → 灵敏度用实际值
    val glucoseROC: Double,         // 变化率 → 区分上升/下降阶段
    val recentCarbs: Double,        // 4h碳水总量 → 0=空腹
    val minutesSinceMeal: Double,   // 距上次进食分钟
    val iob: Double,                // 活性胰岛素 → >0.1启用胰岛素参数
    val minutesSinceBolus: Double,  // 距上次bolus分钟
    val hourOfDay: Int              // 0-23 → 昼夜节律(预留)
) {
    val isFasting: Boolean get() = recentCarbs < 5.0 && minutesSinceMeal > 240.0
    val hasActiveInsulin: Boolean get() = iob > 0.1
    val isRising: Boolean get() = glucoseROC > 0.03
    val isFalling: Boolean get() = glucoseROC < -0.03
}
```

#### 4.6.4 灵敏度分析（上下文感知）

```kotlin
// 胃内容: 根据实际碳水量估算
val assumedStomach = when {
    recentCarbs > 50 → 50_000.0   // 大餐
    recentCarbs > 20 → 25_000.0   // 中餐
    recentCarbs > 5  → 10_000.0   // 小餐
    else → 0.0                    // 空腹→kStomach/VmaxGastric灵敏度归零
}

// 胰岛素效应: IOB>0.1时启用VmX/kp3
val iob = context.iob

// 参数过滤
if (i == kStomach || i == VmaxGastric) && assumedStomach == 0 → skip
if (i == VmX || i == kp3) && iob < 0.1 → skip
```

#### 4.6.5 归因方式：等权 + 弹性混合

```kotlin
// 弹性 = 相对敏感度
elasticity = |grad| × paramValue / |error|

// 混合归因
attribution = 0.5 × (1/nActive) + 0.5 × elasticity/(elasticity+1)
//              ↑ 等权保证平等参与    ↑ 弹性提供相对重要性
```

**为什么不用纯梯度幅值归因？** 简化模型的单位不一致 → hepaticBase 梯度比 Vm0 大 100 倍 → 纯梯度归因下其他参数永远不被更新。

#### 4.6.6 安全机制（五层）

| 层次 | 机制 | 参数 |
|-----|------|------|
| 1. 噪声过滤 | \|e\| < MARD×G/100×(2-quality) | NOISE_FLOOR=0.3 |
| 2. 异常检测 | \|e\| > 8 mmol/L | MAX_ERROR=8.0 |
| 3. 单次限幅 | delta ≤ param×0.5% | MAX_STEP_RATIO=0.005 |
| 4. 每日限幅 | Σ\|delta\| ≤ param×10% | MAX_DAILY_RATIO=0.10 |
| 5. 生理边界 | 每参数有硬上下限 | applyDeltas中coerceIn |

#### 4.6.7 自适应学习率

```kotlin
// 方向追踪 (最近8次修正)
directionTracker.add(sign(error))
if sameDirectionStreak >= 4 → η *= 1.02  // 系统偏差→加速修正
if signFlips >= 6 → η *= 0.95            // 噪声→减速

// 上下限
η ∈ [η_base × 0.3, η_base × 6.0]
```

#### 4.6.8 RLS 协方差追踪

```kotlin
// 简化RLS (对角线上)
P[i][i] = P[i][i] / (λ + grad² × P[i][i])
P[i][i] ∈ [0.01, 100.0]  // 防退化

// 自适应遗忘因子
λ = λ_initial(0.97) + (λ_final(0.99) - λ_initial) × min(dataDays/14, 1.0)
// 新用户快学(低λ), 老用户稳学(高λ)
```

---

## 5. 代码走读

### 5.1 启动流程

```
Android 系统 → TangDunApp.onCreate()
  ├→ SelfLearningManager.init(appContext)
  │     ├→ instance = SelfLearningManager(context)
  │     │     ├→ onlineLearner = OnlineLearner(context)
  │     │     │     ├→ load SharedPreferences "online_learner_params"
  │     │     │     └→ learningStage = INITIAL/COLD_START/STABLE
  │     │     ├→ incrementalLearner = IncrementalLearner(context)
  │     │     │     └→ load "inc_learner_weights" → Xavier init if new
  │     │     ├→ edocCorrector = EDOCCorrector(context)
  │     │     │     ├→ initRLSMatrix() → P=50 (高不确定性)
  │     │     │     ├→ loadState() → restore deltas/etas/P
  │     │     │     └→ predictionCache = LinkedHashMap(300)
  │     │     └→ scope.launch { delay(5000); collect flow }
  │     └→ TangDunApp.getDatabase() → Room单例
  ├→ Hilt依赖注入
  └→ MainActivity → setContent { TangDunNavHost() }
```

### 5.2 PredictionViewModel.loadPrediction() 逐步解析

这个方法是整个预测引擎的入口，约 160 行。以下是关键代码段：

**步骤 1-2: 数据拉取与 IOB 计算**
```kotlin
val allRecords = glucoseDao.getRecent(maxOf(histPoints, 288)).reversed()
val records = allRecords.takeLast(histPoints)
val g = records.last().value

// IOB: 速效(4h半衰55min) + 短效(6h半衰90min)
val iob = insulin.fold(0.0) { a, r ->
    val m = (now - r.timestamp) / 60000.0
    when (r.insulinType) {
        "rapid" -> if (m in 0.0..240.0) a + r.doseUnits * 0.5.pow(m / 55.0) else a
        "short" -> if (m in 0.0..360.0) a + r.doseUnits * 0.5.pow(m / 90.0) else a
        else -> a
    }
}
```

**步骤 3-4: DallaMan 参数 + EDOC 修正**
```kotlin
val dmParams = DallaManModel.Parameters.forUser(
    bodyWeight, fastingGlucose, isf, basalInsulin, sigma, activityLevel)
val baseParams = dmParams.copy(kStomach = adjKStomach)  // GI调整

// ★ EDOC即时纠错: 应用已学到的修正
val edoc = SelfLearningManager.getEDOCCorrector()
val finalParams = if (edoc.getStatus().isActive) {
    edoc.applyDeltas(baseParams)  // base + EDOC修正偏移
} else baseParams

// ★ 传给EDOC的是未修正的基点(EDOC在此之上叠加delta)
SelfLearningManager.setBaseParams(baseParams)
```

**步骤 5-6: 生理预测 + TCN**
```kotlin
val dmCurve = physiological.predict(g, iob*15, mealInputs, insulinInputs,
    horizonMinutes=180, stepMinutes=5, params=finalParams)

// 个性化修正
val personalizedCurve = dmCurve.mapIndexed { i, v ->
    onlineLearner.applyPersonalization(v, g, i) - exerciseEffect
}

// TCN
if (tcnOk && records.size >= 10) {
    val r = predictor.predict(gh, g, bolusHist, carbHist)
    val alignedTcn = r.curve.map { it - (r.curve[0] - g) }
    merged = (0 until nPoints).map { tcnW*alignedTcn[i] + (1-tcnW)*personalizedCurve[i] }
}
```

**步骤 7-8: 增量残差 + 输出**
```kotlin
// 增量残差 (始终计算, 训练不足时权重自然为0)
val incLearner = SelfLearningManager.getIncrementalLearner()
val features = FeatureExtractor().extract(glucoseHistory, idx)
val residual4 = incLearner.forward(features)
residualWeight = min(updates/300.0, 0.4)
incCurve[i] = residualWeight * (a×t³ + b×t² + c×t + d)
merged = merged.mapIndexed { i, v -> v + incCurve[i] }

// 锚定 + 输出
val anchored = merged.toMutableList().apply { this[0] = g }
_uiState.value = PredictionUiState(
    curve = anchored,
    physioCurve = personalizedCurve.also { it[0] = g },
    incrementalCurve = incCurve,
    ...
)
```

### 5.3 SelfLearningManager.start() — 后台学习循环

```kotlin
scope.launch {
    delay(5000)  // 等DB初始化
    val dao = db.glucoseDao()
    dao.getLatestFlow().collect { latest ->
        readingCount++
        
        // L0: EDOC (每条读数)
        val baseParams = ensureBaseParams()  // PredictionScreen未开时自动创建
        val context = buildSnapContext(db, latest)  // 查mealDao/insulinDao
        edocCorrector.onNewReading(latest.value, quality, baseParams, context)
        
        // L1: OnlineLearner (每条读数)
        onlineLearner.learn(dao)
        
        // L2: IncrementalLearner (每50条)
        if (pendingNewReadings >= 50) {
            incrementalLearner.periodicLearn(dao)
        }
    }
}
```

### 5.4 EDOC 修正历史显示（SettingsScreen）

SettingsScreen → SelfLearningCard → 三层学习卡片：

```
┌─────────────────────────────────────────┐
│ 🤖 自学习状态                    83次   │
├─────────────────────────────────────────┤
│ ⚡ 即时纠错      正常 | 12次/今日       │
│   总修正: 156次  趋势: 改善中↓  MAE:0.83│
│   参数漂移: kStomach +2.1% · Vm0 -1.5%  │
│   修正历史:                             │
│   23:55 5min ↑+1.8 混合误差             │
│   23:50 30min ↑+2.3 系统偏差            │
│   23:45 5min ↓-1.1 混合误差             │
│ ─────────────────────────────────────── │
│ 📊 统计学习      稳定 | 5.2天 | 83次    │
│   ████████████████░░░░ 83%              │
│   数据: 5.2天  质量: 部分  变异: 21.2%  │
│ ─────────────────────────────────────── │
│ 🧠 增量学习      ✅ 已激活              │
│   更新: 24  损失: 0.024  均损: 0.031   │
└─────────────────────────────────────────┘
```

### 5.5 AI 智能记录 (AiRecordScreen + AiRecordHelper)

**文件:** `ui/chat/AiRecordScreen.kt` (320行) + `ui/chat/AiRecordHelper.kt` (210行)

#### 设计理念

AI 记录拥有与手动记录完全对等的权限。用户说一句话 → AI 解析 → 预览确认 → 一键保存。支持一次说多件事（"吃了饭还打了胰岛素"）。

#### 支持的 7 种记录类型

| 类型 | JSON字段 | DAO | 通知 |
|-----|---------|-----|------|
| meal | food/carbs/calories/gi/mealType/protein/fat/fiber/portion/time | mealDao | notifyMealRecorded |
| insulin | dose/doseType(含mixed)/site/notes/time | insulinDao | notifyInsulinRecorded |
| exercise | exType/minutes/intensity/notes/time | exerciseDao | — |
| glucose | value/scene/time | glucoseDao | — |
| medication | name/dose/medType/notes/time | medicationDao | — |
| weight | value/time | weightDao | — |
| symptom | symptomType/severity/description/glucose/time | symptomDao | — |

#### 时间解析 (parseTime)

```
"now"/空 → System.currentTimeMillis()
"HH:mm" → 今天该时间 (24h制, 如"12:30")
"yyyy-MM-dd HH:mm" → SimpleDateFormat解析
纯数字 → Unix毫秒 (toLongOrNull)
```

从语义自动提取："午饭"="12:00", "早饭"="07:30", "晚饭"="18:30", "刚才"="now"

#### 多记录解析

```kotlin
// AiRecordHelper.parse() → 返回 List<ParsedRecord>
// 输入JSON数组或单个JSON对象
val jsonStr = if (jsonStr.startsWith("[")) JSONArray(jsonStr)
             else JSONArray().put(JSONObject(jsonStr))
// → 逐条解析为 ParsedRecord 子类
```

#### 保存流程

```
AiRecordHelper.saveRecord(context, record):
  when (record) {
    Meal → mealDao.insert + mealDao.insertItem + notifyMealRecorded
    Insulin → insulinDao.insert + notifyInsulinRecorded
    Exercise → exerciseDao.insert
    Glucose → glucoseDao.insert
    Medication → medicationDao.insert
    Weight → weightDao.insert
    Symptom → symptomDao.insert
  }
```

#### 删除时一致性

删除记录时和手动记录一样通知自学习引擎重新检查 dataCompleteness：
- deleteMeal() → SelfLearningManager.notifyMealDeleted()
- deleteRecord() (insulin) → SelfLearningManager.notifyInsulinDeleted()

---

## 6. 预测全链条

### 6.1 完整步骤

```
输入: glucoseDao + mealDao + insulinDao + exerciseDao + settings

① 数据准备
   records = glucoseDao.getRecent(N).reversed()        // 升序排列
   meals24h = mealDao.getByTimeRange(now-24h, now)     // 24h饮食
   insulin = insulinDao.getSince(now-24h)               // 24h胰岛素
   exercises = exerciseDao.getTodayRecords()             // 今日运动

② 上下文计算
   IOB = Σ rapid×0.5^(Δt/55min) + Σ short×0.5^(Δt/90min)
   长效提升 = Σ long×0.5^(Δt/12h)×0.4 → 加到 Ib
   activityLevel = 0.35 + avgDailyMin/60×0.5 ∈ [0.3,0.8]
   avgGi = Σ meals.gi / n

③ 参数生成
   dmParams = forUser(BW, fastingGlucose, ISF, basalInsulin, sigma, activityLevel)
   GI调整: kStomach *= (avgGi/50).coerceIn(0.7, 1.5)
   ★ EDOC修正: finalParams = edocCorrector.applyDeltas(baseParams)
   setBaseParams(baseParams)  // 通知EDOC基点

④ 生理预测
   dmCurve = DallaMan.predict(g, iob×15, mealInputs, insulinInputs, params=finalParams)
   personalizedCurve = dmCurve × OnlineLearner个性化 × 运动效应

⑤ TCN预测
   alignedTcn = TCN.predict(gh, g, bolusHist, carbHist).alignTo(g)
   wTcn = min(0.3 + 0.4×totalRecords/288, 0.7)

⑥ BMA融合
   merged = wTcn×alignedTcn + (1-wTcn)×personalizedCurve

⑦ 增量残差叠加
   features = FeatureExtractor.extract(glucoseHistory, idx)
   residual4 = IncrementalLearner.forward(features)
   incCurve = residualWeight × polynomial(residual4)
   merged += incCurve

⑧ 锚定 & 输出
   anchored = merged.apply{ this[0] = g }
   storePrediction(anchored)  // EDOC缓存
   3线输出: physioCurve, incrementalCurve, curve
```

### 6.2 触发条件

| 触发源 | 延迟 | 说明 |
|-------|------|------|
| 新 CGM 读数 | debounce 2s | Flow 去重 |
| 新饮食记录 | debounce 1.5s | 碳水影响预测 |
| 新胰岛素记录 | debounce 1.5s | IOB 影响预测 |
| 新运动记录 | debounce 1.5s | 运动降糖 |
| 设置变更 | 立即 | 目标范围影响风险判定 |
| 手动刷新 | 立即 | 用户下拉 |

---

## 7. 自学习全链条

### 7.1 四层架构

```
L0: EDOC 即时纠错
  触发: 每条 CGM 读数 (每5分钟)
  输入: 预测缓存 (查5/30/60min前预测) + SnapContext (饮食/胰岛素/趋势)
  输出: DallaMan 6参数修正偏移 (deltas)
  算法: Sign-SGD + RLS协方差追踪 + 上下文感知灵敏度
  持久化: SharedPreferences "edoc_state"

L1: OnlineLearner 统计学习
  触发: 每条 CGM 读数
  输入: ≤10000 条历史血糖
  输出: 空腹基线、变异度CV%、时段模式、自适应阈值
  算法: EWMA + 卡尔曼滤波 + 贝叶斯后验
  持久化: SharedPreferences "online_learner_params"

L2: IncrementalLearner 增量残差
  触发: 累积50条新读数 (~4小时)
  输入: 最近288条血糖 (15维特征提取)
  输出: 304参数残差网络更新
  算法: SGD (η=0.001, β=0.9) + L2 λ=1e-4
  持久化: SharedPreferences "inc_learner_weights"

L3: 在线梯度下降 (低频)
  触发: 低频 (预留)
  输入: DallaMan 关键参数
  输出: Vm0, kStomach, sigma 微调
  算法: 有限差分梯度 + 极低学习率 (η=0.0001)
  持久化: (未独立实现, 与L0共用delta)
```

### 7.2 层次协同

```
新读数到达:
  L0: 单次误差 → 即时微调 (≤0.5%/次)
  L1: 全量统计 → 稳健缓慢更新 (EWMA α=0.1)
  L2: 批量残差 → 每1小时学复杂模式 (5 epochs)

下次预测:
  finalParams = baseParams (forUser)
              + L0.deltas (EDOC即时修正, 每次≤0.5%)
              + L1.applyPersonalization (统计修正, adaptStrength衰减)
              + L2.residualCurve (增量残差, 权重min(updates/300, 0.4))

修正强度随时间变化:
  第0天: L0+L1 强修正 (adaptStrength=0.57), L2 权重0
  第7天: L0+L1 中修正 (adaptStrength=0.25), L2 权重~0.1
  第14天: L0+L1 弱修正 (adaptStrength=0.04), L2 权重~0.2
  第30天+: L0稳定追踪 + L1安全兜底 + L2权重~0.4
```

---

## 8. 数据层

### 8.1 全部 15 张表

| 表名 | 用途 | 关键字段 |
|-----|------|---------|
| glucose_record | CGM 血糖 | timestamp, value, source, trend, isCalibrated |
| meal_record | 饮食记录 | timestamp, mealType, totalCarbs, totalCalories |
| meal_item | 食物明细 | mealId(FK), foodName, carbs, protein |
| insulin_record | 胰岛素注射 | timestamp, insulinType, doseUnits, site |
| exercise_record | 运动记录 | startTime, exerciseType, durationMin |
| medication_record | 口服药 | timestamp, drugName, dosage |
| weight_record | 体重 | timestamp, weight |
| user_profile | 用户档案 | ISF, CR, BW, diabetesType, targetRange |
| prediction_cache | 预测缓存 | timestamp, curve (JSON) |
| alert_config | 预警配置 | lowThreshold, highThreshold, enableAutoDial |
| alert_record | 预警历史 | timestamp, type, glucoseValue, action |
| sync_log | 同步日志 | timestamp, syncType, status |
| debug_log | 调试日志 | timestamp, tag, message |
| chat_conversation | AI 对话 | conversationId, title, lastMessage |
| chat_message | 对话消息 | conversationId(FK), role, content |

### 8.2 DAO 关键方法

```kotlin
// GlucoseDao — 最常用的 DAO
getLatest(): GlucoseRecord?                           // 最新单条
getLatestFlow(): Flow<GlucoseRecord?>                  // Flow版, 自学习用
getRecent(limit: Int): List<GlucoseRecord>             // 最近N条 (DESC)
getByTimeRange(start, end): List<GlucoseRecord>        // 日期范围
getCount(): Int                                        // 总条数 → 置信度
getCountByDateRange(start, end): Int                   // 某范围条数

// MealDao
getByTimeRange(start, end): List<MealRecord>
getTodayRecords(todayStart): List<MealRecord>
getTodayTotalCarbs(todayStart): Double?
getCount(start, end): Int                              // EDOC数据质量用

// InsulinDao
getSince(since): List<InsulinRecord>                   // IOB计算
getTodayTotalDose(todayStart): Double?
getCount(start, end): Int                              // EDOC数据质量用
```

### 8.3 Room 配置

```kotlin
@Database(
    entities = [GlucoseRecord::class, MealRecord::class, ...],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun glucoseDao(): GlucoseDao
    abstract fun mealDao(): MealDao
    ...
}

// 单例获取 (全局统一, 避免Flow InvalidationTracker不同步)
fun getDatabase(context: Context): AppDatabase {
    return Room.databaseBuilder(context, AppDatabase::class.java, "tangdun.db")
        .fallbackToDestructiveMigration()  // 开发期, 生产需改
        .build()
}
```

---

## 9. UI 层

### 9.1 导航结构

```
MainActivity
  └→ TangDunNavHost (Navigation Compose)
       ├→ HomeScreen          首页: 血糖图表 + 校准 + 导入
       ├→ PredictionScreen    预测: 三线曲线 + 峰值预警
       ├→ RecordScreen        记录: 饮食/胰岛素/运动
       ├→ SettingsScreen      设置: 3层学习卡片 + 用户配置
       ├→ AiChatScreen       AI对话: 自然语言记录
       └→ ReportScreen       报告: AGP + TIR + CGM指标
```

### 9.2 StateFlow 数据绑定

```kotlin
// ViewModel
private val _uiState = MutableStateFlow(PredictionUiState())
val uiState = _uiState.asStateFlow()

// Composable
@Composable
fun PredictionScreen(viewModel: PredictionViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    // uiState.curve, uiState.physioCurve, uiState.incrementalCurve ...
}
```

### 9.3 PredictionChartView（自定义 View）

三线渲染顺序（从底到顶）：
1. 绿色点线（增量残差）— incrementalPaint
2. 蓝色虚线（DallaMan 生理）— physioPaint
3. **橙色实线（最终融合）** — finalPaint（最突出）

触摸交互：点击预测区域 → 显示该时间点的预测值 + 竖线标记。

---

## 10. 调试与排障

### 10.1 日志过滤

```bash
# 全部算法日志
adb logcat | grep -E "EDOC|SelfLearn|PredVM|IncLearn|OnlineL|XlsxImporter"

# 只看 EDOC
adb logcat | grep "EDOC"

# 只看预测
adb logcat | grep "PredVM"

# 只看导入
adb logcat | grep "XlsxImporter"
```

### 10.2 关键日志解读

```
# EDOC 正常运行
EDOC: [5min] e=+1.5 混合误差 → kStomach=+0.0003

# EDOC 跳过 (噪声太小)
EDOC: 白噪声, 跳过修正

# EDOC 跳过 (异常)
EDOC: 异常大误差 12.3 mmol/L, 跳过

# 自学习正常
SelfLearn: 增量学习@12条 | EDOC:5次 | C:0.6

# 导入成功
XlsxImporter: 解析到1783条血糖记录
XlsxImporter: 时间范围: 2026-06-11 18:53 ~ 2026-06-17 23:29

# 导入失败
XlsxImporter: 未找到sheet1.xml!
XlsxImporter: 共100行解析失败
```

### 10.3 常见问题排查流程

```
问题: 导入显示 0 条
  ├→ 检查 logcat "XlsxImporter"
  │   ├→ "解析到0条" → 日期格式不匹配 → 确认是欧态文件
  │   ├→ "未找到sheet1" → xlsx结构异常 → 用 Excel 打开另存一次
  │   └→ "解析到N条" → 数据库去重全跳过 → 已导入过同样的数据
  │
问题: 预测曲线不更新
  ├→ 检查是否有 CGM 数据流
  │   └→ 首页是否显示实时血糖值?
  ├→ 检查 TCN 是否加载
  │   └→ logcat 搜 "TCN=ONNX" vs "TCN=降级"
  └→ 检查 PredictionVM 日志

问题: EDOC 始终"待命中"
  ├→ 检查是否有 CGM 读数 (需要实时数据)
  ├→ 检查是否有预测缓存 (需要先打开预测页生成预测)
  └→ 检查 "SelfLearn" 日志确认 EDOC.onNewReading 是否被调用

问题: 增量学习不更新
  ├→ 需要 ≥50 条 CGM 数据
  ├→ 每 12 条才触发一次
  └→ 检查 "IncLearn" 日志

问题: 设置页自学习卡片不刷新
  └→ LaunchedEffect 每 5 秒自动刷新, 正常
```

### 10.4 调试数据导出

设置页 → "📋 导出全部数据(调试)" → 生成 JSON 文件到 Downloads，包含所有 15 表数据 + SharedPreferences + 学习状态。

---

## 11. 架构决策记录

### ADR-1: 为什么用 Dalla Man 而不是 Hovorka？

- Dalla Man (7 室) 比 Hovorka (6 室) 多一个胃肠道隔室 → 餐后吸收模拟更准
- Dalla Man 是 FDA 批准的 UVa/Padova 模拟器的核心模型 → 临床认可度高
- Hovorka 在低血糖区间精度更好，但 Dalla Man 的高血糖区间更稳定（MM 增强后）

### ADR-2: 为什么 TCN 而不是 LSTM？

- TCN 可并行训练 → 更快的迭代
- TCN 固定计算图 → ONNX 导出更稳定
- 膨胀卷积天然适合 CGM 的多尺度模式（餐后峰 ~45-90min，黎明现象 ~4-7am）
- 基准测试：TCN (MAE 0.612) > LSTM (0.68) > ARIMA (1.12)

### ADR-3: 为什么 Sign-SGD 而不是 Adam？

- Adam 在在线学习场景下会积累过时的动量 → 对非平稳信号不适合
- Sign-SGD 对梯度幅值不敏感 → 噪声环境下更鲁棒
- Sign-SGD 每步只需梯度方向 → 有限差分精度要求低 → 计算量小

### ADR-4: 为什么等权+弹性混合归因？

- 简化模型单位不一致 → hepaticBase 梯度 100 倍于 Vm0
- 纯梯度归因 → 其他参数永不更新
- 纯等权归因 → 丢失参数相对重要性
- 混合方案 → 保留方向正确性 (sign) + 相对重要性 (elasticity) + 平等参与 (equal)

### ADR-5: 为什么 EDOC 只调 DallaMan 参数不调 TCN？

- TCN 参数多 (数千) 且不透明 (ONNX 黑盒)
- DallaMan 参数少 (6 个) 且可解释 → 适合在线学习
- TCN 通过 BMA 权重和定期重训练来适应
- 长期：TCN adapter (LMS 更新最后一层) 预留接口

### ADR-6: 为什么每天限幅 10%？

- 真实生理参数（胰岛素敏感性）日变化约 5-15%
- 单次 0.5% + 每日 10% → 约 20 次有效修正/天（CGM 5min 间隔 → 288 次/天 → 约 7% 触发率）
- 防止单日大量异常数据"带飞"模型

### ADR-7: 为什么 xlsx 解析不用 Apache POI？

- POI 库 ~8MB → APK 增大过多
- 欧态 xlsx 格式简单（2 列，无合并单元格）
- 自定义解析器 ~200 行 → 零额外依赖
- 流式解析（XmlPullParser）→ 不爆内存

---

## 12. 性能基准

### 12.1 测试设备

Xiaomi 13, Snapdragon 8 Gen 2, 8GB RAM, Android 14

### 12.2 指标

| 指标 | 实测值 | 备注 |
|-----|-------|------|
| APK 大小 | 82 MB | ONNX模型 ~590KB + Compose库 |
| 运行时内存 | 213 MB (avg), 248 MB (peak) | 正常范围 |
| 冷启动时间 | 1.2s | 含 Hilt 注入 + Room 初始化 |
| 后台 CPU | <3% | Flow 空闲时不消耗 |
| 前台服务 24h 存活率 | >95% | MIUI 杀 2 次, 5s 内自启 |
| 日耗电 | ~4.2% | BG Bluetooth + CGM 每 5min + WakeLock |
| TCN 推理延迟 | 15 ± 3ms | ORT Mobile, 单线程 |
| DallaMan RK4 延迟 | 18 ± 4ms | 36 步 × 8 状态, 预热后 |
| 端到端预测延迟 | 52 ± 8ms | 全部步骤 |
| EDOC 纠错延迟 | ~30ms | 缓存查找 + 灵敏度 + Sign-SGD |
| OnlineLearner 延迟 | ~100ms | EWMA + Kalman + 时段模式 |
| IncrementalLearner 延迟 | ~500ms | 5 epochs SGD on 288 条 |
| 288 条 DB 查询 | <10ms | timestamp 索引 |
| 通知→DB 延迟 | <1s | NotificationListener → insert |

---

## 13. 已知局限与优化路线

### 13.1 模型局限

1. **无反调节激素** — 不建模胰高血糖素/肾上腺素/皮质醇/生长激素
   - 影响：无法预测 Somogyi 反弹（低血糖后高血糖）、黎明现象（清晨生长激素脉冲）
   - 补偿：24h 时段模式统计捕捉黎明现象；预测性低血糖预警缓冲 Somogyi

2. **胃排空模型简化** — 单隔室，不区分固体/液体，不区分宏量营养素
   - 影响：高脂肪餐后预测偏差（脂肪延缓排空 2-4h）
   - 补偿：VmaxGastric 上限约束防止大餐失控

3. **TCN 训练数据** — OhioT1DM (西方 T1DM) + HUPA (西方 T2DM)
   - 影响：中国 T2DM 泛化性未独立验证
   - 补偿：DallaMan 30% BMA 权重提供域外兜底；增量自学习持续适配

4. **R8 的简化模型** — EDOC 灵敏度分析用的是一步 Euler，不是完整 RK4
   - 影响：灵敏度幅值不可靠（但方向正确）
   - 补偿：Sign-SGD 只用方向，不用幅值；等权+弹性混合归因

### 13.2 短期优化（1-3 个月）

**桌面 Widget** — Jetpack Glance + Compose 实现桌面血糖显示，5-15min 自动刷新
**PDF 报告** — Android Canvas 绘制标准化 CGM 报告 (AGP, TIR, GMI, CV)
**更多 CGM 品牌** — 持续扩充通知栏白名单
**单元测试** — 目前 0 测试。优先测：IOB 计算, EDOC 灵敏度方向, DallaMan 参数个性化

### 13.3 中期优化（3-6 个月）

**反调节激素建模** — DallaMan + 胰高血糖素隔室（α 细胞感知低血糖 → cAMP/PKA → 肝糖原分解）
**脂肪/蛋白质升糖** — 饮食输入扩展为 macros（碳水+脂肪+蛋白），修正胃排空 + 延迟升糖
**ExecuTorch 迁移** — ONNX→ExecuTorch: 更小体积 (~2MB vs 8MB)，支持设备端训练
**联邦学习** — 多用户隐私保护（本地差分隐私 + 安全聚合），提升群体模型泛化

### 13.4 长期优化（6-12 个月）

**闭环胰岛素输注** — 蓝牙泵通信（Medtronic/Tandem/微泰），混合闭环系统 (HCL)
**数字孪生** — 多维指标（血糖+血脂+血压+体重+eGFR），患者特异性模拟
**前瞻性临床试验** — RCT 评估 HbA1c/TIR/严重低血糖事件/生活质量

---

## 14. 如何新增功能

### 14.1 新增 CGM 品牌

1. `CGMNotificationListener.kt` → `PACKAGE_WHITELIST` 加包名
2. 观察该品牌 App 的通知栏文本格式
3. 如 `tryExtract()` 无法解析 → 添加新的正则匹配
4. 测试：打开该品牌 App → 检查 logcat `CGMNotificationListener` 是否有解析成功日志

### 14.2 新增算法层

1. `domain/algorithm/` 创建新类
2. `SelfLearningManager.start()` 中调用
3. `SelfLearningCard` (SettingsScreen.kt) 添加 UI
4. `SelfLearningManager.getStatus()` 暴露状态

### 14.3 新增数据库表

1. `data/local/entity/` 创建 Entity (含 Room 注解)
2. `data/local/dao/` 创建 DAO (含 @Query)
3. `AppDatabase.kt` → `@Database.entities` 添加 → 升级 `version` → 添加 `Migration`
4. `AppModule.kt` → `@Provides` 提供 DAO 实例

### 14.4 新增预测模型

1. 训练 PyTorch/TF 模型
2. `torch.onnx.export()` 导出 ONNX
3. 放入 `app/src/main/assets/`
4. 创建 `XxxPredictor.kt` (参考 `TCNPredictor.kt`)
5. `PredictionViewModel.loadPrediction()` 中集成
6. 调整 BMA 权重函数

### 14.5 新增预测曲线（例如第 4 条线）

1. `PredictionUiState` 添加 `val newCurve: List<Double>`
2. `PredictionViewModel` 计算新曲线值
3. `PredictionChartView` 添加新 `Paint` + 绘制代码
4. `PredictionCurveCard` 传递新曲线

---

## 15. SharedPreferences 速查

### OnlineLearner (`online_learner_params`)

```
键名                     类型    默认值   含义
─────────────────────────────────────────────────────
fasting_baseline         Float   6.0     空腹基线 (mmol/L)
post_meal_peak           Float   9.0     餐后峰值 (mmol/L)
glucose_variability      Float   1.5     血糖变异度 (CV%)
trend_sensitivity        Float   1.0     趋势敏感度
recovery_rate            Float   0.5     恢复速率 (mmol/L/h)
meal_response            Float   2.0     餐后响应幅度
adaptive_low             Float   3.9     自适应低血糖阈值
adaptive_high            Float   10.0    自适应高血糖阈值
data_days                Float   0.0     累积数据天数
update_count             Int     0       更新次数
last_update              Long    0       最后更新时间
data_completeness        Float   0.0     数据完整度 (0.3/0.6/1.0)
hourly_0 ~ hourly_23     Float   0.0     24小时偏离值
```

### IncrementalLearner (`inc_learner_weights`)

```
w1_{i}_{j}   Float   Xavier   15×16 权重矩阵 (i:0-14, j:0-15)
b1_{j}       Float   0.0      16 偏置
w2_{i}_{j}   Float   Xavier   16×4 权重矩阵 (i:0-15, j:0-3)
b2_{j}       Float   0.0      4 偏置
last_loss    Float   0.0      最近一次训练损失
avg_loss     Float   0.0      平均损失
updates      Int     0        训练次数
```

### EDOCCorrector (`edoc_state`)

```
total_corrections       Int     0       总修正次数
corrections_today       Int     0       今日修正次数
eta_5min                Float   0.0003  5min时域学习率
eta_30min               Float   0.001   30min时域学习率
eta_60min               Float   0.003   60min时域学习率
delta_kstomach          Float   0.0     kStomach修正偏移
delta_vmaxgastric       Float   0.0     VmaxGastric修正偏移
delta_vm0               Float   0.0     Vm0修正偏移
delta_vmx               Float   0.0     VmX修正偏移
delta_hepatic           Float   0.0     hepaticBase修正偏移
delta_kp3               Float   0.0     kp3修正偏移
P_0 ~ P_5               Float   50.0    RLS协方差对角线 (6参数)
```

### 用户设置 (`settings`)

```
insulin_sensitivity     Float   1.5     胰岛素敏感因子 (mmol/L/U)
carb_ratio              Float   12.0    碳水比率 (g/U)
weight_kg               Float   65.0    体重 (kg)
target_low              Float   3.9     目标下限 (mmol/L)
target_high             Float   10.0    目标上限 (mmol/L)
alert_enabled           Boolean true    预警开关
alert_low               Float   3.9     低血糖预警阈值
alert_high              Float   13.9    高血糖预警阈值
ai_api_url              String  ""      AI API地址
ai_api_key              String  ""      AI API密钥
```

---

## 16. 术语表

| 缩写 | 全称 | 含义 |
|-----|------|------|
| CGM | Continuous Glucose Monitoring | 持续血糖监测 |
| T1DM / T2DM | Type 1/2 Diabetes Mellitus | 1型/2型糖尿病 |
| TIR | Time In Range | 目标范围内时间 (3.9-10.0 mmol/L) |
| TAR / TBR | Time Above/Below Range | 高于/低于范围时间 |
| CV | Coefficient of Variation | 血糖变异系数 (%) |
| HbA1c | Glycated Hemoglobin | 糖化血红蛋白 |
| GMI | Glucose Management Indicator | 血糖管理指标 |
| ISF | Insulin Sensitivity Factor | 胰岛素敏感因子 (mmol/L/U) |
| CR / ICR | Carbohydrate Ratio | 碳水比率 (g/U) |
| IOB | Insulin On Board | 体内残余活性胰岛素 (U) |
| MARD | Mean Absolute Relative Difference | CGM 传感器精度指标 |
| ROC | Rate of Change | 血糖变化率 (mmol/L/min) |
| TCN | Temporal Convolutional Network | 时序卷积网络 |
| BMA | Bayesian Model Averaging | 贝叶斯模型平均 |
| RLS | Recursive Least Squares | 递归最小二乘 |
| EWC | Elastic Weight Consolidation | 弹性权重巩固 |
| RK4 | Runge-Kutta 4th Order | 4阶龙格-库塔数值积分 |
| ODE | Ordinary Differential Equation | 常微分方程 |
| SGD | Stochastic Gradient Descent | 随机梯度下降 |
| MM | Michaelis-Menten | 酶/转运蛋白饱和动力学方程 |
| EWMA | Exponentially Weighted Moving Average | 指数加权移动平均 |
| EGP | Endogenous Glucose Production | 内源性葡萄糖产生 (肝脏) |
| EDOC | Error-Driven Online Correction | 预测误差驱动在线纠正 |
| SnapContext | Snapshot Context | EDOC 的 6 维上下文特征快照 |
| LOPO-CV | Leave-One-Patient-Out CV | 留一患者交叉验证 |

---

*糖盾 (TangDun) 项目组 — 2026-06-22*
