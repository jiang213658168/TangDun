# backend/app/api/v1/prediction.py
# 血糖预测API路由

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
from typing import Optional, List

from app.database import get_db
from app.api.v1.auth import get_current_user
from app.models.prediction import PredictionRecord, AlertRecord
from app.schemas.report import PredictionResult, AlertResponse

router = APIRouter()


@router.get("/", response_model=PredictionResult)
async def get_prediction(
    horizon: int = Query(120, description="预测时域(分钟): 30/60/90/120"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取血糖预测结果（TCN曲线预测）

    返回0-120分钟连续曲线，包含关键时间点预测：
    - 5分钟、15分钟、30分钟、60分钟、120分钟
    """
    from app.services.prediction_service import PredictionService

    prediction_service = PredictionService()
    try:
        result = await prediction_service.predict(current_user, db, horizon)
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"预测失败: {str(e)}")


@router.get("/history", response_model=List[PredictionResult])
async def get_prediction_history(
    start: Optional[datetime] = Query(None, description="开始时间"),
    end: Optional[datetime] = Query(None, description="结束时间"),
    limit: int = Query(50, le=200, description="返回记录数上限"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取预测历史记录"""
    query = db.query(PredictionRecord).filter(PredictionRecord.user_id == current_user)

    if start:
        query = query.filter(PredictionRecord.prediction_time >= start)
    if end:
        query = query.filter(PredictionRecord.prediction_time <= end)

    records = query.order_by(PredictionRecord.prediction_time.desc()).limit(limit).all()

    # 转换为响应格式
    results = []
    for record in records:
        results.append(
            PredictionResult(
                prediction_time=record.prediction_time,
                risk_level=record.risk_level or "normal",
                model_type_name=record.model_type or "tcn_v2",
                current_glucose=record.current_value or 0.0,
                predicted_30min=record.predicted_value,
            )
        )

    return results


@router.get("/accuracy")
async def get_prediction_accuracy(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取预测准确率统计"""
    # 获取有实际值的预测记录
    records = (
        db.query(PredictionRecord)
        .filter(
            PredictionRecord.user_id == current_user,
            PredictionRecord.actual_value.isnot(None),
        )
        .all()
    )

    if not records:
        return {"message": "暂无足够的数据计算准确率", "records_count": 0}

    # 计算各时域的MAE
    import numpy as np

    horizons = {}
    for record in records:
        h = record.horizon_min
        if h not in horizons:
            horizons[h] = {"predicted": [], "actual": []}
        horizons[h]["predicted"].append(record.predicted_value)
        horizons[h]["actual"].append(record.actual_value)

    accuracy = {}
    for h, data in horizons.items():
        predicted = np.array(data["predicted"])
        actual = np.array(data["actual"])
        mae = np.mean(np.abs(predicted - actual))
        rmse = np.sqrt(np.mean((predicted - actual) ** 2))
        accuracy[f"horizon_{h}"] = {
            "mae": round(mae, 2),
            "rmse": round(rmse, 2),
            "count": len(predicted),
        }

    # 计算Clarke A区比例
    clarke_a_count = 0
    total_count = 0
    for record in records:
        actual = record.actual_value
        predicted = record.predicted_value

        # Clarke A区判定
        if actual < 5.6:
            in_a = abs(predicted - actual) <= 1.4
        else:
            in_a = 0.8 * actual <= predicted <= 1.2 * actual

        if in_a:
            clarke_a_count += 1
        total_count += 1

    clarke_a_percent = round(clarke_a_count / total_count * 100, 1) if total_count > 0 else 0

    return {
        "accuracy": accuracy,
        "clarke_a_percent": clarke_a_percent,
        "total_records": total_count,
    }


# 预警接口
@router.get("/alerts", response_model=List[AlertResponse])
async def get_alerts(
    is_read: Optional[int] = Query(None, description="是否已读: 0=未读, 1=已读"),
    limit: int = Query(50, le=200, description="返回记录数上限"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取预警记录列表"""
    query = db.query(AlertRecord).filter(AlertRecord.user_id == current_user)

    if is_read is not None:
        query = query.filter(AlertRecord.is_read == is_read)

    records = query.order_by(AlertRecord.created_at.desc()).limit(limit).all()
    return records


@router.put("/alerts/{alert_id}/read")
async def mark_alert_read(
    alert_id: int,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """标记预警为已读"""
    alert = (
        db.query(AlertRecord)
        .filter(AlertRecord.id == alert_id, AlertRecord.user_id == current_user)
        .first()
    )
    if not alert:
        raise HTTPException(status_code=404, detail="预警记录不存在")

    alert.is_read = 1
    db.commit()

    return {"message": "预警已标记为已读"}


@router.put("/alerts/read-all")
async def mark_all_alerts_read(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """标记所有预警为已读"""
    db.query(AlertRecord).filter(
        AlertRecord.user_id == current_user, AlertRecord.is_read == 0
    ).update({"is_read": 1})
    db.commit()

    return {"message": "所有预警已标记为已读"}
