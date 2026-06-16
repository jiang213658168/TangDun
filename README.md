# 糖盾 (TangDun) — AI糖尿病智能血糖预测与健康管理

[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.20-blue)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-1.5.4-purple)](https://developer.android.com/jetpack/compose)
[![ONNX](https://img.shields.io/badge/ONNX%20Runtime-1.16.0-orange)](https://onnxruntime.ai)

**AI驱动的糖尿病血糖预测与智能管理助手 | 支持40+品牌动态血糖仪 | 120-180分钟血糖预测**

---

## ✨ 核心特性

### 🧠 AI血糖预测
| 引擎 | 原理 | 精度 |
|------|------|------|
| **TCN时序卷积网络** | 15维特征深度学习，ONNX本地推理 | MAE 0.552 mmol/L |
| **Dalla Man七隔室生理模型** | FDA商用闭环级，8状态RK4积分，体重个性化 | 生理约束 |
| **BMA贝叶斯融合** | 数据充足→TCN权重高，数据不足→生理模型稳定 | 动态自适应 |

### 📡 血糖数据接入
- **通知栏智能读取**: 40+品牌CGM（欧态/Dexcom/Libre/美敦力/微泰/三诺等）
- **xDrip+广播**: 兼容com.eveningoutpost.dexdrip.BgEstimate
- **欧态xlsx导入**: 直接解析Excel文件导入历史数据
- **手动录入**: 指尖血糖仪/饮食/胰岛素/运动

### 🔄 自进化学习
```
使用第1天 → 通用模型 (MAE 0.552)
使用第1周 → 学习空腹基线、餐后峰值、时段模式
使用第1月 → 增量残差学习生效，预测更准
使用第3月 → 接近完全个性化
```
- **统计学习**: EWMA + 卡尔曼滤波 + 贝叶斯后验 + 24时段模式
- **增量学习**: 15→16→4网络 (304参数)，SGD在线训练，每~1h更新
- **数据质量分层**: 纯血糖/部分/完整 → 自适应权重

### 🔔 智能预警
- 严重低血糖 (<3.0 mmol/L) → 系统闹钟 + 自动拨打紧急联系人 (30min冷却)
- 低血糖/高血糖/严重高血糖
- 预测性预警: 不等血糖越线，提前30分钟提醒
- 快速上升/下降 (ROC检测)
- 数据缺失 (>30分钟)

### 💬 AI健康助手
- 糖尿病健康咨询 (兼容OpenAI API)
- **自然语言记录**: "我吃了米饭200g" → 自动记录饮食
- 拍照识食: 百度AI菜品识别 → 自动查询营养信息

### 📊 专业功能
- **指尖校准**: 一次即生效，自适应EWMA平滑
- **预测曲线**: 180分钟预测 + 历史对比 + 时间范围选择 (1h/3h/6h/12h/24h)
- **日期回看**: 左右翻看任意历史日期数据
- **后台持久运行**: 前台服务 + START_STICKY + 定时唤醒 (xDrip+模式)
- **通知栏曲线**: 下拉通知栏直接看血糖趋势图
- **激活码系统**: AES-128-CBC加密，按功能维度限次
- **远程监护(糖剑)**: ESP32+MQTT云+多对多绑定 (设计方案)

## 🏗 架构

```
┌──────────────────────────────────────────────────┐
│                  Android App                     │
│         Jetpack Compose + Material Design 3      │
├──────────────────────────────────────────────────┤
│  UI: 首页/预测/记录/报告/远程/设置 + AI对话       │
│  ViewModel: MVVM + Hilt DI (6个ViewModel)       │
│  Domain: TCN + DallaMan(7室RK4) + BMA融合       │
│          + OnlineLearner + IncrementalLearner   │
│          + SelfLearningManager + SmartAdvisor   │
│  Data: Room DB (15表) + SharedPreferences       │
│  Sync: NotificationListener + BroadcastReceiver │
│        + ForegroundService + WorkManager        │
└──────────────────────────────────────────────────┘
```

## 🚀 快速开始

### 环境
- JDK 17, Android SDK 34, Gradle 8.2

### 编译
```bash
cd android
export JAVA_HOME=<jdk17路径>
export ANDROID_HOME=<sdk路径>
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### 配置CGM数据源
1. **通知监听**: 设置→通知使用权限→糖盾→开启
2. **xDrip+**: 设置→Inter-app settings→Broadcast locally→Identify receiver: com.tangdun.app
3. **xlsx导入**: 首页→数据源卡片→导入xlsx→选择欧态导出文件

## 📁 项目结构

```
tangdun/
├── android/app/src/main/java/com/tangdun/app/
│   ├── api/              # Retrofit网络层
│   ├── data/local/       # Room数据库 (实体/DAO/转换器)
│   ├── data/remote/      # 远程API (AI对话/食物识别/百度AI)
│   ├── di/               # Hilt依赖注入
│   ├── domain/algorithm/ # ★ 核心算法
│   │   ├── DallaManModel.kt          # 七隔室生理模型(RK4)
│   │   ├── TCNPredictor.kt           # ONNX TCN推理
│   │   ├── FusionPredictor.kt        # BMA融合
│   │   ├── PersonalizedPredictor.kt  # 四层自学习总控
│   │   ├── OnlineLearner.kt          # 统计学习(EWMA/卡尔曼/贝叶斯)
│   │   ├── IncrementalLearner.kt     # SGD残差学习(304参数)
│   │   ├── SelfLearningManager.kt    # 自学习统一入口
│   │   ├── FeatureExtractor.kt       # 15维特征提取
│   │   ├── RealTimeGlucoseMonitor.kt # 实时监测引擎
│   │   ├── SmartAdvisor.kt           # 智能建议
│   │   ├── AlertEngine.kt            # 预警引擎
│   │   ├── AutoParamEstimator.kt     # ISF/CR自动测算
│   │   └── CGMCalibrator.kt          # EWMA指尖校准
│   ├── service/          # 后台服务 (前台服务/告警/导入)
│   ├── sync/             # 数据同步 (通知监听/广播/xDrip)
│   ├── ui/               # UI层 (首页/预测/记录/报告/远程/设置/对话)
│   ├── util/             # 工具类 (设置/激活/调试导出)
│   └── widget/           # 自定义View (血糖图表/预测图表/通知图表)
├── activation_server/    # 激活码生成器 (Python)
├── docs/                 # 文档 (论文/使用说明/广告/设计方案)
├── scripts/              # 辅助脚本
└── broadcast-test/       # 广播测试工具
```

## 📖 文档

| 文档 | 说明 |
|------|------|
| [使用说明书](docs/user_guide.md) | 完整使用指南 (安装/校准/预测/AI/常见问题) |
| [产品广告](docs/ad_copy.md) | 营销文案 |
| [技术论文](docs/paper_tangdun.md) | 学术论文 (Markdown + .docx) |
| [项目进度](docs/PROGRESS.md) | 开发交接文档 (2026-06-14) |
| [糖剑设计](docs/tangjian_design.md) | 远程监护系统技术方案 |

## 📊 DallaMan模型参数 (中国T2DM个性化)

| 参数 | 说明 | 个性化来源 |
|------|------|-----------|
| Gb | 基础血糖 | OnlineLearner.fastingBaseline |
| Ib | 基础胰岛素 | 8.0 + 长效剂量贡献 |
| sigma | 内源性分泌 | 3.0×2.0/ISF (动态) |
| k1 | 非胰岛素利用 | 活动量自适应 (0.035-0.080) |
| Vm0/VmX | 葡萄糖利用 | ISF+活动量自适应 |
| kStomach | 胃排空 | ISF+GI自适应 (0.030-0.055) |
| VmaxGastric | 胃排空上限 | ISF自适应 (5-12 mg/kg/min) |
| kp3/kp2 | 胰岛素作用 | ISF自适应 |

## ⚠️ 免责声明

本应用仅供健康管理参考，不构成医疗诊断或治疗建议。血糖预测存在误差 (MAE约0.55 mmol/L)，任何医疗决策请咨询专业医生。

## 📄 许可证

MIT License © 2026 TangDun
