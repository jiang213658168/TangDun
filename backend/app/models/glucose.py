# backend/app/models/glucose.py
# 血糖记录数据模型

from sqlalchemy import Column, Integer, String, Float, DateTime, ForeignKey, UniqueConstraint
from sqlalchemy.sql import func
from app.database import Base


class GlucoseRecord(Base):
    """血糖记录表模型 - 来自CGM的血糖数据"""

    __tablename__ = "glucose_records"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    timestamp = Column(DateTime, nullable=False, comment="测量时间")
    value = Column(Float, nullable=False, comment="血糖值 mmol/L")
    trend = Column(String, comment="趋势: rising/falling/stable/rising_fast/falling_fast")
    source = Column(String, default="cgm", comment="数据来源: cgm/finger")
    sensor_id = Column(String, comment="传感器ID")
    raw_data = Column(Float, comment="原始传感器数据")
    filtered_data = Column(Float, comment="滤波后数据")
    is_calibrated = Column(Integer, default=0, comment="是否已校准")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")

    # 联合唯一约束：同一用户同一时间只能有一条记录
    __table_args__ = (UniqueConstraint("user_id", "timestamp", name="uq_glucose_user_time"),)
