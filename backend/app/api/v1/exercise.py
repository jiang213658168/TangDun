# backend/app/api/v1/exercise.py
# 运动管理API路由

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
from typing import Optional, List

from app.database import get_db
from app.api.v1.auth import get_current_user
from app.models.exercise import ExerciseRecord, HeartRateRecord, StepRecord, SleepRecord
from app.schemas.exercise import (
    ExerciseRecordCreate,
    ExerciseRecordResponse,
    HeartRateRecordCreate,
    HeartRateRecordResponse,
    StepRecordCreate,
    StepRecordResponse,
    SleepRecordCreate,
    SleepRecordResponse,
    ExerciseStats,
    ExercisePrescription,
)

router = APIRouter()

# 运动MET值定义
MET_VALUES = {
    "walking": 3.0,
    "running": 8.0,
    "cycling": 6.0,
    "swimming": 7.0,
    "yoga": 2.5,
    "dancing": 5.0,
}


@router.post("/", response_model=ExerciseRecordResponse)
async def create_exercise_record(
    record: ExerciseRecordCreate,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """创建运动记录"""
    # 计算运动时长
    duration_min = record.duration_min
    if duration_min is None and record.end_time:
        duration_min = int((record.end_time - record.start_time).total_seconds() / 60)

    # 计算消耗热量
    calories_burned = record.calories_burned
    if calories_burned is None and duration_min:
        met = MET_VALUES.get(record.exercise_type, 3.0)
        # 热量(kcal) = MET * 体重(kg) * 时间(小时)
        # 假设体重70kg
        calories_burned = round(met * 70 * (duration_min / 60), 1)

    # 判断运动强度
    intensity = record.intensity
    if intensity is None and record.avg_heart_rate:
        # 基于心率储备百分比计算强度
        # 假设静息心率60，最大心率190
        hr_reserve = record.avg_heart_rate - 60
        max_reserve = 190 - 60
        hr_percent = hr_reserve / max_reserve

        if hr_percent < 0.3:
            intensity = "low"
        elif hr_percent < 0.6:
            intensity = "moderate"
        else:
            intensity = "high"

    db_record = ExerciseRecord(
        user_id=current_user,
        start_time=record.start_time,
        end_time=record.end_time,
        exercise_type=record.exercise_type,
        duration_min=duration_min,
        avg_heart_rate=record.avg_heart_rate,
        max_heart_rate=record.max_heart_rate,
        steps=record.steps,
        calories_burned=calories_burned,
        intensity=intensity,
        source=record.source,
    )
    db.add(db_record)
    db.commit()
    db.refresh(db_record)
    return db_record


@router.get("/", response_model=List[ExerciseRecordResponse])
async def get_exercise_records(
    start: Optional[datetime] = Query(None, description="开始时间"),
    end: Optional[datetime] = Query(None, description="结束时间"),
    limit: int = Query(50, le=200, description="返回记录数上限"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取运动记录列表"""
    query = db.query(ExerciseRecord).filter(ExerciseRecord.user_id == current_user)

    if start:
        query = query.filter(ExerciseRecord.start_time >= start)
    if end:
        query = query.filter(ExerciseRecord.start_time <= end)

    records = query.order_by(ExerciseRecord.start_time.desc()).limit(limit).all()
    return records


@router.get("/stats", response_model=ExerciseStats)
async def get_exercise_stats(
    start: Optional[datetime] = Query(None, description="开始时间"),
    end: Optional[datetime] = Query(None, description="结束时间"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取运动统计信息"""
    query = db.query(ExerciseRecord).filter(ExerciseRecord.user_id == current_user)

    if start:
        query = query.filter(ExerciseRecord.start_time >= start)
    if end:
        query = query.filter(ExerciseRecord.start_time <= end)

    records = query.all()
    if not records:
        return ExerciseStats(
            total_duration_min=0,
            total_steps=0,
            total_calories=0,
            avg_heart_rate=None,
            exercise_count=0,
        )

    total_duration = sum(r.duration_min or 0 for r in records)
    total_steps = sum(r.steps or 0 for r in records)
    total_calories = sum(r.calories_burned or 0 for r in records)
    heart_rates = [r.avg_heart_rate for r in records if r.avg_heart_rate]
    avg_hr = round(sum(heart_rates) / len(heart_rates), 1) if heart_rates else None

    return ExerciseStats(
        total_duration_min=total_duration,
        total_steps=total_steps,
        total_calories=round(total_calories, 1),
        avg_heart_rate=avg_hr,
        exercise_count=len(records),
    )


@router.get("/prescription", response_model=ExercisePrescription)
async def get_exercise_prescription(
    current_glucose: Optional[float] = Query(None, description="当前血糖值 mmol/L"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取运动处方推荐"""
    # 如果未提供当前血糖，获取最新血糖值
    if current_glucose is None:
        from app.models.glucose import GlucoseRecord

        latest = (
            db.query(GlucoseRecord)
            .filter(GlucoseRecord.user_id == current_user)
            .order_by(GlucoseRecord.timestamp.desc())
            .first()
        )
        if latest:
            current_glucose = latest.value
        else:
            current_glucose = 6.0

    # 获取用户目标范围
    from app.models.user import User

    user = db.query(User).filter(User.id == current_user).first()
    target_low = user.target_range_low if user else 3.9
    target_high = user.target_range_high if user else 10.0

    # 根据血糖水平推荐运动
    if current_glucose < target_low:
        # 低血糖 - 不建议运动
        return ExercisePrescription(
            exercise_type="休息",
            duration_min=0,
            intensity="none",
            expected_glucose_drop=0,
            notes="当前血糖偏低，不建议运动，请先补充糖分",
        )
    elif current_glucose > target_high:
        # 高血糖 - 轻度运动
        return ExercisePrescription(
            exercise_type="walking",
            duration_min=30,
            intensity="low",
            expected_glucose_drop=1.5,
            notes="当前血糖偏高，建议轻度步行30分钟",
        )
    else:
        # 正常范围 - 中等强度运动
        return ExercisePrescription(
            exercise_type="walking",
            duration_min=30,
            intensity="moderate",
            expected_glucose_drop=2.0,
            notes="血糖正常，建议中等强度步行30分钟",
        )


# 心率记录接口
@router.post("/heart-rate", response_model=HeartRateRecordResponse)
async def create_heart_rate_record(
    record: HeartRateRecordCreate,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """创建心率记录"""
    db_record = HeartRateRecord(
        user_id=current_user,
        timestamp=record.timestamp,
        heart_rate=record.heart_rate,
        source=record.source,
    )
    db.add(db_record)
    db.commit()
    db.refresh(db_record)
    return db_record


@router.get("/heart-rate", response_model=List[HeartRateRecordResponse])
async def get_heart_rate_records(
    start: Optional[datetime] = Query(None, description="开始时间"),
    end: Optional[datetime] = Query(None, description="结束时间"),
    limit: int = Query(100, le=1000, description="返回记录数上限"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取心率记录列表"""
    query = db.query(HeartRateRecord).filter(HeartRateRecord.user_id == current_user)

    if start:
        query = query.filter(HeartRateRecord.timestamp >= start)
    if end:
        query = query.filter(HeartRateRecord.timestamp <= end)

    records = query.order_by(HeartRateRecord.timestamp.desc()).limit(limit).all()
    return records


# 步数记录接口
@router.post("/steps", response_model=StepRecordResponse)
async def create_step_record(
    record: StepRecordCreate,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """创建步数记录"""
    db_record = StepRecord(
        user_id=current_user,
        timestamp=record.timestamp,
        step_count=record.step_count,
        distance=record.distance,
        calories=record.calories,
        source=record.source,
    )
    db.add(db_record)
    db.commit()
    db.refresh(db_record)
    return db_record


@router.get("/steps", response_model=List[StepRecordResponse])
async def get_step_records(
    start: Optional[datetime] = Query(None, description="开始时间"),
    end: Optional[datetime] = Query(None, description="结束时间"),
    limit: int = Query(100, le=1000, description="返回记录数上限"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取步数记录列表"""
    query = db.query(StepRecord).filter(StepRecord.user_id == current_user)

    if start:
        query = query.filter(StepRecord.timestamp >= start)
    if end:
        query = query.filter(StepRecord.timestamp <= end)

    records = query.order_by(StepRecord.timestamp.desc()).limit(limit).all()
    return records


# 睡眠记录接口
@router.post("/sleep", response_model=SleepRecordResponse)
async def create_sleep_record(
    record: SleepRecordCreate,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """创建睡眠记录"""
    # 计算睡眠时长
    duration_min = record.duration_min
    if duration_min is None:
        duration_min = int((record.end_time - record.start_time).total_seconds() / 60)

    db_record = SleepRecord(
        user_id=current_user,
        start_time=record.start_time,
        end_time=record.end_time,
        duration_min=duration_min,
        deep_sleep_min=record.deep_sleep_min,
        light_sleep_min=record.light_sleep_min,
        rem_sleep_min=record.rem_sleep_min,
        source=record.source,
    )
    db.add(db_record)
    db.commit()
    db.refresh(db_record)
    return db_record


@router.get("/sleep", response_model=List[SleepRecordResponse])
async def get_sleep_records(
    start: Optional[datetime] = Query(None, description="开始时间"),
    end: Optional[datetime] = Query(None, description="结束时间"),
    limit: int = Query(30, le=100, description="返回记录数上限"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取睡眠记录列表"""
    query = db.query(SleepRecord).filter(SleepRecord.user_id == current_user)

    if start:
        query = query.filter(SleepRecord.start_time >= start)
    if end:
        query = query.filter(SleepRecord.start_time <= end)

    records = query.order_by(SleepRecord.start_time.desc()).limit(limit).all()
    return records
