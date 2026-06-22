# 糖盾 (TangDun) — AI 接手提示词

> 读完这份文档，你应该能在 5 分钟内开始改代码、跑测试、修 bug。不需要读其他任何文件。

---

## 1. 这是什么项目

糖盾是一个跑在 Android 手机上的**糖尿病血糖预测与管理系统**。核心功能：从 CGM（持续血糖监测）设备每 5 分钟采集一次血糖，用 **TCN 神经网络 + DallaMan 七隔室生理模型 + BMA 贝叶斯融合** 三层混合模型预测未来 180 分钟的血糖走势，并在手机上持续自学习个性化。

- **GitHub**: `https://github.com/jiang213658168/TangDun`
- **本地路径**: `D:\tangdun\`
- **当前版本**: v3.0.23 (versionCode 320)
- **构建状态**: BUILD SUCCESSFUL (APK 82MB, 编译 26-45s)

---

## 2. 环境与命令

### 编译 APK
```bash
cd /d/tangdun/android
export JAVA_HOME="C:/Users/21365/android-tools/jdk-17.0.19+10"
export ANDROID_HOME="C:/Users/21365/android-tools/android-sdk"
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Python 测试（本地算法验证，不需要 Android）
```bash
cd /d/tangdun/backend/training
python comprehensive_test.py    # 12场景x5轮 = 60个测试
python final_fix.py             # 4场景快速验证
python meal_fix_test.py         # 饮食专项测试
```

### Git 操作
```bash
cd /d/tangdun
# 当前在 feature/ui-redesign-v2 分支
git checkout feature/ui-redesign-v2
# 改完代码后
git add <files> && git commit -m "..." && git push origin feature/ui-redesign-v2
git checkout main && git merge feature/ui-redesign-v2 --no-edit && git push origin main
git checkout feature/ui-redesign-v2
```

### 日志查看
```bash
adb logcat | grep -E "EDOC|SelfLearn|PredVM|DallaMan|IncLearn"
```

---

## 3. 架构核心（5 层）

```
UI Layer (Compose): HomeScreen / PredictionScreen / SettingsScreen / ...
  ↓
ViewModel Layer (6个): PredictionVM(★最核心) / HomeVM / SettingsVM / ...
  ↓
Domain Layer (20个算法类):
  ├─ 预测链: FeatureExtractor → TCNPredictor(ONNX) → DallaManModel(RK4)
  │          → FusionPredictor → BMA融合 → 输出曲线
  ├─ 自学习: EDOCCorrector(L0) → OnlineLearner(L1) → IncrementalLearner(L2)
  │          → SelfLearningManager(总管)
  └─ 辅助: AlertEngine / CGMCalibrator / SmartAdvisor / ...
  ↓
Data Layer: Room (15表) + SharedPreferences
  ↓
Collection: CGMNotificationListener(40+品牌) / XlsxImporter / ForegroundService
```

---

## 4. 核心文件速查（只列出必须知道的）

### 预测链（最重要）
| 文件 | 行数 | 作用 |
|------|------|------|
| `PredictionViewModel.kt` | ~450 | **预测入口**：拉数据→算IOB→DallaMan→TCN→BMA→物理门控→输出 |
| `DallaManModel.kt` | ~450 | 7隔室RK4生理模型，8状态ODE，含双相胃排空 |
| `TCNPredictor.kt` | ~150 | ONNX推理，15维特征→4曲线参数[a,b,c,d] |
| `FusionPredictor.kt` | ~280 | BMA融合，权重计算，fallback |
| `FeatureExtractor.kt` | ~200 | 288点滑动窗口→15维特征向量 |

### 自学习链
| 文件 | 行数 | 作用 |
|------|------|------|
| `SelfLearningManager.kt` | ~445 | **自学习总管**：四层调度+Activity生命周期 |
| `EDOCCorrector.kt` | ~808 | L0即时纠错：预测缓存→误差→Sign-SGD→参数修正 |
| `OnlineLearner.kt` | ~411 | L1统计学习：EWMA+Kalman，空腹基线/变异度/时段模式 |
| `IncrementalLearner.kt` | ~433 | L2增量残差：304参数SGD网络，学TCN残差 |

### 关键 UI
| 文件 | 作用 |
|------|------|
| `PredictionScreen.kt` | 预测页：三线曲线(TCN/DM/融合)+峰值+风险 |
| `SettingsScreen.kt` | 设置页：自学习卡片+系统信息(BuildConfig动态读) |
| `HomeScreen.kt` | 首页：血糖图表+校准+导入 |

### 构建配置
| 文件 | 关键内容 |
|------|---------|
| `app/build.gradle.kts` | versionCode=320, BuildConfig动态注入MODEL_NAME/MAE/CLARKE |
| `build.gradle.kts` | AGP 8.1.0, Kotlin 1.9.20, Hilt 2.48 |

---

## 5. 关键算法参数（当前版本 v3.0.23）

### DallaMan 7隔室模型参数（`forUser()` 个性化后）
```
kStomach  = 0.035 - isfFactor×0.004     // 中式慢排空 (v3.0.22)
kGut      = 0.050                         // 肠道吸收 (v3.0.22)
Vm0       = 2.5 - isfFactor×0.2 + act×0.3 // 论文值 (v3.0.20)
VmX       = 0.05 - isfFactor×0.01         // 论文值 (v3.0.20)
Km0       = 100.0                         // 论文值 (v3.0.20)
k1        = 0.060 + act×0.030             // 非胰岛素依赖清除 (v3.0.20)
hepaticBase = 2.07 + isfFactor×0.4        // 肝糖输出 (v3.0.20)
ka1/ka2   = 0.024                         // 速效胰岛素吸收 (v3.0.22)
kp3       = 0.045 - isfFactor×0.007       // 胰岛素远端利用激活
isfFactor = 1.5 / ISF (coerce 0.3-3.0)
```

### 双相胃排空 (v3.0.23)
进食 <15min: **20% 碳水直接入肠** (rapidGut), 80% 在胃慢相
进食 ≥15min: 标准单相排空
目的: 消除"刚记录吃饭→肠道空→Ra=0→血糖反降"的错误

### TCN 物理门控 R1-R7 (v3.0.22)
TCN 的 c 项（线性项）几乎恒正 → ONNX 模型在胰岛素场景下也预测上升。
7 条规则检测 TCN 不可信场景，触发后 TCN 曲线 = **75% DallaMan + 25% 原始 TCN**。

```
R1: IOB > 1U && carb2h < 30g     → DM 主导
R2: IOB > 0.5 && TCN↑ && DM↓     → DM 主导
R3: carb2h ≥ 30 && TCN保守 && DM↓ → DM 主导
R4: |TCN Δ30min| > 2.5            → DM 主导 (生理不可能)
R5: IOB > 1 && carb2h ≥ 30 && TCN↑ → DM 主导
R6: carb2h ≥ 30 && IOB=0 && TCNΔ>2.0 → DM 主导
R7: TCN方向×DM方向<0 && |TCNΔ|≥0.6 → DM 主导
陡降保护: |DM30min| > 4.0 → DM权重降至50% (防过冲)
```

### 模型性能
```
TCN v3: MAE 0.612 mmol/L, Clarke A 92.5%
训练数据: IOBP2(440人)+OhioT1DM(12人)+CTR3(30人)+HUPA(25人) = 474人, 1610万行
训练模型: 2,058,214参数 → 导出ONNX 590KB → 推理15ms
```

---

## 6. 版本演进关键节点

| 版本 | 关键变更 |
|------|---------|
| v3.0.20 | DallaMan 参数修正: Vm0 4.5→2.5, Km0 25→100, 稳态 Gb=7.0 |
| v3.0.21 | BuildConfig 动态版本, EDOC 保留 errorHistory |
| **v3.0.22** | **kStomach 0.050→0.035, ka 0.018→0.024, kGut 0.065→0.050, R1-R7 门控** |
| **v3.0.23** | **双相胃排空 20%快速入肠** |

---

## 7. 常见修改指南

### 改 DallaMan 参数
1. 改 `DallaManModel.kt` 的 `forUser()` 工厂方法
2. 同步更新 `backend/training/comprehensive_test.py` 的 `dalla()` 函数
3. 跑 `python comprehensive_test.py` 确认 60/60
4. 编译 APK 验证

### 改 TCN 物理门控
1. 改 `PredictionViewModel.kt` 的 `physicalOverride` when 块（两处：merged 计算 + UI 显示线）
2. 同步更新 `comprehensive_test.py` 的 `gate()` 函数
3. 跑 `python comprehensive_test.py` 确认 60/60

### 改自学习逻辑
1. 改 `SelfLearningManager.kt` 或对应算法类
2. 不需要 Python 测试（自学习是 App 端行为）
3. 编译 APK，在真机/模拟器上看 logcat

### 加新功能
1. 先在 `DEVELOPER_GUIDE.md` 的 §14 找到对应指南
2. 改源码
3. 编译 APK
4. 更新 `DEVELOPER_GUIDE.md` + 论文

---

## 8. 测试体系

### Python 测试（算法层）
```
comprehensive_test.py  — 12场景 x 5随机种子 = 60测试 (每次改算法必须跑)
final_fix.py           — 4场景快速验证 (改完后快速检查)
meal_fix_test.py       — 饮食专项10场景 (改胃排空/碳水相关必须跑)
scenario_test.py       — 基础4场景诊断 (排查问题时用)
```

### Android 测试
```
./gradlew assembleDebug  — 编译检查 (每次改.kt后必跑)
adb install + 手动测试   — 真机验证
adb logcat | grep ...    — 日志排查
```

---

## 9. 已知问题与注意事项

1. **TCN c项恒正**: ONNX 模型几乎总是预测上升（c>0），物理门控 R1-R7 是 workaround，根本修复需要重新训练模型增加胰岛素下降场景样本。
2. **VmaxGastric 限速**: 50g 和 120g 餐在胃排空限速下前 15min 表现相同（都撞 650mg/min 上限），大餐场景可能被低估。
3. **无反调节激素**: 不建模胰高血糖素/肾上腺素，无法预测 Somogyi 反弹和黎明现象。
4. **GitHub 连接不稳定**: 经常连不上，commit 后记得多试几次 push。
5. **JDK 路径**: 必须用 `C:/Users/21365/android-tools/jdk-17.0.19+10`（完整 JDK 含 jlink），不能用 PyCharm JBR。

---

## 10. 快速上手检查清单

- [ ] 能编译: `./gradlew assembleDebug` → BUILD SUCCESSFUL
- [ ] 能跑测试: `python comprehensive_test.py` → 60/60 PASS
- [ ] 知道改 DallaMan 参数要改哪些文件
- [ ] 知道改 TCN 门控要改哪些文件
- [ ] 知道 git 分支是 `feature/ui-redesign-v2`
- [ ] commit 消息格式: `v3.0.XX: 简短描述`
- [ ] Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
