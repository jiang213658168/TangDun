# 糖剑 (TangJian) — 远程血糖监护系统 技术设计

## 1. 系统架构总览

```
┌──────────────────────────┐
│     学生端 (ESP32)        │
│                          │
│  ESP32-S3 + 4G模块       │
│  OLED/TFT本地显示        │
│  BLE → 微泰第2代CGM      │
│  MQTT → 云服务器         │
│  本地告警 (蜂鸣器+LED)    │
└──────────┬───────────────┘
           │ MQTT/TLS
           ▼
┌──────────────────────────┐
│      云服务器             │
│                          │
│  EMQX MQTT Broker        │
│  TangJian Server (Go)    │
│  PostgreSQL              │
│  Redis (缓存)            │
│  FCM推送                 │
└──────────┬───────────────┘
           │ MQTT/HTTP
           ▼
┌──────────────────────────┐
│     家长端 (Android)      │
│                          │
│  糖盾App + 远程监护Tab    │
│  实时血糖+预测+告警       │
│  多孩子切换               │
└──────────────────────────┘
```

---

## 2. 硬件选型 (学生端)

### ESP32-S3 + 4G方案

| 组件 | 型号 | 连接 | 单价 |
|------|------|------|------|
| 主控 | ESP32-S3-WROOM-1 | - | ¥25 |
| 4G模块 | SIM7600G-H | UART AT指令 | ¥55 |
| 显示屏 | 1.3寸 OLED (SH1106) | I2C | ¥12 |
| SIM卡 | 物联网卡 (1GB/月) | SIM7600 | ¥5/月 |
| 电源 | TP4056 + 18650电池 | - | ¥8+¥15 |
| PCB + 外壳 | 3D打印 | - | ¥30 |
| **总硬件成本** | | | **~¥150** |

### 备选：WiFi版本（学校有WiFi时）

省去SIM7600和SIM卡，总成本降至**~¥80**。ESP32-S3自带WiFi 2.4GHz。

### 显示方案

OLED 128×64 显示内容：
```
小明的血糖
6.8 ↗️ mmol/L
电量 ████░░ 72%
●━━━● 在线
```

---

## 3. BLE驱动移植 (Medtrum Gen2 → ESP32)

### 3.1 xDrip+ Medtrum驱动架构

```
MedtrumCollectionService (Java, ~1700行)
├── automata() 状态机
├── Scanner (BLE扫描, 制造商ID 0x4781)
├── Crypt.code() XOR加密
├── 消息类 (~10个 Tx/Rx pairs)
├── MedtrumSensor.calculatedGlucose()
└── TransmitterData → BgReading
```

### 3.2 ESP32 C/C++移植方案

**BLE库选择:** NimBLE (ESP-IDF内置, 比Bluedroid省~400KB RAM)

**文件结构:**
```
esp32-tangjian/
├── main/
│   ├── main.c              # 入口, WiFi/4G初始化
│   ├── medtrum_ble.c       # BLE扫描+连接+状态机
│   ├── medtrum_proto.c     # 协议解析(移植自Java消息类)
│   ├── medtrum_crypto.c    # XOR加密
│   ├── mqtt_client.c       # MQTT上报
│   ├── display.c           # OLED显示
│   ├── alarm.c             # 本地告警(蜂鸣器)
│   └── config.h            # 设备ID/WiFi/MQTT配置
├── partitions.csv
└── sdkconfig
```

### 3.3 核心状态机

```
INIT → SCAN → CONNECT → DISCOVER → AUTH → CALIBRATE 
  → SET_TIME → SET_CONN → GET_DATA → LISTEN
  ↓
  每5分钟接收一次数据包
  ↓
  解析: raw_data, filtered_data, timestamp, battery, sensor_state
  ↓
  计算血糖: glucose = (raw * 1000 / slope) + intercept
  ↓
  MQTT发布 + 本地显示
```

### 3.4 Medtrum Gen2 BLE参数

```
制造商ID:    0x4781
CGM Service: 669A9002-0008-968F-E311-6050405558B3  
Notify Char: 669A9140-0008-968F-E311-6050405558B3
Indicate Char: 669A9101-0008-968F-E311-6050405558B3
CCCD:       00002902-0000-1000-8000-00805F9B34FB
```

### 3.5 移植注意事项

- ESP32 RAM仅520KB, 需要优化协议栈内存占用
- NimBLE配置: `max_connections=1`, `max_gattc_db_elements=8`
- 微泰Gen2可能和A6协议不同, 需要抓包验证UUID
- 如果Gen2协议不兼容, 回退到通知监听方案(ESP32通过UART连接Android手机)

---

## 4. 通信协议

### 4.1 MQTT主题设计

```
# 数据上报 (学生端→云)
tangjian/{device_id}/data        
tangjian/{device_id}/prediction  
tangjian/{device_id}/battery     
tangjian/{device_id}/status      # online/offline (LWT遗嘱消息)

# 告警 (双向)
tangjian/{device_id}/alert/up    
tangjian/{device_id}/alert/ack   # 家长确认

# 指令 (家长端→学生端)
tangjian/{device_id}/cmd/refresh  # 强制刷新
tangjian/{device_id}/cmd/config   # 远程配置

# 设备发现 (学生端→云)
tangjian/register                 # 首次注册
```

### 4.2 MQTT Payload格式 (JSON, 极简)

```json
// 血糖数据上报
{
  "ts": 1718352000,
  "v": 6.8,
  "t": "rising",
  "r": 0.05,
  "b": 72,
  "s": "ok"
}
// ts=时间戳, v=血糖mmol/L, t=趋势, r=ROC, b=电量%, s=传感器状态
```

每条消息约80字节，每月1GB流量可传~1200万条。

### 4.3 MQTT QoS策略

| 主题 | QoS | 理由 |
|------|-----|------|
| data | 1 | 至少送达一次, 去重 |
| alert | 2 | 告警必须送达 |
| status | 0 | LWT遗嘱, 丢失可接受 |
| cmd | 1 | 指令至少一次 |

---

## 5. 云服务器设计

### 5.1 服务组件

```
nginx:443 (TLS终止)
  ├→ EMQX:1883 (MQTT over WebSocket, 备用)
  ├→ EMQX:8883 (MQTT over TLS)
  └→ TangJian API:8080 (HTTP REST)
       ├→ PostgreSQL:5432 (用户/设备/数据)
       └→ Redis:6379 (会话缓存 + 实时状态)
```

### 5.2 数据库设计

```sql
-- 用户表 (家长)
CREATE TABLE users (
    id UUID PRIMARY KEY,
    phone VARCHAR(16) UNIQUE,
    nickname VARCHAR(32),
    password_hash VARCHAR(128),
    role VARCHAR(8) DEFAULT 'parent',  -- parent|admin
    created_at TIMESTAMPTZ DEFAULT now()
);

-- 学生档案
CREATE TABLE students (
    id UUID PRIMARY KEY,
    name VARCHAR(32),
    birth_date DATE,
    diabetes_type INT DEFAULT 1,  -- 1=1型, 2=2型
    weight_kg REAL,
    emergency_phone VARCHAR(16),
    created_at TIMESTAMPTZ DEFAULT now()
);

-- 设备表 (ESP32)
CREATE TABLE devices (
    id UUID PRIMARY KEY,
    device_sn VARCHAR(32) UNIQUE,    -- 设备序列号
    student_id UUID REFERENCES students(id),
    mqtt_username VARCHAR(64),
    mqtt_password_hash VARCHAR(128),
    firmware_version VARCHAR(16),
    last_seen TIMESTAMPTZ,
    battery INT,
    status VARCHAR(8) DEFAULT 'offline',  -- online|offline|alarm
    created_at TIMESTAMPTZ DEFAULT now()
);

-- 多对多绑定: 家长↔学生
CREATE TABLE bindings (
    user_id UUID REFERENCES users(id),
    student_id UUID REFERENCES students(id),
    role VARCHAR(8) DEFAULT 'viewer',  -- viewer|guardian|admin
    nickname VARCHAR(32),             -- 家长对学生的昵称
    alert_enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (user_id, student_id)
);

-- 血糖数据 (时序, 按月分区)
CREATE TABLE glucose_data (
    device_id UUID REFERENCES devices(id),
    ts TIMESTAMPTZ,
    value REAL,                       -- mmol/L
    trend VARCHAR(16),
    roc REAL,
    battery INT,
    sensor_status VARCHAR(16),
    PRIMARY KEY (device_id, ts)
) PARTITION BY RANGE (ts);

-- 告警记录
CREATE TABLE alerts (
    id BIGSERIAL PRIMARY KEY,
    device_id UUID REFERENCES devices(id),
    student_id UUID REFERENCES students(id),
    type VARCHAR(16),                 -- low|severe_low|high|severe_high|predict_low|missed_data
    value REAL,
    message TEXT,
    acked_by UUID[] DEFAULT '{}',     -- 已确认的家长ID数组
    created_at TIMESTAMPTZ DEFAULT now()
);
```

### 5.3 API设计 (REST)

```
POST   /api/v1/auth/login           # 家长登录 (手机号+密码/验证码)
POST   /api/v1/auth/bind-device     # 绑定设备 (输入设备序列号)
GET    /api/v1/students             # 我绑定的学生列表
GET    /api/v1/students/{id}/glucose # 学生最新血糖
GET    /api/v1/students/{id}/history # 历史数据 (支持时间范围)
GET    /api/v1/students/{id}/prediction # 预测数据
POST   /api/v1/alerts/{id}/ack      # 确认告警
PUT    /api/v1/devices/{id}/config  # 远程配置告警阈值
```

### 5.4 推送服务

告警触发时通过FCM(Android)/APNs(iOS)推送到家长手机：
```json
{
  "type": "severe_low",
  "student_name": "小明",
  "value": 2.8,
  "message": "小明血糖严重偏低! 2.8 mmol/L",
  "device_id": "abc123",
  "timestamp": 1718352000
}
```

---

## 6. 家长端App集成

### 6.1 新增：糖盾"远程监护"Tab

在现有的5个Tab旁边增加第6个Tab：`Screen.Remote`

```
主屏幕底部导航栏:
┌──────┬──────┬──────┬──────┬──────┬──────┐
│ 首页 │ 预测 │ 记录 │ 报告 │ 远程 │ 我的 │
└──────┴──────┴──────┴──────┴──────┴──────┘
```

### 6.2 RemoteScreen UI

```
┌─────────────────────────────────┐
│  远程监护                         │
│                                  │
│  ┌─学生选择器──────────────┐     │
│  │ 👦 小明  👧 小红        │     │
│  └─────────────────────────┘     │
│                                  │
│  👦 小明              在线 ●    │
│                                  │
│  ┌──────────────────────────┐   │
│  │   血糖 6.8 ↗️ mmol/L     │   │
│  │      30秒前更新           │   │
│  └──────────────────────────┘   │
│                                  │
│  ┌─预测曲线─────────────────┐   │
│  │   (复用PredictionChartView) │   │
│  └──────────────────────────┘   │
│                                  │
│  ⚠ 预测30分钟后低血糖!          │
│  [确认告警] [联系孩子]          │
│                                  │
│  设备电量: ████░░ 72%            │
│  传感器: 正常 | 已用5天          │
│  最近24h TIR: 82% ✅             │
│  ┌─历史────────────────────┐    │
│  │  近期血糖趋势 (sparkline) │    │
│  └──────────────────────────┘    │
└─────────────────────────────────┘
```

### 6.3 MQTT客户端 (Android端)

使用 Eclipse Paho Android Service：
- MQTT持久连接
- 订阅 `tangjian/{device_id}/#`
- 自动重连
- 收到数据→更新RemoteViewModel

### 6.4 数据流

```
ESP32 → MQTT → EMQX → TangJian Server → PostgreSQL
                          ↓
                   家长在线? ─是→ MQTT直推家长端
                          ↓否→ FCM推送唤醒家长端
                          ↓
                    告警? ─是→ 高优先级推送 + 短信
```

---

## 7. 多对多绑定模型

### 7.1 绑定关系

```
        家长A ←──→ 学生1 (小明)     # 爸爸看小明
        家长B ←──→ 学生1 (小明)     # 妈妈也看小明
        家长A ←──→ 学生2 (小红)     # 爸爸看两个孩子
        老师C ←──→ 学生1,2,3,4,5   # 老师看全班同学
```

### 7.2 绑定流程

```
1. 学生端ESP32首次上电 → 生成设备序列号 → 显示在OLED上
2. 家长打开糖盾 → 远程监护 → 添加设备 → 输入序列号
3. 服务器验证 → 创建绑定 → 家长SQL中存储 device_id
4. ESP32注册MQTT → 服务器转发数据给所有绑定的家长
5. 解绑: 家长端发起 → 服务器移除绑定 → 停止数据转发
```

### 7.3 权限模型

| 角色 | 查看血糖 | 查看预测 | 接收告警 | 远程配置 | 添加其他家长 |
|------|---------|---------|---------|---------|------------|
| viewer | ✅ | ✅ | ❌ | ❌ | ❌ |
| guardian | ✅ | ✅ | ✅ | ❌ | ❌ |
| admin | ✅ | ✅ | ✅ | ✅ | ✅ |

---

## 8. 实施计划

### Phase 1: 协议验证 (1周)
- 抓包微泰Gen2 BLE通信
- 对比xDrip+ Medtrum A6协议
- 确认UUID和加密算法

### Phase 2: ESP32原型 (2周)
- ESP32 BLE连接微泰Gen2
- 协议解析+血糖计算
- OLED本地显示
- MQTT WiFi上报

### Phase 3: 云服务器 (1.5周)
- 部署EMQX + PostgreSQL
- TangJian Server开发(Go)
- 用户/设备/绑定API
- FCM推送集成

### Phase 4: 家长端App (1周)
- 糖盾新增RemoteScreen
- MQTT客户端集成
- 多学生切换UI
- 告警确认+推送

### Phase 5: 集成测试 (1周)
- 端到端数据链路测试
- 断网重连测试
- 多对多场景测试

**总计: 6-7周**

---

## 9. 备选/降级方案

| 原方案 | 降级方案 | 触发条件 |
|--------|---------|---------|
| ESP32 BLE直连 | ESP32通过UART接Android手机→通知监听 | 微泰Gen2协议无法破解 |
| 4G MQTT上报 | WiFi → 学校网络 → MQTT | 4G信号差 |
| 云MQTT | ESP32直连家长端IP (zerotier隧道) | 没有云服务器时 |
| FCM推送 | 短信告警 | 家长离线 |

---

## 10. 安全设计

- MQTT连接: TLS 1.3 + 设备证书认证
- API: JWT token (家长登录)
- 设备序列号: 不可猜测的随机字符串
- MQTT用户名/密码: 每个设备独立生成
- 数据存储: 服务器端AES-256加密
- 血糖数据: 脱敏后用于模型训练
