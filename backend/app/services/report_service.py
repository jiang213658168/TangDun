# backend/app/services/report_service.py
# 报告服务模块

from datetime import datetime, date, timedelta
from typing import Dict, List, Optional
from sqlalchemy.orm import Session
import numpy as np

from app.models.glucose import GlucoseRecord
from app.models.meal import MealRecord
from app.models.exercise import ExerciseRecord
from app.models.user import User


class ReportService:
    """报告服务类"""

    def generate_daily_report(self, user_id: int, report_date: date,
                                db: Session) -> Dict:
        """生成日报告

        Args:
            user_id: 用户ID
            report_date: 报告日期
            db: 数据库会话

        Returns:
            日报告数据
        """
        # 获取当日时间范围
        start = datetime.combine(report_date, datetime.min.time())
        end = start + timedelta(days=1)

        # 获取用户信息
        user = db.query(User).filter(User.id == user_id).first()
        low = user.target_range_low if user else 3.9
        high = user.target_range_high if user else 10.0

        # 获取血糖数据
        glucose_records = (
            db.query(GlucoseRecord)
            .filter(
                GlucoseRecord.user_id == user_id,
                GlucoseRecord.timestamp >= start,
                GlucoseRecord.timestamp < end,
            )
            .order_by(GlucoseRecord.timestamp.asc())
            .all()
        )

        if not glucose_records:
            return None

        values = [r.value for r in glucose_records]

        # 计算统计指标
        avg_glucose = round(np.mean(values), 1)
        min_glucose = round(np.min(values), 1)
        max_glucose = round(np.max(values), 1)
        std_glucose = round(np.std(values), 2)

        # 计算TIR
        in_range = sum(1 for v in values if low <= v <= high)
        below_range = sum(1 for v in values if v < low)
        above_range = sum(1 for v in values if v > high)
        total = len(values)

        tir = round(in_range / total * 100, 1)
        tir_low = round(below_range / total * 100, 1)
        tir_high = round(above_range / total * 100, 1)

        # 计算GRI (血糖风险指数)
        gri = round(3.0 * tir_low + 1.6 * tir_high, 1)

        # 获取饮食数据
        meal_records = (
            db.query(MealRecord)
            .filter(
                MealRecord.user_id == user_id,
                MealRecord.timestamp >= start,
                MealRecord.timestamp < end,
            )
            .all()
        )

        total_carbs = sum(m.total_carbs or 0 for m in meal_records)
        total_calories = sum(m.total_calories or 0 for m in meal_records)

        # 获取运动数据
        exercise_records = (
            db.query(ExerciseRecord)
            .filter(
                ExerciseRecord.user_id == user_id,
                ExerciseRecord.start_time >= start,
                ExerciseRecord.start_time < end,
            )
            .all()
        )

        total_steps = sum(r.steps or 0 for r in exercise_records)
        total_exercise_min = sum(r.duration_min or 0 for r in exercise_records)

        # 构建血糖曲线数据
        glucose_curve = [
            {"time": r.timestamp.isoformat(), "value": r.value}
            for r in glucose_records
        ]

        return {
            "date": report_date,
            "tir": tir,
            "tir_low": tir_low,
            "tir_high": tir_high,
            "avg_glucose": avg_glucose,
            "min_glucose": min_glucose,
            "max_glucose": max_glucose,
            "std_glucose": std_glucose,
            "gri": gri,
            "total_carbs": round(total_carbs, 1),
            "total_calories": round(total_calories, 1),
            "total_steps": total_steps,
            "total_exercise_min": total_exercise_min,
            "glucose_curve": glucose_curve,
        }

    def generate_weekly_report(self, user_id: int, start_date: date,
                                 db: Session) -> Dict:
        """生成周报告

        Args:
            user_id: 用户ID
            start_date: 周开始日期
            db: 数据库会话

        Returns:
            周报告数据
        """
        end_date = start_date + timedelta(days=7)
        start = datetime.combine(start_date, datetime.min.time())
        end = datetime.combine(end_date, datetime.min.time())

        # 获取用户信息
        user = db.query(User).filter(User.id == user_id).first()
        low = user.target_range_low if user else 3.9
        high = user.target_range_high if user else 10.0

        # 获取一周的血糖数据
        glucose_records = (
            db.query(GlucoseRecord)
            .filter(
                GlucoseRecord.user_id == user_id,
                GlucoseRecord.timestamp >= start,
                GlucoseRecord.timestamp < end,
            )
            .all()
        )

        if not glucose_records:
            return None

        # 计算每日TIR
        tir_trend = []
        for day_offset in range(7):
            day_start = start + timedelta(days=day_offset)
            day_end = day_start + timedelta(days=1)
            day_values = [
                r.value for r in glucose_records
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

        all_values = [r.value for r in glucose_records]
        avg_tir = round(np.mean([t["tir"] for t in tir_trend if t["tir"] > 0]), 1)
        avg_glucose = round(np.mean(all_values), 1)
        glucose_variability = round(np.std(all_values), 2)

        # 获取饮食和运动统计
        meal_records = (
            db.query(MealRecord)
            .filter(
                MealRecord.user_id == user_id,
                MealRecord.timestamp >= start,
                MealRecord.timestamp < end,
            )
            .all()
        )

        exercise_records = (
            db.query(ExerciseRecord)
            .filter(
                ExerciseRecord.user_id == user_id,
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

        return {
            "start_date": start_date,
            "end_date": end_date - timedelta(days=1),
            "avg_tir": avg_tir,
            "tir_trend": tir_trend,
            "avg_glucose": avg_glucose,
            "glucose_variability": glucose_variability,
            "highlights": highlights,
            "improvements": improvements,
            "total_carbs": round(total_carbs, 1),
            "total_steps": total_steps,
            "total_exercise_min": total_exercise_min,
        }
