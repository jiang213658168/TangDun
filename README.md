# 糖盾 (TangDun) — 糖尿病智能健康管理系统

[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.20-blue)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-1.5.4-purple)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

基于多源数据融合的糖尿病智能健康管理系统

---

## ✨ 核心特性

| 功能 | 说明 |
|------|------|
| 🔄 **血糖自动接收** | 通过 xDrip+ 本地广播接收 CGM 血糖数据 |
| 🧠 **AI 预测** | TCN 时序卷积网络 (ONNX, MAE 0.552) + Bergman 生理模型 + BMA 融合 |
| 📈 **自进化学习** | EWMA 平滑 + 卡尔曼滤波 + 贝叶斯参数估计 + 时段模式 |
| 🔬 **增量训练** | 304 参数残差网络 SGD 在线学习 |
| 🏃 **智能建议** | 补针/运动/饮食建议 + 夜间低血糖监测 |
| 📊 **专业报告** | TIR/GRI/HbA1c + 日/周/月报告 |
| 💬 **AI 对话** | 糖尿病健康咨询 (OpenAI 兼容接口) |
| 📷 **食物识别** | 百度 AI 菜品识别 + 大模型营养查询 |

## 🏗 架构

```
┌──────────────────────────────────────────┐
│               Android App                │
│  Jetpack Compose + Material Design 3     │
├──────────────────────────────────────────┤
│  UI: 9 页面 (首页/饮食/胰岛素/预测/AI...) │
│  ViewModel: MVVM + Hilt DI              │
│  Domain: TCN + Bergman + OnlineLearner  │
│           + IncrementalLearner          │
│  Data: Room DB (15 表) + SharedPrefs    │
│  Sync: xDrip+ 广播 + WorkManager        │
└──────────────────────────────────────────┘
```

## 🚀 快速开始

### 环境要求
- JDK 17
- Android SDK 34
- Gradle 8.2

### 编译

```bash
cd android
export JAVA_HOME=<jdk17路径>
export ANDROID_HOME=<sdk路径>
gradle assembleDebug
```

### 配置 xDrip+

在 xDrip+ 中设置 Inter-app settings：
- ✅ Broadcast locally
- Identify receiver → `com.tangdun.app`

## 📁 项目结构

```
tangdun/
├── android/          # Android 主应用 (Kotlin + Compose)
│   └── app/src/main/java/com/tangdun/app/
│       ├── api/           # Retrofit 网络层
│       ├── data/          # Room 数据库 (实体/DAO/工具)
│       ├── di/            # Hilt 依赖注入
│       ├── domain/        # 核心算法 (TCN/Bergman/学习器)
│       ├── model/         # API 响应模型
│       ├── service/       # 后台服务
│       ├── sync/          # 数据同步 (广播/xDrip/华为)
│       ├── ui/            # UI 层 (9 页面 + 主题)
│       ├── util/          # 工具类
│       └── widget/        # 自定义 View
├── backend/          # Python 后端 + 模型训练
├── broadcast-test/   # 广播测试工具
├── flutter/          # Flutter App (半成品)
└── docs/             # 项目文档
```

## ⚠️ 免责声明

本应用仅供健康管理参考，不构成医疗诊断或治疗建议。任何医疗决策请咨询专业医生。

## 📄 许可证

MIT License © 2026 TangDun
