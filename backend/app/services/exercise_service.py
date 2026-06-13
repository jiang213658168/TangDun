# backend/app/services/exercise_service.py
# 运动服务模块

from datetime import datetime, timedelta
from typing import List, Dict, Optional
from sqlalchemy.orm import Session

from app.models.exercise import ExerciseRecord, HeartRateRecord, StepRecord
from app.models.user import User
from app.schemas.exercise import ExercisePrescription


class ExerciseService:
    """运动服务类"""

    # 运动MET值定义
    MET_VALUES = {
        "walking": 3.0,
        "running": 8.0,
        "cycling": 6.0,
        "swimming": 7.0,
        "yoga": 2.5,
        "dancing": 5.0,
    }

    def calculate_calories(self, exercise_type: str, duration_min: int,
                            weight_kg: float = 70) -> float:
        """计算运动消耗热量

        Args:
            exercise_type: 运动类型
            duration_min: 运动时长(分钟)
            weight_kg: 体重(千克)

        Returns:
            消耗热量(kcal)
        """
        met = self.MET_VALUES.get(exercise_type, 3.0)
        # 热量(kcal) = MET * 体重(kg) * 时间(小时)
        calories = met * weight_kg * (duration_min / 60)
        return round(calories, 1)

    def calculate_intensity(self, heart_rate: float,
                             resting_hr: float = 60,
                             max_hr: float = 190) -> str:
        """计算运动强度

        Args:
            heart_rate: 运动心率
            resting_hr: 静息心率
            max_hr: 最大心率

        Returns:
            强度等级: low/moderate/high
        """
        hr_reserve = heart_rate - resting_hr
        max_reserve = max_hr - resting_hr
        hr_percent = hr_reserve / max_reserve

        if hr_percent < 0.3:
            return "low"
        elif hr_percent < 0.6:
            return "moderate"
        else:
            return "high"

    def get_exercise_prescription(self, current_glucose: float,
                                    user: User) -> ExercisePrescription:
        """获取运动处方推荐

        Args:
            current_glucose: 当前血糖值
            user: 用户信息

        Returns:
            运动处方
        """
        target_low = user.target_range_low or 3.9
        target_high = user.target_range_high or 10.0

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

    def get_exercise_stats(self, records: List[ExerciseRecord]) -> Dict:
        """获取运动统计信息

        Args:
            records: 运动记录列表

        Returns:
            统计信息
        """
        if not records:
            return {
                "total_duration_min": 0,
                "total_steps": 0,
                "total_calories": 0,
                "avg_heart_rate": None,
                "exercise_count": 0,
            }

        total_duration = sum(r.duration_min or 0 for r in records)
        total_steps = sum(r.steps or 0 for r in records)
        total_calories = sum(r.calories_burned or 0 for r in records)
        heart_rates = [r.avg_heart_rate for r in records if r.avg_heart_rate]
        avg_hr = round(sum(heart_rates) / len(heart_rates), 1) if heart_rates else None

        return {
            "total_duration_min": total_duration,
            "total_steps": total_steps,
            "total_calories": round(total_calories, 1),
            "avg_heart_rate": avg_hr,
            "exercise_count": len(records),
        }
