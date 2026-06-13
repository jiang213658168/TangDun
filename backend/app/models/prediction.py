# backend/app/models/prediction.py
# 预测和预警数据模型

from sqlalchemy import Column, Integer, String, Float, DateTime, ForeignKey
from sqlalchemy.sql import func
from app.database import Base


class PredictionRecord(Base):
    """血糖预测记录表模型"""

    __tablename__ = "prediction_records"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    prediction_time = Column(DateTime, nullable=False, comment="预测生成时间")
    target_time = Column(DateTime, nullable=False, comment="预测目标时间")
    horizon_min = Column(Integer, nullable=False, comment="预测时域(分钟)")
    predicted_value = Column(Float, nullable=False, comment="预测血糖值")
    confidence_lower = Column(Float, comment="置信区间下限")
    confidence_upper = Column(Float, comment="置信区间上限")
    actual_value = Column(Float, comment="实际血糖值(事后填充)")
    model_type = Column(String, comment="模型类型: bergman/transformer_lstm/fusion")
    risk_level = Column(String, comment="风险等级: normal/low_risk/high_risk")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")


class AlertRecord(Base):
    """预警记录表模型"""

    __tablename__ = "alert_records"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    alert_type = Column(String, nullable=False, comment="预警类型: low_glucose/high_glucose/rapid_change")
    severity = Column(String, nullable=False, comment="严重程度: warning/critical")
    glucose_value = Column(Float, comment="触发预警的血糖值")
    predicted_value = Column(Float, comment="预测血糖值")
    message = Column(String, comment="预警消息")
    is_read = Column(Integer, default=0, comment="是否已读")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")
