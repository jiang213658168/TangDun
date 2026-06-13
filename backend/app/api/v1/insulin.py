# backend/app/api/v1/insulin.py
# 胰岛素管理API路由

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
from typing import Optional, List

from app.database import get_db
from app.api.v1.auth import get_current_user
from app.models.insulin import InsulinRecord, SensorChange, EmergencyContact
from app.schemas.insulin import (
    InsulinRecordCreate, InsulinRecordResponse,
    InsulinOnBoard,
    SensorChangeCreate, SensorChangeResponse,
    EmergencyContactCreate, EmergencyContactResponse,
)

router = APIRouter()


# ==================== 胰岛素记录 ====================

@router.post("/", response_model=InsulinRecordResponse)
async def create_insulin_record(
    record: InsulinRecordCreate,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """创建胰岛素记录"""
    db_record = InsulinRecord(
        user_id=current_user,
        timestamp=record.timestamp,
        insulin_type=record.insulin_type,
        dose_units=record.dose_units,
        injection_site=record.injection_site,
        meal_id=record.meal_id,
        notes=record.notes,
    )
    db.add(db_record)
    db.commit()
    db.refresh(db_record)
    return db_record


@router.get("/", response_model=List[InsulinRecordResponse])
async def get_insulin_records(
    start: Optional[datetime] = Query(None, description="开始时间"),
    end: Optional[datetime] = Query(None, description="结束时间"),
    limit: int = Query(50, le=200, description="返回记录数上限"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取胰岛素记录列表"""
    query = db.query(InsulinRecord).filter(InsulinRecord.user_id == current_user)

    if start:
        query = query.filter(InsulinRecord.timestamp >= start)
    if end:
        query = query.filter(InsulinRecord.timestamp <= end)

    records = query.order_by(InsulinRecord.timestamp.desc()).limit(limit).all()
    return records


@router.get("/iob", response_model=InsulinOnBoard)
async def get_insulin_on_board(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取当前胰岛素活性(IOB)

    IOB计算基于胰岛素作用曲线:
    - 速效胰岛素(rapid): 4小时活性曲线，峰值1小时
    - 长效胰岛素(long): 24小时活性曲线，无明显峰值
    - 预混胰岛素(mixed): 介于两者之间
    """
    # 获取最近24小时的胰岛素记录
    since = datetime.now() - timedelta(hours=24)
    records = (
        db.query(InsulinRecord)
        .filter(
            InsulinRecord.user_id == current_user,
            InsulinRecord.timestamp >= since,
        )
        .order_by(InsulinRecord.timestamp.asc())
        .all()
    )

    if not records:
        return InsulinOnBoard(
            current_iob=0,
            peak_iob=0,
            iob_curve=[],
            stacking_warning=False,
        )

    # 计算IOB
    current_time = datetime.now()
    total_iob = 0.0
    peak_iob = 0.0
    iob_curve = []
    recent_injections = 0

    for record in records:
        minutes_ago = (current_time - record.timestamp).total_seconds() / 60

        # 根据胰岛素类型计算剩余活性
        if record.insulin_type == "rapid":
            # 速效胰岛素: 4小时活性曲线
            # 活性函数: A(t) = (t/60) * exp(1 - t/60) for t in [0, 360]
            if minutes_ago <= 360:  # 6小时内
                t = minutes_ago
                activity = (t / 60) * (2.718 ** (1 - t / 60)) if t > 0 else 1.0
                activity = max(0, min(1, activity))
                remaining_iob = record.dose_units * (1 - activity)
                total_iob += remaining_iob

                # 记录IOB曲线点
                iob_curve.append({
                    "time": record.timestamp.isoformat(),
                    "dose": record.dose_units,
                    "remaining": round(remaining_iob, 2),
                })

            # 检查最近1小时内是否有注射
            if minutes_ago <= 60:
                recent_injections += 1

        elif record.insulin_type == "long":
            # 长效胰岛素: 24小时均匀释放
            if minutes_ago <= 1440:  # 24小时内
                remaining_iob = record.dose_units * (1 - minutes_ago / 1440)
                total_iob += max(0, remaining_iob)

        elif record.insulin_type == "mixed":
            # 预混胰岛素: 50%速效 + 50%长效
            if minutes_ago <= 360:
                t = minutes_ago
                activity_rapid = (t / 60) * (2.718 ** (1 - t / 60)) if t > 0 else 1.0
                activity_rapid = max(0, min(1, activity_rapid))
                total_iob += record.dose_units * 0.5 * (1 - activity_rapid)

            if minutes_ago <= 1440:
                total_iob += record.dose_units * 0.5 * (1 - minutes_ago / 1440)

    # 叠加警告: 最近1小时内注射超过2次
    stacking_warning = recent_injections >= 2
    stacking_message = None
    if stacking_warning:
        stacking_message = f"警告: 最近1小时内注射了{recent_injections}次胰岛素，存在叠加风险"

    return InsulinOnBoard(
        current_iob=round(total_iob, 2),
        peak_iob=round(peak_iob, 2),
        iob_curve=iob_curve,
        stacking_warning=stacking_warning,
        stacking_message=stacking_message,
    )


# ==================== 传感器管理 ====================

@router.post("/sensor", response_model=SensorChangeResponse)
async def create_sensor_change(
    record: SensorChangeCreate,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """记录传感器更换"""
    # 计算预热结束时间 (通常2小时)
    warmup_end_time = record.change_time + timedelta(hours=2)

    # 计算过期日期 (通常14天)
    expiry_date = record.change_time + timedelta(days=14)

    db_record = SensorChange(
        user_id=current_user,
        change_time=record.change_time,
        sensor_type=record.sensor_type,
        sensor_id=record.sensor_id,
        warmup_end_time=warmup_end_time,
        expiry_date=expiry_date,
        notes=record.notes,
    )
    db.add(db_record)
    db.commit()
    db.refresh(db_record)
    return db_record


@router.get("/sensor", response_model=List[SensorChangeResponse])
async def get_sensor_changes(
    limit: int = Query(10, le=50, description="返回记录数上限"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取传感器更换记录"""
    records = (
        db.query(SensorChange)
        .filter(SensorChange.user_id == current_user)
        .order_by(SensorChange.change_time.desc())
        .limit(limit)
        .all()
    )
    return records


@router.get("/sensor/status")
async def get_sensor_status(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取当前传感器状态"""
    latest = (
        db.query(SensorChange)
        .filter(SensorChange.user_id == current_user)
        .order_by(SensorChange.change_time.desc())
        .first()
    )

    if not latest:
        return {"status": "unknown", "message": "未找到传感器记录"}

    now = datetime.now()
    is_warmup = now < latest.warmup_end_time if latest.warmup_end_time else False
    is_expired = now > latest.expiry_date if latest.expiry_date else False
    days_remaining = (latest.expiry_date - now).days if latest.expiry_date else None

    if is_warmup:
        status = "warmup"
        message = "传感器正在预热中，数据可能不准确"
    elif is_expired:
        status = "expired"
        message = "传感器已过期，请更换新传感器"
    elif days_remaining is not None and days_remaining <= 2:
        status = "expiring_soon"
        message = f"传感器将在{days_remaining}天后过期"
    else:
        status = "active"
        message = f"传感器正常工作，剩余{days_remaining}天"

    return {
        "status": status,
        "message": message,
        "sensor_type": latest.sensor_type,
        "change_time": latest.change_time.isoformat(),
        "warmup_end_time": latest.warmup_end_time.isoformat() if latest.warmup_end_time else None,
        "expiry_date": latest.expiry_date.isoformat() if latest.expiry_date else None,
        "days_remaining": days_remaining,
    }


# ==================== 紧急联系人 ====================

@router.post("/emergency-contact", response_model=EmergencyContactResponse)
async def create_emergency_contact(
    contact: EmergencyContactCreate,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """创建紧急联系人"""
    # 如果设为主要联系人，取消其他主要联系人
    if contact.is_primary:
        db.query(EmergencyContact).filter(
            EmergencyContact.user_id == current_user,
            EmergencyContact.is_primary == 1,
        ).update({"is_primary": 0})

    db_contact = EmergencyContact(
        user_id=current_user,
        name=contact.name,
        phone=contact.phone,
        relationship=contact.relationship,
        is_primary=contact.is_primary,
    )
    db.add(db_contact)
    db.commit()
    db.refresh(db_contact)
    return db_contact


@router.get("/emergency-contact", response_model=List[EmergencyContactResponse])
async def get_emergency_contacts(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取紧急联系人列表"""
    contacts = (
        db.query(EmergencyContact)
        .filter(EmergencyContact.user_id == current_user)
        .order_by(EmergencyContact.is_primary.desc())
        .all()
    )
    return contacts


@router.delete("/emergency-contact/{contact_id}")
async def delete_emergency_contact(
    contact_id: int,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """删除紧急联系人"""
    contact = (
        db.query(EmergencyContact)
        .filter(
            EmergencyContact.id == contact_id,
            EmergencyContact.user_id == current_user,
        )
        .first()
    )
    if not contact:
        raise HTTPException(status_code=404, detail="联系人不存在")

    db.delete(contact)
    db.commit()
    return {"message": "联系人已删除"}


# ==================== 饮酒记录 ====================

@router.post("/alcohol")
async def create_alcohol_record(
    timestamp: datetime,
    drink_type: str,
    amount_ml: float,
    alcohol_percent: float,
    notes: Optional[str] = None,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """记录饮酒

    饮酒可能导致延迟低血糖(数小时后)，系统会根据饮酒记录发出预警。
    """
    # 计算纯酒精量 (ml)
    pure_alcohol_ml = amount_ml * alcohol_percent / 100

    # 估算影响持续时间 (小时)
    # 每10ml纯酒精约影响4小时
    impact_hours = pure_alcohol_ml / 10 * 4

    # 存储为备注
    if notes is None:
        notes = ""
    notes += f"\n纯酒精: {pure_alcohol_ml:.1f}ml, 预计影响{impact_hours:.1f}小时"

    # 返回饮酒信息 (实际应存储到数据库)
    return {
        "timestamp": timestamp.isoformat(),
        "drink_type": drink_type,
        "amount_ml": amount_ml,
        "alcohol_percent": alcohol_percent,
        "pure_alcohol_ml": round(pure_alcohol_ml, 1),
        "impact_hours": round(impact_hours, 1),
        "warning": "饮酒可能导致延迟低血糖，请注意监测血糖",
        "notes": notes,
    }


@router.get("/alcohol/warnings")
async def get_alcohol_warnings(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取饮酒相关预警"""
    # 检查最近24小时是否有饮酒记录
    # 实际应从数据库查询
    return {
        "has_warning": False,
        "message": "暂无饮酒预警",
    }
