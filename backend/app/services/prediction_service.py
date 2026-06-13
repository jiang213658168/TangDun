# backend/app/services/prediction_service.py
# 预测服务 - 使用TCN连续曲线模型
#
# 模型: TCN (Temporal Convolutional Network)
# 性能: MAE 0.552 mmol/L, Clarke A 92.4%
# 输出: 0-120分钟血糖曲线（25个点）

import numpy as np
import pandas as pd
from datetime import datetime, timedelta
from typing import List, Dict, Any, Optional
from sqlalchemy.orm import Session

from app.models.glucose import GlucoseRecord
from app.models.meal import MealRecord, MealItem
from app.models.exercise import ExerciseRecord
from app.models.insulin import InsulinRecord
from app.models.user import User
from app.schemas.report import PredictionResult
from app.algorithms.bergman.model import BergmanModel
from app.services.ml_model_service import ml_model_service


class PredictionService:
    """血糖预测服务 - 使用TCN连续曲线模型"""

    def __init__(self):
        self.bergman_model = BergmanModel()
        ml_model_service.load_model()

    async def predict(self, user_id: int, db: Session, horizon: int = 120) -> PredictionResult:
        """执行血糖预测

        Args:
            user_id: 用户ID
            db: 数据库会话
            horizon: 预测时长（分钟），默认120分钟

        Returns:
            预测结果，包含0-120分钟连续曲线
        """
        # 获取最近24小时的血糖数据（TCN需要24h历史）
        since = datetime.now() - timedelta(hours=24)
        glucose_records = (
            db.query(GlucoseRecord)
            .filter(GlucoseRecord.user_id == user_id, GlucoseRecord.timestamp >= since)
            .order_by(GlucoseRecord.timestamp.asc())
            .all()
        )

        if len(glucose_records) < 6:
            return self._simple_prediction(glucose_records, horizon)

        # 提取血糖值
        glucose_values = [r.value for r in glucose_records]
        current_value = glucose_values[-1]

        # 使用TCN模型预测
        if ml_model_service.is_loaded:
            # 获取患者的胰岛素/碳水/心率/步数数据
            patient_data = await self._get_patient_data(user_id, db)
            ml_model_service.set_patient_data(user_id, patient_data)

            # 预测
            glucose_array = np.array(glucose_values)
            result = ml_model_service.predict(glucose_array, current_value, patient_id=user_id)

            return PredictionResult(
                prediction_time=datetime.now(),
                risk_level=result['risk_level'],
                model_type_name=result['model_type'],
                current_glucose=round(current_value, 1),
                predicted_5min=result.get('predicted_5min'),
                predicted_15min=result.get('predicted_15min'),
                predicted_30min=result.get('predicted_30min'),
                predicted_60min=result.get('predicted_60min'),
                predicted_120min=result.get('predicted_120min'),
                curve=result.get('curve', []),
                upper_bound=result.get('upper'),
                lower_bound=result.get('lower'),
            )
        else:
            # 回退到Bergman模型
            return self._bergman_prediction(glucose_values, current_value, user_id, db, horizon)

    async def _get_patient_data(self, user_id: int, db: Session) -> Dict[str, np.ndarray]:
        """获取患者的胰岛素/碳水/心率/步数数据（最近24小时）"""
        since = datetime.now() - timedelta(hours=24)

        # 初始化默认数据（288个点，每5分钟一个）
        n_points = 288
        patient_data = {
            'bolus': np.zeros(n_points),
            'carbs': np.zeros(n_points),
            'heart_rate': np.zeros(n_points),
            'steps': np.zeros(n_points),
        }

        try:
            # 获取胰岛素记录
            insulin_records = (
                db.query(InsulinRecord)
                .filter(InsulinRecord.user_id == user_id, InsulinRecord.timestamp >= since)
                .order_by(InsulinRecord.timestamp.asc())
                .all()
            )
            for record in insulin_records:
                # 计算时间索引（每5分钟一个点）
                minutes_ago = (datetime.now() - record.timestamp).total_seconds() / 60
                idx = int((24 * 60 - minutes_ago) / 5)
                if 0 <= idx < n_points:
                    patient_data['bolus'][idx] = record.dose or 0

            # 获取碳水记录
            meal_records = (
                db.query(MealRecord)
                .filter(MealRecord.user_id == user_id, MealRecord.timestamp >= since)
                .order_by(MealRecord.timestamp.asc())
                .all()
            )
            for record in meal_records:
                minutes_ago = (datetime.now() - record.timestamp).total_seconds() / 60
                idx = int((24 * 60 - minutes_ago) / 5)
                if 0 <= idx < n_points:
                    patient_data['carbs'][idx] = record.total_carbs or 0

            # 获取运动记录（心率、步数）
            exercise_records = (
                db.query(ExerciseRecord)
                .filter(ExerciseRecord.user_id == user_id, ExerciseRecord.start_time >= since)
                .order_by(ExerciseRecord.start_time.asc())
                .all()
            )
            for record in exercise_records:
                minutes_ago = (datetime.now() - record.start_time).total_seconds() / 60
                idx = int((24 * 60 - minutes_ago) / 5)
                if 0 <= idx < n_points:
                    patient_data['heart_rate'][idx] = record.heart_rate or 0
                    patient_data['steps'][idx] = record.steps or 0

        except Exception as e:
            print(f"获取患者数据失败: {e}")

        return patient_data

    def _bergman_prediction(self, glucose_values: List[float], current_value: float,
                             user_id: int, db: Session, horizon: int) -> PredictionResult:
        """Bergman模型预测"""
        # 获取饮食和运动数据
        meal_records = (
            db.query(MealRecord)
            .filter(
                MealRecord.user_id == user_id,
                MealRecord.timestamp >= datetime.now() - timedelta(hours=4),
            )
            .all()
        )

        exercise_records = (
            db.query(ExerciseRecord)
            .filter(
                ExerciseRecord.user_id == user_id,
                ExerciseRecord.start_time >= datetime.now() - timedelta(hours=4),
            )
            .all()
        )

        # 构造模型输入
        meals = []
        for meal in meal_records:
            minutes_ago = (datetime.now() - meal.timestamp).total_seconds() / 60
            meals.append({'time': -minutes_ago, 'carbs': meal.total_carbs or 0})

        exercises = []
        for exercise in exercise_records:
            minutes_ago = (datetime.now() - exercise.start_time).total_seconds() / 60
            exercises.append({
                'start_time': -minutes_ago,
                'duration': exercise.duration_min or 30,
                'met': 3.0,
            })

        # Bergman模型预测
        initial_state = (current_value, 0, 10.0)
        time_points = np.arange(0, horizon + 1, 5)
        prediction = self.bergman_model.predict(initial_state, time_points, meals, exercises)

        predicted_value = prediction[-1]
        std = np.std(prediction[-12:]) if len(prediction) >= 12 else 1.0

        if predicted_value < 3.9:
            risk_level = "low_risk"
        elif predicted_value > 10.0:
            risk_level = "high_risk"
        else:
            risk_level = "normal"

        return PredictionResult(
            prediction_time=datetime.now(),
            risk_level=risk_level,
            model_type_name="bergman",
        )

    def _simple_prediction(self, records: List[GlucoseRecord],
                            horizon: int) -> PredictionResult:
        """简单预测（数据不足时）"""
        if records:
            current_value = records[-1].value
        else:
            current_value = 6.0

        return PredictionResult(
            prediction_time=datetime.now(),
            risk_level="normal",
            model_type_name="simple",
        )


class WhatIfService:
    """What-if模拟服务"""

    def __init__(self):
        self.bergman_model = BergmanModel()

    async def simulate(self, current_glucose: float,
                        meal_data: List[Dict]) -> Dict[str, Any]:
        """模拟进食后的血糖变化

        使用Bergman模型生成平滑曲线
        """
        meals = []
        total_carbs = 0
        weighted_gi_sum = 0

        for item in meal_data:
            carbs = item.get('carbs', 0)
            gi = item.get('gi', 50)
            meals.append({'time': 0, 'carbs': carbs})
            total_carbs += carbs
            weighted_gi_sum += gi * carbs

        weighted_gi = weighted_gi_sum / total_carbs if total_carbs > 0 else 50

        # Bergman模型预测
        initial_state = (current_glucose, 0, 10.0)
        time_points = np.arange(0, 181, 5)  # 3小时

        glucose_curve = self.bergman_model.predict(initial_state, time_points, meals)

        # 找到峰值
        peak_idx = np.argmax(glucose_curve)
        predicted_peak = glucose_curve[peak_idx]
        peak_time = int(time_points[peak_idx])

        # 生成曲线数据
        curve_data = [
            {"time": int(t), "value": round(float(v), 1)}
            for t, v in zip(time_points, glucose_curve)
        ]

        # 生成替代建议
        alternatives = []
        for item in meal_data:
            if item.get("gi", 50) > 60:
                low_gi_meals = [{'time': 0, 'carbs': item.get('carbs', 0)}]
                low_gi_curve = self.bergman_model.predict(initial_state, time_points, low_gi_meals)
                low_gi_peak = np.max(low_gi_curve)

                alternatives.append({
                    "original": item["food_name"],
                    "suggestion": f"将{item['food_name']}替换为低GI替代品",
                    "expected_peak": round(float(low_gi_peak), 1),
                    "peak_reduction": round(float(predicted_peak - low_gi_peak), 1),
                })

        return {
            "predicted_peak": round(float(predicted_peak), 1),
            "peak_time": peak_time,
            "glucose_curve": curve_data,
            "alternatives": alternatives,
        }
