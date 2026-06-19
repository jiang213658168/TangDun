# 糖盾 (TangDun) 开发者指南

> 写于 2026-06-19，给接手的开发者。本文档假设你熟悉 Kotlin/Android 基础但不了解本项目。

---

## 1. 项目概览

糖盾是一个 Android 血糖预测与管理系统。核心能力：
- 从 40+ 品牌 CGM App 的通知栏实时采集血糖数据
- 用三层混合模型（TCN + DallaMan 生理模型 + BMA）预测未来 180 分钟血糖
- 四层在线自学习持续个性化（EDOC + 统计 + 增量残差 + 在线梯度）
- AI 自然语言对话记录饮食/胰岛素/运动

**技术栈：** Kotlin 1.9.20, Jetpack Compose 1.5.4, Room 2.6.1, ONNX Runtime 1.16.0, Hilt 2.48.1

**目标平台：** Android 8.0+ (API 26), Target SDK 34

**构建：** 在 Android Studio 中打开 `D:\tangdun\android`，Gradle 8.2。

**编译命令：**
```bash
export JAVA_HOME="你的JDK17路径"
export ANDROID_HOME="你的Android SDK路径"
cd D:\tangdun\android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────┐
│                      UI Layer (Compose)                  │
│  HomeScreen  PredictionScreen  SettingsScreen  ...       │
├─────────────────────────────────────────────────────────┤
│                  ViewModel Layer (6 ViewModels)           │
│  HomeVM  PredictionVM  RecordVM  SettingsVM  ChatVM      │
├─────────────────────────────────────────────────────────┤
│              Domain Layer (14 algorithm classes)          │
│  ┌───────────────────────────────────────────────────┐  │
│  │  预测链:                                           │  │
│  │  FeatureExtractor → TCN → ┐                       │  │
│  │  DallaMan(7室+EDOC修正) → ├→ BMA → 增量残差 → 输出 │  │
│  │  OnlineLearner个性化 ←───┘                        │  │
│  ├───────────────────────────────────────────────────┤  │
│  │  自学习链:                                         │  │
│  │  L0-EDOC(每读数) → L1-OnlineLearner(每读数)        │  │
│  │  → L2-IncrementalLearner(每12读数)                 │  │
│  └───────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────┤
│                  Data Layer (Room 15 tables)              │
│  glucose_record  meal_record  insulin_record  ...        │
├─────────────────────────────────────────────────────────┤
│              Collection Layer (Services)                  │
│  CGMNotificationListener  XlsxImporter  ForegroundService│
└─────────────────────────────────────────────────────────┘
```

### 数据流向

```
CGM传感器 → 品牌App通知栏 → CGMNotificationListener
  → GlucoseRecord → Room INSERT
    → Flow发射 ┬→ SelfLearningManager (后台学习)
                │   ├─ L0 EDOC: 查预测缓存→算误差→修正DallaMan参数
                │   ├─ L1 OnlineLearner: EWMA+Kalman→统计基线/变异度
                │   └─ L2 IncrementalLearner: 每12条→SGD→304参数残差网络
                │
                └→ PredictionViewModel → loadPrediction()
                    ├─ 1. forUser(ISF,BW,sigma,...) → DallaMan基础参数
                    ├─ 2. applyDeltas(base) → EDOC修正后参数
                    ├─ 3. DallaMan.predict(corrected_params) → 生理曲线
                    ├─ 4. OnlineLearner.applyPersonalization() → 个性化曲线
                    ├─ 5. TCN.predict() → 数据驱动曲线
                    ├─ 6. BMA(w*TCN + (1-w)*DallaMan) → 融合曲线
                    ├─ 7. IncrementalLearner.forward() → 残差叠加
                    ├─ 8. storePrediction(anchored) → 缓存供EDOC下次对比
                    └─ 9. setBaseParams(uncorrected) → EDOC记录基点
```

---

## 3. 核心算法详解

### 3.1 Dalla Man 七隔室生理模型

**文件:** `domain/algorithm/DallaManModel.kt`

8 状态 RK4 ODE 系统：
- G（血糖）、I（胰岛素）、X（外周胰岛素作用）、X_L（肝脏胰岛素作用）
- Stomach、Gut（胃肠道）、subQ1、subQ2（皮下胰岛素双池）

**ISF 驱动个性化：** `isfFactor = 1.5/ISF`，将 6 个参数映射到用户 ISF 值：
```
kStomach    = 0.050 - isfFactor × 0.005
VmaxGastric = 10.0  - isfFactor × 2.0
Vm0         = 4.5   - isfFactor × 0.5
VmX         = 0.16  - isfFactor × 0.03
hepaticBase = 1.8   + isfFactor × 0.6
kp3         = 0.045 - isfFactor × 0.007
```
胰岛素抵抗患者（ISF=0.7 → isfFactor=2.14）→ 低清糖+高肝糖输出+慢胃排空。

**五项增强（对原始 Dalla Man 模型的改进）：**
1. Michaelis-Menten 饱和动力学（替换线性 Uid）：`Uid = Vm×G×18/(Km+G×18)×BW/(Vg×18)`
2. VmaxGastric 胃排空上限：`min(kStomach×Stomach, VmaxGastric×BW)`
3. sigma 内源胰岛素分泌（T2DM 残余 β 细胞）：`dI = sigma×max(0,G-Gb)`
4. 长效胰岛素指数加权（半衰 12h，×0.4 加入 Ib）
5. 活动量自适应：7 天滚动平均运动时间 → 调节 k1 和 Vm0

### 3.2 TCN 时序卷积网络

**文件:** `domain/algorithm/TCNPredictor.kt` + `PersonalizedPredictor.kt`

架构：15维×288步 → Linear(15,64) → Dropout(0.2) → Conv1d×3(dilation=1,2,4) → GlobalAvgPool → Linear(128,4)

4 个输出参数 [a,b,c,d] 重建三次多项式：`G(t) = G₀×(1 + a·t³ + b·t² + c·t + d)`

**ONNX 部署：** PyTorch 训练 → ONNX 导出（590KB）→ ORT Mobile 推理（~15ms）

**锚定校准：** `aligned_TCN[i] = raw[i] - (raw[0] - G₀)`，消除起点断崖。

### 3.3 BMA 贝叶斯模型平均

**文件:** `domain/algorithm/FusionPredictor.kt`（实际计算在 PredictionViewModel）

`w_TCN = min(0.3 + 0.4×N/288, 0.7)`，`w_DallaMan = 1 - w_TCN`

| 数据量 N | w_TCN | 行为 |
|---------|-------|------|
| 0（新用户） | 0.3 | DallaMan 主导（生理先验安全） |
| 288（1天） | 0.7 | TCN 主导（数据充分） |
| ≥ 288 | 0.7 | TCN 70% + DallaMan 30% 兜底 |

### 3.4 OnlineLearner 统计学习层（L1）

**文件:** `domain/algorithm/OnlineLearner.kt`

从 ≤10000 条历史中学习 6 组参数：
1. **空腹基线**：0-6 点血糖 EWMA（前 10 次 α=0.3，之后 0.1）
2. **餐后峰值**：日最大值 EWMA
3. **变异度 CV%**：std/mean×100
4. **恢复速率**：高血糖下降段线性斜率
5. **自适应阈值**：P5/P95 百分位 + 当前阈值加权
6. **24h 时段模式**：24 个 δ_hour（EWMA α=0.2）

**个性化修正公式：**
```
adaptStrength = 0.7 × max(1-D/14, 0.15) × (1-C×0.6)
personalized[i] = (raw[i] + baselineAdjustment) × variabilityFactor
```
D=数据天数，C=dataCompleteness。D=0 时 strength≈0.57（强修正），D≥14 时 ≈0.04（弱修正）。

### 3.5 IncrementalLearner 增量残差学习（L2）

**文件:** `domain/algorithm/IncrementalLearner.kt`

304 参数网络：15→16（ReLU）→4，SGD（η=0.001, β=0.9, L2=1e-4），Xavier 初始化。

每 12 条新数据触发一次训练。残差修正权重：`min(updates/300, 0.4)`。

安全机制：
- 值域验证：glucose ∈ [1,30]
- 损失异常：>1000 → 自动重置权重
- DESC 排序修复：`reversed()` + `idx = size-7`
- f11/f13 归一化：[0,1] 区间（早期 999 → 梯度爆炸）

### 3.6 EDOC 即时纠错（L0）

**文件:** `domain/algorithm/EDOCCorrector.kt`（808 行，最复杂的算法类）

**核心循环（每条 CGM 读数）：**
```
1. 查预测缓存（5min/30min/60min 前做的预测）
2. 算误差 e = actual - predicted
3. 噪声过滤：|e| < MARD×G/100×(2-quality) → 跳过
4. 异常检测：|e| > 8 mmol/L → 跳过
5. 误差分类：ACF(1) 自相关 → 系统偏差/白噪声/混合
6. 上下文感知灵敏度：
   - 空腹 → kStomach/VmaxGastric 灵敏度归零
   - 无胰岛素 → VmX/kp3 灵敏度归零
   - 用实际血糖而非固定值
7. Sign-SGD 更新：delta = η×error×sign(grad)×attribution/rlsDiag
8. 参数限幅：每次≤±0.5%，每天≤±10%
9. 方向感知：连续同向→加速，频繁翻转→减速
10. RLS 协方差更新：追踪参数不确定性
```

**SnapContext（6 维上下文特征）：**
| 字段 | 来源 | 用途 |
|-----|------|------|
| currentGlucose | CGM 读数 | 灵敏度计算的真实血糖 |
| glucoseROC | trend 字段 | 区分上升/下降阶段 |
| recentCarbs | mealDao(4h) | 0=空腹→跳过胃排空参数 |
| minutesSinceMeal | mealDao | 判断空腹状态 |
| iob | insulinDao 计算 | >0.1→启用胰岛素参数 |
| minutesSinceBolus | insulinDao | 判断胰岛素作用阶段 |
| hourOfDay | Calendar | 昼夜节律（预留） |

**归因方式：** 等权(50%) + 弹性(50%)混合。等权保证每个活跃参数平等参与，弹性提供相对重要性。混合公式解决简化模型单位不一致导致的梯度幅值失衡（hepaticBase 梯度 100 倍于 Vm0）。

---

## 4. 文件结构与关键代码

```
android/app/src/main/java/com/tangdun/app/
├── TangDunApp.kt              # Application入口, init SelfLearningManager
├── MainActivity.kt             # 单Activity + NavHost
│
├── domain/algorithm/           # ★ 核心算法 (14个类)
│   ├── DallaManModel.kt        # 7室生理模型 (8状态RK4, ISF个性, 5增强)
│   ├── TCNPredictor.kt         # TCN ONNX推理 (15ms)
│   ├── PersonalizedPredictor.kt# 预测包装器
│   ├── FusionPredictor.kt      # BMA融合
│   ├── FeatureExtractor.kt     # 15维特征 (f11/f13归一化要点)
│   ├── OnlineLearner.kt        # L1统计学习 (EWMA+Kalman, 6组参数)
│   ├── IncrementalLearner.kt   # L2增量残差 (304参数SGD)
│   ├── EDOCCorrector.kt        # L0即时纠错 (808行, SignSGD+RLS+上下文感知)
│   ├── SelfLearningManager.kt  # 自学习统一入口+数据质量系统
│   ├── RealTimeGlucoseMonitor.kt # CGM质量控制 (6步管道)
│   ├── CGMCalibrator.kt        # 指尖血校准
│   ├── SmartAdvisor.kt         # 智能建议 (IOB感知)
│   ├── InsulinCalculator.kt    # 餐时胰岛素计算
│   ├── AlertEngine.kt          # 多级预警
│   └── TrendCalculator.kt      # ROC/AUC趋势
│
├── ui/                         # Compose UI
│   ├── home/HomeViewModel.kt   # 首页+导入+EDOC批量
│   ├── prediction/
│   │   ├── PredictionViewModel.kt # 预测引擎 (3线输出)
│   │   └── PredictionScreen.kt    # 预测页面 (3线曲线图)
│   ├── settings/SettingsScreen.kt # 设置页 (3层学习卡片)
│   └── ...
│
├── service/
│   ├── CGMNotificationListener.kt # 40+品牌通知栏监听
│   ├── GlucoseForegroundService.kt# 前台保活
│   ├── GlucoseAlarmService.kt    # 预警+自动拨号
│   └── XlsxImporter.kt           # xlsx解析 (中文12h制支持)
│
├── data/local/
│   ├── AppDatabase.kt            # Room DB (15表)
│   ├── dao/                      # 15个DAO接口
│   └── entity/                   # 15个Entity
│
└── widget/
    └── PredictionChartView.kt    # 自定义View (3线预测图)
```

---

## 5. 关键常量速查

### Dalla Man 参数边界
| 参数 | 最小值 | 最大值 | 典型值 | 含义 |
|-----|-------|-------|-------|------|
| kStomach | 0.020 | 0.080 | 0.045 | 胃排空率 (min⁻¹) |
| VmaxGastric | 3.0 | 15.0 | 8.0 | 最大胃排空 (mg/kg/min) |
| Vm0 | 1.5 | 8.0 | 4.0 | 基础葡萄糖利用 (mg/kg/min) |
| VmX | 0.03 | 0.25 | 0.13 | 胰岛素刺激清糖增量 |
| hepaticBase | 0.5 | 5.0 | 2.4 | 基础肝糖输出 (mg/kg/min) |
| kp3 | 0.015 | 0.070 | 0.038 | 外周胰岛素作用延迟 (min⁻¹) |

### EDOC 学习率
| 时域 | 基值 | 范围 | 含义 |
|-----|------|------|------|
| 5min | 0.0003 | [0.00009, 0.0018] | 即时响应修正 |
| 30min | 0.001 | [0.0003, 0.006] | 动态参数修正 |
| 60min | 0.003 | [0.0009, 0.018] | 基线修正 |

### EDOC 安全限幅
| 限幅 | 值 | 含义 |
|-----|-----|------|
| MAX_STEP_RATIO | 0.5%/次 | 单次最多改变 0.5% |
| MAX_DAILY_RATIO | 10%/天 | 每天累计最多改变 10% |
| NOISE_FLOOR | 0.3 mmol/L | 最小噪声门槛 |
| MAX_ERROR | 8.0 mmol/L | 超过此值视为异常 |
| SENSOR_MARD | 9% | CGM 传感器典型误差率 |

---

## 6. 预测全链条详解

### 每次预测的完整步骤（PredictionViewModel.loadPrediction）

```
输入: CGM记录, 饮食记录, 胰岛素记录, 运动记录, 用户设置

步骤1: 数据准备
  - glucoseDao.getRecent(max(histPoints, 288)).reversed()
  - mealDao.getByTimeRange(now-24h, now)
  - insulinDao.getSince(now-24h)
  - IOB计算: 速效半衰55min, 短效半衰90min
  - 长效胰岛素: 指数加权×0.4→提高Ib

步骤2: DallaMan参数生成
  - forUser(BW, fastingGlucose, ISF, basalInsulin, sigma, activityLevel)
  - GI因子: (avgGi/50).coerceIn(0.7, 1.5) → 修正kStomach
  - EDOC修正: applyDeltas(baseParams) → finalParams

步骤3: 生理预测
  - DallaMan.predict(g, iob, mealInputs, insulinInputs, params=finalParams)
  - OnlineLearner.applyPersonalization() → 个性化曲线
  - 运动效应: exerciseEffect×exp(-i/24)

步骤4: TCN预测
  - PersonalizedPredictor.predict(gh, g, bolusHist, carbHist)
  - 锚定校准: aligned = raw - (raw[0] - g)

步骤5: BMA融合
  - w = min(0.3+0.4×totalRecords/288, 0.7)
  - merged = w×alignedTcn + (1-w)×personalized

步骤6: 增量残差叠加
  - FeatureExtractor.extract(glucoseHistory, idx) → 15维特征
  - IncrementalLearner.forward(features) → 4参数残差
  - residualWeight = min(updates/300, 0.4)
  - incCurve[i] = weight×(a×t³+b×t²+c×t+d)
  - merged = merged + incCurve

步骤7: 输出
  - 锚定merged[0]=g
  - 三条曲线: physioCurve, incrementalCurve, curve
  - storePrediction(curve) → EDOC缓存
  - setBaseParams(baseParams) → EDOC基点
```

### EDOC 反馈闭环（每5分钟）

```
PredictionViewModel:
  storePrediction(curve)     ← 存预测曲线到缓存
  setBaseParams(baseParams)  ← 存未修正基点

SelfLearningManager (新读数到达):
  收集SnapContext (mealDao/insulinDao 4h窗口)
  edocCorrector.onNewReading(g, quality, baseParams, context)
    ├→ checkAndCorrect(now-5min):  查缓存→误差→修正kStomach
    ├→ checkAndCorrect(now-30min): 查缓存→误差→修正Vm0/VmX/kp3
    └→ checkAndCorrect(now-60min): 查缓存→误差→修正hepaticBase

下次预测:
  PredictionViewModel.loadPrediction()
    → edocCorrector.applyDeltas(baseParams)  ← 应用EDOC学到的修正
    → DallaMan预测使用修正后参数
    → 预测更准确 → 误差更小 → 闭环
```

---

## 7. 调试指南

### 日志过滤
```
adb logcat | grep -E "EDOC|SelfLearn|PredVM|XlsxImporter"
```

### 关键日志示例
```
EDOC: [5min] e=+1.5 混合误差 → kStomach=+0.0003, Vm0=-0.0001
EDOC: [30min] e=+2.3 系统偏差 → hepaticBase=+0.0032
SelfLearn: 增量学习@12条 | EDOC:5次 | C:0.6
XlsxImporter: sharedStrings: 45231 bytes
XlsxImporter: sheet1.xml: 128056 bytes
XlsxImporter: 解析到1783条血糖记录
XlsxImporter: 时间范围: 2026-06-11 18:53 ~ 2026-06-17 23:29
```

### 常见问题

**导入显示 0 条：**
1. 确认是欧态健康 App 导出的 .xlsx 文件
2. 检查 logcat `XlsxImporter` 日志，看解析到多少条
3. 欧态日期格式是 `2026.6.17 下午11:29`（中文 12 小时制）

**EDOC 显示"待命中"：**
- EDOC 是数据驱动的，需要 CGM 读数到达才触发
- 新用户前几条读数可能没有缓存的预测（无历史预测可对比）
- 检查 `SelfLearn` 日志确认 EDOC 是否有执行

**三条预测线不显示：**
- 生理线（蓝色虚线）始终存在
- 增量线（绿色点线）训练次数<20 时接近零线
- 检查 PredictionViewModel 日志确认预测是否正常

**增量学习不更新：**
- 需要至少 50 条 CGM 数据
- 每 12 条新读数触发一次
- 检查 `IncrementalLearner` 日志

**MAE 显示为 0：**
- 正常现象：EDOC 内部 errorHistory 为空时 MAE=0
- 需要 EDOC 至少执行一次修正后才有数据

---

## 8. 数据表结构

### glucose_record（核心表）
```sql
id INTEGER PRIMARY KEY AUTOINCREMENT
timestamp INTEGER NOT NULL    -- Unix毫秒
value REAL NOT NULL            -- mmol/L
source TEXT DEFAULT 'manual'   -- cgm/finger/manual/xlsx_import
trend TEXT                     -- rising_fast/rising/stable/falling/falling_fast
isCalibrated INTEGER DEFAULT 0
scene TEXT DEFAULT 'other'
CREATE INDEX idx_glucose_timestamp ON glucose_record(timestamp)
```

### meal_record + meal_item（一对多）
```sql
-- meal_record
id, timestamp, mealType, totalCarbs, totalCalories, totalProtein, totalFat, avgGi

-- meal_item
id, mealId(FK→meal_record), foodName, portionGrams, carbs, calories, protein, fat
```

### insulin_record
```sql
id, timestamp, insulinType, doseUnits, injectionSite, notes
-- insulinType: rapid/short/intermediate/long_acting/mixed
```

---

## 9. 已知局限与优化方向

### 模型局限
1. **无反调节激素**：不建模胰高血糖素/肾上腺素 → 无法预测 Somogyi 反弹和黎明现象（仅靠 24h 时段模式统计补偿）
2. **单隔室胃排空**：不区分固体/液体，不区分脂肪/蛋白质延迟效应
3. **TCN 训练数据**：OhioT1DM+HUPA 为西方人群 → 中国 T2DM 泛化性待验证
4. **ONNX Runtime**：Android 15+ 兼容性未验证

### 短期优化（1-3 个月）
1. **桌面 Widget**：Jetpack Glance 实现桌面血糖显示
2. **黑暗模式**：完善 Material 3 Dark Theme
3. **PDF 报告**：Android Canvas 绘制标准化 CGM 报告
4. **更多 CGM 品牌**：持续扩充 CGMNotificationListener 白名单

### 中期优化（3-6 个月）
1. **反调节激素建模**：DallaMan 模型加胰高血糖素隔室
2. **脂肪/蛋白质升糖**：饮食模型扩展宏量营养素
3. **ExecuTorch 迁移**：ONNX→ExecuTorch，减小体积+支持设备端训练
4. **联邦学习**：多用户隐私保护梯度聚合

### 长期优化（6-12 个月）
1. **闭环胰岛素输注**：蓝牙泵通信→混合闭环系统
2. **数字孪生**：多维指标患者特异性模型
3. **前瞻性临床试验**：RCT 验证临床效果

---

## 10. 如何新增功能

### 新增 CGM 品牌支持
编辑 `CGMNotificationListener.kt` 的包名白名单，检查 `tryExtract()` 是否正确解析该品牌的通知文本格式。

### 新增算法层
1. 在 `domain/algorithm/` 创建新类
2. 在 `SelfLearningManager.start()` 中调用
3. 在 `SelfLearningCard`（SettingsScreen）中添加 UI 展示
4. 在 `SelfLearningManager.getStatus()` 中暴露状态

### 新增数据库表
1. 在 `data/local/entity/` 创建 Entity
2. 在 `data/local/dao/` 创建 DAO
3. 在 `AppDatabase.kt` 注册表（增加版本号 + Migration）
4. 在 `AppModule.kt` 提供 DAO 注入

### 新增预测模型
1. 训练模型 → 导出 ONNX
2. 放到 `app/src/main/assets/`
3. 创建 Predictor 类（参考 TCNPredictor.kt）
4. 在 PredictionViewModel 中集成

---

## 11. 关键类之间的依赖关系

```
TangDunApp
  └→ SelfLearningManager.init()
       ├→ OnlineLearner(context)
       ├→ IncrementalLearner(context)
       └→ EDOCCorrector(context)
            └→ predictionCache (LinkedHashMap, 300条)
            └→ deltas (ParamDeltas, 6参数修正偏移)
            └→ P (RLS协方差, 6×6)

PredictionViewModel
  ├→ DallaManModel.physiological
  ├→ PersonalizedPredictor (TCN)
  ├→ SelfLearningManager.getOnlineLearner()
  ├→ SelfLearningManager.getIncrementalLearner()
  ├→ SelfLearningManager.getEDOCCorrector()
  │    └→ applyDeltas(baseParams) → 预测时读修正
  │    └→ storePrediction(curve)  → 预测后缓存
  ├→ FeatureExtractor
  └→ CGMCalibrator

SelfLearningManager (后台, 每条CGM读数)
  ├→ ensureBaseParams() → 预测页未开时用默认参数
  ├→ edocCorrector.onNewReading(g, quality, params, context)
  ├→ onlineLearner.learn(dao)
  └→ incrementalLearner.periodicLearn(dao)  [每12条]
```

---

## 12. SharedPreferences 键值说明

### OnlineLearner (`online_learner_params`)
```
fasting_baseline, post_meal_peak, glucose_variability
trend_sensitivity, recovery_rate, meal_response
adaptive_low, adaptive_high, data_days
update_count, last_update, data_completeness
hourly_0 ~ hourly_23  (24小时偏离值)
```

### IncrementalLearner (`inc_learner_weights`)
```
w1_{i}_{j}  (15×16 权重矩阵)
b1_{j}      (16 偏置)
w2_{i}_{j}  (16×4 权重矩阵)
b2_{j}      (4 偏置)
last_loss, avg_loss, updates
```

### EDOCCorrector (`edoc_state`)
```
total_corrections, corrections_today
eta_5min, eta_30min, eta_60min
delta_kstomach, delta_vmaxgastric, delta_vm0
delta_vmx, delta_hepatic, delta_kp3
P_0 ~ P_5  (RLS协方差对角线)
```

### 用户设置 (`settings`)
```
insulin_sensitivity, carb_ratio, weight_kg
target_low, target_high
alert_enabled, alert_low, alert_high
ai_api_url, ai_api_key, ai_model
```

---

*最后更新: 2026-06-19 | 糖盾项目组*
