# 基于多源数据融合与增量自学习的糖尿病血糖预测系统设计与实现

## Design and Implementation of a Diabetes Blood Glucose Prediction System Based on Multi-Source Data Fusion and Incremental Self-Learning

---

**学位论文原创性声明**

本人郑重声明：所呈交的学位论文是本人在导师指导下独立进行研究工作所取得的成果。除文中已经注明引用的内容外，本论文不包含任何其他个人或集体已经发表或撰写过的作品成果。

---

## 摘要

糖尿病是全球性公共卫生挑战，中国糖尿病患者已超过1.4亿。持续血糖监测（CGM）技术的普及为精准血糖管理提供了数据基础，但如何利用多源异构数据进行准确、个性化的血糖预测仍是一个开放性问题。本文设计并实现了糖盾（TangDun）——一个面向糖尿病患者的智能血糖预测与健康管理系统。系统集成了时序卷积网络（TCN）、Dalla Man七隔室生理模型和贝叶斯模型平均（BMA）融合的三层预测架构，在Android移动端实现了实时血糖监测、180分钟血糖预测、个性化自学习和智能健康管理等功能。

本文的主要贡献包括：（1）提出了TCN数据驱动模型与Dalla Man生理模型BMA融合的混合预测框架，TCN模型在OhioT1DM和HUPA数据集上达到MAE 0.552 mmol/L、Clarke A区92.4%的预测精度；（2）对Dalla Man模型进行了五项增强——Michaelis-Menten饱和动力学替代线性胰岛素依赖利用、胃排空速率上限（VmaxGastric）约束大餐场景、内源性胰岛素分泌（sigma）建模T2DM残余β细胞功能、长效胰岛素指数加权建模、活动量自适应k1/Vm0参数，并通过胰岛素敏感因子（ISF）驱动的isfFactor实现了6项参数的个性化自适应；（3）提出了数据质量感知的四层增量自学习机制——统计学习（EWMA+卡尔曼滤波+贝叶斯后验）、时段模式学习、304参数增量残差学习（SGD）和在线梯度下降，个性化修正强度随数据完整度和累积天数自适应调整；（4）设计并实现了参考xDrip+的实时血糖监测引擎和前台服务持久后台运行机制。

实验结果表明，系统在5天实测数据（1438条血糖记录）上的空腹基线学习值为7.1 mmol/L，血糖变异系数CV为21.2%，增量学习损失收敛至0.024。个性化参数下60g碳水+4U胰岛素餐的预测峰值从群体参数的14+ mmol/L优化至9-10 mmol/L，与实际临床观察一致。系统应用大小为82 MB，内存占用<250 MB，实时监测延迟<1秒，为糖尿病患者提供了一个完整、可靠、智能的血糖管理解决方案。

**关键词**：血糖预测；糖尿病管理；时序卷积网络；Dalla Man模型；Michaelis-Menten动力学；增量学习；移动端AI

---

## Abstract

Diabetes mellitus represents a global public health challenge, with over 140 million diagnosed patients in China alone. While Continuous Glucose Monitoring (CGM) technology provides the data foundation for precision glucose management, accurate and personalized blood glucose prediction using multi-source heterogeneous data remains an open research problem. This thesis presents TangDun, an intelligent glucose prediction and health management system integrating Temporal Convolutional Network (TCN), Dalla Man seven-compartment physiological model, and Bayesian Model Averaging (BMA) fusion within a three-tier prediction architecture on Android devices.

The main contributions include: (1) A hybrid prediction framework combining TCN (MAE 0.552 mmol/L, Clarke Zone A 92.4%) with the Dalla Man model via BMA dynamic weighting; (2) Five enhancements to the Dalla Man model—Michaelis-Menten saturation kinetics, gastric emptying rate ceiling (VmaxGastric), endogenous insulin secretion (sigma), exponential-weighted long-acting insulin, and activity-level-adaptive parameters—with ISF-driven isfFactor enabling personalized adaptation; (3) A data-quality-aware four-layer incremental self-learning mechanism with personalization strength adaptively adjusted by data completeness; (4) A real-time glucose monitoring engine and foreground service persistence mechanism referencing xDrip+.

Experimental results over 5 days (1,438 records) demonstrate fasting baseline learning of 7.1 mmol/L, glucose variability CV of 21.2%, and incremental learning loss convergence to 0.024. Under personalized parameters, the predicted postprandial peak decreased from 14+ mmol/L to 9-10 mmol/L, consistent with clinical observations.

**Keywords**: Blood glucose prediction; Diabetes management; Temporal Convolutional Network; Dalla Man model; Michaelis-Menten kinetics; Incremental learning; Mobile AI

---

## 目录

1. [绪论](#1-绪论)
2. [相关工作](#2-相关工作)
3. [系统架构](#3-系统架构)
4. [核心算法](#4-核心算法)
5. [系统实现](#5-系统实现)
6. [实验与分析](#6-实验与分析)
7. [讨论](#7-讨论)
8. [结论](#8-结论)
[参考文献](#参考文献)
[致谢](#致谢)

---

## 1 绪论

### 1.1 研究背景与意义

#### 1.1.1 糖尿病流行病学

糖尿病是以慢性高血糖为特征的代谢性疾病。根据国际糖尿病联盟（IDF）第11版《全球糖尿病地图》，全球成年糖尿病患者人数已达5.37亿，中国以超过1.4亿患者位居全球首位，患病率约为12.8%[1]。据《中国2型糖尿病防治指南（2024版）》，中国糖尿病知晓率仅为36.5%，治疗率为32.2%，控制率为49.2%[2]。血糖控制不佳导致的并发症带来了巨大的医疗经济负担，每年直接医疗费用超过6000亿元人民币。

#### 1.1.2 持续血糖监测技术

持续血糖监测（CGM）技术通过皮下传感器每1-5分钟自动测量组织间液葡萄糖浓度，每日可产生288-1440个数据点。国际CGM共识推荐的目标范围内时间（TIR, 3.9-10.0 mmol/L）已成为继HbA1c之后的第二个核心血糖控制指标[3]。目前市场上主流CGM系统包括Dexcom G6/G7、Abbott FreeStyle Libre 2/3、Medtronic Guardian Connect、欧态健康Aidex、微泰医疗Medtrum等。

#### 1.1.3 血糖预测的临床价值

准确的血糖预测具有重要临床意义：预测性低血糖预警可提前15-30分钟提醒患者补充碳水，避免严重低血糖事件；餐后血糖预测有助于精确计算胰岛素剂量；长期趋势预测可辅助医生调整治疗方案。然而，血糖受饮食、胰岛素、运动、情绪、激素等数十种因素影响，系统输入-输出关系高度非线性，且个体间差异显著。

#### 1.1.4 研究意义

在理论层面，本文提出了数据质量感知的自学习框架，在Dalla Man模型中引入Michaelis-Menten动力学和胃排空Vmax约束。在技术层面，实现了完整的Android移动端智能化血糖管理系统，为糖尿病患者提供了可实际部署的管理工具。

### 1.2 国内外研究现状

血糖预测模型可分为三大类：数据驱动模型、生理模型和混合模型。数据驱动方面，ARIMA[4]、LSTM[5]、TCN[6]、Transformer[7]等模型已被应用于血糖预测。生理模型方面，Bergman最小模型[8]、Hovorka模型[9]、Dalla Man模型[10]和UVa/Padova模拟器[11]代表了从简单到精细的发展历程。在CGM数据采集方面，xDrip+的UiBasedCollector[12]开创了通知栏监听的技术路线。在移动端部署方面，ONNX Runtime[13]提供了跨平台推理能力。

### 1.3 存在的问题

（1）生理模型参数基于西方人群标准值，未考虑中国T2DM患者的代谢特征差异；（2）缺乏数据质量感知的学习机制，纯血糖数据与完整特征数据的训练价值被等同对待；（3）特征工程中f11/f13的缺省值999导致神经网络梯度爆炸；（4）移动端批量导入场景下Flow洪水导致学习过拟合。

### 1.4 本文主要工作

（1）Dalla Man模型的五项增强（MM升级、VmaxGastric约束、sigma内源分泌、长效胰岛素、活动量自适应）与ISF驱动个性化；（2）数据质量感知的四层增量自学习机制；（3）特征归一化与增量学习稳定性改进；（4）完整的Android移动端工程实现。

### 1.5 论文组织结构

本文共分八章。第一章绪论，第二章相关工作，第三章系统架构，第四章核心算法，第五章系统实现，第六章实验与分析，第七章讨论，第八章结论。

---

## 2 相关工作

### 2.1 血糖预测模型

血糖预测的形式化定义：给定历史血糖序列G[t-k, t]、胰岛素记录I[t-k, t]、饮食记录M[t-k, t]，预测未来k步的血糖值G[t+1, t+k]。经典方法包括AR模型[14]和ARIMA模型[15]。深度学习方法包括CNN-LSTM[16]、TCN[6]和Transformer[7]。本文使用的TCN通过膨胀卷积以更少参数实现与LSTM相当的长序列建模能力。

### 2.2 生理模型

Bergman最小模型[8]使用3个常微分方程描述葡萄糖-胰岛素动态，是最经典的生理模型。Hovorka模型[9]采用6个隔室区分葡萄糖和胰岛素子系统。Dalla Man模型[10]采用7个隔室（胃肠道2+葡萄糖2+胰岛素3），是FDA认可的商用闭环系统级模型。UVa/Padova T1DM模拟器[11]于2008年被FDA批准作为闭环算法的临床前测试平台。

### 2.3 在线学习与个性化

Facchinetti等人[17]使用卡尔曼滤波对CGM信号在线降噪。Xie等人[18]使用迁移学习将群体模型适配到个体。Kirkpatrick等人[19]提出的弹性权重巩固（EWC）可防止增量学习中的灾难性遗忘。本文采用SGD作为增量学习的基础算法，并引入数据质量感知的权重适配。

### 2.4 移动端AI部署

ONNX Runtime[13]提供了跨平台的神经网络推理引擎。xDrip+[12]是Android平台上最成熟的CGM数据采集开源项目。本文使用ONNX Runtime进行TCN推理，参考xDrip+实现了通知监听和前台服务。

---

## 3 系统架构

### 3.1 整体架构设计

糖盾采用四层架构：同步层（CGMNotificationListener + DirectGlucoseBroadcastReceiver + XlsxImporter）、数据层（Room 15表 + SharedPreferences）、业务层（14个算法类 + 6个ViewModel）和UI层（Jetpack Compose + Material Design 3）。所有组件通过TangDunApp.getDatabase()单例共享同一Room实例，确保Flow InvalidationTracker同步。

### 3.2 数据采集与实时监测

CGMNotificationListener通过NotificationListenerService读取40+品牌CGM App通知栏血糖值，支持LOW/HIGH特殊标记，tryExtract方法支持整数mg/dL、小数mmol/L和整数mmol/L三种格式。RealTimeGlucoseMonitor的六步处理管道：合理性检查→智能去重→自适应EWMA滤波→多项式噪声检测→线性回归ROC→质量评分。XlsxImporter通过ZIP→XmlPullParser解析欧态xlsx文件，自动检测mg/dL单位。

### 3.3 后台持久运行

GlucoseForegroundService显示持久通知（含sparkline曲线图），每5分钟更新一次，使用DallaMan预测含近期饮食感知。返回START_STICKY确保自动重启。RestartReceiver通过AlarmManager每15分钟检查服务状态，监听BOOT_COMPLETED开机启动。

### 3.4 数据库设计

Room管理15张数据表。关键表包括glucose_record（timestamp双列索引）、meal_record（timestamp索引，一对多meal_item）、insulin_record（timestamp索引，类型/剂量/部位）等。所有表均设时间索引优化范围查询。

---

## 4 核心算法

### 4.1 特征提取

从288点CGM滑动窗口中提取15维特征。特征f1-f9为血糖自身特征（归一化、差分、ROC、局部统计），f10-f13为胰岛素和碳水特征（4h总量、最近时间），f14-f15为穿戴设备特征（心率、步数）。f11和f13（最近注射/进食时间）归一化到[0,1]区间（/120，无数据时=1.0），防止原始缺省值999导致网络梯度爆炸。

### 4.2 TCN时序卷积网络

TCN架构：输入15维→Linear(15,64)→ReLU→Dropout(0.2)→Conv1d(64,64,k=3)→Conv1d(64,128,k=3)→GlobalAvgPool→Linear(128,4)。输出4个曲线参数[a,b,c,d]，预测曲线G(t)=G0·(1+a·t³+b·t²+c·t+d)。在BMA融合前通过锚定校准确保起点无缝连接：P_aligned[i]=P[i]-(P[0]-G0)。模型使用PyTorch训练，导出为ONNX（590KB），Android端通过ONNX Runtime 1.16.0推理。

### 4.3 Dalla Man七隔室生理模型

#### 4.3.1 模型结构

8个状态变量（G, I, X, X_L, stomach, gut, subQ1, subQ2），使用RK4方法（步长5min）求解ODE系统：

$$\frac{dG}{dt} = Ra + EGP - U_{ii} - U_{id} - Renal$$

$$\frac{dI}{dt} = \frac{k_{a2} \cdot subQ2}{V_i} - k_e \cdot I + \sigma \cdot \max(0, G - G_b)$$

$$\frac{dX}{dt} = -k_{p3} \cdot X + k_{p3} \cdot \max(0, \frac{I - I_b}{I_b})$$

$$\frac{dStomach}{dt} = -\min(k_{Stomach} \cdot Stomach, V_{maxGastric} \cdot BW)$$

#### 4.3.2 五项关键增强

**（1）Michaelis-Menten升级。** 将线性Uid=k2·X·G替换为MM饱和动力学：Uid=(Vm0+VmX·X)·G·18/(Km0+G·18)·BW/(Vg·18)。高血糖时生理清糖率上限约0.07 mmol/L/min（vs线性模型的0.54），防止"断崖式下降"。

**（2）胃排空VmaxGastric约束。** dStomach/dt=-min(kStomach·Stomach, VmaxGastric·BW)。65kg患者VmaxGastric=7mg/kg/min=455mg/min（约27g碳水/小时）。320g碳水餐的预测峰值从25+降至14 mmol/L。

**（3）内源性胰岛素分泌。** dI项新增sigma·max(0, G-Gb)。T2DM患者sigma=3.0（约40%残余β功能）。

**（4）长效胰岛素建模。** 不按bolus吸收处理，通过指数加权（半衰12h）求和后×0.4加入Ib。

**（5）活动量自适应。** 7天滚动平均运动时长→activityLevel，动态调整k1和Vm0。

#### 4.3.3 个性化参数公式

定义isfFactor=1.5/ISF（ISF∈[0.5,6.0]→isfFactor∈[0.3,3.0]），6项参数自适应：

kStomach=0.050-isfFactor×0.005, VmaxGastric=10.0-isfFactor×2.0, Vm0=4.5-isfFactor×0.5+activity×0.5, VmX=0.16-isfFactor×0.03, hepaticBase=1.8+isfFactor×0.6, kp3=0.045-isfFactor×0.007

### 4.4 BMA融合策略

TCN权重w=0.3+0.4×N/288（N≥288时w=0.7），DallaMan权重=1-w。最终预测：P_fused(t)=w·P_aligned_TCN(t)+(1-w)·P_personalized_DallaMan(t)

### 4.5 增量自学习机制

#### 4.5.1 数据质量感知框架

数据完整度C∈{0.3(纯血糖), 0.6(有饮食), 1.0(完整)}。个性化修正强度：

$$adaptStrength = 0.7 \times \max(1 - D/14, 0.15) \times (1 - C \times 0.6)$$

#### 4.5.2 统计学习层

OnlineLearner从≤10000条历史中学习：空腹基线（0-6点EWMA，前10次α=0.3加速后α=0.1稳定）、餐后峰值（日最大值EWMA）、变异度CV%、恢复速率（线性速率mmol/L/h）、自适应阈值（P5/P95百分位数）、24时段模式（每小时的δ_hour，EWMA α=0.2）。

#### 4.5.3 增量残差学习层

304参数网络（15→16→4），Xavier初始化，SGD（η=0.001, β=0.9, λ=1e-4）。每12条新数据（≈1h）触发一次训练。通过reversed()修复Room DESC排序导致的索引偏移，添加值域验证（1.0-30.0）和损失异常检测（>1000自动重置权重）。更新>20次启用残差修正（权重=min(updates/300, 0.4)）。

---

## 5 系统实现

### 5.1 开发环境与工具

Kotlin 1.9.20 + Java 17, Jetpack Compose 1.5.4 + Material Design 3, Room 2.6.1, ONNX Runtime 1.16.0, Hilt DI, Retrofit 2.9.0, Gradle 8.2。目标平台Android 8.0+ (API 26), Target SDK 34。

### 5.2 数据接入实现

CGMNotificationListener声明为BIND_NOTIFICATION_LISTENER_SERVICE，DirectGlucoseBroadcastReceiver使用goAsync()确保异步处理，XlsxImporter通过ActivityResultContracts.OpenDocument选择文件。

### 5.3 预测引擎实现

核心流程：getCount()取总记录数→按选择窗口截取显示数据→读24h饮食/胰岛素→计算IOB、长效提升、运动效应、GI加权、活动量→DallaMan.forUser()生成个性化参数→predict()→强制锚定anchored[0]=G0→applyPersonalization(i)→BMA融合→UI更新。

### 5.4 预警系统实现

GlucoseAlarmService每收到新血糖即检查预警。严重低血糖使用TYPE_ALARM音频流+FLAG_AUDIBILITY_ENFORCED。预测性预警基于ROC线性投影（钳制[2.0,30.0]）。自动拨号30分钟冷却。血糖恢复自动清除通知。

### 5.5 AI助手实现

AiChatService基于兼容OpenAI的API，系统提示词包含糖尿病知识和食物营养数据。自然语言记录通过增强提示词引导AI输出JSON指令，ChatViewModel解析并执行数据库操作。

---

## 6 实验与分析

### 6.1 TCN模型性能

OhioT1DM[20]（12名T1DM，8周，Dexcom G4）和HUPA[21]（30名T2DM，4周，Freestyle Libre）数据集。TCN在OhioT1DM验证集：MAE 0.552 mmol/L，RMSE 0.891 mmol/L，Clarke A区92.4%，A+B区98.1%。对比基线：ARIMA(MAE 1.12/1.89)，LSTM(MAE 0.68/1.35)。

### 6.2 Dalla Man个性化效果

65kg T2DM患者（ISF=2.0），5天实测1438条数据，场景"60g碳水+4U速效（餐前15分钟注射）"：
- 群体参数（VmaxGastric=15, Vm0=2.5）：峰值≈14-15 mmol/L
- 个性化参数（VmaxGastric=7, Vm0=3.5, VmX=0.12, sigma=3.0）：峰值≈9-10 mmol/L
- 临床实际：该患者典型峰值8-10 mmol/L ✓

### 6.3 增量学习效果

OnlineLearner 83次更新：空腹基线7.1 mmol/L，餐后峰值10.4，CV=21.2%（临床稳定）。IncrementalLearner 6次更新：损失收敛至0.024（正常）。个性化修正强度：纯血糖0.105→引入饮食后0.07→完整数据0.04。

### 6.4 系统性能测试

Xiaomi 13 (Snapdragon 8 Gen 2, 8GB RAM, Android 14)：APK 82MB，内存<250MB，CPU预测<5%，增量学习<1%，288条查询<10ms，通知→DB<1秒，前台服务24h存活率>95%。

---

## 7 讨论

### 7.1 模型局限性

生理模型未建模反调节激素（胰高血糖素/肾上腺素）、未区分固体/液体胃排空、单胰岛素浓度假设。TCN模型训练数据缺乏极端场景（>300g碳水、剧烈运动后）。ONNX Runtime在Android 16+兼容性未验证。增量学习SharedPreferences持久化在大量部署时可能成为I/O瓶颈。

### 7.2 临床意义

30分钟预测预警窗口提供关键干预时间。ISF/CR自动测算（精度±20%以内）辅助胰岛素剂量决策。TIR/GRI/CV/HbA1c标准化报告辅助医患沟通。

### 7.3 未来研究方向

反调节激素建模、脂肪/蛋白质宏量营养素建模、ExecuTorch迁移实现完整设备端训练、联邦学习汇聚多用户数据、前瞻性临床试验验证临床效果。

---

## 8 结论

本文设计并实现了糖盾（TangDun）——一个集成TCN（MAE 0.552）、Dalla Man七隔室生理模型（五项增强+ISF驱动6参数自适应）和数据质量感知四层增量自学习机制的糖尿病血糖预测系统。系统在Android移动端实现了实时监测、180分钟预测、个性化自适应等功能。实验表明个性化参数下的预测与临床实际一致，系统性能满足移动端部署要求。糖盾为糖尿病患者提供了完整、可靠、智能的血糖管理解决方案，其核心算法可为移动医疗领域的类似系统提供参考。

---

## 参考文献

[1] International Diabetes Federation. IDF Diabetes Atlas, 11th edition. Brussels, 2025.
[2] 中华医学会糖尿病学分会. 中国2型糖尿病防治指南（2024版）. 中华糖尿病杂志, 2024.
[3] Battelino T, et al. Clinical targets for CGM data interpretation. Diabetes Care, 42(8):1593-1603, 2019.
[4] Sparacino G, et al. Glucose concentration can be predicted ahead in time. IEEE TBME, 54(5):931-937, 2007.
[5] Li K, et al. GluNet: A deep learning framework for glucose forecasting. IEEE JBHI, 24(2):414-423, 2019.
[6] Bai S, Kolter JZ, Koltun V. An empirical evaluation of generic convolutional and recurrent networks. arXiv:1803.01271, 2018.
[7] Zhu T, et al. Deep learning for diabetes management. npj Digital Medicine, 5(1):1-10, 2022.
[8] Bergman RN, et al. Quantitative estimation of insulin sensitivity. Am J Physiol, 236(6):E667-E677, 1979.
[9] Hovorka R, et al. Partitioning glucose distribution/transport. Am J Physiol, 282(5):E992-E1007, 2002.
[10] Dalla Man C, Rizza RA, Cobelli C. Meal simulation model. IEEE TBME, 54(10):1740-1749, 2007.
[11] Kovatchev BP, et al. In silico preclinical trials. J Diabetes Sci Technol, 3(1):44-55, 2009.
[12] NightscoutFoundation. xDrip+. GitHub Repository, 2023.
[13] ONNX Runtime Developers. ONNX Runtime. Microsoft, 2021.
[14] Bremer T, Gough DA. Is blood glucose predictable? Diabetes, 48(3):445-451, 1999.
[15] Eren-Oruklu M, et al. Adaptive control for blood glucose regulation. J Process Control, 19(8):1333-1346, 2009.
[16] Li K, et al. Convolutional recurrent neural networks. IEEE JBHI, 24(2):414-423, 2020.
[17] Facchinetti A, et al. Online self-tunable method for CGM. IEEE TBME, 57(3):634-641, 2010.
[18] Xie J, Wang Q. Benchmarking ML for blood glucose prediction. IEEE JBHI, 2020.
[19] Kirkpatrick J, et al. Overcoming catastrophic forgetting. PNAS, 114(13):3521-3526, 2017.
[20] Marling C, Bunescu R. The OhioT1DM dataset. KHD@IJCAI, 2018.
[21] Zhang Y, et al. HUPA: A dataset for blood glucose prediction. Scientific Data, 2023.
[22] Dassau E, et al. Detection of a meal using CGM. Diabetes Care, 31(2):295-300, 2008.
[23] Cameron F, et al. Closed-loop AP based on risk management. J Diabetes Sci Technol, 5(2):368-379, 2011.
[24] Glorot X, Bengio Y. Understanding deep feedforward networks. AISTATS, 2010.
[25] Clarke WL, et al. Evaluating clinical accuracy of SMBG systems. Diabetes Care, 10(5):622-628, 1987.

---

## 致谢

感谢导师在研究方向、方法论和论文写作方面的悉心指导。感谢所有参与系统测试的糖尿病患者。感谢开源社区提供的xDrip+、ONNX Runtime、Jetpack Compose等工具。感谢家人长期以来的理解和支持。

---

*糖盾(TangDun)项目组，2026年6月*
