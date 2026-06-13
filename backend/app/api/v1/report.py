# backend/app/api/v1/report.py
# 健康报告API路由

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session
from datetime import datetime, date, timedelta
from typing import Optional
import io
import csv

from app.database import get_db
from app.api.v1.auth import get_current_user
from app.models.glucose import GlucoseRecord
from app.models.meal import MealRecord
from app.models.exercise import ExerciseRecord
from app.schemas.report import DailyReport, WeeklyReport, MonthlyReport

router = APIRouter()


@router.get("/daily", response_model=DailyReport)
async def get_daily_report(
    report_date: date = Query(default=None, description="报告日期，默认今天"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取日报告"""
    if report_date is None:
        report_date = date.today()

    # 获取当日血糖数据
    start = datetime.combine(report_date, datetime.min.time())
    end = start + timedelta(days=1)

    glucose_records = (
        db.query(GlucoseRecord)
        .filter(
            GlucoseRecord.user_id == current_user,
            GlucoseRecord.timestamp >= start,
            GlucoseRecord.timestamp < end,
        )
        .order_by(GlucoseRecord.timestamp.asc())
        .all()
    )

    if not glucose_records:
        raise HTTPException(status_code=404, detail="当日暂无血糖数据")

    import numpy as np

    values = [r.value for r in glucose_records]
    avg_glucose = round(np.mean(values), 1)
    min_glucose = round(np.min(values), 1)
    max_glucose = round(np.max(values), 1)
    std_glucose = round(np.std(values), 2)

    # 获取用户目标范围
    from app.models.user import User

    user = db.query(User).filter(User.id == current_user).first()
    low = user.target_range_low if user else 3.9
    high = user.target_range_high if user else 10.0

    # 计算TIR
    in_range = sum(1 for v in values if low <= v <= high)
    below_range = sum(1 for v in values if v < low)
    above_range = sum(1 for v in values if v > high)
    total = len(values)

    tir = round(in_range / total * 100, 1)
    tir_low = round(below_range / total * 100, 1)
    tir_high = round(above_range / total * 100, 1)

    # 计算GRI（血糖风险指数）
    # GRI = 3.0 * (%VBG) + 2.4 * (%LBG) + 1.6 * (%VHB) + 0.8 * (%HB)
    # 简化计算
    gri = round(3.0 * tir_low + 1.6 * tir_high, 1)

    # 获取当日饮食记录
    meal_records = (
        db.query(MealRecord)
        .filter(
            MealRecord.user_id == current_user,
            MealRecord.timestamp >= start,
            MealRecord.timestamp < end,
        )
        .all()
    )

    total_carbs = sum(m.total_carbs or 0 for m in meal_records)
    total_calories = sum(m.total_calories or 0 for m in meal_records)

    # 获取当日运动记录
    exercise_records = (
        db.query(ExerciseRecord)
        .filter(
            ExerciseRecord.user_id == current_user,
            ExerciseRecord.start_time >= start,
            ExerciseRecord.start_time < end,
        )
        .all()
    )

    total_steps = sum(r.steps or 0 for r in exercise_records)
    total_exercise_min = sum(r.duration_min or 0 for r in exercise_records)

    # 构建血糖曲线数据
    glucose_curve = [
        {"time": r.timestamp.isoformat(), "value": r.value} for r in glucose_records
    ]

    return DailyReport(
        date=report_date,
        tir=tir,
        tir_low=tir_low,
        tir_high=tir_high,
        avg_glucose=avg_glucose,
        min_glucose=min_glucose,
        max_glucose=max_glucose,
        std_glucose=std_glucose,
        gri=gri,
        total_carbs=round(total_carbs, 1),
        total_calories=round(total_calories, 1),
        total_steps=total_steps,
        total_exercise_min=total_exercise_min,
        glucose_curve=glucose_curve,
        meal_records=[
            {
                "id": m.id,
                "time": m.timestamp.isoformat(),
                "meal_type": m.meal_type,
                "carbs": m.total_carbs,
                "calories": m.total_calories,
            }
            for m in meal_records
        ],
        exercise_records=[
            {
                "id": r.id,
                "start_time": r.start_time.isoformat(),
                "type": r.exercise_type,
                "duration": r.duration_min,
                "calories": r.calories_burned,
            }
            for r in exercise_records
        ],
    )


@router.get("/weekly", response_model=WeeklyReport)
async def get_weekly_report(
    start_date: date = Query(default=None, description="周开始日期，默认本周一"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取周报告"""
    if start_date is None:
        today = date.today()
        start_date = today - timedelta(days=today.weekday())

    end_date = start_date + timedelta(days=7)

    # 获取一周的血糖数据
    start = datetime.combine(start_date, datetime.min.time())
    end = datetime.combine(end_date, datetime.min.time())

    glucose_records = (
        db.query(GlucoseRecord)
        .filter(
            GlucoseRecord.user_id == current_user,
            GlucoseRecord.timestamp >= start,
            GlucoseRecord.timestamp < end,
        )
        .all()
    )

    if not glucose_records:
        raise HTTPException(status_code=404, detail="本周暂无血糖数据")

    import numpy as np

    # 计算每日TIR
    from app.models.user import User

    user = db.query(User).filter(User.id == current_user).first()
    low = user.target_range_low if user else 3.9
    high = user.target_range_high if user else 10.0

    tir_trend = []
    for day_offset in range(7):
        day_start = start + timedelta(days=day_offset)
        day_end = day_start + timedelta(days=1)
        day_values = [
            r.value
            for r in glucose_records
            if day_start <= r.timestamp < day_end
        ]
        if day_values:
            in_range = sum(1 for v in day_values if low <= v <= high)
            tir = round(in_range / len(day_values) * 100, 1)
        else:
            tir = 0
        tir_trend.append(
            {"date": (start_date + timedelta(days=day_offset)).isoformat(), "tir": tir}
        )

    all_values = [r.value for r in glucose_records]
    avg_tir = round(np.mean([t["tir"] for t in tir_trend if t["tir"] > 0]), 1)
    avg_glucose = round(np.mean(all_values), 1)
    glucose_variability = round(np.std(all_values), 2)

    # 获取一周的饮食和运动统计
    meal_records = (
        db.query(MealRecord)
        .filter(
            MealRecord.user_id == current_user,
            MealRecord.timestamp >= start,
            MealRecord.timestamp < end,
        )
        .all()
    )

    exercise_records = (
        db.query(ExerciseRecord)
        .filter(
            ExerciseRecord.user_id == current_user,
            ExerciseRecord.start_time >= start,
            ExerciseRecord.start_time < end,
        )
        .all()
    )

    total_carbs = sum(m.total_carbs or 0 for m in meal_records)
    total_steps = sum(r.steps or 0 for r in exercise_records)
    total_exercise_min = sum(r.duration_min or 0 for r in exercise_records)

    # 生成亮点和改进建议
    highlights = []
    improvements = []

    if avg_tir >= 70:
        highlights.append("本周TIR达标，血糖控制良好")
    else:
        improvements.append("本周TIR未达标，建议加强血糖管理")

    if glucose_variability < 2.0:
        highlights.append("血糖波动较小，稳定性好")
    else:
        improvements.append("血糖波动较大，建议注意饮食规律")

    return WeeklyReport(
        start_date=start_date,
        end_date=end_date - timedelta(days=1),
        avg_tir=avg_tir,
        tir_trend=tir_trend,
        avg_glucose=avg_glucose,
        glucose_variability=glucose_variability,
        highlights=highlights,
        improvements=improvements,
        total_carbs=round(total_carbs, 1),
        total_steps=total_steps,
        total_exercise_min=total_exercise_min,
    )


@router.get("/monthly", response_model=MonthlyReport)
async def get_monthly_report(
    year: int = Query(default=None, description="年份"),
    month: int = Query(default=None, description="月份"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取月报告"""
    if year is None:
        year = date.today().year
    if month is None:
        month = date.today().month

    # 获取月份的起止时间
    start_date = date(year, month, 1)
    if month == 12:
        end_date = date(year + 1, 1, 1)
    else:
        end_date = date(year, month + 1, 1)

    start = datetime.combine(start_date, datetime.min.time())
    end = datetime.combine(end_date, datetime.min.time())

    # 获取一个月的血糖数据
    glucose_records = (
        db.query(GlucoseRecord)
        .filter(
            GlucoseRecord.user_id == current_user,
            GlucoseRecord.timestamp >= start,
            GlucoseRecord.timestamp < end,
        )
        .all()
    )

    if not glucose_records:
        raise HTTPException(status_code=404, detail="本月暂无血糖数据")

    import numpy as np

    # 获取用户目标范围
    from app.models.user import User
    user = db.query(User).filter(User.id == current_user).first()
    low = user.target_range_low if user else 3.9
    high = user.target_range_high if user else 10.0

    # 计算每日TIR趋势
    tir_trend = []
    days_in_month = (end_date - start_date).days
    for day_offset in range(days_in_month):
        day_start = start + timedelta(days=day_offset)
        day_end = day_start + timedelta(days=1)
        day_values = [
            r.value
            for r in glucose_records
            if day_start <= r.timestamp < day_end
        ]
        if day_values:
            in_range = sum(1 for v in day_values if low <= v <= high)
            tir = round(in_range / len(day_values) * 100, 1)
        else:
            tir = 0
        tir_trend.append({
            "date": (start_date + timedelta(days=day_offset)).isoformat(),
            "tir": tir,
        })

    # 计算整体统计
    all_values = [r.value for r in glucose_records]
    avg_tir = round(np.mean([t["tir"] for t in tir_trend if t["tir"] > 0]), 1)
    avg_glucose = round(np.mean(all_values), 1)

    # 估算糖化血红蛋白 (HbA1c)
    # 公式: HbA1c = (平均血糖 + 2.59) / 1.59
    hba1c_estimate = round((avg_glucose + 2.59) / 1.59, 1)

    # 获取模型训练状态
    from app.models.sync_status import ModelTrainingStatus
    model_status = (
        db.query(ModelTrainingStatus)
        .filter(ModelTrainingStatus.user_id == current_user)
        .first()
    )

    model_progress = {}
    if model_status:
        model_progress = {
            "stage": model_status.stage,
            "training_samples": model_status.training_samples,
            "mae_30": model_status.mae_30,
            "mae_60": model_status.mae_60,
            "mae_90": model_status.mae_90,
        }
    else:
        model_progress = {
            "stage": "initial",
            "training_samples": len(glucose_records),
            "mae_30": None,
            "mae_60": None,
            "mae_90": None,
        }

    # 生成管理建议
    recommendations = []
    if avg_tir >= 70:
        recommendations.append("本月血糖控制达标，继续保持良好的生活习惯")
    else:
        recommendations.append("本月TIR未达标，建议加强饮食控制和运动")

    if hba1c_estimate <= 7.0:
        recommendations.append("糖化血红蛋白估算值达标，血糖控制良好")
    else:
        recommendations.append("糖化血红蛋白偏高，建议咨询医生调整治疗方案")

    glucose_variability = round(np.std(all_values), 2)
    if glucose_variability > 2.0:
        recommendations.append("血糖波动较大，建议规律饮食和运动")

    recommendations.append("建议定期复查糖化血红蛋白")

    return MonthlyReport(
        year=year,
        month=month,
        avg_tir=avg_tir,
        tir_trend=tir_trend,
        avg_glucose=avg_glucose,
        hba1c_estimate=hba1c_estimate,
        model_progress=model_progress,
        recommendations=recommendations,
    )


@router.get("/export/csv")
async def export_csv(
    start: date = Query(..., description="开始日期"),
    end: date = Query(..., description="结束日期"),
    data_type: str = Query("glucose", description="数据类型: glucose/meal/exercise"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """导出CSV格式数据"""
    start_dt = datetime.combine(start, datetime.min.time())
    end_dt = datetime.combine(end + timedelta(days=1), datetime.min.time())

    output = io.StringIO()
    writer = csv.writer(output)

    if data_type == "glucose":
        writer.writerow(["时间", "血糖值(mmol/L)", "趋势", "来源"])
        records = (
            db.query(GlucoseRecord)
            .filter(
                GlucoseRecord.user_id == current_user,
                GlucoseRecord.timestamp >= start_dt,
                GlucoseRecord.timestamp < end_dt,
            )
            .order_by(GlucoseRecord.timestamp.asc())
            .all()
        )
        for r in records:
            writer.writerow([r.timestamp.isoformat(), r.value, r.trend, r.source])

    elif data_type == "meal":
        writer.writerow(["时间", "餐型", "碳水(g)", "热量(kcal)", "蛋白质(g)", "脂肪(g)", "GI值"])
        records = (
            db.query(MealRecord)
            .filter(
                MealRecord.user_id == current_user,
                MealRecord.timestamp >= start_dt,
                MealRecord.timestamp < end_dt,
            )
            .order_by(MealRecord.timestamp.asc())
            .all()
        )
        for r in records:
            writer.writerow([
                r.timestamp.isoformat(),
                r.meal_type,
                r.total_carbs,
                r.total_calories,
                r.total_protein,
                r.total_fat,
                r.avg_gi,
            ])

    elif data_type == "exercise":
        writer.writerow(["开始时间", "运动类型", "时长(分钟)", "步数", "消耗热量(kcal)", "强度"])
        records = (
            db.query(ExerciseRecord)
            .filter(
                ExerciseRecord.user_id == current_user,
                ExerciseRecord.start_time >= start_dt,
                ExerciseRecord.start_time < end_dt,
            )
            .order_by(ExerciseRecord.start_time.asc())
            .all()
        )
        for r in records:
            writer.writerow([
                r.start_time.isoformat(),
                r.exercise_type,
                r.duration_min,
                r.steps,
                r.calories_burned,
                r.intensity,
            ])

    else:
        raise HTTPException(status_code=400, detail="不支持的数据类型")

    output.seek(0)
    return StreamingResponse(
        io.BytesIO(output.getvalue().encode("utf-8-sig")),
        media_type="text/csv",
        headers={"Content-Disposition": f"attachment; filename={data_type}_{start}_{end}.csv"},
    )
