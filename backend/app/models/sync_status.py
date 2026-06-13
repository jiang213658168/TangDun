# backend/app/models/sync_status.py
# 数据同步状态和模型训练状态数据模型

from sqlalchemy import Column, Integer, String, Float, DateTime, ForeignKey, UniqueConstraint
from sqlalchemy.sql import func
from app.database import Base


class SyncStatus(Base):
    """数据同步状态表模型"""

    __tablename__ = "sync_status"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    source = Column(String, nullable=False, comment="数据源: cgm/huawei_watch")
    last_sync_time = Column(DateTime, comment="最后同步时间")
    status = Column(String, default="idle", comment="状态: idle/syncing/success/error")
    record_count = Column(Integer, default=0, comment="同步记录数")
    error_message = Column(String, comment="错误信息")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now(), comment="更新时间")

    __table_args__ = (UniqueConstraint("user_id", "source", name="uq_sync_user_source"),)


class ModelTrainingStatus(Base):
    """模型训练状态表模型"""

    __tablename__ = "model_training_status"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    model_type = Column(String, nullable=False, comment="模型类型")
    stage = Column(String, nullable=False, comment="阶段: initial/cold_start/stable")
    training_samples = Column(Integer, default=0, comment="训练样本数")
    last_training_time = Column(DateTime, comment="最后训练时间")
    mae_30 = Column(Float, comment="30分钟MAE")
    mae_60 = Column(Float, comment="60分钟MAE")
    mae_90 = Column(Float, comment="90分钟MAE")
    clarke_a_percent = Column(Float, comment="Clarke A区比例")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now(), comment="更新时间")
