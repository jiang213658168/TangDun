# backend/app/models/insulin.py
# 胰岛素记录数据模型

from sqlalchemy import Column, Integer, String, Float, DateTime, ForeignKey
from sqlalchemy.sql import func
from app.database import Base


class InsulinRecord(Base):
    """胰岛素记录表模型"""

    __tablename__ = "insulin_records"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    timestamp = Column(DateTime, nullable=False, comment="注射时间")
    insulin_type = Column(String, nullable=False, comment="胰岛素类型: rapid/long/mixed")
    dose_units = Column(Float, nullable=False, comment="剂量(单位U)")
    injection_site = Column(String, comment="注射部位: abdomen/arm/thigh/buttock")
    meal_id = Column(Integer, ForeignKey("meal_records.id"), comment="关联的饮食记录ID")
    notes = Column(String, comment="备注")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")


class SensorChange(Base):
    """CGM传感器更换记录表模型"""

    __tablename__ = "sensor_changes"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    change_time = Column(DateTime, nullable=False, comment="更换时间")
    sensor_type = Column(String, comment="传感器类型: dexcom_g6/libre_5/硅基动感")
    sensor_id = Column(String, comment="传感器序列号")
    warmup_end_time = Column(DateTime, comment="预热结束时间")
    expiry_date = Column(DateTime, comment="过期日期")
    notes = Column(String, comment="备注")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")


class EmergencyContact(Base):
    """紧急联系人表模型"""

    __tablename__ = "emergency_contacts"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    name = Column(String, nullable=False, comment="联系人姓名")
    phone = Column(String, nullable=False, comment="联系电话")
    relationship = Column(String, comment="关系: 家人/朋友/医生")
    is_primary = Column(Integer, default=0, comment="是否主要联系人")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")
