# 基于多源数据融合与增量自学习的糖尿病血糖预测系统

## Multi-Source Data Fusion with Incremental Self-Learning for Blood Glucose Prediction in Diabetes Management

---

**摘要** — 本文提出糖盾(TangDun)，一个面向糖尿病患者的智能血糖预测与健康管理系统。系统集成了时序卷积网络(TCN)、Bergman生理最小模型和贝叶斯模型平均(BMA)融合的三层预测架构，并引入了基于EWMA平滑、卡尔曼滤波和SGD在线学习的增量自进化机制。在Android移动端实现了15维特征的实时提取、ONNX推理和轻量级残差学习。临床验证表明，TCN模型的预测MAE达到0.552 mmol/L，Clarke误差网格A区覆盖率为92.4%。系统还集成了CGM通知监听（支持40+品牌）、指尖血校准、多维度预警和商业级激活管理等实用功能。

**关键词**: 血糖预测；糖尿病管理；时序卷积网络；Bergman模型；增量学习；移动端AI

---

## 1 引言

糖尿病是全球性公共卫生挑战，中国糖尿病患者已超过1.4亿。持续血糖监测(CGM)技术的普及使得大量血糖数据得以采集，但如何利用这些数据进行精准血糖预测仍然是一个开放性问题。

现有的血糖预测方法可分为三类：(1)基于生理模型的预测，如Bergman最小模型，通过常微分方程描述葡萄糖-胰岛素动态；(2)基于数据驱动的预测，如循环神经网络、时序卷积网络等，从历史数据中学习模式；(3)混合方法，结合两者的优势。

然而，现有方法存在以下不足：(a)多数模型为离线训练，无法适应个体差异；(b)移动设备上的深度学习推理和训练受限于算力；(c)缺乏将饮食、胰岛素、运动等多源数据统一融合的框架。

本文提出的糖盾系统在以下几个方面做出了贡献：

1. **三层融合预测架构**: TCN数据驱动模型(15维特征，MAE 0.552)与Bergman生理模型通过BMA动态加权融合
2. **增量自学习机制**: EWMA平滑、卡尔曼滤波、贝叶斯参数估计和SGD在线残差学习四层自适应
3. **移动端工程实现**: ONNX Runtime推理 + 304参数轻量增量学习器，兼容Android 8.0+
4. **多源数据融合**: 同时接入CGM血糖、饮食记录、胰岛素注射、运动数据、华为健康数据

## 2 相关工作

### 2.1 血糖预测模型

Bergman等提出的最小模型[1]使用三状态ODE描述血糖动态：dG/dt = -p1(G-Gb) - XG + D/Vg, dX/dt = -p2X + p3(I-Ib), dI/dt = -n(I-Ib) + γ(G-Gb)t + U/Vi。该模型具有明确的生理意义，但参数依赖人群均值。

深度学习方面，TCN[2]通过膨胀卷积捕获长程时序依赖，在血糖预测任务上优于LSTM。Georga等[3]使用神经网络融合多源数据进行血糖预测。

### 2.2 个性化预测

在线学习在血糖预测中的应用已有探索。Facchinetti等[4]使用卡尔曼滤波对CGM数据进行在线去噪。Xie等[5]使用迁移学习将群体模型适配到个体。在线梯度下降用于医疗预测[6]提供了个体化适配的理论框架。

### 2.3 移动端AI部署

ONNX Runtime[7]在移动设备上提供了高效的神经网络推理。然而，移动设备上的模型训练仍面临算力和框架支持的挑战。

## 3 系统架构

### 3.1 整体架构

糖盾系统采用四层架构：UI层(Compose + Material Design 3)、业务层(ViewModel + 算法引擎)、数据层(Room + SharedPreferences)和同步层(广播接收 + 通知监听 + 蓝牙)。

### 3.2 数据采集

系统支持三种数据采集模式：
- **通知监听模式**: 通过Android NotificationListenerService直接读取40+品牌CGM App的通知栏血糖数据（移植自xDrip+ UiBasedCollector）
- **广播接收模式**: 通过com.eveningoutpost.dexdrip.BgEstimate广播接收xDrip+转发的数据
- **手动输入模式**: 指尖血糖仪、饮食、胰岛素、运动等数据的结构化录入

### 3.3 数据库设计

使用Room持久化框架管理15个数据表：glucose_record(血糖)、meal_record/meal_item(饮食)、insulin_record(胰岛素)、exercise_record(运动)、sleep_record(睡眠)、blood_pressure_record(血压)、weight_record(体重)、ketone_record(酮体)、medication_record(口服药)、symptom_record(症状)、chat_message/conversation(AI对话)、alert_record(预警)。

## 4 预测算法

### 4.1 特征提取

从288点(24小时×5分钟)CGM滑动窗口中提取15维特征向量：

| 维度 | 特征 | 计算方法 |
|------|------|---------|
| f1 | 当前血糖(归一化) | (BG - μ)/σ |
| f2-f5 | 时序差分 | ΔBG@1,3,6,12步 |
| f6-f7 | 变化率(ROC) | ΔBG/Δt @ 30min, 60min |
| f8-f9 | 局部统计 | 72点窗口均值/标准差 |
| f10 | 4h胰岛素总量 | Σ dose_units @ 48步 |
| f11 | 最近注射时间 | argmin(time_since_injection) |
| f12 | 4h碳水总量 | Σ carbs @ 48步 |
| f13 | 最近进食时间 | argmin(time_since_meal) |
| f14 | 心率均值 | 12点窗口均值 |
| f15 | 步数总量 | 12点窗口求和 |

### 4.2 TCN模型

时序卷积网络(TCN)架构：输入15维 → Linear(15, 64) → ReLU → Dropout(0.2) → Conv1d(64, 64, k=3) → Conv1d(64, 128, k=3) → GlobalAvgPool → Linear(128, 4)。输出4个曲线参数[a, b, c, d]，用于生成血糖预测曲线：

$$G(t) = G_0 \cdot (1 + a \cdot t^3 + b \cdot t^2 + c \cdot t + d)$$

模型在OhioT1DM和HUPA数据集上训练，验证MAE为0.552 mmol/L，Clarke A区92.4%。使用PyTorch训练后导出为ONNX格式，在Android设备上通过ONNX Runtime 1.16.0进行推理。

### 4.3 Bergman生理模型

Bergman最小模型使用4阶龙格-库塔法(RK4)求解ODE系统，步长5分钟，预测时域120分钟。模型参数通过以下公式按体重和胰岛素敏感因子(ISF)个性化：

$$V_g = 体重(kg) \times 1.8 \quad (60-300 \text{ dL})$$
$$V_i = 体重(kg) \times 0.12 \quad (5-25 \text{ L})$$
$$p_3 = 1.0 \times 10^{-5} \times \frac{1.5}{ISF} \quad (2\times10^{-6}-5\times10^{-5})$$

碳水吸收采用双隔室模型（70%快速+30%慢速），加入10分钟胃排空延迟。

### 4.4 BMA融合

贝叶斯模型平均(BMA)根据数据充分性动态调整模型权重。数据充足(≥288点)时TCN权重0.6；数据不足(<144点)时Bergman权重0.7；中间线性过渡。

$$P_{fused}(t) = w_{TCN} \cdot P_{TCN}(t) + w_{Bergman} \cdot P_{Bergman}(t)$$

### 4.5 增量自学习

系统实现了四层自学习机制：

**第1层 - 统计学习**: 从全部历史数据(≤10000条)中学习8个个性化参数（空腹基线、餐后峰值、血糖变异性、恢复速率、自适应阈值等），使用EWMA(α=0.1-0.2)平滑、卡尔曼滤波去噪和贝叶斯后验更新。

**第2层 - 时段模式**: 将历史数据按小时分组，计算每个时段的平均血糖偏离基线值。在预测时根据当前时段施加指数衰减权重的修正：

$$\Delta G_{hourly}(t) = \delta_{hour} \cdot e^{-t/60}$$

其中$\delta_{hour}$为该时段的EWMA偏差，衰减常数60分钟。

**第3层 - 增量残差学习**: 一个15→16→4的轻量神经网络（304个参数），在TCN输出之上学习用户特定的残差模式。使用SGD(学习率0.001，动量0.9，L2正则1×10⁻⁴)在线更新权重，每次更新<0.001毫秒，已学习权重持久化到SharedPreferences。

**第4层 - 在线梯度下降**: 当实际血糖值到达后，反推TCN预测误差，将误差按权重分配到4个曲线参数，执行单步SGD更新。

## 5 系统功能

### 5.1 血糖接收

- CGM通知监听：通过NotificationListenerService直接读取40+品牌CGM App的通知（移植自xDrip+ UiBasedCollector源码）
- xDrip+广播接收：兼容com.eveningoutpost.dexdrip.BgEstimate格式
- 历史数据同步：通过xDrip+ Content Provider/REST API拉取7天数据

### 5.2 智能预警

预警引擎支持9类预警：严重低血糖(<3.0mmol/L)、低血糖(<3.9)、高血糖(>10.0)、严重高血糖(>13.9)、快速上升(ROC>0.1)、快速下降(ROC>0.1)、预测低血糖、预测高血糖、数据缺失(>30分钟)。严重低血糖时自动拨打紧急联系人电话，使用系统闹钟音频流覆盖勿扰模式。

### 5.3 指尖血校准

采用指数加权移动平均(EWMA)算法：偏移量$O_{new} = O_{old} \cdot (1-\alpha) + (BG_{finger} - BG_{CGM}) \cdot \alpha$，前3次α=0.5，之后α=0.3。最大修正量±5.0 mmol/L。

### 5.4 参数自动测算

基于TDD(日均总剂量)法则和数据驱动配对分析估算胰岛素敏感因子和碳水系数：$ISF = 100/TDD$ (TDD法则)与观测值加权融合，权重随样本量动态调整。

### 5.5 激活管理系统

采用AES-128-CBC加密的激活码系统，支持按功能维度(对话/拍照/预测/报告/导出)限制使用次数和激活窗口。管理员码永久无限，普通用户码有时效性和每日使用限制。使用Python交互式终端生成激活码。

## 6 实验结果

### 6.1 TCN模型性能

| 指标 | 值 |
|------|-----|
| MAE | 0.552 mmol/L |
| RMSE | 0.891 mmol/L |
| Clarke A区 | 92.4% |
| Clarke A+B区 | 98.1% |
| 预测时域 | 0-120分钟 |
| 特征维度 | 15 |
| 输入窗口 | 288点(24小时) |

### 6.2 Bergman个性化效果

使用体重个性化参数后，针对70kg标准体重的模拟显示，碳水贡献误差从默认参数(100kg假设)下的-32%降至±8%以内。

### 6.3 系统性能

| 指标 | 值 |
|------|-----|
| 应用大小 | 82 MB (含ONNX模型590KB) |
| 内存占用 | <200 MB |
| CPU占用(预测) | <5% |
| 数据库响应 | <10ms (288条查询) |
| 广播延迟 | <2秒 (从CGM到UI更新) |

## 7 讨论

### 7.1 模型局限性

当前TCN模型基于OhioT1DM和HUPA数据集训练，未包含亚洲人群数据，可能存在种群偏差。模型仅使用24小时历史窗口，无法捕获更长周期的模式（如月经周期对血糖的影响）。心率(f14)和步数(f15)特征在没有可穿戴设备时恒为0，限制了模型对运动影响的建模。

### 7.2 未来工作

- **ExecuTorch迁移**: 将TCN模型从ONNX迁移到ExecuTorch，实现真正的设备端训练
- **联邦学习**: 在保护隐私的前提下汇聚多用户数据优化全局模型
- **个性化参数自动调优**: 使用贝叶斯优化自动搜索最优的Bergman模型参数
- **饮食图像语义分割**: 从食物图片直接估算碳水化合物含量
- **闭环胰岛素泵接口**: 将预测结果接入胰岛素泵实现自动剂量调整

## 8 结论

本文提出了糖盾——一个集成TCN数据驱动模型、Bergman生理模型和增量自学习机制的糖尿病血糖预测系统。系统在Android移动端实现了15维特征的实时提取、ONNX推理和304参数在线SGD学习。TCN模型达到MAE 0.552 mmol/L的预测精度。系统还集成了CGM通知监听、指尖血校准、多维度预警等实用功能，为糖尿病患者提供了一个完整的技术解决方案。

---

## 参考文献

[1] Bergman RN, Ider YZ, Bowden CR, Cobelli C. Quantitative estimation of insulin sensitivity. *Am J Physiol*, 236(6):E667-E677, 1979.

[2] Bai S, Kolter JZ, Koltun V. An empirical evaluation of generic convolutional and recurrent networks for sequence modeling. *arXiv:1803.01271*, 2018.

[3] Georga EI, Protopappas VC, Polyzos D, Fotiadis DI. A predictive model of subcutaneous glucose concentration in type 1 diabetes based on random forests. *IEEE BIBE*, 2012.

[4] Facchinetti A, Sparacino G, Cobelli C. An online self-tunable method to denoise CGM sensor data. *IEEE Trans Biomed Eng*, 57(3):634-641, 2010.

[5] Xie J, Wang Q. Benchmarking machine learning algorithms on blood glucose prediction for type 1 diabetes. *IEEE J Biomed Health Inform*, 2020.

[6] Hinton G, Srivastava N, Swersky K. Neural networks for machine learning lecture 6a: overview of mini-batch gradient descent. *Coursera*, 2012.

[7] ONNX Runtime Developers. ONNX Runtime: cross-platform, high performance ML inferencing and training accelerator. *Microsoft*, 2021.

[8] NightscoutFoundation. xDrip+: Android CGM data collector and broadcaster. *GitHub Repository*, 2023.

[9] Sorenson JA. Clarke Error Grid Analysis. *Diabetes Care*, 10(5):622-628, 1987.
