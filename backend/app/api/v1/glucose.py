# backend/app/api/v1/glucose.py
# 血糖数据API路由

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
from typing import Optional, List

from app.database import get_db
from app.api.v1.auth import get_current_user
from app.models.glucose import GlucoseRecord
from app.schemas.glucose import (
    GlucoseCreate,
    GlucoseResponse,
    GlucoseBatchCreate,
    GlucoseStats,
    GlucoseTrend,
)

router = APIRouter()


@router.post("/", response_model=GlucoseResponse)
async def create_glucose_record(
    record: GlucoseCreate,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """创建单条血糖记录"""
    # 检查是否已存在同一时间的记录
    existing = (
        db.query(GlucoseRecord)
        .filter(
            GlucoseRecord.user_id == current_user,
            GlucoseRecord.timestamp == record.timestamp,
        )
        .first()
    )
    if existing:
        raise HTTPException(status_code=400, detail="该时间点已存在血糖记录")

    db_record = GlucoseRecord(
        user_id=current_user,
        timestamp=record.timestamp,
        value=record.value,
        trend=record.trend,
        source=record.source,
        sensor_id=record.sensor_id,
        raw_data=record.raw_data,
        filtered_data=record.filtered_data,
    )
    db.add(db_record)
    db.commit()
    db.refresh(db_record)
    return db_record


@router.post("/batch", response_model=dict)
async def create_glucose_records_batch(
    batch: GlucoseBatchCreate,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """批量创建血糖记录"""
    created_count = 0
    skipped_count = 0

    for record in batch.records:
        # 检查是否已存在
        existing = (
            db.query(GlucoseRecord)
            .filter(
                GlucoseRecord.user_id == current_user,
                GlucoseRecord.timestamp == record.timestamp,
            )
            .first()
        )
        if existing:
            skipped_count += 1
            continue

        db_record = GlucoseRecord(
            user_id=current_user,
            timestamp=record.timestamp,
            value=record.value,
            trend=record.trend,
            source=record.source,
            sensor_id=record.sensor_id,
            raw_data=record.raw_data,
            filtered_data=record.filtered_data,
        )
        db.add(db_record)
        created_count += 1

    db.commit()
    return {"created": created_count, "skipped": skipped_count}


@router.get("/", response_model=List[GlucoseResponse])
async def get_glucose_records(
    start: Optional[datetime] = Query(None, description="开始时间"),
    end: Optional[datetime] = Query(None, description="结束时间"),
    limit: int = Query(288, le=1000, description="返回记录数上限"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取血糖记录列表"""
    query = db.query(GlucoseRecord).filter(GlucoseRecord.user_id == current_user)

    if start:
        query = query.filter(GlucoseRecord.timestamp >= start)
    if end:
        query = query.filter(GlucoseRecord.timestamp <= end)

    records = query.order_by(GlucoseRecord.timestamp.desc()).limit(limit).all()
    return records


@router.get("/latest", response_model=GlucoseResponse)
async def get_latest_glucose(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取最新一条血糖记录"""
    record = (
        db.query(GlucoseRecord)
        .filter(GlucoseRecord.user_id == current_user)
        .order_by(GlucoseRecord.timestamp.desc())
        .first()
    )
    if not record:
        raise HTTPException(status_code=404, detail="暂无血糖数据")
    return record


@router.get("/stats", response_model=GlucoseStats)
async def get_glucose_stats(
    start: Optional[datetime] = Query(None, description="开始时间"),
    end: Optional[datetime] = Query(None, description="结束时间"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取血糖统计信息"""
    query = db.query(GlucoseRecord).filter(GlucoseRecord.user_id == current_user)

    if start:
        query = query.filter(GlucoseRecord.timestamp >= start)
    if end:
        query = query.filter(GlucoseRecord.timestamp <= end)

    records = query.all()
    if not records:
        raise HTTPException(status_code=404, detail="暂无血糖数据")

    import numpy as np

    values = [r.value for r in records]
    avg = np.mean(values)
    min_val = np.min(values)
    max_val = np.max(values)
    std = np.std(values)

    # 计算TIR（目标范围内时间占比）
    # 获取用户的目标范围
    from app.models.user import User

    user = db.query(User).filter(User.id == current_user).first()
    low = user.target_range_low if user else 3.9
    high = user.target_range_high if user else 10.0

    in_range = sum(1 for v in values if low <= v <= high)
    below_range = sum(1 for v in values if v < low)
    above_range = sum(1 for v in values if v > high)
    total = len(values)

    return GlucoseStats(
        avg_glucose=round(avg, 1),
        min_glucose=round(min_val, 1),
        max_glucose=round(max_val, 1),
        std_glucose=round(std, 2),
        tir=round(in_range / total * 100, 1),
        tir_low=round(below_range / total * 100, 1),
        tir_high=round(above_range / total * 100, 1),
        count=total,
    )


@router.get("/trend", response_model=GlucoseTrend)
async def get_glucose_trend(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取血糖趋势信息"""
    # 获取最近2小时的数据
    since = datetime.now() - timedelta(hours=2)
    records = (
        db.query(GlucoseRecord)
        .filter(GlucoseRecord.user_id == current_user, GlucoseRecord.timestamp >= since)
        .order_by(GlucoseRecord.timestamp.asc())
        .all()
    )

    if len(records) < 2:
        raise HTTPException(status_code=400, detail="数据不足，无法计算趋势")

    current = records[-1].value
    current_time = records[-1].timestamp

    # 计算30分钟前的值
    time_30min_ago = current_time - timedelta(minutes=30)
    value_30min_ago = None
    for r in reversed(records):
        if r.timestamp <= time_30min_ago:
            value_30min_ago = r.value
            break

    # 计算60分钟前的值
    time_60min_ago = current_time - timedelta(minutes=60)
    value_60min_ago = None
    for r in reversed(records):
        if r.timestamp <= time_60min_ago:
            value_60min_ago = r.value
            break

    change_30 = round(current - value_30min_ago, 2) if value_30min_ago else 0.0
    change_60 = round(current - value_60min_ago, 2) if value_60min_ago else 0.0
    slope_30 = round(change_30 / 30, 4) if value_30min_ago else 0.0
    slope_60 = round(change_60 / 60, 4) if value_60min_ago else 0.0

    # 判断趋势方向
    if slope_30 > 0.1:
        trend = "rising_fast"
    elif slope_30 > 0.02:
        trend = "rising"
    elif slope_30 < -0.1:
        trend = "falling_fast"
    elif slope_30 < -0.02:
        trend = "falling"
    else:
        trend = "stable"

    return GlucoseTrend(
        current_value=current,
        trend=trend,
        change_30min=change_30,
        change_60min=change_60,
        slope_30min=slope_30,
        slope_60min=slope_60,
    )
