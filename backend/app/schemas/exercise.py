# backend/app/schemas/exercise.py
# 运动相关的Pydantic模型

from pydantic import BaseModel, Field, ConfigDict
from datetime import datetime
from typing import Optional, List


class ExerciseRecordBase(BaseModel):
    """运动记录基础模型"""
    start_time: datetime = Field(..., description="运动开始时间")
    end_time: Optional[datetime] = Field(None, description="运动结束时间")
    exercise_type: str = Field(..., description="运动类型: walking/running/cycling/swimming/etc")
    duration_min: Optional[int] = Field(None, ge=0, description="运动时长(分钟)")
    avg_heart_rate: Optional[float] = Field(None, ge=30, le=220, description="平均心率")
    max_heart_rate: Optional[float] = Field(None, ge=30, le=220, description="最大心率")
    steps: Optional[int] = Field(None, ge=0, description="步数")
    calories_burned: Optional[float] = Field(None, ge=0, description="消耗热量(kcal)")
    intensity: Optional[str] = Field(None, description="强度: low/moderate/high")
    source: str = Field(default="huawei_watch", description="数据来源")


class ExerciseRecordCreate(ExerciseRecordBase):
    """创建运动记录请求模型"""
    pass


class ExerciseRecordResponse(ExerciseRecordBase):
    """运动记录响应模型"""
    model_config = ConfigDict(from_attributes=True)

    id: int = Field(..., description="记录ID")
    user_id: int = Field(..., description="用户ID")
    created_at: datetime = Field(..., description="创建时间")


class HeartRateRecordBase(BaseModel):
    """心率记录基础模型"""
    timestamp: datetime = Field(..., description="记录时间")
    heart_rate: int = Field(..., ge=30, le=220, description="心率(bpm)")
    source: str = Field(default="huawei_watch", description="数据来源")


class HeartRateRecordCreate(HeartRateRecordBase):
    """创建心率记录请求模型"""
    pass


class HeartRateRecordResponse(HeartRateRecordBase):
    """心率记录响应模型"""
    model_config = ConfigDict(from_attributes=True)

    id: int = Field(..., description="记录ID")
    user_id: int = Field(..., description="用户ID")
    created_at: datetime = Field(..., description="创建时间")


class StepRecordBase(BaseModel):
    """步数记录基础模型"""
    timestamp: datetime = Field(..., description="记录时间")
    step_count: int = Field(..., ge=0, description="步数")
    distance: Optional[float] = Field(None, ge=0, description="距离(m)")
    calories: Optional[float] = Field(None, ge=0, description="消耗热量(kcal)")
    source: str = Field(default="huawei_watch", description="数据来源")


class StepRecordCreate(StepRecordBase):
    """创建步数记录请求模型"""
    pass


class StepRecordResponse(StepRecordBase):
    """步数记录响应模型"""
    model_config = ConfigDict(from_attributes=True)

    id: int = Field(..., description="记录ID")
    user_id: int = Field(..., description="用户ID")
    created_at: datetime = Field(..., description="创建时间")


class SleepRecordBase(BaseModel):
    """睡眠记录基础模型"""
    start_time: datetime = Field(..., description="睡眠开始时间")
    end_time: datetime = Field(..., description="睡眠结束时间")
    duration_min: Optional[int] = Field(None, ge=0, description="睡眠时长(分钟)")
    deep_sleep_min: Optional[int] = Field(None, ge=0, description="深睡时长(分钟)")
    light_sleep_min: Optional[int] = Field(None, ge=0, description="浅睡时长(分钟)")
    rem_sleep_min: Optional[int] = Field(None, ge=0, description="REM时长(分钟)")
    source: str = Field(default="huawei_watch", description="数据来源")


class SleepRecordCreate(SleepRecordBase):
    """创建睡眠记录请求模型"""
    pass


class SleepRecordResponse(SleepRecordBase):
    """睡眠记录响应模型"""
    model_config = ConfigDict(from_attributes=True)

    id: int = Field(..., description="记录ID")
    user_id: int = Field(..., description="用户ID")
    created_at: datetime = Field(..., description="创建时间")


class ExerciseStats(BaseModel):
    """运动统计信息模型"""
    total_duration_min: int = Field(..., description="总运动时长(分钟)")
    total_steps: int = Field(..., description="总步数")
    total_calories: float = Field(..., description="总消耗热量(kcal)")
    avg_heart_rate: Optional[float] = Field(None, description="平均心率")
    exercise_count: int = Field(..., description="运动次数")


class ExercisePrescription(BaseModel):
    """运动处方模型"""
    exercise_type: str = Field(..., description="推荐运动类型")
    duration_min: int = Field(..., description="推荐运动时长(分钟)")
    intensity: str = Field(..., description="推荐运动强度")
    expected_glucose_drop: float = Field(..., description="预计血糖下降值 mmol/L")
    notes: str = Field(..., description="注意事项")
