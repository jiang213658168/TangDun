# backend/app/models/exercise.py
# 运动相关数据模型

from sqlalchemy import Column, Integer, String, Float, DateTime, ForeignKey, UniqueConstraint
from sqlalchemy.sql import func
from app.database import Base


class ExerciseRecord(Base):
    """运动记录表模型"""

    __tablename__ = "exercise_records"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    start_time = Column(DateTime, nullable=False, comment="运动开始时间")
    end_time = Column(DateTime, comment="运动结束时间")
    exercise_type = Column(String, comment="运动类型: walking/running/cycling/swimming/etc")
    duration_min = Column(Integer, comment="运动时长(分钟)")
    avg_heart_rate = Column(Float, comment="平均心率")
    max_heart_rate = Column(Float, comment="最大心率")
    steps = Column(Integer, comment="步数")
    calories_burned = Column(Float, comment="消耗热量(kcal)")
    intensity = Column(String, comment="强度: low/moderate/high")
    source = Column(String, default="huawei_watch", comment="数据来源")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")


class HeartRateRecord(Base):
    """心率记录表模型"""

    __tablename__ = "heart_rate_records"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    timestamp = Column(DateTime, nullable=False, comment="记录时间")
    heart_rate = Column(Integer, nullable=False, comment="心率(bpm)")
    source = Column(String, default="huawei_watch", comment="数据来源")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")

    __table_args__ = (UniqueConstraint("user_id", "timestamp", name="uq_hr_user_time"),)


class StepRecord(Base):
    """步数记录表模型"""

    __tablename__ = "step_records"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    timestamp = Column(DateTime, nullable=False, comment="记录时间")
    step_count = Column(Integer, nullable=False, comment="步数")
    distance = Column(Float, comment="距离(m)")
    calories = Column(Float, comment="消耗热量(kcal)")
    source = Column(String, default="huawei_watch", comment="数据来源")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")


class SleepRecord(Base):
    """睡眠记录表模型"""

    __tablename__ = "sleep_records"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    start_time = Column(DateTime, nullable=False, comment="睡眠开始时间")
    end_time = Column(DateTime, nullable=False, comment="睡眠结束时间")
    duration_min = Column(Integer, comment="睡眠时长(分钟)")
    deep_sleep_min = Column(Integer, comment="深睡时长(分钟)")
    light_sleep_min = Column(Integer, comment="浅睡时长(分钟)")
    rem_sleep_min = Column(Integer, comment="REM时长(分钟)")
    source = Column(String, default="huawei_watch", comment="数据来源")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")
