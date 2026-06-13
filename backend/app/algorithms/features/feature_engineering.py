# backend/app/algorithms/features/feature_engineering.py
# 特征工程模块

import numpy as np
import pandas as pd
from typing import Dict, List, Optional
from datetime import datetime, timedelta


class FeatureEngineering:
    """特征工程类

    负责从原始数据中提取模型所需的特征向量

    特征维度:
    - 血糖特征: 22维
    - 饮食特征: 8维
    - 运动特征: 7维
    - 时序特征: 4维
    总计: 41维
    """

    def __init__(self):
        """初始化特征工程器"""
        # 标准化参数 (从训练数据中计算并保存)
        self.glucose_mean = None
        self.glucose_std = None
        self.meal_mean = None
        self.meal_std = None
        self.exercise_mean = None
        self.exercise_std = None
        self.temporal_mean = None
        self.temporal_std = None

    def extract_glucose_features(self, glucose_data: pd.DataFrame) -> np.ndarray:
        """提取血糖特征 (22维)

        Args:
            glucose_data: 血糖数据DataFrame，包含 'timestamp' 和 'value' 列

        Returns:
            22维血糖特征向量
        """
        if glucose_data.empty:
            return np.zeros(22)

        values = glucose_data['value'].values
        current = values[-1] if len(values) > 0 else 5.0

        # 1. 当前血糖值
        f1 = current

        # 2-3. 变化量
        f2 = current - values[-6] if len(values) >= 6 else 0  # 30分钟变化
        f3 = current - values[-12] if len(values) >= 12 else 0  # 60分钟变化

        # 4-5. 斜率 (mmol/L/min)
        f4 = f2 / 30 if len(values) >= 6 else 0  # 30分钟斜率
        f5 = f3 / 60 if len(values) >= 12 else 0  # 60分钟斜率

        # 6-10. 最近6小时统计 (72个点)
        recent_6h = values[-72:] if len(values) >= 72 else values
        f6 = np.mean(recent_6h)  # 均值
        f7 = np.std(recent_6h)   # 标准差
        f8 = np.max(recent_6h)   # 最大值
        f9 = np.min(recent_6h)   # 最小值
        f10 = f8 - f9            # 极差

        # 11-12. 最近峰值和谷值
        recent_3h = values[-36:] if len(values) >= 36 else values
        f11 = np.max(recent_3h)  # 峰值
        f12 = np.min(recent_3h)  # 谷值

        # 13. 最近6小时TIR
        tir_mask = (recent_6h >= 3.9) & (recent_6h <= 10.0)
        f13 = np.sum(tir_mask) / len(recent_6h) * 100

        # 14. 低血糖比例
        f14 = np.sum(recent_6h < 3.9) / len(recent_6h) * 100

        # 15. 高血糖比例
        f15 = np.sum(recent_6h > 10.0) / len(recent_6h) * 100

        # 16-17. 最近1小时统计
        recent_1h = values[-12:] if len(values) >= 12 else values
        f16 = np.mean(recent_1h)
        f17 = np.std(recent_1h)

        # 18-19. 最近3小时统计
        f18 = np.mean(recent_3h)
        f19 = np.std(recent_3h)

        # 20-22. 趋势特征
        # 线性回归斜率
        if len(values) >= 12:
            x = np.arange(12)
            y = values[-12:]
            slope = np.polyfit(x, y, 1)[0]
            f20 = slope
        else:
            f20 = 0

        # 变化率的变化率 (加速度)
        if len(values) >= 18:
            slope_1 = (values[-6] - values[-12]) / 30
            slope_2 = (values[-12] - values[-18]) / 30
            f21 = (slope_1 - slope_2) / 30
        else:
            f21 = 0

        # 变异性系数
        f22 = f7 / f6 if f6 > 0 else 0

        features = np.array([f1, f2, f3, f4, f5, f6, f7, f8, f9, f10,
                            f11, f12, f13, f14, f15, f16, f17, f18, f19, f20, f21, f22])

        return features

    def extract_meal_features(self, meal_data: pd.DataFrame,
                               current_time: datetime) -> np.ndarray:
        """提取饮食特征 (8维)

        Args:
            meal_data: 饮食数据DataFrame
            current_time: 当前时间

        Returns:
            8维饮食特征向量
        """
        if meal_data.empty:
            return np.zeros(8)

        # 最近4小时的饮食
        cutoff = current_time - timedelta(hours=4)
        recent_meals = meal_data[meal_data['timestamp'] >= cutoff]

        if recent_meals.empty:
            return np.zeros(8)

        # 1. 最近4小时总碳水
        f1 = recent_meals['total_carbs'].sum()

        # 2. 总热量
        f2 = recent_meals['total_calories'].sum()

        # 3. 总蛋白质
        f3 = recent_meals['total_protein'].sum()

        # 4. 总脂肪
        f4 = recent_meals['total_fat'].sum()

        # 5. 总纤维
        f5 = recent_meals['total_fiber'].sum()

        # 6. 加权平均GI
        if f1 > 0:
            f6 = (recent_meals['avg_gi'] * recent_meals['total_carbs']).sum() / f1
        else:
            f6 = 0

        # 7. 最近进食时间 (分钟前)
        last_meal_time = recent_meals['timestamp'].max()
        f7 = (current_time - last_meal_time).total_seconds() / 60

        # 8. 餐数
        f8 = len(recent_meals)

        features = np.array([f1, f2, f3, f4, f5, f6, f7, f8])

        return features

    def extract_exercise_features(self, exercise_data: pd.DataFrame,
                                   current_time: datetime) -> np.ndarray:
        """提取运动特征 (7维)

        Args:
            exercise_data: 运动数据DataFrame
            current_time: 当前时间

        Returns:
            7维运动特征向量
        """
        if exercise_data.empty:
            return np.zeros(7)

        # 最近4小时的运动
        cutoff = current_time - timedelta(hours=4)
        recent_exercises = exercise_data[exercise_data['start_time'] >= cutoff]

        if recent_exercises.empty:
            return np.zeros(7)

        # 1. 总运动时长 (分钟)
        f1 = recent_exercises['duration_min'].sum()

        # 2. 总MET-minutes
        # MET值映射
        met_mapping = {
            'walking': 3.0,
            'running': 8.0,
            'cycling': 6.0,
            'swimming': 7.0,
            'yoga': 2.5,
            'dancing': 5.0,
        }
        total_met_min = 0
        for _, row in recent_exercises.iterrows():
            met = met_mapping.get(row.get('exercise_type', 'walking'), 3.0)
            total_met_min += met * row.get('duration_min', 0)
        f2 = total_met_min

        # 3. 平均心率
        f3 = recent_exercises['avg_heart_rate'].mean() if 'avg_heart_rate' in recent_exercises else 0

        # 4. 最大心率
        f4 = recent_exercises['max_heart_rate'].max() if 'max_heart_rate' in recent_exercises else 0

        # 5. 总步数
        f5 = recent_exercises['steps'].sum() if 'steps' in recent_exercises else 0

        # 6. 最近运动结束时间 (分钟前)
        if 'end_time' in recent_exercises:
            last_end_time = recent_exercises['end_time'].max()
            f6 = (current_time - last_end_time).total_seconds() / 60
        else:
            f6 = 0

        # 7. 运动类型编码
        # 0=无运动, 1=步行, 2=跑步, 3=骑行, 4=游泳, 5=其他
        type_encoding = {
            'walking': 1,
            'running': 2,
            'cycling': 3,
            'swimming': 4,
        }
        last_type = recent_exercises.iloc[-1].get('exercise_type', 'other')
        f7 = type_encoding.get(last_type, 5)

        features = np.array([f1, f2, f3, f4, f5, f6, f7])

        return features

    def extract_temporal_features(self, timestamp: datetime) -> np.ndarray:
        """提取时序特征 (6维)

        Args:
            timestamp: 时间戳

        Returns:
            6维时序特征向量
        """
        # 1. 小时 (0-23)
        f1 = timestamp.hour

        # 2. 星期 (0-6, 0=周一)
        f2 = timestamp.weekday()

        # 3. 是否工作日
        f3 = 1 if timestamp.weekday() < 5 else 0

        # 4. 是否用餐时间
        hour = timestamp.hour
        is_meal_time = 1 if (6 <= hour <= 9) or (11 <= hour <= 13) or (17 <= hour <= 19) else 0
        f4 = is_meal_time

        # 5. 是否黎明现象时段 (4-8点)
        # 黎明现象: 清晨4-8点血糖自然升高
        is_dawn = 1 if 4 <= hour <= 8 else 0
        f5 = is_dawn

        # 6. 时间编码 (sin/cos编码，捕捉昼夜节律)
        # 使用sin编码，0点=0，12点=1，24点=0
        import math
        f6 = math.sin(2 * math.pi * hour / 24)

        features = np.array([f1, f2, f3, f4, f5, f6])

        return features

    def extract_insulin_features(self, insulin_data: pd.DataFrame,
                                   current_time: datetime) -> np.ndarray:
        """提取胰岛素特征 (5维)

        Args:
            insulin_data: 胰岛素数据DataFrame
            current_time: 当前时间

        Returns:
            5维胰岛素特征向量
        """
        if insulin_data.empty:
            return np.zeros(5)

        # 最近6小时的胰岛素记录
        cutoff = current_time - timedelta(hours=6)
        recent_insulin = insulin_data[insulin_data['timestamp'] >= cutoff]

        if recent_insulin.empty:
            return np.zeros(5)

        # 1. 最近6小时总胰岛素剂量
        f1 = recent_insulin['dose_units'].sum()

        # 2. 最近1小时胰岛素剂量
        cutoff_1h = current_time - timedelta(hours=1)
        recent_1h = recent_insulin[recent_insulin['timestamp'] >= cutoff_1h]
        f2 = recent_1h['dose_units'].sum() if not recent_1h.empty else 0

        # 3. 最近注射时间 (分钟前)
        last_injection = recent_insulin['timestamp'].max()
        f3 = (current_time - last_injection).total_seconds() / 60

        # 4. 注射次数
        f4 = len(recent_insulin)

        # 5. 速效胰岛素比例
        rapid_count = len(recent_insulin[recent_insulin['insulin_type'] == 'rapid'])
        f5 = rapid_count / len(recent_insulin) if len(recent_insulin) > 0 else 0

        features = np.array([f1, f2, f3, f4, f5])

        return features

    def extract_all_features(self, glucose_data: pd.DataFrame,
                              meal_data: pd.DataFrame,
                              exercise_data: pd.DataFrame,
                              current_time: datetime) -> np.ndarray:
        """提取所有特征

        Args:
            glucose_data: 血糖数据
            meal_data: 饮食数据
            exercise_data: 运动数据
            current_time: 当前时间

        Returns:
            41维特征向量
        """
        glucose_features = self.extract_glucose_features(glucose_data)
        meal_features = self.extract_meal_features(meal_data, current_time)
        exercise_features = self.extract_exercise_features(exercise_data, current_time)
        temporal_features = self.extract_temporal_features(current_time)

        # 拼接所有特征
        all_features = np.concatenate([
            glucose_features,
            meal_features,
            exercise_features,
            temporal_features
        ])

        return all_features

    def fit_scaler(self, feature_matrix: np.ndarray):
        """拟合标准化参数

        Args:
            feature_matrix: 特征矩阵 (n_samples, n_features)
        """
        self.mean = np.mean(feature_matrix, axis=0)
        self.std = np.std(feature_matrix, axis=0)
        # 避免除以0
        self.std[self.std == 0] = 1.0

    def transform(self, features: np.ndarray) -> np.ndarray:
        """标准化特征

        Args:
            features: 原始特征

        Returns:
            标准化后的特征
        """
        if self.mean is None or self.std is None:
            # 如果未拟合，返回原始特征
            return features

        return (features - self.mean) / self.std

    def inverse_transform(self, features: np.ndarray) -> np.ndarray:
        """反标准化

        Args:
            features: 标准化特征

        Returns:
            原始特征
        """
        if self.mean is None or self.std is None:
            return features

        return features * self.std + self.mean

    def save_scaler(self, filepath: str):
        """保存标准化参数

        Args:
            filepath: 保存路径
        """
        import json
        data = {
            'mean': self.mean.tolist() if self.mean is not None else None,
            'std': self.std.tolist() if self.std is not None else None,
        }
        with open(filepath, 'w') as f:
            json.dump(data, f)

    def load_scaler(self, filepath: str):
        """加载标准化参数

        Args:
            filepath: 加载路径
        """
        import json
        with open(filepath, 'r') as f:
            data = json.load(f)

        if data['mean'] is not None:
            self.mean = np.array(data['mean'])
        if data['std'] is not None:
            self.std = np.array(data['std'])
