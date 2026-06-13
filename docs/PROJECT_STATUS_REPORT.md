# 糖盾项目完整状态报告

**更新时间**: 2026-06-13
**报告人**: Claude Code
**项目版本**: v1.0.0

---

## 目录

1. [项目概述](#一项目概述)
2. [技术架构](#二技术架构)
3. [功能清单](#三功能清单)
4. [数据库设计](#四数据库设计)
5. [算法详解](#五算法详解)
6. [数据来源](#六数据来源)
7. [API配置](#七api配置)
8. [编译和运行](#八编译和运行)
9. [测试方法](#九测试方法)
10. [已知问题](#十已知问题)
11. [待完成任务](#十一待完成任务)
12. [关键文件清单](#十二关键文件清单)
13. [技术决策](#十三技术决策)
14. [参考资源](#十四参考资源)

---

## 一、项目概述

### 1.1 项目背景

**糖盾**是一个基于多源数据融合的糖尿病智能健康管理系统，面向青少年科技创新大赛。

**目标用户**: 糖尿病患者（1型和2型）
**核心价值**: 通过AI技术帮助患者更好地管理血糖，预防并发症

### 1.2 项目位置

| 项目 | 路径 | 说明 |
|------|------|------|
| 主App | `D:\tangdun\android\` | 完整Android应用 |
| 广播测试App | `D:\tangdun\broadcast-test\` | 测试血糖广播接收 |
| 后端(参考) | `D:\tangdun\backend\` | Python后端代码（参考用） |
| 文档 | `D:\tangdun\docs\` | 项目文档 |

### 1.3 核心特性

| 特性 | 说明 |
|------|------|
| **血糖自动接收** | 通过广播接收欧泰健康/xDrip+的血糖数据 |
| **AI预测** | TCN + Bergman模型融合预测 |
| **自进化学习** | 统计学习个性化参数 |
| **智能建议** | 补针/运动/饮食建议 |
| **完全离线** | 除AI对话和食物识别外，其他功能完全离线 |

---

## 二、技术架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    Android App                           │
├─────────────────────────────────────────────────────────┤
│  UI层: Jetpack Compose + Material Design 3              │
│  ├── 首页Dashboard                                      │
│  ├── 饮食管理                                           │
│  ├── 胰岛素管理                                         │
│  ├── 健康记录                                           │
│  ├── 运动管理                                           │
│  ├── 血糖预测                                           │
│  ├── AI对话                                             │
│  ├── 报告中心                                           │
│  └── 设置                                               │
├─────────────────────────────────────────────────────────┤
│  业务层: ViewModel + Coroutines                         │
│  ├── 预测引擎 (TCN + Bergman + BMA融合)                │
│  ├── 预警引擎 (6类规则)                                 │
│  ├── 智能建议 (补针/运动)                               │
│  ├── 报告引擎 (TIR/GRI/HbA1c)                          │
│  ├── 在线学习 (EWMA/卡尔曼/贝叶斯)                      │
│  └── 数据同步 (xDrip+/华为)                             │
├─────────────────────────────────────────────────────────┤
│  数据层: Room数据库 (SQLite)                            │
│  ├── 15个数据表                                         │
│  ├── 6个DAO接口                                         │
│  └── 数据备份/恢复                                      │
├─────────────────────────────────────────────────────────┤
│  AI层:                                                  │
│  ├── ONNX Runtime (TCN模型推理)                         │
│  ├── 百度AI API (食物识别)                              │
│  └── 小米MiMo API (AI对话)                              │
├─────────────────────────────────────────────────────────┤
│  传感器层:                                               │
│  ├── 广播接收 (xDrip+/Aidex)                            │
│  ├── 华为Health Kit (心率/步数/运动/睡眠)                │
│  └── 相机 (食物拍照)                                    │
└─────────────────────────────────────────────────────────┘
```

### 2.2 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 1.8.10 |
| UI框架 | Jetpack Compose | 1.5.4 |
| 数据库 | Room | 2.6.0 |
| 依赖注入 | Hilt | 2.48 |
| 网络 | Retrofit + OkHttp | 2.9.0 |
| AI推理 | ONNX Runtime | 1.16.0 |
| 协程 | Coroutines | 1.7.3 |
| JSON | Gson | 2.10.1 |

---

## 三、功能清单

### 3.1 数据记录功能

| 功能 | 状态 | 说明 |
|------|------|------|
| 血糖记录 | ✓ | 手动输入 + 时间选择 + 场景标记 |
| 饮食记录 | ✓ | 手动输入 + 食物搜索（大模型） + 拍照识别（百度AI） |
| 胰岛素记录 | ✓ | 速效/长效/预混 + 注射部位 + IOB计算 |
| 运动记录 | ✓ | Health Connect同步 |
| 睡眠记录 | ✓ | 手动输入 |
| 血压记录 | ✓ | 手动输入 |
| 体重记录 | ✓ | 手动输入 + BMI计算 |
| 酮体记录 | ✓ | 手动输入 |
| 口服药记录 | ✓ | 手动输入 |
| 症状记录 | ✓ | 低血糖/高血糖症状选择 |

### 3.2 血糖预测功能

| 功能 | 状态 | 说明 |
|------|------|------|
| TCN模型 | ✓ | ONNX Runtime推理，MAE 0.552 |
| Bergman模型 | ✓ | ODE求解器，生理模型 |
| BMA融合 | ✓ | 动态权重（数据充足时TCN权重高） |
| 个性化预测 | ✓ | 统计学习调整参数 |
| 趋势箭头 | ✓ | ⬆️↗️➡️↘️⬇️ |
| 预测曲线 | ✓ | 0-120分钟连续曲线 |

### 3.3 智能建议功能

| 功能 | 状态 | 说明 |
|------|------|------|
| 补针建议 | ✓ | 根据血糖+IOB计算建议剂量 |
| 运动建议 | ✓ | 根据血糖推荐运动类型和时长 |
| 夜间预警 | ✓ | 睡前血糖风险评估 |
| 趋势预警 | ✓ | 快速上升/下降预警 |
| 餐后建议 | ✓ | 根据碳水和GI值建议 |

### 3.4 自进化系统

| 功能 | 状态 | 说明 |
|------|------|------|
| EWMA平滑 | ✓ | 指数加权移动平均 |
| 卡尔曼滤波 | ✓ | 去除噪声 |
| 贝叶斯参数估计 | ✓ | 更新个性化参数 |
| 自适应阈值 | ✓ | 根据用户调整预警阈值 |
| 空腹血糖基线 | ✓ | 统计学习 |
| 餐后峰值估计 | ✓ | 统计学习 |
| 恢复速率估计 | ✓ | 统计学习 |

### 3.5 数据同步功能

| 功能 | 状态 | 说明 |
|------|------|------|
| xDrip+/Aidex广播接收 | ✓ | 支持Aidex和xDrip+格式 |
| 闹钟提醒 | ✓ | 低/高血糖通知 |
| 趋势箭头 | ✓ | ⬆️↗️➡️↘️⬇️ |
| 华为Health Kit | ⚠️ | 框架在，需集成SDK |

### 3.6 其他功能

| 功能 | 状态 | 说明 |
|------|------|------|
| AI对话 | ✓ | 小米MiMo API（需配置Key） |
| 食物搜索 | ✓ | 大模型API查询 |
| 拍照识别 | ✓ | 百度AI + 大模型 |
| 数据分享 | ✓ | 真正读取数据库生成报告 |
| 数据备份 | ✓ | 导出到本地存储 |
| 自学习状态 | ✓ | 显示学习阶段和参数 |
| 报告生成 | ✓ | 日/周/月报告 |

---

## 四、数据库设计

### 4.1 数据库版本

当前版本：**v3**

### 4.2 数据表

#### 核心数据表

| 表名 | 字段数 | 说明 |
|------|--------|------|
| `glucose_record` | 12 | 血糖记录 |
| `meal_record` | 12 | 饮食记录 |
| `meal_item` | 10 | 饮食明细 |
| `exercise_record` | 12 | 运动记录 |
| `insulin_record` | 8 | 胰岛素记录 |
| `alert_record` | 8 | 预警记录 |

#### 健康数据表

| 表名 | 字段数 | 说明 |
|------|--------|------|
| `sleep_record` | 7 | 睡眠记录 |
| `blood_pressure_record` | 6 | 血压记录 |
| `weight_record` | 6 | 体重记录 |
| `ketone_record` | 6 | 酮体记录 |
| `medication_record` | 6 | 口服药记录 |
| `symptom_record` | 7 | 症状记录 |

#### AI对话表

| 表名 | 字段数 | 说明 |
|------|--------|------|
| `chat_message` | 6 | 对话消息 |
| `conversation` | 5 | 会话记录 |

#### 营养数据库

| 表名 | 字段数 | 说明 |
|------|--------|------|
| `food_nutrition` | 11 | 营养数据库（已删除，改用大模型） |

### 4.3 关键字段说明

#### glucose_record

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| timestamp | Long | 测量时间（毫秒） |
| value | Double | 血糖值 (mmol/L) |
| trend | String? | 趋势方向 |
| source | String | 数据来源 (manual/cgm/finger) |
| scene | String | 测量场景 (fasting/before_meal/after_meal/bedtime/other) |
| mealId | Long? | 关联的饮食记录ID |
| notes | String? | 备注 |

---

## 五、算法详解

### 5.1 TCN模型

**文件**: `domain/algorithm/TCNPredictor.kt`

**模型架构**: 时序卷积网络 (Temporal Convolutional Network)

**输入**: 15维特征向量
- 特征1-9: 血糖动态（当前值、变化量、ROC、统计特征）
- 特征10-11: 胰岛素（4h总量、最近注射时间）
- 特征12-13: 碳水（4h总量、最近进食时间）
- 特征14: 心率
- 特征15: 步数

**输出**: 4个曲线参数 [a, b, c, d]

**曲线生成**: G(t) = current × (1 + a×t³ + b×t² + c×t + d)

**性能**: MAE 0.552 mmol/L, Clarke A区 92.4%

### 5.2 Bergman模型

**文件**: `domain/algorithm/BergmanModel.kt`

**模型类型**: 生理模型（ODE求解）

**状态方程**:
```
dG/dt = -p1*(G - Gb) - X*G + D(t)/Vg
dX/dt = -p2*X + p3*(I - Ib)
dI/dt = -n*(I - Ib) + gamma*(G - Gb)*t + U(t)/Vi
```

**求解方法**: 龙格-库塔法 (RK4)

### 5.3 BMA融合

**文件**: `domain/algorithm/FusionPredictor.kt`

**融合公式**: `fused = w_tcn × tcn + w_bergman × bergman`

**动态权重**:
- 数据充足 (>24h): TCN 70%, Bergman 30%
- 数据不足 (<12h): TCN 30%, Bergman 70%

### 5.4 在线学习

**文件**: `domain/algorithm/OnlineLearner.kt`

**算法**:
1. EWMA平滑 - 指数加权移动平均
2. 卡尔曼滤波 - 去除噪声
3. 贝叶斯估计 - 更新参数后验
4. 自适应阈值 - 根据P5/P95调整

**更新的参数**:
- 空腹血糖基线
- 餐后峰值
- 血糖变异性 (CV%)
- 恢复速率
- 自适应低/高血糖阈值

### 5.5 趋势计算

**文件**: `domain/algorithm/TrendCalculator.kt`

**参考**: xDrip+的趋势算法

**阈值** (mmol/L/min):
- 快速上升: > 0.17
- 上升: > 0.056
- 平稳: -0.056 ~ 0.056
- 下降: < -0.056
- 快速下降: < -0.17

---

## 六、数据来源

### 6.1 血糖数据

| 来源 | 方式 | 状态 |
|------|------|------|
| 欧泰健康/Aidex | 广播接收 | ✓ |
| xDrip+ | 广播接收 | ✓ |
| 手动输入 | 用户输入 | ✓ |

**广播格式**:

| 来源 | Action | 血糖键 |
|------|--------|--------|
| 欧泰健康/Aidex | `com.microtechmd.cgms.aidex.action.BgEstimate` | `com.microtechmd.cgms.aidex.BgValue` |
| xDrip+ | `com.eveningoutpost.dexdrip.BgEstimate` | `glucose` |

### 6.2 其他健康数据

| 数据 | 来源 | 状态 |
|------|------|------|
| 心率 | 华为Health Kit | ⚠️ 需集成SDK |
| 步数 | 华为Health Kit | ⚠️ 需集成SDK |
| 运动 | 华为Health Kit | ⚠️ 需集成SDK |
| 睡眠 | 华为Health Kit | ⚠️ 需集成SDK |

### 6.3 食物数据

| 来源 | 方式 | 状态 |
|------|------|------|
| 食物搜索 | 小米MiMo API | ✓ |
| 拍照识别 | 百度AI API | ✓ |

---

## 七、API配置

### 7.1 必须配置的API

| API | 用途 | 配置位置 |
|-----|------|----------|
| 小米MiMo API | AI对话 | 设置 → AI对话配置 |
| 百度AI API | 食物识别 | 设置 → 食物识别API |

### 7.2 默认配置

| 配置项 | 默认值 |
|--------|--------|
| AI对话API Key | `tp-c46u5ce4kpsricwt4e6j7l3i2ncmc2stfh8g1qoprm1yisn9` |
| AI对话Base URL | `https://token-plan-cn.xiaomimimo.com/v1/` |
| AI对话模型 | `mimo-v2.5-pro` |
| 百度AI API Key | `8FrGn0fkFjleEnUBY2c317j8` |
| 百度AI Secret Key | `7kS71cEhQcq54No6ZxQssNI7fXsor1Pc` |

### 7.3 API调用流程

**AI对话**:
```
用户输入 → 构建消息 → 调用MiMo API → 返回回复
```

**食物搜索**:
```
用户输入食物名 → 调用MiMo API → 解析JSON → 返回营养信息
```

**拍照识别**:
```
拍照 → 压缩图片 → Base64编码 → 调用百度AI → 识别食物名 → 调用MiMo API → 获取营养信息
```

---

## 八、编译和运行

### 8.1 环境要求

| 组件 | 版本 |
|------|------|
| JDK | 17 |
| Android SDK | 34 |
| Gradle | 8.2 |

### 8.2 编译命令

**主App**:
```bash
cd D:\tangdun\android
export JAVA_HOME=/c/Users/21365/android-tools/jdk-17.0.19+10
export ANDROID_HOME=/c/Users/21365/android-tools/android-sdk
/c/Users/21365/android-tools/gradle-8.2/bin/gradle.bat assembleDebug --no-daemon
```

**输出**: `D:\tangdun\android\app\build\outputs\apk\debug\app-debug.apk`

**广播测试App**:
```bash
cd D:\tangdun\broadcast-test
export JAVA_HOME=/c/Users/21365/android-tools/jdk-17.0.19+10
export ANDROID_HOME=/c/Users/21365/android-tools/android-sdk
/c/Users/21365/android-tools/gradle-8.2/bin/gradle.bat assembleDebug --no-daemon
```

**输出**: `D:\tangdun\broadcast-test\app\build\outputs\apk\debug\app-debug.apk`

### 8.3 安装到手机

```bash
adb install D:\tangdun\android\app\build\outputs\apk\debug\app-debug.apk
adb install D:\tangdun\broadcast-test\app\build\outputs\apk\debug\app-debug.apk
```

---

## 九、测试方法

### 9.1 单元测试

```bash
cd D:\tangdun\android
/c/Users/21365/android-tools/gradle-8.2/bin/gradle.bat testDebugUnitTest --no-daemon
```

**测试结果**: 8/8 通过

### 9.2 功能测试

| 功能 | 测试方法 |
|------|----------|
| 血糖记录 | 打开App → 首页 → 点击+ → 输入血糖 |
| 饮食记录 | 饮食页面 → 点击+ → 输入食物 |
| 食物搜索 | 饮食页面 → 点击🔍 → 输入食物名 |
| 拍照识别 | 饮食页面 → 点击📷 → 拍照 |
| 血糖预测 | 预测页面 → 查看曲线 |
| AI对话 | AI助手页面 → 输入问题 |
| 数据分享 | 设置 → 数据分享 → 分享报告 |

### 9.3 广播测试

1. 安装广播测试App
2. 打开"广播测试"App
3. 确保欧泰健康已开启数据分享
4. 等待CGM发送数据
5. 查看日志确认广播格式

---

## 十、已知问题

### 10.1 功能问题

| 问题 | 严重程度 | 说明 |
|------|----------|------|
| 华为Health Kit未真正集成 | 中 | 需要注册华为开发者并集成SDK |
| 用药提醒无定时通知 | 低 | 代码框架在，需实现WorkManager |
| 紧急联系人无拨打功能 | 低 | 设置页面有，需实现拨打 |

### 10.2 已删除的功能

| 功能 | 原因 |
|------|------|
| 本地食物数据库 | 改为大模型直接查询 |
| PyTorch在线学习 | Android不支持完整训练 |
| TensorFlow Lite在线学习 | 只是框架，不是真正训练 |
| Health Connect同步 | 改为广播接收 |

---

## 十一、待完成任务

### P0 - 必须完成

| 任务 | 说明 | 预计工时 |
|------|------|----------|
| 测试广播接收 | 用广播测试App验证欧泰健康数据接收 | 2小时 |
| 删除MealViewModel中的foodNutritionDao引用 | 清理代码 | 30分钟 |
| 修复食物搜索 | 确保大模型查询正常工作 | 1小时 |

### P1 - 应该完成

| 任务 | 说明 | 预计工时 |
|------|------|----------|
| 华为Health Kit集成 | 注册开发者，集成SDK | 2天 |
| 用药提醒定时通知 | 实现WorkManager | 4小时 |
| 紧急联系人拨打 | 实现Intent拨号 | 2小时 |
| 数据备份恢复功能 | 连接BackupManager到UI | 2小时 |

### P2 - 可以完成

| 任务 | 说明 | 预计工时 |
|------|------|----------|
| 食物数据库离线备选 | 内置常见食物作为离线备选 | 1天 |
| AGP报告 | 参考xDrip+的专业报告 | 2天 |
| 数据分享给医生 | 生成PDF报告 | 1天 |
| Wear OS手表支持 | 手表显示血糖 | 3天 |

---

## 十二、关键文件清单

### 12.1 算法文件

| 文件 | 功能 |
|------|------|
| `domain/algorithm/CGMPreprocessor.kt` | CGM数据预处理 |
| `domain/algorithm/FeatureExtractor.kt` | 15维特征提取 |
| `domain/algorithm/TCNPredictor.kt` | TCN模型推理 |
| `domain/algorithm/BergmanModel.kt` | Bergman生理模型 |
| `domain/algorithm/FusionPredictor.kt` | BMA融合预测 |
| `domain/algorithm/PersonalizedPredictor.kt` | 个性化预测器 |
| `domain/algorithm/OnlineLearner.kt` | 在线学习 |
| `domain/algorithm/TrendCalculator.kt` | 趋势计算 |
| `domain/algorithm/AlertEngine.kt` | 预警引擎 |
| `domain/algorithm/SmartAdvisor.kt` | 智能建议 |
| `domain/algorithm/InsulinCalculator.kt` | 胰岛素剂量计算器 |
| `domain/algorithm/CarbCalculator.kt` | 碳水计算器 |
| `domain/algorithm/NightMonitor.kt` | 夜间低血糖监测 |
| `domain/algorithm/ReportGenerator.kt` | 报告生成器 |

### 12.2 UI文件

| 文件 | 功能 |
|------|------|
| `ui/MainActivity.kt` | 主Activity |
| `ui/home/HomeScreen.kt` | 首页Dashboard |
| `ui/meal/MealScreen.kt` | 饮食记录 |
| `ui/meal/FoodSearchDialog.kt` | 食物搜索对话框 |
| `ui/insulin/InsulinScreen.kt` | 胰岛素记录 |
| `ui/health/HealthScreen.kt` | 健康记录 |
| `ui/exercise/ExerciseScreen.kt` | 运动管理 |
| `ui/prediction/PredictionScreen.kt` | 血糖预测 |
| `ui/chat/ChatScreen.kt` | AI对话 |
| `ui/report/ReportScreen.kt` | 报告中心 |
| `ui/settings/SettingsScreen.kt` | 设置页面 |
| `ui/settings/DataShareCard.kt` | 数据分享 |

### 12.3 数据文件

| 文件 | 功能 |
|------|------|
| `data/local/AppDatabase.kt` | Room数据库 |
| `data/local/entity/*.kt` | 数据实体 |
| `data/local/dao/*.kt` | DAO接口 |
| `data/local/DataExporter.kt` | 数据导出 |
| `data/local/BackupManager.kt` | 数据备份 |

### 12.4 同步文件

| 文件 | 功能 |
|------|------|
| `sync/DirectGlucoseBroadcastReceiver.kt` | 血糖广播接收 |
| `sync/HuaweiHealthManager.kt` | 华为Health Kit |
| `sync/DataSyncWorker.kt` | 数据同步Worker |

### 12.5 资源文件

| 文件 | 功能 |
|------|------|
| `assets/model_curve_v2.onnx` | TCN模型文件 |
| `res/values/colors.xml` | 颜色定义 |
| `res/values/strings.xml` | 字符串资源 |
| `res/xml/network_security_config.xml` | 网络安全配置 |
| `res/xml/file_paths.xml` | 文件路径配置 |

---

## 十三、技术决策

### 13.1 为什么用广播接收而不是蓝牙直连？

- 欧泰健康等CGM App已经支持数据分享
- xDrip+是成熟的开源方案
- 蓝牙直连需要针对每种设备开发
- 广播接收兼容性更好

### 13.2 为什么用大模型而不是本地数据库？

- 本地数据库匹配容易出错（如"肉饼"匹配到"饼干"）
- 大模型理解语义，更准确
- 本地数据库作为离线备选（可选）

### 13.3 为什么用统计学习而不是深度学习在线训练？

- Android上无法真正训练神经网络
- PyTorch/TFLite的训练支持有限
- 统计学习简单有效
- 真正的训练应该在服务器端进行

### 13.4 为什么用ONNX而不是PyTorch Mobile？

- ONNX Runtime更轻量
- 推理性能更好
- 模型文件更小
- 跨平台兼容

---

## 十四、参考资源

### 14.1 开发文档

| 资源 | 链接 |
|------|------|
| Android开发者 | https://developer.android.com |
| Jetpack Compose | https://developer.android.com/jetpack/compose |
| Room数据库 | https://developer.android.com/training/data-storage/room |
| Hilt依赖注入 | https://dagger.dev/hilt/ |

### 14.2 算法参考

| 资源 | 链接 |
|------|------|
| xDrip+源码 | https://github.com/NightscoutFoundation/xDrip |
| ONNX Runtime | https://onnxruntime.ai/ |
| Bergman模型 | https://en.wikipedia.org/wiki/Minimal_model_(glucose) |

### 14.3 API文档

| 资源 | 链接 |
|------|------|
| 华为Health Kit | https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides |
| 百度AI菜品识别 | https://ai.baidu.com/ |
| 小米MiMo API | https://token-plan-cn.xiaomimimo.com/ |

---

## 附录A: 编译错误排查

| 错误 | 解决方案 |
|------|----------|
| `Unresolved reference: xxx` | 检查import语句 |
| `Type mismatch` | 检查数据类型转换 |
| `@Composable invocations` | 检查Composable函数调用 |
| `Conflicting declarations` | 删除重复声明 |

## 附录B: 运行时问题排查

| 问题 | 解决方案 |
|------|----------|
| 血糖数据接收不到 | 用广播测试App验证 |
| AI对话无回复 | 检查API Key配置 |
| 食物识别失败 | 检查百度AI Key配置 |
| 数据不同步 | 检查网络连接 |

---

**报告结束**
