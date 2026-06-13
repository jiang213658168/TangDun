# backend/app/schemas/report.py
# 报告相关的Pydantic模型

from pydantic import BaseModel, Field, ConfigDict
from datetime import datetime, date
from typing import Optional, List


class DailyReport(BaseModel):
    """日报告模型"""
    model_config = ConfigDict(from_attributes=True)

    date: date
    tir: float
    tir_low: float
    tir_high: float
    avg_glucose: float
    min_glucose: float
    max_glucose: float
    std_glucose: float
    gri: float
    total_carbs: float = 0
    total_calories: float = 0
    total_steps: int = 0
    total_exercise_min: int = 0


class WeeklyReport(BaseModel):
    """周报告模型"""
    model_config = ConfigDict(from_attributes=True)

    start_date: date
    end_date: date
    avg_tir: float
    avg_glucose: float
    glucose_variability: float
    highlights: List[str] = []
    improvements: List[str] = []
    total_carbs: float = 0
    total_steps: int = 0
    total_exercise_min: int = 0


class MonthlyReport(BaseModel):
    """月报告模型"""
    model_config = ConfigDict(from_attributes=True, protected_namespaces=())

    year: int
    month: int
    avg_tir: float
    tir_trend: List[dict] = []
    avg_glucose: float
    hba1c_estimate: Optional[float] = None
    model_progress: dict = {}
    recommendations: List[str] = []


class PredictionResult(BaseModel):
    """预测结果模型（支持TCN曲线预测）"""
    model_config = ConfigDict(from_attributes=True, protected_namespaces=())

    prediction_time: datetime
    risk_level: str
    model_type_name: str = "tcn_v2"

    # 当前血糖
    current_glucose: float = 0.0

    # 关键时间点预测
    predicted_5min: Optional[float] = None
    predicted_15min: Optional[float] = None
    predicted_30min: Optional[float] = None
    predicted_60min: Optional[float] = None
    predicted_120min: Optional[float] = None

    # 曲线数据（0-120分钟，25个点）
    curve: List[float] = []

    # 预测范围
    upper_bound: Optional[float] = None
    lower_bound: Optional[float] = None


class AlertResponse(BaseModel):
    """预警响应模型"""
    model_config = ConfigDict(from_attributes=True)

    id: int
    alert_type: str
    severity: str
    glucose_value: Optional[float] = None
    predicted_value: Optional[float] = None
    message: Optional[str] = None
    is_read: int = 0
    created_at: datetime
