# backend/app/schemas/insulin.py
# 胰岛素相关的Pydantic模型

from pydantic import BaseModel, Field, ConfigDict
from datetime import datetime
from typing import Optional, List


class InsulinRecordBase(BaseModel):
    """胰岛素记录基础模型"""
    timestamp: datetime = Field(..., description="注射时间")
    insulin_type: str = Field(..., description="胰岛素类型: rapid/long/mixed")
    dose_units: float = Field(..., gt=0, description="剂量(单位U)")
    injection_site: Optional[str] = Field(None, description="注射部位: abdomen/arm/thigh/buttock")
    meal_id: Optional[int] = Field(None, description="关联的饮食记录ID")
    notes: Optional[str] = Field(None, description="备注")


class InsulinRecordCreate(InsulinRecordBase):
    """创建胰岛素记录请求模型"""
    pass


class InsulinRecordResponse(InsulinRecordBase):
    """胰岛素记录响应模型"""
    model_config = ConfigDict(from_attributes=True)

    id: int = Field(..., description="记录ID")
    user_id: int = Field(..., description="用户ID")
    created_at: datetime = Field(..., description="创建时间")


class InsulinOnBoard(BaseModel):
    """胰岛素活性(IOB)模型"""
    current_iob: float = Field(..., description="当前胰岛素活性(单位U)")
    peak_iob: float = Field(..., description="峰值胰岛素活性(单位U)")
    iob_curve: List[dict] = Field(default=[], description="IOB曲线数据")
    stacking_warning: bool = Field(default=False, description="是否有叠加警告")
    stacking_message: Optional[str] = Field(None, description="叠加警告消息")


class SensorChangeBase(BaseModel):
    """传感器更换记录基础模型"""
    change_time: datetime = Field(..., description="更换时间")
    sensor_type: Optional[str] = Field(None, description="传感器类型")
    sensor_id: Optional[str] = Field(None, description="传感器序列号")
    notes: Optional[str] = Field(None, description="备注")


class SensorChangeCreate(SensorChangeBase):
    """创建传感器更换记录请求模型"""
    pass


class SensorChangeResponse(SensorChangeBase):
    """传感器更换记录响应模型"""
    model_config = ConfigDict(from_attributes=True)

    id: int = Field(..., description="记录ID")
    user_id: int = Field(..., description="用户ID")
    warmup_end_time: Optional[datetime] = Field(None, description="预热结束时间")
    expiry_date: Optional[datetime] = Field(None, description="过期日期")
    created_at: datetime = Field(..., description="创建时间")


class EmergencyContactBase(BaseModel):
    """紧急联系人基础模型"""
    name: str = Field(..., description="联系人姓名")
    phone: str = Field(..., description="联系电话")
    relationship: Optional[str] = Field(None, description="关系")
    is_primary: int = Field(default=0, description="是否主要联系人")


class EmergencyContactCreate(EmergencyContactBase):
    """创建紧急联系人请求模型"""
    pass


class EmergencyContactResponse(EmergencyContactBase):
    """紧急联系人响应模型"""
    model_config = ConfigDict(from_attributes=True)

    id: int = Field(..., description="记录ID")
    user_id: int = Field(..., description="用户ID")
    created_at: datetime = Field(..., description="创建时间")


class AlcoholRecord(BaseModel):
    """饮酒记录模型"""
    timestamp: datetime = Field(..., description="饮酒时间")
    drink_type: str = Field(..., description="酒类: beer/wine/spirit")
    amount_ml: float = Field(..., description="饮酒量(ml)")
    alcohol_percent: float = Field(..., description="酒精度数(%)")
    notes: Optional[str] = Field(None, description="备注")
