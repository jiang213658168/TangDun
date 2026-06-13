# backend/app/api/v1/health_sync.py
# 健康数据同步API路由

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from datetime import datetime
from typing import List, Dict, Any

from app.database import get_db
from app.api.v1.auth import get_current_user
from app.models.sync_status import SyncStatus
from app.models.exercise import HeartRateRecord, StepRecord, ExerciseRecord, SleepRecord
from pydantic import BaseModel

router = APIRouter()


class SyncStatusResponse(BaseModel):
    """同步状态响应模型"""
    source: str
    last_sync_time: datetime = None
    status: str = "idle"
    record_count: int = 0
    error_message: str = None


class HealthDataUpload(BaseModel):
    """健康数据上传模型"""
    heart_rate: List[Dict[str, Any]] = []
    steps: List[Dict[str, Any]] = []
    exercise: List[Dict[str, Any]] = []
    sleep: List[Dict[str, Any]] = []


@router.get("/sync-status", response_model=List[SyncStatusResponse])
async def get_sync_status(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取数据同步状态"""
    statuses = db.query(SyncStatus).filter(SyncStatus.user_id == current_user).all()
    return [
        SyncStatusResponse(
            source=s.source,
            last_sync_time=s.last_sync_time,
            status=s.status,
            record_count=s.record_count,
            error_message=s.error_message,
        )
        for s in statuses
    ]


@router.post("/sync")
async def sync_health_data(
    data: HealthDataUpload,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """同步健康数据 - 接收Android同步App上传的数据"""
    created_count = {
        "heart_rate": 0,
        "steps": 0,
        "exercise": 0,
        "sleep": 0,
    }

    # 处理心率数据
    for item in data.heart_rate:
        timestamp = datetime.fromtimestamp(item["timestamp"] / 1000)
        # 检查是否已存在
        existing = (
            db.query(HeartRateRecord)
            .filter(
                HeartRateRecord.user_id == current_user,
                HeartRateRecord.timestamp == timestamp,
            )
            .first()
        )
        if not existing:
            record = HeartRateRecord(
                user_id=current_user,
                timestamp=timestamp,
                heart_rate=item["heart_rate"],
                source="huawei_watch",
            )
            db.add(record)
            created_count["heart_rate"] += 1

    # 处理步数数据
    for item in data.steps:
        timestamp = datetime.fromtimestamp(item["timestamp"] / 1000)
        existing = (
            db.query(StepRecord)
            .filter(
                StepRecord.user_id == current_user,
                StepRecord.timestamp == timestamp,
            )
            .first()
        )
        if not existing:
            record = StepRecord(
                user_id=current_user,
                timestamp=timestamp,
                step_count=item["steps"],
                source="huawei_watch",
            )
            db.add(record)
            created_count["steps"] += 1

    # 处理运动数据
    for item in data.exercise:
        start_time = datetime.fromtimestamp(item["start_time"] / 1000)
        end_time = datetime.fromtimestamp(item["end_time"] / 1000)
        record = ExerciseRecord(
            user_id=current_user,
            start_time=start_time,
            end_time=end_time,
            exercise_type=str(item.get("exercise_type", "other")),
            duration_min=item.get("duration_min", 0),
            source="huawei_watch",
        )
        db.add(record)
        created_count["exercise"] += 1

    # 处理睡眠数据
    for item in data.sleep:
        start_time = datetime.fromtimestamp(item["start_time"] / 1000)
        end_time = datetime.fromtimestamp(item["end_time"] / 1000)
        record = SleepRecord(
            user_id=current_user,
            start_time=start_time,
            end_time=end_time,
            duration_min=item.get("duration_min", 0),
            source="huawei_watch",
        )
        db.add(record)
        created_count["sleep"] += 1

    db.commit()

    # 更新同步状态
    for source in ["huawei_watch"]:
        sync_status = (
            db.query(SyncStatus)
            .filter(SyncStatus.user_id == current_user, SyncStatus.source == source)
            .first()
        )
        if sync_status is None:
            sync_status = SyncStatus(user_id=current_user, source=source)
            db.add(sync_status)

        sync_status.last_sync_time = datetime.now()
        sync_status.status = "success"
        sync_status.record_count = sum(created_count.values())
        sync_status.error_message = None

    db.commit()

    return {
        "message": "数据同步成功",
        "created": created_count,
        "total": sum(created_count.values()),
    }
