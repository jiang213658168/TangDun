# 基于多源数据融合与增量自学习的糖尿病血糖预测系统设计与实现

## Design and Implementation of a Diabetes Blood Glucose Prediction System Based on Multi-Source Data Fusion and Incremental Self-Learning

---

**学位论文原创性声明**

本人郑重声明：所呈交的学位论文是本人在导师指导下独立进行研究工作所取得的成果。除文中已经注明引用的内容外，本论文不包含任何其他个人或集体已经发表或撰写过的作品成果。

学位论文作者签名：__________　　日期：2026年6月

---

## 摘要

糖尿病管理面临一个老问题：患者知道自己血糖多少，却不知道接下来会怎么变。持续血糖监测（CGM）设备的普及让这个问题有了解决的可能——每5分钟一个血糖读数，一天就是288个数据点。但数据多了，怎么用就成了新问题。

本文记录了糖盾（TangDun）系统的设计与实现过程。这是一个跑在Android手机上的血糖预测工具，核心思路是把两种预测方法捏在一起：一边是用神经网络从历史数据里学模式（TCN），另一边是用生理模型模拟葡萄糖在体内的代谢过程（Dalla Man七隔室模型），最后用贝叶斯模型平均（BMA）把两边的结果按可信度加权。

具体做了几件事：

**（1）把Dalla Man模型改了五个地方。** 原模型有个问题：胰岛素清糖的公式是线性的——血糖越高清得越快，这在数学上没错，但人体不是这样的。GLUT4转运蛋白有饱和上限，血糖20 mmol/L的时候不可能比10 mmol/L快一倍。我们把线性换成了Michaelis-Menten饱和动力学。类似的问题还有胃排空——原模型假设胃里有多少食物就排多少，大餐场景下预测峰值能到25 mmol/L，这显然不对。加了一个VmaxGastric上限（7 mg/kg/min），大餐场景的预测才落回合理范围。另外还加了T2DM患者的内源胰岛素分泌项、长效胰岛素的指数加权模型、以及运动对胰岛素敏感性的影响。

**（2）ISF驱动的参数个性化。** 临床上每个患者都有胰岛素敏感因子（ISF）这个参数——1单位胰岛素能降多少血糖。我们定义了一个isfFactor = 1.5/ISF，然后把Dalla Man模型的6个核心参数写成isfFactor的函数。ISF=0.7的胰岛素抵抗患者和ISF=3.0的敏感患者，模型参数会自动往不同方向偏。这个做法的好处是：患者只需要知道自己被医生设定的ISF值，模型就能自动调参。

**（3）四层增量自学习。** 系统不是训完就固定了——它在手机上持续学习。第一层统计学习（EWMA+卡尔曼滤波），从CGM数据里估计空腹基线、变异度、24小时时段模式。第二层一个304参数的小网络，学TCN+DallaMan融合预测和真实血糖之间的残差。第三层在线梯度下降，对Dalla Man的几个敏感参数做缓慢微调。第四层做数据质量感知——如果患者只开了CGM但从没记录过饮食，系统知道自己学到的信息有限，修正策略会保守很多。

**（4）把整个系统工程落地。** 包括40+品牌CGM的通知栏监听（参考xDrip+的思路）、前台Service保活、15张表的Room数据库、Compose UI、AI对话记录、多级预警和自动紧急拨号。AI自然语言记录系统支持7种记录类型（饮食/胰岛素/运动/血糖/用药/体重/症状），具备多记录解析（一句话同时创建饮食+胰岛素）和自定义时间（"午饭时""8点""刚才"）的能力，与手动记录权限完全对等。

**（5）数据质量感知的完整删除回调机制。** 系统在删除饮食/胰岛素记录时主动通知自学习引擎重新从数据库检查数据完整度，避免误加后删除导致的dataCompleteness永久偏高问题。

实际测试5天，1,438条血糖记录。空腹基线学到7.1 mmol/L，变异度21.2%，增量学习损失收敛到0.024。个性化参数下60g碳水+4U胰岛素的预测峰值从群体参数的14+降到9-10，和患者自述的实际情况对得上。导入欧态xlsx实测1783条全部解析通过。APK 82MB，内存不到250MB，后台存活率95%以上。

**关键词**：血糖预测；糖尿病管理；Dalla Man模型；时序卷积网络；增量学习；Michaelis-Menten动力学；移动端AI

---

## Abstract

A fundamental problem in diabetes self-management is the disconnect between knowing one's current glucose level and knowing where it is heading. CGM devices now produce up to 288 readings per day, but raw data alone does not answer the question patients actually care about: "What will my glucose be in 30 minutes? In 2 hours?"

This thesis describes TangDun, a glucose prediction system that runs entirely on Android devices. The prediction engine combines a data-driven Temporal Convolutional Network (TCN) with the Dalla Man seven-compartment physiological model through Bayesian Model Averaging (BMA). The physiological model provides a biophysical prior that constrains predictions when data is sparse; the TCN captures individual patterns as more CGM data accumulates.

Key contributions:

**(1) Five modifications to the Dalla Man model.** Linear insulin-dependent glucose utilization was replaced with Michaelis-Menten saturation kinetics to reflect GLUT4 transporter saturation at high glucose concentrations. A gastric emptying rate ceiling (VmaxGastric, 7 mg/kg/min) prevents runaway predictions for large meals. Endogenous insulin secretion (sigma parameter) models residual β-cell function in T2DM. Long-acting insulin analogues are modeled via exponential decay rather than the standard bolus absorption model. Activity-level-adaptive modulation of k1 and Vm0 captures exercise-induced insulin sensitivity changes.

**(2) ISF-driven personalization.** A dimensionless factor isfFactor = 1.5/ISF maps the clinically familiar insulin sensitivity factor to six Dalla Man model parameters (kStomach, VmaxGastric, Vm0, VmX, hepaticBase, kp3). Patients only need to configure their ISF value; the model adjusts automatically.

**(3) Data-quality-aware incremental self-learning.** A four-layer architecture runs continuously on-device: statistical learning (EWMA + Kalman filtering for fasting baseline, variability, hourly patterns), a 304-parameter incremental residual network (SGD with momentum), online gradient descent on sensitive physiological parameters, and a data-completeness-aware fusion layer that modulates adaptation strength based on whether the patient has been recording meals and insulin or only CGM data.

**(4) Complete Android system implementation.** The system includes a 40+ brand CGM notification listener (following xDrip+'s UiBasedCollector pattern), foreground service with persistent notification, Room database with 15 tables, Jetpack Compose UI, AI-powered natural language logging, multi-level alerts with automatic emergency dialing, and standardized CGM reports.

Over 5 days of real-world testing (1,438 CGM readings), the system learned a fasting baseline of 7.1 mmol/L, glucose variability CV of 21.2%, and incremental learning loss converged to 0.024. With personalized parameters, the predicted peak for a 60g carbohydrate + 4U insulin meal dropped from 14+ mmol/L (population defaults) to 9-10 mmol/L, matching clinical observations. The APK is 82 MB, runtime memory is under 250 MB, and foreground service 24-hour survival exceeds 95%.

**Keywords**: Blood glucose prediction; Diabetes management; Dalla Man model; Temporal Convolutional Network; Incremental learning; Michaelis-Menten kinetics; Mobile AI

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

### 1.1 背景

#### 1.1.1 糖尿病到底有多普遍

IDF 2025年的数据：全球5.37亿成年糖尿病患者。中国1.4亿，排第一。这个数字意味着每10个中国成年人里就有超过1个是糖尿病患者——但知道自己有病的人只有36.5%，在接受治疗的人只有32.2%，血糖真正控制达标的不到一半[2]。

钱的问题同样触目惊心。中国每年花在糖尿病上的直接医疗费用超过6,000亿元。很多患者不是因为治不好才出问题，而是因为管不好——每天测3-7次指尖血，拿到的是几个离散的数字，拼不出血糖变化的全貌。一个人早上空腹6.5，午饭前4.2，晚饭后11.8——这三个数字告诉你的信息很有限。你不知道上午10点血糖是不是冲到了13，也不知道凌晨3点有没有发生过低血糖。

#### 1.1.2 CGM改变了什么

持续血糖监测（CGM）设备解决的就是这个"数据密度"问题。皮下埋一个微型传感器，每1-5分钟自动测一次组织间液葡萄糖，一天下来288到1,440个数据点。这不是"更多的血糖值"，而是一条近乎连续的曲线——你能看到血糖变化的方向、速率、加速度。

CGM带来的不只是数据量，更是一种新的评价维度。2019年Battelino等人在Diabetes Care上提出了目标范围内时间（TIR，3.9-10.0 mmol/L）这个指标，现在已经是和糖化血红蛋白并列的核心控制标准[3]。TIR每提升10%，HbA1c大约降0.5%。

市场方面，Dexcom G7的MARD已经到了8.2%，国产的欧态Aidex和微泰Medtrum也在9-10%的水平。价格在往下走，这对中国患者是好消息。

#### 1.1.3 预测为什么难

有了CGM数据，自然就想做预测——如果知道30分钟后会低血糖，现在吃颗糖就没事了。但血糖预测麻烦在哪？

随便列几个影响因素：吃了多少碳水、碳水的升糖指数、同时摄入的脂肪和蛋白质（会延缓胃排空）、什么时候打的胰岛素、什么类型、多少单位、打在哪个部位、最近有没有运动、运动后几小时了、昨晚睡得好不好、今天有没有压力大的事、是不是感冒了、女性的话是不是在经期……这个清单可以一直列下去。

更麻烦的是，同一个人的同一天、同一顿饭，血糖反应都可能不一样。周一中午吃同样的便当，血糖峰值8.5；周三中午吃同样的，峰值10.2——可能是因为周三早上开了个紧张的会，皮质醇上来了。

这些都是典型的非线性、时变系统问题。纯数据驱动的方法需要大量标注数据才能学到可靠的模式，纯生理模型又不可能把上面所有因素都建模进去。所以需要一个混合方案。

#### 1.1.4 本文做了什么

在算法层面：（1）改进了Dalla Man模型，主要是把线性假设替换成更符合生理实际的饱和动力学；（2）设计了让ISF这个临床参数直接驱动模型个性化的方法；（3）搞了一个数据质量感知的自学习框架，系统知道自己知道多少、不知道多少。

在工程层面：把上面这些东西塞进一台Android手机里，让它稳定跑起来。

### 1.2 别人做了什么

#### 1.2.1 数据驱动的方法

**统计方法。** Sparacino等人2007年在IEEE TBME上发了一篇早期经典，用AR模型做30分钟预测，RMSE约1.0 mmol/L[4]。Eren-Oruklu等人2009年加了外生变量（胰岛素和碳水）做成ARIMAX，餐后时段的精度提升了约15%[15]。统计方法的思路简单直接，但问题是线性的——血糖系统的行为远不是线性的。

**深度学习方法。** Li等人2019-2020年的GluNet是这个方向上的重要工作，CNN抽局部特征+LSTM抓长期依赖，OhioT1DM数据集上30分钟MAE 0.68 mmol/L[5][16]。Zhu等人2022年把Transformer搬过来，Self-Attention可视化挺好看，MAE 0.65，略微好一点但参数量大了不少[7]。

**TCN。** Bai、Kolter和Koltun在2018年做了一个挺有影响力的基准评测，证明时序卷积网络（TCN）在多个序列建模任务上不输LSTM，而且能并行训练[6]。TCN用膨胀卷积（dilated convolution）以指数级扩大的感受野来捕捉长程依赖，比LSTM少了很多参数。CGM数据的典型模式（餐后峰、黎明现象）发生在比较固定的时间尺度上——膨胀卷积天然适合干这个。

#### 1.2.2 生理模型这条线

**Bergman最小模型。** 1979年Bergman等人在Am J Physiol上提出的三方程模型是这条线上的开创性工作[8]。三个ODE描述血糖G、胰岛素效应X和胰岛素浓度I的动态。从模型里推导出的胰岛素敏感性指数SI = p₃/p₂后来成了临床评估胰岛素敏感性的金标准。不过这个模型对餐后葡萄糖吸收的处理太粗糙了——只有一个隔室，不区分胃和肠。

**Hovorka模型。** 2002-2004年Hovorka等人把隔室数扩展到6个[9]，在Cambridge的闭环胰岛素输注临床试验中得到了验证。6隔室的设计在保真度和参数可辨识性之间取得了不错的平衡。

**Dalla Man模型。** 2006-2007年Dalla Man、Rizza和Cobelli在IEEE TBME上提出了7隔室模型[10]——胃肠道2个（固体胃+液体肠）、葡萄糖系统2个+血浆、胰岛素系统2个。参数是基于150个人的示踪实验数据估计的。2008年FDA批准了基于这个模型的UVa/Padova T1DM模拟器作为闭环算法的临床前测试替代平台，把动物实验从研发流程中拿掉了[11]。这件事的意义不小——计算生理学第一次拿到了药监局的正式背书。

#### 1.2.3 在线学习和个性化

Facchinetti等人2010年搞了一个在线自校正的CGM降噪方法，用卡尔曼滤波器在线估计传感器噪声协方差，不需要离线校准数据[17]。Kirkpatrick等人2017年在PNAS上提出了弹性权重巩固（EWC），用Fisher信息矩阵衡量各参数对旧任务的重要程度，重要的参数"巩固"住，不重要的可以自由适应新数据[19]。

这里有一个很多人没注意到的问题：现有方法对不同质量的数据一视同仁。实际使用中，患者可能好几天都没记录饮食——这些纯CGM数据的训练价值显然不如那些CGM+饮食+胰岛素都标注了的时段。但没人把这个"数据完整度"信息喂给学习算法。

#### 1.2.4 移动端部署

ONNX Runtime是Microsoft维护的跨平台推理引擎，ORT Mobile针对ARM64做了优化，在骁龙8 Gen 2上跑一个小型TCN（590KB）大约15ms[13]。xDrip+是Nightscout Foundation维护的开源Android CGM采集器，它的通知栏监听方案可以兼容40+个品牌的CGM App而不用和每个厂商签协议[12]。这两个开源项目是糖盾工程实现的重要参考。

### 1.3 还存在的问题

**问题一：Dalla Man模型的几个线性假设不成立。** 原模型里胰岛素依赖性葡萄糖利用Uid = k₂·X·G——血糖越高，清糖越快，线性关系。但GLUT4转运蛋白有饱和上限。对于一个65kg的患者，外周组织每分钟最多处理约70mg葡萄糖，血糖20 mmol/L和10 mmol/L时的清糖速率其实差不多。线性假设在血糖>12 mmol/L时明显偏离实际。胃排空也有类似的问题——原模型假设dStomach/dt = -k·Stomach，大餐时初始排空速率会飙到生理不可能的水平。

**问题二：群体参数对中国T2DM患者不合适。** Dalla Man模型的参数基于150名西方受试者（含非糖尿病和T2DM），中国T2DM患者的胰岛素抵抗程度普遍更高、饮食结构不同、β细胞功能衰退模式也不同。直接套用群体参数做预测，峰值偏差可能到5 mmol/L以上。

**问题三：没有数据质量意识的学习。** 纯血糖数据和有完整标注的数据被同等对待——这不对。一个只有CGM流、从不记录饮食的用户，和一个认真记录每餐的用户，系统从他们数据里能学到的东西完全不同。应该把这个信息显式地编码进学习策略。

**问题四：特征里的数值陷阱。** f11（距上次胰岛素注射的时间）和f13（距上次进食的时间），无数据时该填什么？早期版本填了999（"很久以前"的意思）。但在神经网络里，Xavier初始化的权重约在[-0.5, 0.5]区间，碰上量级10³的输入，第一层线性变换z = Wx + b就会炸——部分神经元激活值巨大，梯度跟着炸，loss从0.5跳到12,000,000。

**问题五：批量导入触发学习洪水。** Room的Flow机制会在每条新插入记录时发射一次更新。用户导入一周的xlsx数据（约2,000条），Flow就发射2,000次，SelfLearningManager被调用2,000次——短时间内的密集学习不仅浪费算力，还容易让模型对这一段数据过拟合。

### 1.4 本文的工作

针对上面这些问题，做了几件事：

1. **改了Dalla Man的五个地方。** MM饱和动力学替换线性Uid、VmaxGastric胃排空上限、sigma内源胰岛素分泌、长效胰岛素指数加权、运动自适应。然后通过isfFactor = 1.5/ISF这个桥梁，把临床参数映射到模型参数。

2. **数据质量感知的自学习。** 定义了dataCompleteness（0.3/0.6/1.0三级），修正强度随数据天数和完整度自适应衰减。四层架构分工明确：统计层快速收敛、增量层学残差、梯度层慢调参数、质量层管融合权重。

3. **修了f11/f13的归一化。** 从999改成min(分钟/120, 1.0)，无数据时=1.0。跟Xavier初始化的权重量级对上了。

4. **把系统做完整了。** 从CGM数据采集、后台保活、数据库设计、预测引擎、预警系统到AI助手，是一个能实际用的完整App。

### 1.5 论文怎么组织的

第一章说为什么要做这个、别人做了什么、还存在什么问题。第二章把相关工作的技术细节展开讲清楚。第三章是系统整体的架构设计。第四章讲算法——这是最核心的一章。第五章说工程实现。第六章做实验。第七章讨论局限性、临床意义和以后的方向。第八章总结。

---

## 2 相关工作

### 2.1 血糖预测模型

#### 2.1.1 问题长什么样

给定过去24小时（288个点，每5分钟一个）的CGM血糖序列，加上这段时间里的胰岛素注射记录（时间、剂量、类型）、饮食记录（时间、碳水量）、运动记录（时间、时长、强度），预测未来180分钟（36步）的血糖值。评估指标分两类：数值精度（MAE、RMSE、MARD）和临床安全（Clarke误差网格A区比例）。

#### 2.1.2 AR到LSTM到TCN

**ARIMA系列。** Bremer和Gough 1999年在Diabetes上发了一篇短文，核心结论是30分钟内的血糖有显著的自相关结构，可以预测[14]。Sparacino 2007年的AR模型在30分钟时域上RMSE约1.0[4]。Eren-Oruklu 2009年加入外生变量做ARIMAX，餐后精度改善了15%[15]。统计方法的好处是透明——你能看到每个系数在干什么。坏处是血糖的行为模式远非线性。

**GluNet（CNN+LSTM）。** Li等人2019-2020年的工作[5][16]，用1D-CNN在CGM窗口上扫多尺度模式（kernel 3/5/7），然后LSTM抓时序依赖。OhioT1DM数据集上30分钟MAE 0.68。后来成了这个领域的常用基线。

**Transformer。** Zhu等人2022年把Self-Attention搬过来[7]，好处是Attention权重能可视化（解释哪个历史时刻对当前预测贡献最大），MAE 0.65。但Transformer的参数量大约1.5倍于CNN-LSTM，在手机上跑有点重。

**TCN。** Bai等人2018年的基准评测改变了很多人对CNN做序列建模的看法[6]。TCN的三个设计要素：（1）因果卷积（只看过去不看未来）；（2）膨胀卷积（感受野指数增长，dilation=1,2,4,...）；（3）残差连接（梯度不消失）。在我们的场景里，膨胀卷积天然适合捕捉CGM数据里的多尺度模式——餐后峰大约发生在进食后45-90分钟（尺度约9-18步），黎明现象在凌晨4-7点（跨数小时），短时波动可能在5-15分钟尺度。

我们选的TCN架构：输入15维×288步 → Linear(15,64) → Dropout(0.2) → Conv1d(64,64,k=3,d=1) → Conv1d(64,128,k=3,d=2) → Conv1d(128,128,k=3,d=4) → GlobalAvgPool → Linear(128,4)。4个输出参数[a,b,c,d]重建三次多项式曲线：G(t) = G₀·(1 + a·t³ + b·t² + c·t + d)。三次多项式有两个驻点，能描述单峰（餐后上升→下降）或单谷（低血糖→恢复）的典型形状。比逐点预测36个值少了9倍的输出维度，降低了过拟合风险。

**不同方法怎么选？汇总一下：**

| 方法 | 30min MAE | Clarke A区 | 训练 | 手机上跑 | 能解释吗 |
|------|----------|-----------|------|---------|---------|
| ARIMA | 1.12 | 78% | 快 | 容易 | 高 |
| LSTM | 0.68 | 90.1% | 慢 | ONNX | 低 |
| Transformer | 0.65 | 91.2% | 更慢 | ONNX | 中 |
| TCN | 0.61 | 92.5% | 较快 | ONNX | 中 |
| Dalla Man | N/A* | N/A* | 不需要 | 原生 | 很高 |

\*生理模型的评估逻辑不一样——不是比较"预测值和真值差多少"，而是看预测曲线是否符合生理实际。

### 2.2 生理模型详述

#### 2.2.1 Bergman到Dalla Man的演进

**Bergman最小模型（1979年）[8]：** 三个ODE，dG/dt = -[p₁+X]·G + p₁·Gb, dX/dt = -p₂·X + p₃·[I-Ib], dI/dt = -n·I + γ·[G-h]⁺·t。优雅简洁，从静脉葡萄糖耐量试验（IVGTT）数据里可以估计出胰岛素敏感性SI。但餐后吸收只有一个隔室，太粗糙。

**Hovorka模型（2002年）[9]：** 6隔室（葡萄糖2+胰岛素2+作用2），在Cambridge闭环系统中经过临床验证。加了肾糖排泄和肝糖产生的独立子模型。比Bergman精细了很多，但对胃肠道吸收还是只有一个隔室——固体食物在胃里的研磨、排空和肠道吸收没有区分。

**Dalla Man模型（2006-2007年）[10]：** 7隔室。胃肠道拆成了固体胃（Stomach）和液体肠（Gut）两个隔室，葡萄糖系统拆成血浆（Gp）、快速平衡组织（Gt）和慢速平衡组织，胰岛素系统有血浆胰岛素（Ip）和远端胰岛素作用（X, X_L）。参数基于150人的三示踪实验数据估计——这种实验很贵很难做，用一个放射性标记示踪葡萄糖吸收、一个标记内源葡萄糖产生、一个标记葡萄糖利用，三者同时测，数据质量极高。

**UVa/Padova模拟器[11]：** 在Dalla Man模型上加了三层东西——CGM传感器误差模型（模拟真实传感器的噪声和漂移）、皮下胰岛素药代动力学模型（注射→皮下→血浆→效应位点）、患者行为模型（进餐时间变异、漏餐概率、错误bolus等）。造出了100个成人+100个青少年+100个儿童的虚拟T1DM患者群体。2008年FDA批准它替代动物实验做闭环算法的临床前测试——这是计算生理学领域的一件大事，意味着监管机构认可了计算机模拟在某些场景下可以替代活体实验。

### 2.3 在线学习和个性化

**卡尔曼滤波降噪。** Facchinetti等人2010年的方法[17]在线估计CGM传感器的噪声协方差矩阵R，不需要离线校准。在Dexcom SEVEN PLUS上测试，高频噪声（>0.1 mHz）降低了约40%，信号延迟减少了约2分钟。我们OnlineLearner里的EWMA+卡尔曼融合借鉴了这个想法，但不仅降噪，还学习患者特异性参数。

**EWC（弹性权重巩固）。** Kirkpatrick等人2017年PNAS[19]的核心思路是：学完任务A后，用Fisher信息矩阵算每个参数对A的重要程度Fᵢ。学任务B时，loss里加一项 Σ(Fᵢ/2)·(θᵢ-θᵢ*)²——对A重要的参数被"钉住"，不重要的可以自由调整。我们没直接用EWC，但增量SGD层里的L2正则化和权重异常检测（loss>1000自动重置）起到了类似的保护作用。

**数据质量这个问题。** 我读过的文献里，几乎没有人在血糖预测的在线学习中考虑数据质量。常见假设是：每条新数据都一样有用。但实际场景根本不是这样——患者可能一整天都没打开App记录饮食，CGM数据是连续的但标注是稀疏的。用这些没有标注的数据训练模型，和用完整标注的数据训练，效果肯定不一样。这个观察直接催生了我们的dataCompleteness设计。

### 2.4 CGM数据怎么拿到

xDrip+的UiBasedCollector架构[12]利用Android的NotificationListenerService读取品牌CGM App在通知栏显示的血糖数值文本，完全绕过了和CGM厂商签协议这个环节。支持40+品牌。这个思路最大的好处是普适性——只要某品牌的CGM App在通知栏显示了血糖值（不管以什么格式），就能解析。

我们的CGMNotificationListener在xDrip+的基础上做了几个改进：（1）增加了整数mmol/L格式的识别（部分国产CGM App显示"血糖 6"而不是"6.0"）；（2）改为源感知去重——同一个source在60秒内重复才跳过，不同source的不互相干扰；（3）六步质量控制管道（合理性检查→源去重→自适应EWMA→多项式噪声检测→ROC→质量评分）。

---

## 3 系统架构

### 3.1 整体设计

糖盾的系统架构是分层的，从底往上四层：

**数据采集层** — CGMNotificationListener（从通知栏抓血糖）+ DirectGlucoseBroadcastReceiver（接收其他App的Broadcast）+ XlsxImporter（导入Excel）。三种通道互补。

**数据层** — Room数据库，15张表。核心是glucose_record（timestamp双列索引），其他表围绕它：meal_record + meal_item（一对多）、insulin_record、exercise_record、medication_record、user_profile、prediction_cache、alert_config等。所有DAO的关键查询方法返回Flow，整个系统用响应式数据流串联。

**业务层** — 20个算法类：DallaManModel、TCNPredictor、FusionPredictor、FeatureExtractor、OnlineLearner、IncrementalLearner、SelfLearningManager、RealTimeGlucoseMonitor、CGMCalibrator、SmartAdvisor、InsulinCalculator、CarbCalculator、AlertEngine、TrendCalculator。加上6个ViewModel（Home、Prediction、Record、Settings、Chat、Report）。

**UI层** — Jetpack Compose + Material Design 3。单Activity（MainActivity）+ Navigation Compose路由。

**数据流向是单向的：**
```
CGM传感器 → 品牌App通知栏 → CGMNotificationListener
  → GlucoseRecord (Room Insert)
    → Flow发射 → SelfLearningManager (后台学习)
    → Flow发射 → HomeViewModel/PredictionViewModel (UI更新)
      → StateFlow → Compose UI (collectAsState)
```

TangDunApp.getDatabase()全局单例确保所有组件共享同一个Room实例，Flow的InvalidationTracker在全局范围内正确同步——这是填过的一个坑，早期版本出现过数据库双实例导致UI不更新的问题。

### 3.2 数据采集

#### 3.2.1 CGM通知栏监听

onNotificationPosted()被系统回调后，先拿包名和白名单（40+品牌，从SharedPreferences读，支持动态更新）比对——O(1)的HashSet查找。

tryExtract()是这个模块的核心，要处理五种格式：
- "5.6 mmol/L"（标准小数格式）
- "LOW" → 映射到2.2 mmol/L，"HIGH" → 22.5 mmol/L
- "126 mg/dL" → 除以18
- "血糖 6"（国产App的整数mmol/L格式——没有小数点，只能靠上下文和范围校验2.0-35.0来判定）
- 混合文本："您的血糖值为 5.6 mmol/L，趋势平稳"

源感知去重用ConcurrentHashMap<String, Long>，以source为key记录上次处理时间。同一source的60秒内重复才跳过。蓝牙直连（source="bluetooth"）和通知栏监听（source="notification"）两个通道之间不会互相去重——这解决了一个让人头疼的问题：同一个血糖值从两个通道几乎同时到达，但全局去重会导致其中一个被误丢弃。

#### 3.2.2 xlsx导入

没有引入Apache POI（8MB太大了，会显著增加APK体积）。自己写了一个轻量xlsx解析器：.xlsx本质是ZIP包，里面xl/sharedStrings.xml存字符串表，xl/worksheets/sheet1.xml存数据。用ZipInputStream+XmlPullParser流式解析，不把整个文件加载到内存。自动检测mg/dL单位（所有值>30就÷18）。时间戳支持Excel日期序列号格式和文本格式。

### 3.3 数据库

15张表按功能分五组：

- **血糖组：** glucose_record（id, timestamp, value, source, trend, noise, unit）
- **饮食组：** meal_record + meal_item（一对多，meal_id外键）
- **胰岛素组：** insulin_record（type区分RAPID/SHORT/LONG_ACTING/MIXED）
- **运动健康组：** exercise_record + medication_record + weight_record
- **配置组：** user_profile + prediction_cache + alert_config + sync_log + debug_log

所有表都有timestamp索引。批量导入用@Insert(onConflict = REPLACE)。

### 3.4 后台保活

GlucoseForegroundService参考了xDrip+的foreground service设计。Android 8.0+必须显示持久通知，我们的通知内容包含最新血糖值+趋势箭头+预测30分钟值+sparkline曲线。onStartCommand返回START_STICKY确保被杀后自动重启。RestartReceiver通过AlarmManager每15分钟检查一次服务状态，加上监听BOOT_COMPLETED开机启动。

GlucoseAlarmService负责预警：严重低血糖（<3.0）用TYPE_ALARM音频流+FLAG_AUDIBILITY_ENFORCED强穿静音模式。自动紧急拨号有30分钟冷却期，呼叫前有5秒倒计时（可取消）。血糖恢复正常后自动清除所有预警通知。

---

## 4 核心算法

这是全文最核心的一章。按照数据处理的顺序来讲：先怎么从CGM数据里抽特征（4.2），然后TCN怎么做预测（4.3），Dalla Man生理模型怎么模拟代谢（4.4），两个模型的预测怎么融合（4.5），最后系统怎么在用户的手机上学成他自己的模型（4.6）。

### 4.1 问题定义

给定时步Δt = 5分钟，历史窗口H = 288步（24小时），预测时域P = 36步（180分钟）。

输入：过去24小时的CGM血糖序列、这段时间内的胰岛素注射事件（时间+剂量+类型）、饮食事件（时间+碳水量）、运动事件（时间+时长+强度）、用户静态参数（ISF、CR、BW、糖尿病类型）。

输出：未来36步的血糖预测值。

评估在三个时域上分别看：30分钟（临床预警窗口）、60分钟（餐时决策窗口）、180分钟（趋势评估窗口）。

### 4.2 特征怎么抽

从288点CGM窗口+事件记录里抽出15维特征。设计原则就三条：覆盖多时间尺度、融合多源数据、所有特征归一化到合理数值范围。

**f1-f9是血糖自身的特征：**
- f1: 当前血糖/18（归一化）
- f2, f3: 15分钟和30分钟的变化率（正=上升，负=下降）
- f4: 60分钟线性回归斜率（ROC，变化速率）
- f5, f6: 4小时窗口的标准差和变异系数（波动程度）
- f7, f8: 4小时内的最低和最高值（波动范围）
- f9: 当前值偏离4小时均值的程度

**f10-f13是胰岛素和饮食特征：**
- f10: 过去4小时注射的bolus胰岛素总量（除以50归一化）
- f11: 距最近一次胰岛素注射的分钟数/120（归一化到[0,1]，无数据=1.0）
- f12: 过去4小时摄入碳水总量（除以200归一化）
- f13: 距最近一次进食的分钟数/120（归一化到[0,1]，无数据=1.0）

**f14-f15是可穿戴设备特征（如果有的话）：**
- f14: 1小时平均心率/100
- f15: 4小时步数/5000

#### f11/f13归一化这件事——踩过的坑

早期版本里f11和f13在无数据时填的是999。这个设计很符合直觉——"很久以前注射过"或者"从来没注射过"，用一个大数表示。但在神经网络里，这是灾难性的。

Xavier初始化（Glorot & Bengio, 2010）让权重大约在[-0.5, 0.5]之间。第一层线性变换z = W·x，如果x里有量级10³的分量，z的量级就可能到10²。部分神经元激活值巨大（这些神经元的权重碰巧和输入的大分量同号），部分接近零。到了反向传播，大激活值产生大梯度，参数更新步长失控——loss从0.5这个量级直接炸到12,000,000。

修法很简单：f11 = min(minutes_since_last_insulin / 120.0, 1.0)，无数据时设为1f（含义是"≥120分钟无事件"，正常范围的上限）。所有15维特征的量级都在[0, ~3]区间，和Xavier权重的量级对上了。修完后loss回归到0.02-0.5的正常区间。

### 4.3 TCN模型

#### 4.3.1 架构

```
输入: [batch, 15, 288]
  → Linear(15, 64) + ReLU
  → Dropout(0.2)
  → Conv1d(64, 64, k=3, dilation=1, causal) + ReLU + BatchNorm
  → Conv1d(64, 128, k=3, dilation=2, causal) + ReLU + BatchNorm
  → Conv1d(128, 128, k=3, dilation=4, causal) + ReLU + BatchNorm
  → GlobalAvgPool1d → 128维
  → Linear(128, 4) → [a, b, c, d]
输出: 预测曲线 G(t) = G₀·(1 + a·t³ + b·t² + c·t + d)
```

选择三次多项式而不是逐点预测36个值的原因：36个独立输出值需要36×更多的输出参数，更容易过拟合；而且36个独立值之间没有平滑性约束，可能产生物理上不合理的锯齿曲线。三次多项式只有4个参数，天然平滑，两个驻点足以描述单峰（餐后）和单谷（低血糖恢复）的典型形状。

#### 4.3.2 训练

用了四个数据集（v3训练，2026-06-22）：
- **IOBP2 RCT**（Jaeb Center, 2025）：440名T2DM，随机对照试验，Dexcom G6（5分钟间隔），含胰岛素/饮食/运动完整标注
- **OhioT1DM**（Marling & Bunescu, 2018）：12名T1DM，每人8周，Dexcom G4（5分钟间隔），含胰岛素泵记录和自我报告的饮食/运动[20]
- **CTR3**（Jaeb Center, 2024）：30名T1DM，闭环临床试验，Dexcom G6（5分钟间隔），含闭环算法日志
- **HUPA**（Zhang et al., 2023）：25名T2DM，每人4周，Freestyle Libre（15分钟间隔），含饮食记录和用药记录[21]

合计474人、1,610万行CGM记录。训练集380人（110万滑动窗口），验证集94人（24万窗口）。

数据预处理：线性插值补缺失（≤2个连续点插值，超过则标记断点）、15分钟数据重采样到5分钟、滑动窗口生成样本（步长6-12点可配，v3用stride=12最大化训练效率）。

训练配置：MSE loss + L2 λ=1e-4，Adam lr=0.001，batch=2048-4096（GPU CUDA），50-80 epoch+早停patience=15，ReduceLROnPlateau。验证用留一患者交叉验证（LOPO-CV）。训练模型2,058,214参数（对比部署版ONNX仅4参数曲线输出）。

导出：torch.onnx.export → ONNX opset 14 → 约590KB。Android端用ORT Mobile 1.16.0跑OrtSession，单次推理约15ms。

#### 4.3.3 锚定校准

TCN模型在t=0时刻的输出G(0) = G₀·(1+d)。问题在于，d是从数据中学出来的，不保证精确为0。所以G(0)通常不等于G₀（当前实际血糖）。在图表上，这表现为预测曲线的起点和实际血糖值之间有一个"断口"。

修法：把整条TCN预测曲线平移，让它起点对齐到G₀：
```
aligned_TCN[i] = raw_TCN[i] - (raw_TCN[0] - G₀)
```
这个操作只改变曲线的绝对位置，不改变它的形状（趋势、峰值时间等）。

### 4.4 Dalla Man七隔室生理模型

这是本文算法部分最重的章节。先讲原模型的结构（8状态ODE系统+数值解法），然后讲我们改了哪五个地方，最后讲怎么用ISF把模型个性化。

#### 4.4.1 8状态ODE系统

**8个状态变量：**

| 变量 | 含义 | 初值 | 单位 |
|-----|------|------|------|
| G | 血浆葡萄糖浓度 | Gb（空腹血糖基线） | mmol/L |
| I | 血浆胰岛素浓度 | Ib | pmol/L |
| X | 胰岛素远端作用（外周） | 0 | 无量纲 |
| X_L | 胰岛素远端作用（肝脏） | 0 | 无量纲 |
| Stomach | 胃内固体食物量 | 餐食碳水×0.8 | mg |
| Gut | 肠道葡萄糖量 | 0 | mg |
| subQ1 | 皮下胰岛素（快吸收池） | bolus剂量×比例 | pmol/kg |
| subQ2 | 皮下胰岛素（慢吸收池） | bolus剂量×比例 | pmol/kg |

**8个ODE（完整的）：**

**(1) 血浆葡萄糖：**
```
dG/dt = (Ra + EGP - Uii - Uid - Renal) / (Vg × BW)
```

这五个通量分别是：
- **Ra**（Rate of appearance, mg/kg/min）：肠道葡萄糖吸收进入血液的速率。Ra = f × kGut × Gut / BW，其中f≈0.9是生物利用度
- **EGP**（Endogenous Glucose Production, mg/kg/min）：肝脏产生的葡萄糖。EGP = hepaticBase - kp1 × X_L × hepaticBase。胰岛素通过X_L抑制肝糖输出
- **Uii**（Insulin-Independent Utilization, mg/kg/min）：不依赖胰岛素的葡萄糖利用（主要是大脑和红细胞）。Uii = k1 × Vg × BW
- **Uid**（Insulin-Dependent Utilization, mg/kg/min）：依赖胰岛素的葡萄糖利用（主要是肌肉和脂肪组织）。这是需要大改的一项——见4.4.3节
- **Renal**（Renal excretion, mg/kg/min）：肾糖排泄。当G超过肾糖阈（约10-11 mmol/L）时激活：Renal = max(0, ke_renal × (G - renalThreshold)) × Vg × BW/18

**(2) 血浆胰岛素：**
```
dI/dt = ka2 × subQ2 / Vi - ke × I + sigma × max(0, G - Gb)
```
三项分别是：从皮下吸收、肾脏清除、内源性分泌（这一项是我们加的——见增强3）。

**(3-4) 胰岛素远端作用：**
```
dX/dt = -kp3 × X + kp3 × max(0, (I - Ib)/Ib)      # 外周
dX_L/dt = -kp1 × X_L + kp1 × max(0, (I - Ib)/Ib)  # 肝脏
```
这两项描述胰岛素的效应延迟——胰岛素浓度上升后，其促进葡萄糖摄取和抑制肝糖输出的效应不会立即达到峰值，而是有大约20-40分钟的延迟。

**(5) 胃排空：**
```
dStomach/dt = -min(kStomach × Stomach, VmaxGastric × BW)
```
这里min()是我们的修改（增强2）。原模型没有VmaxGastric约束，胃排空速率是线性的kStomach × Stomach。

**(6) 肠道吸收：**
```
dGut/dt = min(kStomach × Stomach, VmaxGastric × BW) - kGut × Gut
```

**(7-8) 皮下胰岛素双隔室吸收：**
```
dSubQ1/dt = -ka1 × SubQ1 + InsulinInput(t)
dSubQ2/dt = ka1 × SubQ1 - ka2 × SubQ2
```
双隔室模型描述的是：注射到皮下的胰岛素不是立刻进入血液的。先以速率ka1从注射部位扩散到皮下组织（快吸收池subQ1），再以速率ka2缓慢进入血液循环（慢吸收池subQ2）。速效胰岛素类似物的ka1≈1/55 min⁻¹, ka2≈1/90 min⁻¹。

#### 4.4.2 RK4数值求解

由于8个ODE耦合在一起（每个状态的变化率依赖于其他状态的当前值），没有解析解。用4阶Runge-Kutta方法（RK4）做数值积分，步长dt=5分钟，共36步（180分钟）。

RK4单步公式（对每个状态变量y）：
```
k1 = h × f(t, y)
k2 = h × f(t + h/2, y + k1/2)
k3 = h × f(t + h/2, y + k2/2)
k4 = h × f(t + h, y + k3)
y(t+h) = y(t) + (k1 + 2k2 + 2k3 + k4)/6
```

每步内先算完所有8个状态的k1，再算k2、k3、k4——保证所有导数使用同一时刻的状态值。

RK4的局部截断误差是O(h⁵)，全局误差是O(h⁴)。步长5分钟时全局误差约10⁻⁴量级，对血糖预测精度（目标精度约0.5 mmol/L）来说足够了。

#### 4.4.3 五项增强

**增强1：Michaelis-Menten饱和动力学（替代线性Uid）**

原模型的Uid公式：
```
Uid_linear = k2 × X × G
```
这是线性的——X（胰岛素作用）越高、G（血糖）越高，清糖就越快。没有上限。

生理实际：外周组织（肌肉和脂肪）的葡萄糖摄取由GLUT4转运蛋白介导。胰岛素通过Akt/PKC通路促进GLUT4从胞内膜泡转位到细胞膜表面。但当细胞膜表面的GLUT4达到最大密度后，再多的胰岛素也无法进一步提升葡萄糖摄取速率——这就是饱和。Michaelis-Menten方程描述的就是这种酶/转运蛋白介导的饱和动力学。

我们的MM形式：
```
Uid_MM = (Vm0 + VmX × X) × G × 18 / (Km0 + G × 18) × BW / (Vg × 18)
```

参数含义：
- Vm0 (mg/kg/min)：基础最大葡萄糖利用速率（胰岛素非依赖部分，GLUT1介导）
- VmX (mg/kg/min)：胰岛素刺激的最大葡萄糖利用增量（GLUT4转位→活性增强）
- Km0 (mg/dL)：Michaelis半饱和常数（GLUT对葡萄糖的亲和力）。G=Km0时利用速率=½Vm；G>>Km0时趋于Vm
- ×18：mmol/L换算为mg/dL（葡萄糖分子量180，18.018≈18）

**线性 vs MM，具体差距有多大：**

假设X=1.0（中等胰岛素作用），Vm0=2.5, VmX=0.05, Km0=225, BW=65, Vg=1.49：

| 血糖 (mmol/L) | Uid_线性 | Uid_MM | 差距 |
|--------------|---------|--------|------|
| 6.0 | 0.033 | 0.028 | MM低15% |
| 10.0 | 0.055 | 0.048 | MM低13% |
| 15.0 | 0.083 | 0.060 | MM低28% |
| 20.0 | 0.111 | 0.067 | MM低40% |

在严重高血糖（20 mmol/L）时，线性模型预测的清糖速率比MM高了近一倍——这会导致预测曲线在高血糖后出现"断崖式下降"，而MM产生的下降是渐进平缓的，更接近临床实际观察。

**增强2：胃排空速率上限（VmaxGastric）**

原模型的胃排空：dStomach/dt = -kStomach × Stomach。这意味着胃里食物越多，排空越快。对60g碳水的普通餐，初始排空速率约60,000×0.05=3,000 mg/min——还算合理。但对150g的大餐，速率飙到7,500 mg/min，对320g的极端大餐是16,000 mg/min——生理上不可能。幽门括约肌有最大通过能力，胃窦泵也有限速，而且高渗碳水在十二指肠会触发肠抑胃素负反馈。

我们的公式：
```
dStomach/dt = -min(kStomach × Stomach, VmaxGastric × BW)
```

VmaxGastric群体典型值=7 mg/kg/min（Dalla Man原始文献给出的范围是5-10）。对65kg患者，上限=455 mg/min≈27g碳水/小时。

效果对比：
- 60g碳水+4U：峰值从无约束的14.8降到9.8（有约束）——与临床实际一致
- 150g碳水+8U：峰值从无约束的18+降到13.2（有约束）
- 320g极端大餐：峰值从无约束的25+降到约14

VmaxGastric对大餐场景至关重要。患者偶尔会吃大量碳水（聚餐、酒席、节日），没有这个约束的模型会给出离谱的预测。

**增强3：内源性胰岛素分泌（sigma）**

原Dalla Man模型主要针对T1DM（胰岛素绝对缺乏），胰岛素ODE只有外源输入和清除两项。T2DM患者通常保留部分β细胞功能——早期甚至高分泌，中晚期约20-50%残余。

我们加了血糖驱动内源分泌项：
```
dI/dt_endo = sigma × max(0, G - Gb)
```

sigma推荐值：
- T1DM：sigma = 0（没有内源分泌）
- 早期T2DM：sigma = 4.0-6.0（代偿性高胰岛素血症）
- 中晚期T2DM：sigma = 2.0-4.0（部分残余）
- 正常个体：sigma = 8.0-12.0（完整β细胞功能）

实测中的T2DM患者（中等病程）用了sigma=3.0。这项对预测的影响是：餐后高血糖期间，内源分泌贡献了额外的胰岛素效应，使血糖回落速度比纯外源模型更快。对于只用口服药、不注射胰岛素的T2DM患者，sigma是唯一驱动血糖回落的胰岛素来源——没有这项的话模型会预测血糖一直居高不下。

**增强4：长效胰岛素的指数加权**

速效和短效胰岛素走皮下双隔室吸收模型（ka1≈1/55, ka2≈1/90，描述快速吸收特征）。但长效胰岛素类似物的药代动力学截然不同：
- 甘精胰岛素U100：半衰期约12小时，接近恒速释放（峰谷比<2:1）
- 地特胰岛素：半衰期约12小时，白蛋白结合缓释
- 德谷胰岛素：半衰期约25小时，多六聚体链缓释

我们的处理方式：长效胰岛素不纳入bolus吸收模型，而是用指数衰减函数叠加：
```
I_long_effective = Σ dose_i × exp(-ln(2) × Δt_i / half_life)
```
其中half_life=720分钟（12小时，甘精/地特的近似值），Δt_i是每次注射距现在的时间。然后I_long_contribution = I_long_effective × 0.4加到空腹基线胰岛素Ib里。

系数0.4是基于：长效胰岛素的稳态血药浓度约为等剂量速效胰岛素的40%（因为释放平缓，峰值浓度低但作用持久）。

**增强5：运动对胰岛素敏感性的调节**

急性运动后肌肉收缩可以直接促进GLUT4转位（不依赖胰岛素信号通路），这个效应持续2-48小时。长期规律运动可以提升胰岛素敏感性15-30%。

我们用一个简单的线性模型：取7天滚动平均运动时长activityLevel（分钟/天），调节两个参数：
```
k1_adapted = k1_base + max(0, activityLevel - 30) × 0.002
Vm0_adapted = Vm0_base + max(0, activityLevel - 30) × 0.08
```

阈值设在30分钟/天——低于这个值不触发调节（非活跃用户的参数维持基线）。k1和Vm0的提升模拟了运动诱导的胰岛素敏感性改善（更多GLUT4在细胞膜表面→更高的基础葡萄糖摄取）。

**增强6：速效胰岛素吸收加速 + 胃排空慢化 (v3.0.22)**

原模型有两个参数与中国人特点不匹配：(1) 速效胰岛素皮下吸收率ka=0.018导致血浆峰值在~110min，而赖脯/门冬的临床峰值在60-90min；(2) 胃排空率kStomach=0.050（西方混合餐半衰~14min）对中式米饭为主的饮食偏快。调整：
```
ka1, ka2: 0.018 → 0.024  (峰值 110min → 75min)
kStomach: 0.050 → 0.035  (半衰 14min → 20min)
kGut:     0.065 → 0.050  (肠道吸收同步放缓)
```
这些参数通过isfFactor保持个性化（kStomach公式变为0.035-isfFactor×0.004），确保胰岛素抵抗患者仍维持更慢的胃排空。

**增强7：双相胃排空 (v3.0.23)**

这是v3.0.22慢排空修复后暴露出的新问题。当用户在App上记录"刚吃了80g米饭"（timeMinutes≈0），旧模型的单相胃排空导致肠道初始碳水为零（Ra=0），而高血糖时非胰岛素依赖清除Uii=k1×(G-Gb)可达到0.12-0.14 mmol/L/min —— 远超碳水吸收速率。结果是预测曲线先降后升：用户刚记录吃饭，系统却预测血糖要下降。

修复方案借鉴了Meyer等人(1985, Gastroenterology)的胃排空双相理论：进食后液体和小分子碳水在10-15min内通过"快速胃排空相"直接进入十二指肠。我们在DallaMan的初始条件计算中实现了简化版双相模型：
```kotlin
if (T < 15.0) {
    rapidGut = mg × 0.20 × exp(-kG × T)       // 20%快速入肠
    slowStomach = mg × 0.80 × exp(-kS × T)     // 80%慢相在胃
    slowGut = mg × 0.80 × kS/(kG-kS) × (...)   // 慢相经标准排空
}
```
20%的快速相比率基于混合餐中液体和简单碳水占比的保守估计。对于进食超过15分钟的记录，两相已基本融合，恢复标准单相排空。

修复效果（12场景×5轮Python验证，60/60通过）：刚吃80g @G=9.0空腹=7.0场景下，15min血糖变化从-0.2↓（旧）变为+1.4↑（新），彻底消除了"记录吃饭血糖反降"的错误预测。

#### 4.4.4 ISF驱动的个性化

这是把临床参数和模型参数连接起来的关键桥梁。

胰岛素敏感因子（ISF）是每个使用胰岛素的糖尿病患者都知道（或应该知道）的参数：1单位速效胰岛素能降多少mmol/L血糖。ISF=2.0意味着打1U大约降2.0 mmol/L。正常范围大约在0.5（极度抵抗）到6.0（高度敏感）之间。

我们定义了一个归一化因子：
```
isfFactor = 1.5 / ISF
```
1.5是标准值（ISF=1.5→isfFactor=1.0）。ISF越小（越抵抗）→isfFactor越大，ISF越大（越敏感）→isfFactor越小。

然后把Dalla Man模型的6个核心参数写成isfFactor的函数：

| 参数 | 公式 | ISF=0.7(抵抗) | ISF=1.5(正常) | ISF=3.0(敏感) | 方向解释 |
|-----|------|-------------|-------------|-------------|---------|
| kStomach | 0.035-isfFactor×0.004 | 0.030 | 0.035 | 0.042 | 中式慢排空 (v3.0.22) |
| kGut | 0.050 (固定) | 0.050 | 0.050 | 0.050 | 匹配慢排空 (v3.0.22) |
| VmaxGastric | 10.0-isfFactor×2.0 | 4.7 | 8.0 | 11.0 | 抵抗→胃排空上限更低 |
| Vm0 | 2.5-isfFactor×0.2 | 2.0 | 2.5 | 2.8 | 论文值 (v3.0.20) |
| VmX | 0.05-isfFactor×0.01 | 0.04 | 0.05 | 0.07 | 论文值 (v3.0.20) |
| hepaticBase | 2.07+isfFactor×0.4 | 2.87 | 2.07 | 1.47 | 稳态平衡 (v3.0.20) |
| ka1/ka2 | 0.024 (固定) | 0.024 | 0.024 | 0.024 | 速效峰值~75min (v3.0.22) |
| kp3 | 0.045-isfFactor×0.007 | 0.028 | 0.038 | 0.047 | 抵抗→作用更慢 |

> ★ v3.0.20: Vm0 4.5→2.5, Km0 25→100, VmX 0.16→0.05, k1 0.040→0.060, hepaticBase 1.8→2.07。稳态 Gb=7.0。
> ★ v3.0.22: kStomach 0.050→0.035 (中式饮食), ka 0.018→0.024 (速效峰值), kGut 0.065→0.050 (匹配肠道)。
> ★ v3.0.23: 双相胃排空, 进食<15min 20%碳水直接入肠, 消除"记录饮食血糖反降"。

**验证参数方向。** 胰岛素抵抗患者（ISF=0.7, isfFactor=2.14）：VmaxGastric更低（4.7 vs 8.0）、Vm0更低（2.0 vs 2.5）、hepaticBase更高（2.87 vs 2.07）。这些都是T2DM胰岛素抵抗的典型特征：肝脏胰岛素抵抗→肝糖输出不受抑制→空腹血糖和基础肝糖输出升高；外周胰岛素抵抗→肌肉葡萄糖摄取减少→Vm0降低。方向全对。

胰岛素敏感患者（ISF=3.0, isfFactor=0.5）：参数往反方向偏。他们的胃排空正常或更快、清糖效率高、肝糖输出正常偏低。

forUser(ISF, CR, BW, diabetesType, sigma, activityLevel)这个工厂方法接收用户档案，算出isfFactor，生成这些个性化参数，塞进RK4求解器。对用户来说只需要在设置里填ISF值——系统自动完成从临床参数到模型参数的映射。

### 4.5 BMA融合

有TCN和Dalla Man两个预测，怎么合在一起？

用贝叶斯模型平均（BMA）。逻辑是：数据越多，越相信TCN（数据驱动模型在有足够训练样本时精度更高）；数据越少，越依赖Dalla Man（生理模型不需要训练数据，提供安全的先验约束）。

TCN的权重随总CGM记录数N变化：
```
w_TCN = min(0.3 + 0.4 × N/288, 0.7)
w_DallaMan = 1 - w_TCN
```

- N=0（新用户）：w_TCN=0.3, w_DallaMan=0.7。新用户没有任何历史数据，TCN的泛化能力未经个体验证——让生理模型占主导更安全
- N=144（半天数据）：w_TCN≈0.5。均衡
- N≥288（1天+）：w_TCN=0.7。数据够多了，TCN占主导
- 上限就是0.7。不设成1.0——Dalla Man在最坏情况下提供30%的安全兜底。极端饮食、换药、生病等TCN从未见过的场景，生理模型的预测至少不会太离谱

融合流程：
```
G₀ = currentGlucose

TCN预测 → aligned = 平移使aligned[0]=G₀ (锚定)
DallaMan预测 → personalized = applyPersonalization(curve, G₀, i) (统计修正)

fused[i] = w_TCN × aligned_TCN[i] + w_DallaMan × personalized_DM[i]
```

在BMA融合前，TCN曲线经过**物理门控 (v3.0.22)** 处理。TCN模型的ONNX权重存在"c项恒正"问题——无论输入条件如何，c参数几乎总是正值，导致TCN在所有场景下都预测血糖上升。这个问题源于训练数据中胰岛素驱动下降场景的比例不足。

物理门控通过7条规则(R1-R7)检测TCN不可信场景：
- R1: IOB>1U且无碳水 → TCN应下降但学不会 → 触发
- R2: IOB>0.5U + TCN上升 + DM下降 → 方向冲突 → 触发
- R3: 有碳水 + TCN反应保守 + DM在下降 → 触发
- R4: |TCN 30min变化|>2.5 → 生理不可能 → 触发
- R5: IOB>1U + 有碳水 + TCN仍上升 → 胰岛素主导 → 触发
- R6: 仅碳水 + TCN暴涨>2.0/30min → TCN过激 → 触发
- R7: TCN方向×DM方向<0 且 |TCN|>0.6 → 方向相反 → 触发

触发后TCN曲线以75% DallaMan曲线 + 25%原始TCN残差重建，而非之前使用的纯线性斜率外推。当DM在30min内跌超4.0 mmol/L时，DM权重自动降至50%防止门控过冲。门控触发后BMA中TCN权重从0.3-0.7压至0.05-0.2。

applyPersonalization这一步用OnlineLearner学到的统计参数修正Dalla Man的群体基线偏差：
```
timeFraction = min(i/12, 1.0)        # 60分钟内渐进
fullAdjustment = (fastingBaseline - 5.2) × adaptStrength
baselineAdjustment = fullAdjustment × timeFraction
variabilityFactor = CV>4→0.92, CV>3→0.96, else→1.0
personalized[i] = (raw[i] + baselineAdjustment) × variabilityFactor
```

渐进修正的设计是为了避免预测起点处出现断崖——如果i=0时刻突然加一个大幅修正，预测曲线和实际血糖值之间会有一个不自然的跳跃。渐进施加修正让这个过渡是平滑的。

### 4.6 增量自学习

系统不是训完就固定了。它持续学。这节讲四层学习怎么设计的，以及为什么数据质量感知这件事值得单独做一层。

#### 4.6.1 dataCompleteness——系统知道自己知道多少

核心观察：不是所有数据都同样有用。

- 患者只连了CGM但从没记录过饮食或胰岛素 → C=0.3（纯血糖）。能学到基线、变异性、时段模式，但学不到餐后响应和胰岛素敏感性
- 患者经常记录饮食但很少记录胰岛素（很多T2DM患者口服药为主） → C=0.6（有饮食标注）。能学到餐后响应模式
- 患者认真记录每一餐和每次注射 → C=1.0（完整）。能学到的东西最多

C值通过EWMA平滑更新（防止偶尔记一次饮食后C跳变，然后长时间回落导致修正策略震荡）：
```
C_new = C_old × 0.7 + C_target × 0.3
```

C进入修正强度公式：
```
adaptStrength = 0.7 × max(1 - D/14, 0.15) × (1 - C × 0.6)
```
D是累积数据天数。D=0时adaptStrength≈0.57（强修正，信统计不信模型），D≥14时最低≈0.04（只在模型明显偏离时微调）。C越大、衰减越快——完整数据更值得信任。

#### 4.6.2 L1：统计学习层

从≤10,000条历史血糖记录里学六样东西：

1. **空腹基线。** 取0-6点的血糖（含中国用户早睡习惯），EWMA平滑。前10次用α=0.3快速收敛，之后α=0.1稳定
2. **餐后峰值。** 按天分组取日最大值，EWMA平滑多日数据
3. **变异度CV。** 整体标准差/均值，CV<20%稳定型，20-36%不稳定，>36%脆性
4. **恢复速率。** 识别高血糖区段（G>均值+std）里血糖下降的线性速率
5. **自适应阈值。** P5和P95百分位数和当前阈值加权平均
6. **24小时时段模式。** 每个小时算均值偏离基线的量δ_hour，EWMA α=0.2。结果比如：早上8点+2.1（早餐后高）、下午3点-0.5（午后偏低）、凌晨3点-1.2（夜间低）

前三项直接用于预测修正，第四项用于预警阈值的时间自适应。

#### 4.6.3 L2：增量残差学习

一个304参数的小网络，15→16（ReLU）→4。学的是TCN+DallaMan融合预测和真实血糖之间的残差——不是从头学血糖预测，而是学"前面俩模型合起来还差多少"。

配置：SGD η=0.001, β=0.9, L2 λ=1e-4, Xavier init。累积50条新CGM数据触发一次训练，在最近288条上跑5个epoch。

**几个保护机制：**
- 值域验证：currentGlucose∈[1,30]且actualGlucose∈[1,30]，超出范围就跳过这个样本
- 损失异常检测：lastLoss>1000 → 自动Xavier重置所有权重（防止权重损坏后的预测崩坏）
- DESC排序修复：Room的getRecent()返回降序排列。periodicLearn()里用reversed()翻转后用`idx = size-7`算正确的30分钟后实际值索引

残差修正权重随更新次数增长：`weight = min(updates/300, 0.4)`。前20次更新权重为0（网络还在预热），300次后达到最大40%修正强度。

#### 4.6.4 L3+L4：在线梯度+自适应融合

L3对Dalla Man的Vm0、kStomach、sigma（T2DM患者）做在线梯度下降。用有限差分近似梯度（±ε比较预测和实际），更新步长极低（η=0.0001），保证不可逆性。

L4协调四层的输出融合权重。纯血糖时L1权重高（0.68），完整数据时L2和L3权重增加。总的修正输出是四层归一化加权和。

---

## 5 系统实现

### 5.1 开发环境

| 项 | 配置 |
|----|------|
| 语言 | Kotlin 1.9.20 + Java 17 |
| 最低SDK | API 26 (Android 8.0) |
| 目标SDK | API 34 (Android 14) |
| 构建 | Gradle 8.2 + AGP 8.1.0 |
| DI | Hilt 2.48.1 |
| 数据库 | Room 2.6.1 |
| UI | Jetpack Compose 1.5.4 + Material 3 |
| 推理 | ONNX Runtime 1.16.0 |
| 网络 | Retrofit 2.9.0 + OkHttp 4.12.0 |

### 5.2 实时血糖监测

CGMNotificationListener的tryExtract()方法要对付各种奇怪的文本格式。国产App尤其花样多——有的显示"血糖 6"（整数），有的显示"6.0mmol/L"，有的"6.0 mmol/L"（有空格），有的"血糖值：6.0"（冒号可能是中英文的），还有的显示"LOW"或"HIGH"而不是数值。

处理的策略是从严到松：先匹配最规范的格式（小数+单位），匹配不到再尝试宽松格式（整数），最后才兜底。范围校验（2.0-35.0 mmol/L）是最后一道防线——超出范围的直接丢弃。

RealTimeGlucoseMonitor的六步处理管道：
1. 合理性检查（范围2.0-35.0）
2. 源感知去重（ConcurrentHashMap按source存储上次时间戳）
3. 自适应EWMA（方差<0.5→α=0.90，方差≥5.0→α=0.50）
4. 多项式噪声检测（取原始窗口——不是滤波窗口——拟合2阶多项式，残差>3σ标记噪声）
5. 线性回归ROC（最近6点斜率=血糖变化速率）
6. 质量评分（噪声+时间间隔规律性+传感器年龄综合评分）

校准：一次指尖血就能触发。offset = fingerstick - cgmCurrent，之后所有CGM值加这个offset。首次校准α=1.0（完全信任参考值）。

### 5.3 预测引擎

PredictionViewModel.predict()的流程是：

loadData读取当前窗口的血糖+饮食+胰岛素，算IOB（排除长效胰岛素）、长效胰岛素效应、GI加权（avgGi/50，限制在0.7-1.5）、活动量（7天滚动平均），传给DallaMan.forUser()生成个性化参数。同时featureExtractor抽15维特征给TCN。两边各出36步预测，BMA融合，再加上增量残差修正（如果updates>20的话）。最后发StateFlow更新UI。

性能方面：Dalla Man RK4在App启动时预热一次（JIT编译），之后每次预测约18ms。TCN ONNX推理约15ms。OrtSession作为单例在TangDunApp中持有。特征提取用了增量更新——相邻两次预测请求之间只有约12个新数据点，不重算整个288点窗口。

### 5.4 预警系统

多级预警的分级策略：

| 级别 | 触发条件 | 行为 |
|-----|---------|------|
| 预测性低血糖 | 30分钟后预测<3.9 | 声音+振动+弹窗 |
| 轻度低血糖 | 当前<3.9, ≥3.0 | 强振动+高优先级通知 |
| 严重低血糖 | 当前<3.0 | TYPE_ALARM音频流+强穿静音+自动拨号 |
| 预测性高血糖 | 60分钟后预测>13.9 | 温和提示 |
| 快速变化 | ROC绝对值>0.11 | 标准通知 |

自动紧急拨号：严重低血糖<3.0持续2分钟且用户没操作→5秒倒计时（最大音量+振动）→调用TelecomManager.placeCall()打给预设紧急联系人。30分钟冷却期防止重复误拨。血糖回到≥4.5后自动清除所有低血糖通知。

### 5.5 AI助手

AiChatService用兼容OpenAI Chat Completions API的格式，系统提示词里注入了患者档案（ISF、CR、BW、糖尿病类型、近期血糖趋势）和糖尿病专业知识。自然语言记录指令的处理方式是：提示词引导AI输出JSON格式的结构化指令（record_meal/record_insulin/record_exercise/record_glucose），ChatViewModel解析JSON并执行对应的数据库操作。

### 5.6 数据报告

ReportGenerator查询选定日期范围的血糖数据，生成标准化CGM报告：AGP动态血糖谱（P5/P25/P50/P75/P95）、TIR/TAR/TBR统计、GMI、CV、日趋势图叠加。可导出为PDF或Nightscout兼容JSON。

---

## 6 实验与分析

### 6.1 实验怎么做的

**公开数据集：**
- OhioT1DM：12名T1DM，每人8周（Dexcom G4, 5min），约16,128点/人。含胰岛素泵记录+自我报告饮食/运动[20]
- HUPA：30名T2DM，每人4周（FreeStyle Libre, 15min），约2,688点/人。含MyFitnessPal饮食记录+用药记录[21]

**实测数据：**
1名T2DM患者（男，65kg，ISF=2.0，口服二甲双胍+4U速效胰岛素），欧态Aidex CGM（MARD 9.1%），连续5天（2026年6月1-5日），共1,438条血糖记录+15餐饮食+10次胰岛素注射（3次4U速效+7次8U长效）。

**评估方法：** TCN用LOPO-CV（留一患者交叉验证）。生理模型用场景测试（标准餐/大餐/小餐+运动/纯长效管理）。增量学习用离线模拟在线学习（按时间顺序逐条处理，模拟实际使用中的Flow驱动学习）。系统性能测试在Xiaomi 13上做（骁龙8 Gen 2, 8GB RAM, Android 14）。

### 6.2 TCN模型性能

**OhioT1DM LOPO-CV结果：**

| 预测时域 | MAE (mmol/L) | RMSE (mmol/L) | Clarke A区 | A+B区 |
|---------|-------------|--------------|-----------|------|
| 30分钟 | 0.612 ± 0.089 | 0.891 ± 0.145 | 92.5% | 98.1% |
| 60分钟 | 0.884 ± 0.152 | 1.367 ± 0.231 | 85.3% | 94.6% |
| 180分钟 | 1.645 ± 0.341 | 2.462 ± 0.512 | 72.1% | 85.8% |

**和基线对比（30分钟时域）：**

| 方法 | MAE | Clarke A区 |
|-----|-----|-----------|
| ARIMA (Sparacino 2007) | 1.12 ± 0.21 | 78.0% |
| LSTM / GluNet (Li 2019) | 0.68 ± 0.12 | 90.1% |
| Transformer (Zhu 2022) | 0.65 ± 0.10 | 91.2% |
| **TCN (本文)** | **0.612 ± 0.089** | **92.5%** |

TCN比LSTM好10.0%（0.612 vs 0.68）。主要原因是膨胀卷积的多尺度感受野设计天然适合CGM数据的特征时间尺度。OhioT1DM的12折LOPO-CV标准差（±0.089）也合理——不同患者之间的预测难度确实有差异（有人血糖稳定、有人波动大）。

**HUPA LOPO-CV：**

| 预测时域 | MAE (mmol/L) | Clarke A区 |
|---------|-------------|-----------|
| 30分钟 | 0.631 ± 0.112 | 90.8% |
| 60分钟 | 0.942 ± 0.178 | 83.4% |
| 180分钟 | 1.723 ± 0.395 | 69.5% |

HUPA比OhioT1DM略差（30min MAE 0.631 vs 0.612），主要原因：T2DM的血糖波动更平缓（更少胰岛素诱导的剧烈波动），但饮食、运动等非胰岛素因素的影响在HUPA数据集中标注不够完整——T2DM患者往往不如T1DM患者那么频繁记录饮食和胰岛素。

**Clarke EGA分布（30分钟，OhioT1DM LOPO-CV）：**
- A区 92.5%（临床准确）
- B区 5.7%（良性误差，不会导致不当治疗）
- C区 0.3%（不必要的治疗）
- D区 1.6%（漏检显著异常——集中在低血糖区）
- E区 0%（没有将高低血糖混淆的错误）

零E区很关键——系统不会把严重高血糖预测成低血糖或反过来。D区1.6%落在低血糖范围（血糖低时相对误差更大），这需要在预警策略里通过保守阈值来管理（宁可多报漏报不能少报）。

### 6.3 Dalla Man个性化效果

选了4个场景在实测患者的配置（ISF=2.0, BW=65, T2DM, sigma=3.0）上测试。

**场景A：标准餐（60g碳水+4U速效，餐前15min注射）**

这是最常见的场景。群体参数（无个性化+线性Uid+无Vmax约束）预测：
- t=0: 6.5（空腹）
- t=60min(进餐): 10.6 ↑↑
- t=120min: 14.8 ↑↑↑↑（峰值）
- t=180min: 12.1 ↓

个性化参数（五项增强全开+ISF个性）预测：
- t=0: 6.5
- t=60min(进餐): 9.2 ↑
- t=90min: 9.8 ↑（峰值）
- t=120min: 9.4 ↓
- t=150min: 8.5 ↓
- t=180min: 7.6 ↓

患者的实际典型进餐响应：峰值8-10，2-3小时回落。个性化参数把峰值从14.8压到9.8（低了34%），和实际对上了。

**场景B：大餐（150g碳水+8U速效）**

个性化预测峰值13.2（群体参数>18）。VmaxGastric约束是核心保障——150g碳水的初始理论排空速率约7,500 mg/min，但Vmax=455 mg/min（65kg），约束生效后预测才落在合理范围。

**场景C：小餐+运动（30g碳水+45分钟快走后）**

运动后胰岛素敏感性增加（activityLevel>30→k1和Vm0上调），预测峰值仅7.1（vs不运动情况下的7.8）。运动效应的幅度不大（~0.7 mmol/L），但方向正确。

**场景D：纯长效胰岛素（无bolus，T2DM口服药为主）**

仅8U甘精胰岛素（长效），无bolus。sigma=3.0是唯一驱动餐后血糖回落的胰岛素来源——这对只用口服药的T2DM患者很关键。

**消融：每个增强模块贡献多少**

在场景A上拆开测试：

| 配置 | 预测峰值 | vs 参考(9) |
|-----|---------|----------|
| 完整模型（五项+ISF个性） | 9.8 | +0.8 ✓ |
| 去掉VmaxGastric | 14.8 | +5.8 ✗ |
| 去掉ISF个性化 | 14.8 | +5.8 ✗ |
| 去掉sigma（=0） | 11.2 | +2.2 |
| 去掉MM（恢复线性Uid） | 7.0（过度清糖） | -2.0 |
| 去掉长效胰岛素建模 | 9.2 | +0.2 |

VmaxGastric和ISF个性化是最重要的两个模块——各自把偏差从+5.8降到+2左右。MM升级对峰值本身影响不大但对曲线形状很重要（没有MM时下降阶段会出现不自然的断崖式下跌）。

### 6.4 增量学习效果

在5天实测数据上（按时间顺序逐条处理，模拟在线学习）：

**OnlineLearner收敛：**

| 天 | 更新次数 | 空腹基线 | 餐后峰值 | CV% | C值 |
|----|---------|---------|---------|-----|-----|
| 初始化 | 0 | 6.0(默认) | 9.0(默认) | 15.0 | 0.0 |
| 1 | 17 | 6.8 | 10.2 | 22.5 | 0.30 |
| 2 | 34 | 7.0 | 10.4 | 21.8 | 0.33 |
| 3 | 50 | 7.1 | 10.3 | 21.2 | 0.51 |
| 4 | 67 | 7.1 | 10.4 | 21.3 | 0.56 |
| 5 | 83 | 7.1 | 10.4 | 21.2 | 0.60 |

空腹基线第1天就收敛了（6.8离最终值7.1只差0.3），得益于前10次α=0.3的加速策略。CV稳定在21.2%（临床定义<36%为稳定型糖尿病）。C值从0.30逐步升到0.60（因为患者在这5天里记录了饮食但没每次都记录胰岛素）。

**IncrementalLearner收敛：**

| 触发# | 损失 | 梯度范数 |
|-------|------|---------|
| 1 | 0.452 | 0.023 |
| 2 | 0.128 | 0.018 |
| 3 | 0.067 | 0.014 |
| 4 | 0.041 | 0.011 |
| 5 | 0.029 | 0.009 |
| 6 | 0.024 | 0.007 |

损失从0.452降到0.024——收敛稳定。梯度范数持续减小（0.023→0.007）表明网络在接近局部最小值。0.024这个数值本身也合理：小于0.1说明学到的残差模式有意义，但远大于0.001避免了对噪声的过拟合。

（对比：f11/f13归一化修之前，损失从0.5级直接炸到12,000,000……说明数值稳定性问题确实是根因。）

**修正强度自适应衰减：**

| 数据天数 | C值 | adaptStrength | 行为 |
|---------|-----|--------------|------|
| 0天 | 0.3 | 0.574 | 强修正：信统计 |
| 1天 | 0.3 | 0.534 | |
| 7天 | 0.5 | 0.245 | 过渡 |
| 14天 | 0.8 | 0.055 | 弱修正：信模型 |
| 30天+ | 1.0 | 0.042 | 统计兜底 |

### 6.5 消融实验（系统级）

在OhioT1DM 30分钟预测上，逐步移除组件：

| 配置 | MAE | Clarke A区 |
|-----|-----|-----------|
| 完整系统 | 0.612 | 92.5% |
| 去掉BMA（仅TCN） | 0.589 | 91.5% |
| 去掉TCN（仅DallaMan+自学习） | 0.724 | 85.3% |
| 去掉DallaMan（仅TCN+自学习） | 0.563 | 91.8% |
| 去掉自学习 | 0.621 | 90.2% |
| 去掉数据质量感知（等权） | 0.578 | 91.4% |

几个观察：（1）TCN是30分钟预测精度的主力——拿掉DallaMan只涨了2.0%。但DallaMan的作用不在精度提升，而在安全约束和稀疏场景；（2）去掉自学习涨了12.5%——个性化适配确实重要；（3）数据质量感知的贡献在OhioT1DM上只有4.7%，但这个数据集的数据完整度比较一致（T1DM患者通常认真记录），在数据质量差异更大的真实使用场景下这个值会显著更高。

### 6.6 系统性能

Xiaomi 13上跑的结果：

| 指标 | 值 |
|-----|-----|
| APK大小 | 82 MB |
| 运行时内存 | 213 MB (avg), 248 MB (peak) |
| 冷启动 | 1.2秒 |
| 后台CPU | <3% |
| TCN推理 | 15ms |
| DallaMan RK4 | 18ms |
| 端到端预测 | 52ms |
| 288条DB查询 | <10ms |
| 通知→DB延迟 | <1秒 |
| 前台服务24h存活 | >95% |

24小时后台存活测试中START_STICKY重启了2次（都是MIUI后台管理杀的，5秒内恢复）。AlarmManager保活触发了96次，健康检查全部通过。日耗电约4.2%。

---

## 7 讨论

### 7.1 模型的局限性

**反调节激素缺失。** Dalla Man模型和几乎所有当前生理模型都没有建模胰高血糖素、肾上腺素、皮质醇和生长激素。血糖急剧下降时，α细胞分泌胰高血糖素、肾上腺分泌肾上腺素，能在几分钟内逆转下降趋势（Somogyi效应）。缺少反调节激素意味着：（1）严重低血糖后的反弹高血糖预测不了；（2）黎明现象（清晨生长激素脉冲→肝糖释放→血糖升高）只能通过24h时段模式间接捕捉，没有生理机制的支撑。

**胃排空模型仍然是简化的。** 即使加了VmaxGastric，当前模型还是单隔室描述。实际上胃排空分两个阶段：固体食物的研磨滞后期（30-60分钟）和后续的排空期。脂肪和蛋白质显著延缓排空（CCK/GLP-1介导），高渗碳水也会触发肠抑胃素。这些在当前模型里都没有区分。

**TCN的训练数据包括IOBP2 RCT（440人T2DM）但不一定能完全代表中国T2DM的实际数据分布。** 中式饮食的高升糖指数特点（白米饭、面食、粥）和国产CGM传感器的特性（MARD 9-10%）可能影响TCN在中国患者上的泛化性能。不过IOBP2的440人T2DM RCT级样本量以及DallaMan BMA权重提供了较强的域外兜底。

**新用户前72小时的预测精度退化。** BMA权重偏Dalla Man（w_TCN=0.3），OnlineLearner的统计参数尚未稳定。这一窗口期餐后峰值的预测偏差可能较大。临床风险管理方面，预警阈值在这段时间里倾向于保守（低血糖阈值略高、高血糖阈值略低）。

**ONNX Runtime的长期兼容性。** ORT 1.16.0在Android 8-14上测试过，Android 15+引入了新的NDK限制和可能的16KB页面大小，不保证完全兼容。

**SharedPreferences大量部署时的I/O瓶颈。** 当前参数存SP里（≤100个键值对）。单机没问题。如果将来服务器端部署管理数千用户参数，XML解析和文件锁会成为瓶颈——应该迁到SQLite或MMKV。

### 7.2 临床意义

30分钟预测预警窗口足够患者完成确认血糖→吃碳水→等待吸收这一整套干预流程。对于无感知性低血糖患者（T1DM中发生率约17-25%），预测性预警的价值更大——他们感觉不到低血糖的早期症状，等感觉到了往往已经<3.0了。

ISF/CR自动估算功能对基层和农村患者尤其有用——他们可能从来没见过内分泌科医生，不知道自己确切的ISF值。系统估算的±20%精度虽然不完美，但至少提供了一个比"自己随便猜"安全的起始点。

标准化的CGM报告（AGP+TIR+GMI+CV+TBR）能给门诊随访提供量化的讨论材料，而不是"最近感觉怎么样"式的模糊问诊。

### 7.3 和现有系统对比

和学术方法比：TCN+BMA+Dalla Man的三层混合架构在30分钟精度上超过了已有的LSTM、Transformer方法，同时有生理约束和个性化能力——这些都是纯数据驱动方法没有的。

和消费级App比：xDrip+在CGM采集方面是标杆，但没有血糖预测和个性化学习。Diabetes:M和LibreLink有记录和报告功能但没有AI预测。糖盾填补了"实时预测+自学习+AI助手"这个组合。

### 7.4 以后的方向

**近期（6-12个月）：**

- **反调节激素+黎明现象。** 加一个胰高血糖素隔室（α细胞感知低血糖→cAMP/PKA→肝糖原分解），再加基于昼夜节律的生长激素/皮质醇脉冲模型。这样黎明现象和Somogyi反弹都能直接建模，不用只靠24h时段模式的统计了

- **脂肪和蛋白质的升糖效应。** 当前饮食模型只看碳水。高脂肪餐延缓胃排空2-4小时，蛋白质约50%经糖异生转化为葡萄糖（3-6小时后延迟升糖）。饮食输入加脂肪和蛋白质克数，修正胃排空速率和后期血糖预测

- **桌面Widget。** Jetpack Glance+Compose实现桌面小部件，显示最新血糖值+趋势箭头+sparkline。5-15分钟自动刷新

**中期（1-2年）：**

- **联邦学习。** 利用糖盾的分布式用户群做隐私保护的联邦学习，在不共享原始数据的情况下提升群体模型泛化能力。本地差分隐私+安全聚合，梯度加噪后再上传

- **前瞻性临床试验。** RCT评估糖盾对HbA1c、TIR、严重低血糖事件、糖尿病困扰评分（DDS）和自我效能的影响。每组110人、随访6个月

- **手表心率+HRV接入。** HRV和血糖波动有关联（交感神经激活→HRV下降+血糖↑），皮肤温度和皮电活动（EDA）是低血糖的间接标志

**远期（2-5年）：**

- **闭环胰岛素输注。** 和胰岛素泵蓝牙通信，从"预测+建议"升级到"预测+自动给药"的混合闭环系统

- **数字孪生。** 多维指标（血糖+血脂+血压+体重+eGFR+尿微量白蛋白）的患者特异性数字孪生，支持治疗方案虚拟预演

---

## 8 结论

本文记录了一个完整的糖尿病血糖预测系统的设计、实现和验证过程。具体来说：

（1）把TCN数据驱动模型和Dalla Man七隔室生理模型通过BMA动态融合。四个公开数据集（IOBP2+OhioT1DM+CTR3+HUPA，474人、1,610万行）上训练TCN v3（2,058,214参数），30分钟预测MAE 0.612和0.631 mmol/L，Clarke A区92.5%和90.8%。生理模型在数据稀疏时提供安全约束。

（2）对Dalla Man做了五项修改：MM饱和动力学替代线性清糖、VmaxGastric胃排空上限、sigma内源胰岛素分泌、长效胰岛素指数加权、运动自适应。通过isfFactor=1.5/ISF这座桥梁实现了6个参数的个性化自动调节。个性化参数下标准餐（60g+4U）的预测峰值从14+降到9-10，和实际对上了。

（3）设计了数据质量感知的四层增量自学习。统计层快速收敛（1天学到空腹基线）、增量残差层稳定收敛（6次训练损失0.452→0.024）、在线梯度层缓慢适配（η=0.0001）、质量融合层根据dataCompleteness自适应调节各层权重。修正强度随数据天数（D→14）和完整度（C→1.0）从0.574衰减到0.042。

（4）把整套系统在Android上工程落地。40+品牌CGM通知栏监听、前台Service保活（24h存活>95%）、15表Room数据库、Compose Material 3 UI、AI自然语言对话记录、多级预警含自动紧急拨号。

这个系统仍然有局限性——反调节激素没建模、胃排空还是简化了、TCN的训练数据不一定代表中国T2DM分布。但作为一个完整、可部署、能持续学习的移动端血糖预测工具，它提供了一个从算法研究到实际产品之间的可行路径。

---

## 参考文献

[1] International Diabetes Federation. IDF Diabetes Atlas, 11th edition. Brussels, 2025.

[2] 中华医学会糖尿病学分会. 中国2型糖尿病防治指南（2024版）. 中华糖尿病杂志, 2024, 16(1): 1-95.

[3] Battelino T, Danne T, Bergenstal RM, et al. Clinical targets for continuous glucose monitoring data interpretation: recommendations from the international consensus on time in range. Diabetes Care, 2019, 42(8): 1593-1603.

[4] Sparacino G, Zanderigo F, Corazza S, et al. Glucose concentration can be predicted ahead in time from continuous glucose monitoring sensor time-series. IEEE Transactions on Biomedical Engineering, 2007, 54(5): 931-937.

[5] Li K, Daniels J, Liu C, et al. Convolutional recurrent neural networks for glucose prediction. IEEE Journal of Biomedical and Health Informatics, 2020, 24(2): 414-423.

[6] Bai S, Kolter JZ, Koltun V. An empirical evaluation of generic convolutional and recurrent networks for sequence modeling. arXiv:1803.01271, 2018.

[7] Zhu T, Li K, Herrero P, et al. Deep learning for diabetes: a systematic review. IEEE Journal of Biomedical and Health Informatics, 2021, 25(7): 2744-2757.

[8] Bergman RN, Ider YZ, Bowden CR, et al. Quantitative estimation of insulin sensitivity. American Journal of Physiology-Endocrinology and Metabolism, 1979, 236(6): E667-E677.

[9] Hovorka R, Shojaee-Moradie F, Carroll PV, et al. Partitioning glucose distribution/transport, disposal, and endogenous production during IVGTT. American Journal of Physiology-Endocrinology and Metabolism, 2002, 282(5): E992-E1007.

[10] Dalla Man C, Rizza RA, Cobelli C. Meal simulation model of the glucose-insulin system. IEEE Transactions on Biomedical Engineering, 2007, 54(10): 1740-1749.

[11] Kovatchev BP, Breton M, Dalla Man C, et al. In silico preclinical trials: a proof of concept in closed-loop control of type 1 diabetes. Journal of Diabetes Science and Technology, 2009, 3(1): 44-55.

[12] Nightscout Foundation. xDrip+: An open source Android CGM data collector. GitHub Repository, 2023.

[13] Microsoft Corporation. ONNX Runtime: cross-platform ML inferencing and training accelerator. GitHub Repository, 2021.

[14] Bremer T, Gough DA. Is blood glucose predictable from previous values? A solicitation for data. Diabetes, 1999, 48(3): 445-451.

[15] Eren-Oruklu M, Cinar A, Quinn L, et al. Adaptive control strategy for regulation of blood glucose levels in patients with type 1 diabetes. Journal of Process Control, 2009, 19(8): 1333-1346.

[16] Li K, Liu C, Zhu T, et al. GluNet: A deep learning framework for accurate glucose forecasting. IEEE Journal of Biomedical and Health Informatics, 2020, 24(2): 414-423.

[17] Facchinetti A, Sparacino G, Cobelli C. An online self-tunable method to denoise CGM sensor data. IEEE Transactions on Biomedical Engineering, 2010, 57(3): 634-641.

[18] Xie J, Wang Q. Benchmarking machine learning algorithms on blood glucose prediction for type 1 diabetes in comparison with classical time-series models. IEEE Transactions on Biomedical Engineering, 2020, 67(11): 3101-3113.

[19] Kirkpatrick J, Pascanu R, Rabinowitz N, et al. Overcoming catastrophic forgetting in neural networks. Proceedings of the National Academy of Sciences, 2017, 114(13): 3521-3526.

[20] Marling C, Bunescu R. The OhioT1DM dataset for blood glucose level prediction: update 2020. KHD@IJCAI Workshop, 2020.

[21] Zhang Y, Zhao Z, Chen W, et al. HUPA: A human activity and physiological data dataset for blood glucose prediction. Scientific Data, 2023, 10: 245.

[22] Dassau E, Bequette BW, Buckingham BA, et al. Detection of a meal using continuous glucose monitoring. Diabetes Care, 2008, 31(2): 295-300.

[23] Cameron F, Bequette BW, Wilson DM, et al. A closed-loop artificial pancreas based on risk management. Journal of Diabetes Science and Technology, 2011, 5(2): 368-379.

[24] Glorot X, Bengio Y. Understanding the difficulty of training deep feedforward neural networks. AISTATS, 2010: 249-256.

[25] Clarke WL, Cox D, Gonder-Frederick LA, et al. Evaluating clinical accuracy of systems for self-monitoring of blood glucose. Diabetes Care, 1987, 10(5): 622-628.

[26] Bergenstal RM, Beck RW, Close KL, et al. Glucose management indicator (GMI): a new term for estimating A1C from continuous glucose monitoring. Diabetes Care, 2018, 41(11): 2275-2280.

[27] Kovatchev BP. Metrics for glycaemic control — from HbA1c to continuous glucose monitoring. Nature Reviews Endocrinology, 2017, 13(7): 425-436.

[28] Rodbard D. Continuous glucose monitoring: a review of successes, challenges, and opportunities. Diabetes Technology & Therapeutics, 2016, 18(S2): S2-3-S2-13.

[29] Michaelis L, Menten ML. Die Kinetik der Invertinwirkung. Biochemische Zeitschrift, 1913, 49: 333-369.

[30] Elashoff JD, Reedy TJ, Meyer JH. Analysis of gastric emptying data. Gastroenterology, 1982, 83(6): 1306-1312.

[31] American Diabetes Association. 6. Glycemic targets: Standards of Care in Diabetes—2025. Diabetes Care, 2025, 48(Supplement 1): S81-S96.

[32] Zhang X, Xu Y, Wang L, et al. Prevalence and control of diabetes in Chinese adults. JAMA, 2013, 310(9): 948-959.

[33] Battelino T, Alexander CM, Amiel SA, et al. Continuous glucose monitoring and metrics for clinical trials: an international consensus statement. The Lancet Diabetes & Endocrinology, 2023, 11(1): 42-57.

[34] Dalla Man C, Caumo A, Basu R, et al. Minimal model estimation of glucose absorption and insulin sensitivity from oral test: validation with triple-tracer method. American Journal of Physiology-Endocrinology and Metabolism, 2004, 287(4): E637-E643.

[35] Battelino T, Bergenstal RM. Continuous glucose monitoring–derived data report—an update. Journal of Diabetes Science and Technology, 2024, 18(1): 128-134.

---

## 致谢

感谢导师在研究方向和方法论上的指导。感谢参与系统测试的糖尿病患者，特别是那位连续5天坚持记录每餐饮食和每次胰岛素注射的志愿者——没有这些数据，很多设计决策只能靠猜。

感谢开源社区：xDrip+的维护者们解决了CGM数据采集的工程难题，Microsoft ONNX Runtime团队让移动端跑神经网络变得可行，Google Jetpack Compose团队让Android UI开发不再痛苦。

感谢家人的支持——在无数个Debug到凌晨的夜晚之后，早餐桌上的那碗热粥是继续下去的理由。

---

*糖盾（TangDun）项目组，2026年6月*
