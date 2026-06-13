# backend/app/api/v1/personalization.py
# 个人化校准API

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.database import get_db
from app.api.v1.auth import get_current_user
from app.services.personalization_service import personalization_service

router = APIRouter()


@router.get("/stage")
async def get_personalization_stage(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取个人化学习阶段

    返回用户当前的数据收集阶段和模型状态:
    - initial: 0-3天，使用通用模型
    - cold_start: 3-14天，冷启动阶段
    - stable: 14天+，稳定阶段
    """
    return personalization_service.get_user_stage(current_user, db)


@router.post("/calibrate")
async def calibrate_model(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """校准个人化模型

    使用用户的历史数据校准模型，使其适应个体差异。
    建议在收集2周数据后调用此接口。
    """
    result = personalization_service.calibrate_for_user(current_user, db)

    if not result['success']:
        raise HTTPException(status_code=400, detail=result['message'])

    return result


@router.get("/stats")
async def get_personalization_stats(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取个人化统计信息"""
    from app.models.glucose import GlucoseRecord
    import numpy as np

    # 获取用户最近30天数据
    since = now() - timedelta(days=30)
    records = (
        db.query(GlucoseRecord)
        .filter(
            GlucoseRecord.user_id == current_user,
            GlucoseRecord.timestamp >= since,
        )
        .all()
    )

    if not records:
        return {'message': '暂无数据'}

    values = [r.value for r in records]

    return {
        'record_count': len(values),
        'mean_glucose': round(float(np.mean(values)), 1),
        'std_glucose': round(float(np.std(values)), 1),
        'min_glucose': round(float(np.min(values)), 1),
        'max_glucose': round(float(np.max(values)), 1),
        'tir': round(float(np.sum((np.array(values) >= 3.9) & (np.array(values) <= 10.0)) / len(values) * 100), 1),
    }
