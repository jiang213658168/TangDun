# backend/app/schemas/glucose.py
# 血糖相关的Pydantic模型

from pydantic import BaseModel, Field, ConfigDict
from datetime import datetime
from typing import Optional, List


class GlucoseBase(BaseModel):
    """血糖记录基础模型"""
    timestamp: datetime = Field(..., description="测量时间")
    value: float = Field(..., ge=1.0, le=30.0, description="血糖值 mmol/L")
    trend: Optional[str] = Field(None, description="趋势: rising/falling/stable/rising_fast/falling_fast")
    source: str = Field(default="cgm", description="数据来源: cgm/finger")


class GlucoseCreate(GlucoseBase):
    """创建血糖记录请求模型"""
    sensor_id: Optional[str] = Field(None, description="传感器ID")
    raw_data: Optional[float] = Field(None, description="原始传感器数据")
    filtered_data: Optional[float] = Field(None, description="滤波后数据")


class GlucoseResponse(GlucoseBase):
    """血糖记录响应模型"""
    model_config = ConfigDict(from_attributes=True)

    id: int = Field(..., description="记录ID")
    user_id: int = Field(..., description="用户ID")
    sensor_id: Optional[str] = Field(None, description="传感器ID")
    is_calibrated: int = Field(default=0, description="是否已校准")
    created_at: datetime = Field(..., description="创建时间")


class GlucoseBatchCreate(BaseModel):
    """批量创建血糖记录请求模型"""
    records: List[GlucoseCreate] = Field(..., description="血糖记录列表")


class GlucoseStats(BaseModel):
    """血糖统计信息模型"""
    avg_glucose: float = Field(..., description="平均血糖值")
    min_glucose: float = Field(..., description="最低血糖值")
    max_glucose: float = Field(..., description="最高血糖值")
    std_glucose: float = Field(..., description="血糖标准差")
    tir: float = Field(..., description="目标范围内时间占比(%)")
    tir_low: float = Field(..., description="低于目标范围比例(%)")
    tir_high: float = Field(..., description="高于目标范围比例(%)")
    count: int = Field(..., description="数据点数量")


class GlucoseTrend(BaseModel):
    """血糖趋势模型"""
    current_value: float = Field(..., description="当前血糖值")
    trend: str = Field(..., description="趋势方向")
    change_30min: float = Field(..., description="30分钟变化量")
    change_60min: float = Field(..., description="60分钟变化量")
    slope_30min: float = Field(..., description="30分钟斜率")
    slope_60min: float = Field(..., description="60分钟斜率")
