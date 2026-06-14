# 糖盾 (TangDun) — 项目进度与开发交接文档

**日期**: 2026-06-14  
**仓库**: https://github.com/jiang213658168/TangDun.git  
**APK**: `android/app/build/outputs/apk/debug/app-debug.apk` (82MB, 含ONNX模型590KB)

---

## 1. 项目概述

糖盾是一个面向糖尿病患者的智能血糖预测与健康管理 Android App。

**核心功能**:
- CGM 血糖数据接收（通知监听 → 40+品牌 / xDrip+ 广播）
- TCN + Dalla Man 七隔室生理模型 + BMA 融合预测（120分钟时域）
- 四层增量自学习（EWMA/卡尔曼/贝叶斯/304参数SGD）
- 商业级激活码系统（AES-128-CBC）
- 智能预警（9类，严重低血糖自动拨号）
- AI 对话（百度千帆 API）

## 2. 技术架构

```
┌──────────────────────────────────────────────┐
│  UI 层: Jetpack Compose + Material Design 3   │
│  Home | Prediction | Settings | AI Chat       │
├──────────────────────────────────────────────┤
│  业务层: ViewModels + 算法引擎                │
│  HomeVM | PredictionVM | 预测/学习/校准算法    │
├──────────────────────────────────────────────┤
│  数据层: Room (15表) + SharedPreferences     │
│  DAO: Glucose | Meal | Insulin | Exercise ... │
├──────────────────────────────────────────────┤
│  同步层: NotificationListener | Broadcast    │
│  CGM通知监听(40+品牌) | xDrip+兼容广播        │
└──────────────────────────────────────────────┘
```

## 3. 项目结构

```
TangDun/
├── android/                          # Android 主项目
│   ├── app/src/main/java/com/tangdun/app/
│   │   ├── MainActivity.kt           # 入口 (Hilt DI)
│   │   ├── TangDunApp.kt             # Application
│   │   ├── data/                     # 数据层
│   │   │   ├── local/
│   │   │   │   ├── AppDatabase.kt    # Room 数据库 (15表)
│   │   │   │   ├── dao/              # 数据访问对象
│   │   │   │   │   ├── GlucoseDao.kt
│   │   │   │   │   ├── MealDao.kt
│   │   │   │   │   ├── InsulinDao.kt
│   │   │   │   │   └── ...
│   │   │   │   └── entity/           # 数据实体
│   │   │   └── repository/           # 仓库层
│   │   ├── domain/
│   │   │   └── algorithm/            # ★ 核心算法引擎
│   │   │       ├── DallaManModel.kt          # ★ 七隔室生理模型 (RK4)
│   │   │       ├── BergmanModel.kt           # 三隔室模型 (备用)
│   │   │       ├── TCNPredictor.kt           # ONNX TCN 推理
│   │   │       ├── FusionPredictor.kt        # BMA 融合 (使用Bergman)
│   │   │       ├── PersonalizedPredictor.kt  # 四层自学习总控
│   │   │       ├── OnlineLearner.kt          # 统计学习 (EWMA/卡尔曼/贝叶斯)
│   │   │       ├── IncrementalLearner.kt     # SGD残差学习 (304参数)
│   │   │       ├── FeatureExtractor.kt       # 15维特征提取
│   │   │       ├── AutoParamEstimator.kt     # ISF/CR自动测算
│   │   │       ├── CGMCalibrator.kt          # EWMA指尖血校准
│   │   │       └── TrendAnalyzer.kt          # 趋势分析
│   │   ├── receiver/                 # 广播接收器
│   │   │   └── DirectGlucoseBroadcastReceiver.kt
│   │   ├── service/                  # 服务
│   │   │   ├── CGMNotificationListener.kt    # ★ 通知监听(40+品牌)
│   │   │   └── GlucoseAlarmService.kt       # 紧急预警+自动拨号
│   │   ├── ui/                       # UI 界面
│   │   │   ├── home/HomeScreen.kt + HomeViewModel.kt
│   │   │   ├── prediction/PredictionScreen.kt + PredictionViewModel.kt
│   │   │   ├── settings/SettingsScreen.kt
│   │   │   ├── splash/SplashScreen.kt       # 激活流程
│   │   │   ├── import/CsvImporter.kt
│   │   │   └── theme/Theme.kt
│   │   ├── widget/
│   │   │   └── GlucoseChartView.kt  # xDrip风格自定义图表
│   │   └── util/
│   │       ├── SettingsManager.kt    # 设置管理 (DataStore/SharedPrefs)
│   │       └── ActivationManager.kt # 激活码验证
│   └── app/src/main/res/            # 资源文件
├── docs/                            # 文档
│   ├── paper_tangdun.md             # 学术论文 (Markdown)
│   ├── 糖盾系统学术论文.docx         # 学术论文 (Word)
│   ├── graduate_thesis.md           # 毕业论文
│   ├── convert_paper.py             # Markdown → Word 转换
│   └── PROGRESS.md                  # 本文件
├── backend/                         # Python 后端 (FastAPI)
├── activation_server/               # 激活码生成器
│   └── generate_key.py              # 交互式终端
└── broadcast-test/                  # 广播测试工具
```

## 4. 核心模块状态

### 4.1 预测引擎 ✅

| 组件 | 状态 | 说明 |
|------|------|------|
| TCNPredictor | ✅ | ONNX推理, MAE 0.552, 15维特征 |
| DallaManModel | ✅ | 七隔室RK4, 体重个性化, 8状态ODE |
| BergmanModel | ✅ | 三隔室RK4, FusionPredictor仍在使用 |
| FusionPredictor | ⚠️ | 仍引用BergmanModel，未升级到DallaMan |
| PersonalizedPredictor | ⚠️ | 同上，通过FusionPredictor间接使用Bergman |
| PredictionViewModel | ✅ | 直接使用DallaManModel.predict() + TCN + 个性化 |

**注意**: PredictionViewModel 绕过了 FusionPredictor/PersonalizedPredictor，直接调用 DallaManModel。FusionPredictor 和 PersonalizedPredictor 仍使用 BergmanModel。这两个路径需要统一：要么都升级到 DallaMan，要么明确两条路径的分工。

### 4.2 数据采集 ✅

| 组件 | 状态 | 说明 |
|------|------|------|
| CGMNotificationListener | ✅ | 40+品牌通知读取，移植自xDrip+ |
| DirectGlucoseBroadcastReceiver | ✅ | xDrip+广播兼容, goAsync() |
| CsvImporter | ✅ | CSV导入，时区处理，60s去重 |

### 4.3 自学习 ✅

| 组件 | 状态 | 说明 |
|------|------|------|
| OnlineLearner | ✅ | EWMA/卡尔曼/贝叶斯/时段模式 |
| IncrementalLearner | ✅ | 15→16→4网络, SGD, 304参数, SharedPrefs持久化 |
| AutoParamEstimator | ✅ | TDD+数据驱动ISF/CR估算 |

### 4.4 商业功能 ✅

| 组件 | 状态 | 说明 |
|------|------|------|
| ActivationManager | ✅ | AES-128-CBC, Base64 URL_SAFE, 按功能限次 |
| GlucoseAlarmService | ✅ | 9类预警, 严重低血糖自动拨号 |
| SettingsManager | ✅ | DataStore+SharedPrefs, StateFlow, 体重/身高 |

### 4.5 UI ✅

| 组件 | 状态 | 说明 |
|------|------|------|
| HomeScreen | ✅ | 血糖仪表盘, xDrip风格图表 |
| PredictionScreen | ✅ | 预测曲线, BMA权重显示, 风险等级 |
| SettingsScreen | ✅ | 阈值/个人信息/数据源/AI配置 |
| SplashScreen | ✅ | 品牌→协议→激活→权限→主页 |
| GlucoseChartView | ✅ | 触摸查看详情, 目标线/网格/时间轴 |

## 5. 构建与环境

### 依赖
- **JDK 17**: `C:/Users/21365/android-tools/jdk-17.0.19+10`
- **Gradle 8.2**: `C:/Users/21365/android-tools/gradle-8.2`
- **Android SDK**: `C:/Users/21365/android-tools/android-sdk`
- **Python 3.8** (论文转换): `C:/Users/21365/miniconda3`

### 构建命令
```bash
export JAVA_HOME=/c/Users/21365/android-tools/jdk-17.0.19+10
export ANDROID_HOME=/c/Users/21365/android-tools/android-sdk
cd D:/tangdun/android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### 激活码生成
```bash
cd D:/tangdun/activation_server
python generate_key.py
```

## 6. 待办事项

### 高优先级 🔴
1. **统一预测路径**: PredictionViewModel 直接使用 DallaManModel，但 FusionPredictor/PersonalizedPredictor 仍用 BergmanModel。需要统一。
2. **TCN ONNX Android 16 兼容性**: ONNX Runtime 可能在 Android 16 上无法加载，需升级到最新版 ONNX Runtime 或迁移到 ExecuTorch。
3. **身高/体重输入UI**: SettingsManager 有 getHeightCm()/getWeightKg() 方法，但 SettingsScreen 缺少对应的输入框。

### 中优先级 🟡
4. **Dalla Man 参数调优**: 当前参数基于文献默认值，需要针对中国人群进行调优。
5. **ExecuTorch 迁移**: 将 TCN 从 ONNX 迁移到 ExecuTorch，实现真正的设备端训练（目前 SGD 学习器是独立的304参数网络，无法更新 TCN 权重）。
6. **FusionPredictor 升级**: 将 FusionPredictor 的 BergmanModel 替换为 DallaManModel。
7. **饮食图像识别**: 从食物图片估算碳水含量（百度AI API已配置）。

### 低优先级 🟢
8. **联邦学习**: 多用户数据聚合优化全局模型
9. **闭环胰岛素泵接口**: 预测结果接入胰岛素泵
10. **华为健康API**: 心率/步数特征目前恒为0（无可穿戴设备）

## 7. 已知问题与注意事项

### 技术债务
- **FusionPredictor.kt:197**: 仍构造 `BergmanModel.MealInput`，而 PredictionViewModel 使用的是 `DallaManModel.MealInput`。两条路径的类型不兼容。
- **PredictionViewModel.kt:52**: `DallaManModel()` 直接 new，不是通过 Hilt 注入（因为 DallaManModel 没有状态，可以接受）。
- **OnlineLearner.kt:330**: 卡尔曼滤波使用简化实现（P=1 固定），真正的卡尔曼应该动态更新协方差矩阵。
- **IncrementalLearner.kt**: 304参数网络权重持久化在 SharedPreferences（以字符串形式），数据量大时可能影响性能。

### 运行时注意事项
- **通知监听服务**: Android 系统可能在内存压力下杀死 CGMNotificationListener，代码已添加 `onListenerDisconnected` 重绑 + `requestRebind()`。
- **前台服务**: Android 16 限制 foregroundServiceType，当前已删除前台服务。
- **欧态健康 CGM**: 欧态使用 `setPackage("com.eveningoutpost.dexdrip")` 定向广播，需要通过通知监听接收。

## 8. 关键设计决策

### 为什么 Dalla Man 替代 Bergman？
- Bergman: 3隔室, 1个胰岛素作用通道, 商用胰岛素泵仍在使用
- Dalla Man: 7隔室, 3通道胰岛素作用, 皮下双隔室吸收, 肾排泄, FDA认可
- 升级后餐后血糖模拟更准确，个性化能力更强

### 为什么 PredictionViewModel 绕过 FusionPredictor？
- PredictionViewModel 需要在调用生理模型前完成复杂的预处理（24h进食聚合、IOB计算、体重个性化），而 FusionPredictor 的接口过于简化（只接受汇总值）。直接使用 DallaManModel 可以传递完整的时间序列输入。
- 代价：两条预测路径（ViewModel直接调用 vs FusionPredictor）需要维护一致性。

### 为什么 TCN + 生理模型融合而不是端到端？
- TCN 从数据学习模式（高精度，短期预测强）
- 生理模型提供物理约束（可解释，数据稀缺时稳定，what-if模拟）
- BMA 动态加权融合两者的优点

## 9. 下一步开发建议

1. **立即**: 统一预测路径 — 把 FusionPredictor 内部的 BergmanModel 替换为 DallaManModel
2. **本周**: 在 SettingsScreen 添加身高/体重输入框（已有 SettingsManager 方法）
3. **本月**: 研究 ExecuTorch 迁移方案或升级 ONNX Runtime 解决 Android 16 兼容性
4. **持续**: 收集用户反馈，调优 Dalla Man 参数，改进预测精度

---

**上次更新**: 2026-06-14  
**当前版本**: 1.0-dev  
**目标平台**: Android 8.0+ (API 26+), 目标 Android 16 (API 36)
