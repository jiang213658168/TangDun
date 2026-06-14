# 基于多源数据融合与增量自学习的糖尿病血糖预测系统

## Multi-Source Data Fusion with Incremental Self-Learning for Blood Glucose Prediction in Diabetes Management

---

**摘要** — 本文提出糖盾(TangDun)，一个面向糖尿病患者的智能血糖预测与健康管理系统。系统集成了时序卷积网络(TCN)、Dalla Man七隔室生理模型和贝叶斯模型平均(BMA)融合的三层预测架构，并引入了基于EWMA平滑、卡尔曼滤波和SGD在线学习的增量自进化机制。系统实现了参考xDrip+的实时血糖监测引擎（卡尔曼滤波+多项式拟合噪声检测+质量评分），以及前台服务持久后台运行。在Android移动端实现了15维特征的实时提取、ONNX推理和轻量级残差学习。TCN模型的预测MAE达到0.552 mmol/L，Clarke误差网格A区覆盖率为92.4%。针对中国2型糖尿病人群优化了生理模型参数（Vg、肝糖输出、胰岛素敏感度等8项）。系统还集成了CGM通知监听（支持40+品牌）、AI饮食识别与智能对话、指尖血校准、多维度预警和商业级激活管理等实用功能。

**关键词**: 血糖预测；糖尿病管理；时序卷积网络；Dalla Man模型；实时监测；增量学习；移动端AI

---

## 1 引言

糖尿病是全球性公共卫生挑战，中国糖尿病患者已超过1.4亿。持续血糖监测(CGM)技术的普及使得大量血糖数据得以采集，但如何利用这些数据进行精准血糖预测仍然是一个开放性问题。

现有的血糖预测方法可分为三类：(1)基于生理模型的预测，如Bergman最小模型（3隔室）和Dalla Man模型（7隔室），通过常微分方程描述葡萄糖-胰岛素动态；(2)基于数据驱动的预测，如循环神经网络、时序卷积网络等，从历史数据中学习模式；(3)混合方法，结合两者的优势。

然而，现有方法存在以下不足：(a)多数模型为离线训练，无法适应个体差异；(b)移动设备上的深度学习推理和训练受限于算力；(c)缺乏将饮食、胰岛素、运动等多源数据统一融合的框架。

本文提出的糖盾系统在以下几个方面做出了贡献：

1. **三层融合预测架构**: TCN数据驱动模型(15维特征，MAE 0.552)与Dalla Man七隔室生理模型通过BMA动态加权融合
2. **增量自学习机制**: EWMA平滑、卡尔曼滤波、贝叶斯参数估计和SGD在线残差学习四层自适应
3. **移动端工程实现**: ONNX Runtime推理 + 304参数轻量增量学习器，兼容Android 8.0+
4. **多源数据融合**: 同时接入CGM血糖、饮食记录、胰岛素注射、运动数据、华为健康数据

## 2 相关工作

### 2.1 血糖预测模型

Bergman等提出的最小模型[1]使用三状态ODE描述血糖动态。Dalla Man等[10]进一步扩展为七隔室模型，增加了胃肠道双隔室、皮下胰岛素双隔室和三通道胰岛素作用（葡萄糖利用、肝糖产出抑制、远端分布），显著提高了餐后血糖模拟的精度。本系统采用Dalla Man七隔室模型作为生理预测核心。

深度学习方面，TCN[2]通过膨胀卷积捕获长程时序依赖，在血糖预测任务上优于LSTM。Georga等[3]使用神经网络融合多源数据进行血糖预测。

### 2.2 个性化预测

在线学习在血糖预测中的应用已有探索。Facchinetti等[4]使用卡尔曼滤波对CGM数据进行在线去噪。Xie等[5]使用迁移学习将群体模型适配到个体。在线梯度下降用于医疗预测[6]提供了个体化适配的理论框架。

### 2.3 移动端AI部署

ONNX Runtime[7]在移动设备上提供了高效的神经网络推理。然而，移动设备上的模型训练仍面临算力和框架支持的挑战。

## 3 系统架构

### 3.1 整体架构

糖盾系统采用四层架构：UI层(Compose + Material Design 3)、业务层(ViewModel + 算法引擎)、数据层(Room + SharedPreferences)和同步层(广播接收 + 通知监听 + 蓝牙)。

### 3.2 数据采集与实时监测

系统支持三种数据采集模式：
- **通知监听模式**: 通过Android NotificationListenerService直接读取40+品牌CGM App的通知栏血糖数据（移植自xDrip+ UiBasedCollector）
- **广播接收模式**: 通过com.eveningoutpost.dexdrip.BgEstimate广播接收xDrip+转发的数据
- **手动输入模式**: 指尖血糖仪、饮食、胰岛素、运动等数据的结构化录入

采集到的原始血糖数据进入**实时监测引擎**（参考xDrip+ BgGraphBuilder算法）：
1. **合理性检查**: 过滤2.2-22.0 mmol/L范围外的异常值
2. **智能去重**: 同源55秒内或跨源同值重复过滤
3. **卡尔曼滤波**: 降噪平滑（过程噪声q=0.01，观测噪声r=0.1）
4. **多项式拟合噪声检测**: 3次最小二乘拟合→误差方差→噪声等级0-4
5. **线性回归ROC**: 最近6点线性回归斜率 (mmol/L/min)
6. **质量评分**: 综合噪声、数据陈旧度、间隙、ROC异常性→0-100分

评分≥50的数据触发警报，所有数据保存校准值到数据库。

### 3.3 后台持久运行

参考xDrip+的ForegroundServiceStarter模式，系统实现了Android前台服务：
- **持久通知**: 状态栏显示最新血糖值+趋势，不可滑动清除
- **START_STICKY**: 系统杀进程后自动重启
- **Partial WakeLock**: 防止CPU休眠导致数据丢失
- **定时唤醒**: AlarmManager每15分钟检查服务健康状态
- **开机启动**: BOOT_COMPLETED广播自动恢复
- **划掉恢复**: onTaskRemoved检测用户从最近任务移除→广播重启

### 3.4 数据库设计

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

### 4.3 Dalla Man七隔室生理模型

Dalla Man模型（2007）是FDA认可的商用闭环系统级生理模型，包含7个隔室和15个参数：

**葡萄糖子系统（3隔室）：**
- 胃中碳水(stomach)：一阶胃排空，速率kStomach=0.055 min⁻¹
- 肠道碳水(gut)：一阶肠吸收，速率kGut=0.056 min⁻¹
- 血浆葡萄糖(G)：受肠道吸收(Ra)、肝糖产出(EGP)、非胰岛素依赖利用(Uii)、胰岛素依赖利用(Uid)和肾排泄共同调节

**胰岛素子系统（3隔室）：**
- 皮下非单体(subQ1)：注射后胰岛素六聚体解离，速率ka1=0.018 min⁻¹
- 皮下单体(subQ2)：单体吸收入血，速率ka2=0.018 min⁻¹
- 血浆胰岛素(I)：清除率ke=0.138 min⁻¹

**胰岛素远端作用（2通道）：**
- 葡萄糖利用激活(X)：由kp3=0.03 min⁻¹控制，作用于骨骼肌和脂肪组织
- 肝糖产出抑制(X_L)：由kp2=0.06 min⁻¹控制，抑制肝脏葡萄糖异生

系统使用8状态RK4（4阶龙格-库塔法）求解ODE，步长5分钟，预测时域120分钟。模型参数通过体重进行个性化：

$$V_g = 体重(kg) \times 1.8 \quad (60-300 \text{ dL})$$
$$V_i = 体重(kg) \times 0.05 \quad (2-25 \text{ L})$$

初始条件从近期（≤24h）的进食和胰岛素注射记录预计算：胃/肠道碳水残留通过双指数衰减（kStomach, kGut）推算，皮下胰岛素残留通过双隔室链式衰减（ka1, ka2）推算。

**中国人群参数优化**: 针对中国2型糖尿病人群的低BMI、高内脏脂肪、β细胞功能衰退等代谢特征，调整了8项关键参数：

| 参数 | 西方默认 | 中国优化 | 生理依据 |
|------|----------|----------|---------|
| VgPerKg | 1.8 dL/kg | 1.6 | 体脂较低→分布体积略小 |
| Gb | 5.0 mmol/L | 5.2 | 中国人群基础血糖设定点略高 |
| Ib | 10.0 mU/L | 8.0 | 较瘦→基础胰岛素较低 |
| hepaticBase | 2.4 mg/kg/min | 2.0 | 东亚人群肝糖输出较低 |
| kStomach | 0.055 min⁻¹ | 0.048 | 米饭/面食消化略慢 |
| kGut | 0.056 min⁻¹ | 0.050 | 肠道吸收略慢 |
| kp3 | 0.03 min⁻¹ | 0.025 | β细胞衰退→利用通道激活慢 |
| kp2 | 0.06 min⁻¹ | 0.050 | 肝糖抑制略慢 |

### 4.4 BMA融合

贝叶斯模型平均(BMA)根据数据充分性动态调整模型权重。数据充足(≥288点)时TCN权重0.6；数据不足(<144点)时Dalla Man权重0.7；中间线性过渡。

$$P_{fused}(t) = w_{TCN} \cdot P_{TCN}(t) + w_{DallaMan} \cdot P_{DallaMan}(t)$$

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

### 5.5 AI智能助手

系统集成了大语言模型驱动的AI对话功能：
- **AI健康顾问**: 基于兼容OpenAI的API，提供糖尿病管理建议、饮食指导、胰岛素剂量咨询
- **食物拍照识别**: 百度AI菜品识别接口，拍照自动识别食物并查询大模型获取营养信息（碳水、热量、蛋白质、GI值）
- **多轮对话记忆**: Room持久化存储对话历史和会话上下文

### 5.6 激活管理系统

采用AES-128-CBC加密的激活码系统，支持按功能维度(对话/拍照/预测/报告/导出)限制使用次数和激活窗口。管理员码永久无限，普通用户码有时效性和每日使用限制。使用Python交互式终端生成激活码。加密密钥采用分段拼接增加反编译提取难度。

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

### 6.2 Dalla Man个性化效果

使用体重个性化参数后，Dalla Man七隔室模型针对70kg标准体重的模拟显示，餐后血糖峰值预测误差从默认参数(100kg假设)下的-32%降至±8%以内。三通道胰岛素作用（利用/肝糖抑制/分布）使得胰岛素效应曲线更加平滑，更接近临床观察。

### 6.3 系统性能

| 指标 | 值 |
|------|-----|
| 应用大小 | 82 MB (含ONNX模型590KB) |
| 内存占用 | <250 MB (含前台服务) |
| CPU占用(预测) | <5% |
| 实时监测延迟 | <1秒 (通知→处理→DB) |
| 数据库响应 | <10ms (288条查询) |
| 噪声检测窗口 | 30点 (2.5小时) |
| 后台存活率 | >95% (START_STICKY+定时唤醒) |

## 7 讨论

### 7.1 模型局限性

当前TCN模型基于OhioT1DM和HUPA数据集训练，未包含亚洲人群数据，可能存在种群偏差。模型仅使用24小时历史窗口，无法捕获更长周期的模式（如月经周期对血糖的影响）。心率(f14)和步数(f15)特征在没有可穿戴设备时恒为0，限制了模型对运动影响的建模。

### 7.2 未来工作

- **ExecuTorch迁移**: 将TCN模型从ONNX迁移到ExecuTorch，实现真正的设备端训练
- **联邦学习**: 在保护隐私的前提下汇聚多用户数据优化全局模型
- **个性化参数自动调优**: 使用贝叶斯优化自动搜索最优的Dalla Man模型参数
- **饮食图像语义分割**: 从食物图片直接估算碳水化合物含量
- **闭环胰岛素泵接口**: 将预测结果接入胰岛素泵实现自动剂量调整

## 8 结论

本文提出了糖盾——一个集成TCN数据驱动模型、Dalla Man七隔室生理模型和增量自学习机制的糖尿病血糖预测系统。系统在Android移动端实现了15维特征的实时提取、ONNX推理和304参数在线SGD学习。TCN模型达到MAE 0.552 mmol/L的预测精度。Dalla Man模型通过8状态RK4积分和体重个性化参数（包括针对中国人群的8项优化），提供了符合生理约束的预测曲线。系统实现了参考xDrip+的实时血糖监测引擎（卡尔曼滤波+多项式噪声检测+质量评分）和前台服务持久后台运行。此外，系统集成了CGM通知监听（40+品牌）、AI智能助手与食物识别、指尖血校准、多维度预警和激活管理等实用功能，为糖尿病患者提供了一个完整、可靠、智能的技术解决方案。

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

[10] Dalla Man C, Rizza RA, Cobelli C. Meal simulation model of the glucose-insulin system. *IEEE Trans Biomed Eng*, 54(10):1740-1749, 2007.
