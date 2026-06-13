# backend/app/models/user.py
# 用户数据模型

from sqlalchemy import Column, Integer, String, Float, DateTime, Date
from sqlalchemy.sql import func
from app.database import Base


class User(Base):
    """用户表模型"""

    __tablename__ = "users"

    id = Column(Integer, primary_key=True, autoincrement=True)
    openid = Column(String, unique=True, nullable=False, comment="App OpenID")
    name = Column(String, comment="用户昵称")
    avatar_url = Column(String, comment="头像URL")
    diabetes_type = Column(Integer, default=2, comment="糖尿病类型: 1=1型, 2=2型")
    diagnosis_date = Column(Date, comment="确诊日期")
    target_range_low = Column(Float, default=3.9, comment="目标血糖下限 mmol/L")
    target_range_high = Column(Float, default=10.0, comment="目标血糖上限 mmol/L")
    alert_low = Column(Float, default=3.9, comment="低血糖预警阈值")
    alert_high = Column(Float, default=10.0, comment="高血糖预警阈值")
    weight = Column(Float, comment="体重(kg)")
    height = Column(Float, comment="身高(cm)")
    age = Column(Integer, comment="年龄")
    gender = Column(Integer, comment="性别: 0=女, 1=男")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now(), comment="更新时间")
