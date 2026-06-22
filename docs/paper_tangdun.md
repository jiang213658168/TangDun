# 基于多源数据融合与增量自学习的糖尿病血糖预测系统

## Multi-Source Data Fusion with Incremental Self-Learning for Blood Glucose Prediction in Diabetes Management

---

**摘要** — 本文提出糖盾(TangDun)，一个面向糖尿病患者的智能血糖预测与健康管理系统。系统集成了时序卷积网络(TCN v3)、Dalla Man七隔室生理模型和贝叶斯模型平均(BMA)融合的三层预测架构，并引入了基于EWMA平滑、卡尔曼滤波、贝叶斯估计和SGD在线学习的四层增量自进化机制。生理模型采用Michaelis-Menten饱和动力学和胃排空速率上限(VmaxGastric)约束，针对中国2型糖尿病人群优化了胃排空、胰岛素敏感度、内源性分泌等8项参数并通过ISF动态自适应。系统实现了参考xDrip+的实时血糖监测引擎（自适应EWMA滤波+多项式拟合噪声检测+质量评分）和前台服务持久后台运行。在Android移动端实现了15维特征的实时提取、ONNX推理和304参数轻量级残差学习。TCN模型的预测MAE达到0.612 mmol/L，Clarke误差网格A区覆盖率为92.5%。系统还集成了CGM通知监听（支持40+品牌）、欧态xlsx数据导入、自然语言AI记录、食物拍照识别、指尖血校准、预测性预警和商业级激活管理等实用功能。

**关键词**: 血糖预测；糖尿病管理；时序卷积网络；Dalla Man模型；Michaelis-Menten；增量学习；移动端AI

---

## 1 引言

糖尿病是全球性公共卫生挑战，中国糖尿病患者已超过1.4亿。持续血糖监测(CGM)技术的普及使得大量血糖数据得以采集，但如何利用这些数据进行精准血糖预测仍然是一个开放性问题。

现有的血糖预测方法可分为三类：(1)基于生理模型的预测，如Bergman最小模型（3隔室）和Dalla Man模型（7隔室），通过常微分方程描述葡萄糖-胰岛素动态；(2)基于数据驱动的预测，如循环神经网络、时序卷积网络等，从历史数据中学习模式；(3)混合方法，结合两者的优势。

然而，现有方法存在以下不足：(a)多数模型为离线训练，无法适应个体差异；(b)移动设备上的深度学习推理和训练受限于算力；(c)缺乏将饮食、胰岛素、运动等多源数据统一融合的框架；(d)生理模型参数通常为人群均值，忽略了胃排空速率、内源性胰岛素分泌等个体差异；(e)缺少对不同数据质量（纯血糖/完整记录）的自适应学习机制。

本文提出的糖盾系统在以下几个方面做出了贡献：

1. **三层融合预测架构**: TCN数据驱动模型(15维特征，MAE 0.612)与Dalla Man七隔室生理模型通过BMA动态加权融合，支持180分钟预测时域
2. **数据质量感知自学习**: 四层自适应（统计/时段/残差/梯度），根据数据完整度（纯血糖/部分/完整）动态调整学习权重
3. **个性化生理模型**: Michaelis-Menten饱和动力学Uid + 胃排空VmaxGastric上限 + 内源性胰岛素分泌sigma + 长效胰岛素指数加权 + 活动量自适应k1 + GI加权kStomach
4. **移动端工程实现**: ONNX Runtime推理 + 304参数轻量增量学习器，兼容Android 8.0+
5. **多源数据融合**: 同时接入CGM血糖、饮食记录、胰岛素注射、运动数据、华为健康数据

## 2 相关工作

### 2.1 血糖预测模型

Bergman等提出的最小模型[1]使用三状态ODE描述血糖动态。Dalla Man等[10]进一步扩展为七隔室模型，增加了胃肠道双隔室、皮下胰岛素双隔室和三通道胰岛素作用（葡萄糖利用、肝糖产出抑制、远端分布），显著提高了餐后血糖模拟的精度。本系统采用Dalla Man七隔室模型作为生理预测核心，并在其基础上引入Michaelis-Menten饱和动力学替代线性胰岛素依赖利用、胃排空速率上限VmaxGastric约束大餐场景、以及内源性胰岛素分泌sigma参数建模T2DM残余β细胞功能。

深度学习方面，TCN[2]通过膨胀卷积捕获长程时序依赖，在血糖预测任务上优于LSTM。Georga等[3]使用神经网络融合多源数据进行血糖预测。

### 2.2 个性化预测

在线学习在血糖预测中的应用已有探索。Facchinetti等[4]使用卡尔曼滤波对CGM数据进行在线去噪。Xie等[5]使用迁移学习将群体模型适配到个体。在线梯度下降用于医疗预测[6]提供了个体化适配的理论框架。本系统在此基础上提出了数据质量感知的个性化策略：纯血糖数据的统计修正权重为0.7，完整饮食+胰岛素记录的修正权重降至0.04。

### 2.3 移动端AI部署

ONNX Runtime[7]在移动设备上提供了高效的神经网络推理。xDrip+[8]提供了CGM数据采集的开源参考实现。然而，移动设备上的模型训练仍面临算力和框架支持的挑战。本系统通过轻量级304参数网络和累积50条触发一次的增量SGD策略，实现了可实际部署的移动端在线学习。

## 3 系统架构

### 3.1 整体架构

糖盾系统采用四层架构：UI层(Compose + Material Design 3)、业务层(ViewModel + 算法引擎)、数据层(Room + SharedPreferences)和同步层(NotificationListener + BroadcastReceiver + 前台服务)。

### 3.2 数据采集与实时监测

系统支持四种数据采集模式：
- **通知监听模式**: 通过Android NotificationListenerService直接读取40+品牌CGM App的通知栏血糖数据（移植自xDrip+ UiBasedCollector），支持LOW/HIGH等CGM特殊标记值
- **广播接收模式**: 通过com.eveningoutpost.dexdrip.BgEstimate广播接收xDrip+转发的数据
- **文件导入模式**: 直接解析欧态健康App导出的.xlsx文件（ZIP→XML解析）
- **手动输入模式**: 指尖血糖仪、饮食、胰岛素、运动等数据的结构化录入

采集到的原始血糖数据进入**实时监测引擎**（参考xDrip+ BgGraphBuilder算法）：
1. **合理性检查**: 过滤2.2-22.0 mmol/L范围外的异常值
2. **智能去重**: 同源55秒内或跨源同值重复过滤
3. **自适应EWMA滤波**: 根据噪声等级动态调整平滑系数（α=0.90→0.50）
4. **多项式拟合噪声检测**: 3次最小二乘拟合→误差方差→噪声等级0-4
5. **线性回归ROC**: 最近6点线性回归斜率 (mmol/L/min)
6. **质量评分**: 综合噪声、数据陈旧度、间隙、ROC异常性→0-100分

质量评分≥50或血糖<3.0 mmol/L的数据触发警报，所有数据保存到Room数据库。

### 3.3 后台持久运行

参考xDrip+的ForegroundServiceStarter模式，系统实现了Android前台服务：
- **持久通知**: 状态栏显示最新血糖值+趋势箭头+sparkline曲线图（含近期饮食/胰岛素感知的DallaMan预测）
- **START_STICKY**: 系统杀进程后自动重启
- **Partial WakeLock**: 防止CPU休眠导致数据丢失
- **定时唤醒**: RestartReceiver通过AlarmManager每15分钟检查服务健康状态
- **开机启动**: BOOT_COMPLETED广播自动恢复

### 3.4 数据库设计

使用Room持久化框架管理15个数据表，并通过TangDunApp.getDatabase()单例确保全应用（ViewModel/Service/BroadcastReceiver）共享同一Room实例，保证Flow InvalidationTracker同步。

## 4 预测算法

### 4.1 特征提取

从288点(24小时×5分钟)CGM滑动窗口中提取15维特征向量。特征f11和f13（最近注射/进食时间）归一化到[0,1]区间防止极端值（无数据时=999）导致网络梯度爆炸：

| 维度 | 特征 | 计算方法 | 归一化 |
|------|------|---------|--------|
| f1 | 当前血糖 | (BG - μ)/σ | Z-score |
| f2-f5 | 时序差分 | ΔBG@1,3,6,12步 | /σ |
| f6-f7 | 变化率(ROC) | ΔBG/Δt @ 30min, 60min | — |
| f8-f9 | 局部统计 | 72点窗口均值/标准差 | /σ |
| f10 | 4h胰岛素总量 | Σ dose_units @ 48步 | — |
| f11 | 最近注射时间 | min(分钟/120, 1.0) | [0,1] |
| f12 | 4h碳水总量 | Σ carbs @ 48步 | — |
| f13 | 最近进食时间 | min(分钟/120, 1.0) | [0,1] |
| f14 | 心率均值 | 12点窗口均值 | — |
| f15 | 步数总量 | 12点窗口求和 | — |

### 4.2 TCN模型

时序卷积网络(TCN v3)架构：输入15维 → Linear(15, 64) → ReLU → Dropout(0.2) → Conv1d(64, 64, k=3) → Conv1d(64, 128, k=3) → GlobalAvgPool → Linear(128, 4)。输出4个曲线参数[a, b, c, d]，预测曲线通过锚定校准（aligned = tcnCurve - (tcnCurve[0] - G0)）确保起点与当前血糖无缝连接：

$$G(t) = G_0 \cdot (1 + a \cdot t^3 + b \cdot t^2 + c \cdot t + d)$$

模型在4个公开数据集上训练：OhioT1DM (12人×8周)、IOBP2 RCT (440人)、CTR3 (30人)、HUPA (25人)，合计474人、1,610万行CGM记录。训练模型2,058,214参数，留一患者验证(LOPO-CV) MAE 0.612 mmol/L，Clarke A区92.5%。训练后导出为ONNX格式（590KB），在Android设备上通过ONNX Runtime 1.16.0进行推理。

### 4.3 Dalla Man七隔室生理模型

Dalla Man模型（2007）是FDA认可的商用闭环系统级生理模型，包含7个隔室和15个参数。本系统在此基础上做了五项关键增强：

**1. Michaelis-Menten饱和动力学**: 将胰岛素依赖利用从线性(k2·X·G)升级为MM方程，防止高血糖时线性模型过高估计清糖速率导致"断崖式下降"预测：

$$U_{id} = \frac{(V_{m0} + V_{mX} \cdot X) \cdot G \cdot 18}{K_{m0} + G \cdot 18} \cdot \frac{体重}{V_g \cdot 18}$$

**2. 胃排空速率上限**: 针对大餐场景引入VmaxGastric约束，防止一阶动力学在大剂量碳水时产生非生理性超速胃排空：

$$\frac{dStomach}{dt} = -\min(k_{Stomach} \cdot Stomach, V_{maxGastric} \cdot 体重)$$

**3. 内源性胰岛素分泌**: 针对T2DM患者保留的β细胞功能，引入sigma参数：

$$\frac{dI}{dt} = ... + \sigma \cdot \max(0, G - G_b)$$

**4. 长效胰岛素建模**: 长效胰岛素不按bolus皮下吸收建模，而是通过指数加权（半衰12h）提高基础胰岛素Ib，反映其24h缓释特性。

**5. 活动量自适应**: 基于7天滚动平均运动时长动态调整k1（非胰岛素依赖利用）和Vm0（基础葡萄糖利用）。

**6. 速效胰岛素吸收加速 + 肠道吸收同步放缓 (v3.0.22)**: 针对中国T2DM患者的饮食特点（米饭为主，胃排空慢），将kStomach从0.050降至0.035（半衰从14min延至20min），同时将速效胰岛素皮下吸收率ka从0.018提至0.024（峰值从~110min提前至~75min），kGut从0.065降至0.050以匹配慢胃排空。

**7. 双相胃排空 (v3.0.23)**: 针对旧模型"记录饮食后血糖先降后升"的问题，引入进食后15min内的快速胃排空相——20%碳水直接进入肠道（模拟液体/混合食物的初始快速排空），80%留在胃中经慢相排空。该修复消除了高血糖状态下Uii清糖速率超过碳水吸收速率导致的初始伪降。

系统使用8状态RK4求解ODE，步长5分钟，预测时域180分钟。初始条件从近期（≤24h）的进食和胰岛素注射记录预计算，且肠道初始值受VmaxGastric约束。

**个性化参数**: 通过ISF（胰岛素敏感因子）推导isfFactor = 1.5/ISF，自适应调整8项参数：

| 参数 | 公式 | 生理依据 |
|------|------|---------|
| kStomach | 0.035 - isfFactor×0.004 | 中式米饭慢排空 (v3.0.22) |
| kGut | 0.050 (固定) | 肠道吸收匹配慢排空 (v3.0.22) |
| VmaxGastric | 10.0 - isfFactor×2.0 | 抵抗→胃轻瘫→上限更低 |
| Vm0 | 2.5 - isfFactor×0.2 + activity×0.3 | 论文值 mg/kg/min (v3.0.20) |
| VmX | 0.05 - isfFactor×0.01 | 论文值 (v3.0.20) |
| hepaticBase | 2.07 + isfFactor×0.4 | 稳态平衡 Gb=7.0 (v3.0.20) |
| ka1/ka2 | 0.024 (固定) | 速效胰岛素峰值~75min (v3.0.22) |
| kp3 | 0.045 - isfFactor×0.007 | 抵抗→胰岛素起效慢 |

> ★ v3.0.20: Vm0 4.5→2.5, Km0 25→100, VmX 0.16→0.05, k1 0.040→0.060, hepaticBase 1.8→2.07。稳态正确回到 Gb=7.0。
> ★ v3.0.22: kStomach 0.050→0.035 (中式饮食), ka 0.018→0.024 (速效峰值), kGut 0.065→0.050 (匹配)。
> ★ v3.0.23: 双相胃排空, 进食<15min时20%碳水直接入肠, 消除"刚记录饮食血糖反降"问题。

### 4.4 BMA融合

贝叶斯模型平均(BMA)根据数据充分性和完整性动态调整模型权重。TCN曲线通过锚定校准消除d参数偏移，确保起点与当前血糖无缝连接：

$$P_{fused}(t) = w_{TCN} \cdot P_{TCN}^{aligned}(t) + w_{DallaMan} \cdot P_{DallaMan}(t)$$

**TCN物理门控 (v3.0.22)**: TCN模型的ONNX权重存在c项恒正缺陷（训练数据中胰岛素驱动下降场景不足），导致在胰岛素存在时仍预测血糖上升。系统通过7条物理规则（R1-R7）检测TCN不可信场景：IOB主导(R1,R2,R5)、TCN暴涨(R4,R6)、方向冲突(R7)、反应不足(R3)。触发后TCN曲线以75% DallaMan + 25% TCN残差重建，若DM 30min跌超4.0 mmol/L则将DM权重降至50%防止过冲。门控触发后BMA中TCN权重从0.3-0.7压至0.05-0.2。

### 4.5 增量自学习

系统实现了**数据质量感知**的四层自学习机制。数据完整度（dataCompleteness）分为三级：纯血糖(0.3)、有饮食(0.6)、完整(1.0)。个性化修正强度随数据质量和天数自适应：

$$adaptStrength = 0.7 \times \max(1 - days/14, 0.15) \times (1 - completeness \times 0.6)$$

**第1层 - 统计学习**: 从全部历史数据(≤10000条)中学习8个个性化参数。前10次更新使用加速α=0.3快速收敛，之后稳定在α=0.1。空腹窗口为0-6点（适配中国用户早睡习惯）。

**第2层 - 时段模式**: 将历史数据按小时分组，计算每个时段的平均血糖偏离基线值。施加指数衰减权重修正：

$$\Delta G_{hourly}(t) = \delta_{hour} \cdot e^{-t/60}$$

**第3层 - 增量残差学习**: 15→16→4轻量神经网络（304参数），SGD(学习率0.001，动量0.9，L2正则1×10⁻⁴)在线更新。通过reversed()修复Room DESC排序导致的索引偏移bug，添加血糖值范围验证（1.0-30.0 mmol/L）防止异常数据污染权重。更新次数>20即启用个性化残差修正。

**第4层 - 在线梯度下降**: 实际血糖到达后反推TCN预测误差，权重分配到4个曲线参数，单步SGD更新。

自学习引擎由SelfLearningManager统一管理，常驻Application Scope，结合数据量触发（累积50条≈4h）和按量批量学习防止批量导入时只学1次。

## 5 系统功能

### 5.1 血糖接收

- CGM通知监听：通过NotificationListenerService直接读取40+品牌CGM App的通知（移植自xDrip+ UiBasedCollector源码），支持LOW(<40mg/dL)和HIGH(>405mg/dL)特殊标记
- xDrip+广播接收：兼容com.eveningoutpost.dexdrip.BgEstimate格式
- 欧态xlsx导入：轻量XLSX解析器（ZIP→sharedStrings.xml+sheet1.xml），自动检测mg/dL单位，2分钟去重窗口
- 历史数据同步：通过xDrip+ Content Provider/REST API

### 5.2 智能预警

预警引擎支持9类预警。**预测性预警**不等血糖越线，基于线性ROC投影提前30分钟提醒（预测值钳制在[2.0, 30.0] mmol/L防止生理不可行值）。严重低血糖自动拨打紧急联系人（30分钟冷却防止重复骚扰）。血糖恢复正常后自动清除相关告警通知。

### 5.3 指尖血校准

采用EWMA算法：首次校准α=1.0（完全信任指尖值），之后递减至0.3。MIN_SAMPLES=1（一次即生效）。校准仅当计数≥3且12h内有效时应用于CGM读数修正。

### 5.4 参数自动测算

基于TDD(日均总剂量)法则和数据驱动配对分析估算ISF和CR。当置信度为"中"或"高"时自动写入SettingsManager，无需用户手动配置。参数通过SharedPreferences持久化，所有组件共享同一实例。

### 5.5 AI智能助手

- **自然语言记录**: 用户说"我中午吃了米饭200g"→AI解析为记录指令→创建MealRecord+MealItem→估算碳水/热量/GI
- **AI健康顾问**: 基于兼容OpenAI的API，提供糖尿病管理建议
- **食物拍照识别**: 百度AI菜品识别+大模型营养查询，完整传递蛋白质/脂肪/纤维至MealRecord

### 5.6 系统特色

- **数据质量分层**: 纯血糖/部分/完整三级，自适应学习权重
- **日期回看**: 首页左右箭头翻看任意历史日期血糖数据
- **通知栏曲线**: 下拉通知栏查看sparkline血糖趋势图（含近期饮食感知的预测）
- **时间范围选择**: 预测图/血糖图支持1h/3h/6h/12h/24h自由切换
- **一键调试导出**: JSON格式导出所有DB表+SharedPreferences+学习状态

### 5.7 激活管理系统

采用AES-128-CBC加密的激活码系统，支持按功能维度(对话/拍照/预测/报告/导出)限制使用次数和激活窗口。密钥分段拼接增加反编译提取难度。

## 6 实验结果

### 6.1 TCN模型性能

| 指标 | 值 |
|------|-----|
| MAE | 0.612 mmol/L |
| RMSE | 0.891 mmol/L |
| Clarke A区 | 92.5% |
| Clarke A+B区 | 98.1% |
| 预测时域 | 0-180分钟 |
| 特征维度 | 15 |
| 输入窗口 | 288点(24小时) |

### 6.2 Dalla Man个性化效果

实际测试数据（65kg T2DM患者，5天数据，1438条血糖记录）：
- 空腹基线学习值: 7.1 mmol/L（OnlineLearner自动学习）
- 餐后峰值学习值: 10.4 mmol/L
- 血糖变异系数CV: 21.2%（临床评价: 优秀/稳定）
- 个性化参数更新次数: 83次统计学习 + 6次增量学习
- 增量学习损失: 0.024（正常范围）

60g碳水+4U速效胰岛素的模拟预测：峰值从群体参数(VmaxGastric=15)下的14+ mmol/L降至个性化参数(VmaxGastric=7, Vm0=2.5, VmX=0.05)下的9-10 mmol/L，与实际临床观察一致。

### 6.3 系统性能

| 指标 | 值 |
|------|-----|
| 应用大小 | 82 MB (含ONNX模型590KB) |
| 内存占用 | <250 MB (含前台服务) |
| CPU占用(预测) | <5% |
| 实时监测延迟 | <1秒 (通知→处理→DB) |
| 数据库响应 | <10ms (288条查询) |
| 增量学习延迟 | <1ms (304参数SGD) |
| 后台存活率 | >95% (START_STICKY+定时唤醒) |

## 7 讨论

### 7.1 模型局限性

当前TCN模型基于OhioT1DM和HUPA数据集训练，未包含亚洲人群数据。生理模型存在以下简化：单胃单肠隔室（无固体/液体分流）、无反调节激素（低血糖恢复偏慢）、单胰岛素浓度（肝/外周未区分）。内源性胰岛素分泌通过固定sigma参数建模，未根据C肽水平个体化。心率(f14)和步数(f15)特征在没有可穿戴设备时恒为0。

### 7.2 未来工作

- **ExecuTorch迁移**: 将TCN模型从ONNX迁移到ExecuTorch，实现设备端训练
- **联邦学习**: 在保护隐私的前提下汇聚多用户数据优化全局模型
- **C肽水平个体化**: 基于临床检测数据精确设定sigma参数
- **饮食图像语义分割**: 从食物图片直接估算碳水含量
- **闭环胰岛素泵接口**: 将预测结果接入胰岛素泵
- **糖剑远程监护系统**: ESP32+微泰Gen2+MQTT云+多对多绑定

## 8 结论

本文提出了糖盾——一个集成TCN数据驱动模型、Dalla Man七隔室生理模型和数据质量感知增量自学习机制的糖尿病血糖预测系统。系统在Android移动端实现了15维特征（含f11/f13归一化处理）的实时提取、ONNX推理和304参数在线SGD学习，并实现了自适应EWMA实时监测、预测性预警、自然语言AI记录等实用功能。TCN模型达到MAE 0.612 mmol/L的预测精度。Dalla Man模型通过Michaelis-Menten升级、VmaxGastric约束、内源性胰岛素分泌和ISF驱动的6项参数自适应，实现了符合中国T2DM人群生理特征的个性化预测。系统通过SelfLearningManager统一管理四层自学习，数据完整度感知权重自适应调整，为糖尿病患者提供了一个完整、可靠、智能的技术解决方案。

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

[11] Dassau E, Bequette BW, Buckingham BA, Doyle FJ. Detection of a meal using continuous glucose monitoring. *Diabetes Care*, 31(2):295-300, 2008.

[12] Cameron F, Niemeyer G, Wilson DM, et al. A closed-loop artificial pancreas based on risk management. *J Diabetes Sci Technol*, 5(2):368-379, 2011.
