# 糖盾 API 文档

## 概述

糖盾系统后端API基于FastAPI框架构建，提供RESTful风格的接口。

- 基础URL: `http://localhost:8000/api/v1`
- 认证方式: JWT Token
- 数据格式: JSON

## 认证接口

### 登录

```
POST /auth/login?openid={openid}
```

**参数:**
- `openid` (string, 必需): 用户OpenID

**响应:**
```json
{
    "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "token_type": "bearer",
    "expires_in": 604800
}
```

### 获取用户信息

```
GET /auth/me
```

**请求头:**
```
Authorization: Bearer {token}
```

**响应:**
```json
{
    "id": 1,
    "openid": "user_001",
    "name": "用户昵称",
    "avatar_url": null,
    "diabetes_type": 2,
    "target_range_low": 3.9,
    "target_range_high": 10.0
}
```

## 血糖接口

### 创建血糖记录

```
POST /glucose/
```

**请求体:**
```json
{
    "timestamp": "2026-06-06T10:00:00",
    "value": 7.5,
    "trend": "stable",
    "source": "cgm"
}
```

### 批量创建血糖记录

```
POST /glucose/batch
```

**请求体:**
```json
{
    "records": [
        {"timestamp": "2026-06-06T10:00:00", "value": 7.5, "source": "cgm"},
        {"timestamp": "2026-06-06T10:05:00", "value": 7.8, "source": "cgm"}
    ]
}
```

### 获取血糖记录

```
GET /glucose/?start={start}&end={end}&limit={limit}
```

**参数:**
- `start` (datetime, 可选): 开始时间
- `end` (datetime, 可选): 结束时间
- `limit` (int, 可选): 返回记录数上限，默认288

### 获取最新血糖

```
GET /glucose/latest
```

### 获取血糖统计

```
GET /glucose/stats?start={start}&end={end}
```

**响应:**
```json
{
    "avg_glucose": 7.2,
    "min_glucose": 4.5,
    "max_glucose": 11.2,
    "std_glucose": 1.5,
    "tir": 75.0,
    "tir_low": 10.0,
    "tir_high": 15.0,
    "count": 288
}
```

### 获取血糖趋势

```
GET /glucose/trend
```

**响应:**
```json
{
    "current_value": 7.2,
    "trend": "rising",
    "change_30min": 0.8,
    "change_60min": 1.2,
    "slope_30min": 0.027,
    "slope_60min": 0.02
}
```

## 饮食接口

### 创建饮食记录

```
POST /meal/
```

**请求体:**
```json
{
    "timestamp": "2026-06-06T12:00:00",
    "meal_type": "lunch",
    "items": [
        {
            "food_name": "白米饭",
            "portion_grams": 200,
            "carbs": 51.8,
            "calories": 232,
            "gi": 83
        }
    ]
}
```

### 食物识别

```
POST /meal/recognize
```

**请求:** multipart/form-data
- `image`: 图片文件 (jpg/png, <5MB)

**响应:**
```json
{
    "foods": [
        {"name": "白米饭", "confidence": 0.95, "calories_per_100g": 116}
    ]
}
```

### 查询食物营养

```
GET /meal/nutrition/{food_name}
```

### 搜索食物

```
GET /meal/nutrition/search/{keyword}?limit={limit}
```

### What-if模拟

```
POST /meal/what-if
```

**请求体:**
```json
{
    "foods": [
        {"food_name": "白米饭", "portion_grams": 200, "carbs": 51.8, "gi": 83}
    ],
    "current_glucose": 6.0
}
```

## 运动接口

### 创建运动记录

```
POST /exercise/
```

### 获取运动记录

```
GET /exercise/?start={start}&end={end}&limit={limit}
```

### 获取运动统计

```
GET /exercise/stats?start={start}&end={end}
```

### 获取运动处方

```
GET /exercise/prescription?current_glucose={value}
```

### 心率/步数/睡眠记录

```
POST /exercise/heart-rate
POST /exercise/steps
POST /exercise/sleep
GET /exercise/heart-rate
GET /exercise/steps
GET /exercise/sleep
```

## 预测接口

### 获取预测结果

```
GET /prediction/?horizon={horizon}
```

**参数:**
- `horizon` (int, 可选): 预测时域(分钟)，默认60

### 获取预测准确率

```
GET /prediction/accuracy
```

### 获取预警列表

```
GET /prediction/alerts?is_read={is_read}
```

### 标记预警已读

```
PUT /prediction/alerts/{alert_id}/read
PUT /prediction/alerts/read-all
```

## 报告接口

### 日报告

```
GET /report/daily?report_date={date}
```

### 周报告

```
GET /report/weekly?start_date={date}
```

### 数据导出

```
GET /report/export/csv?start={start}&end={end}&data_type={type}
```

**参数:**
- `data_type`: glucose/meal/exercise

## 健康数据同步接口

### 获取同步状态

```
GET /health/sync-status
```

### 同步健康数据

```
POST /health/sync
```

**请求体:**
```json
{
    "heart_rate": [{"timestamp": 1686000000000, "heart_rate": 75}],
    "steps": [{"timestamp": 1686000000000, "steps": 1000}],
    "exercise": [{"start_time": 1686000000000, "end_time": 1686001800000}],
    "sleep": [{"start_time": 1685950000000, "end_time": 1685980000000}]
}
```

## 健康检查

```
GET /health
```

**响应:**
```json
{
    "status": "ok",
    "version": "1.0.0",
    "system": "糖盾糖尿病智能健康管理系统"
}
```

## 错误响应

所有错误响应格式:

```json
{
    "detail": "错误描述"
}
```

常见HTTP状态码:
- 400: 请求参数错误
- 401: 未认证
- 403: 无权限
- 404: 资源不存在
- 422: 数据验证失败
- 500: 服务器内部错误
