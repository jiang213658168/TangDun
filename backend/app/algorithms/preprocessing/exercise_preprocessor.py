# backend/app/algorithms/preprocessing/exercise_preprocessor.py
# 运动数据预处理器

import numpy as np
import pandas as pd


class ExercisePreprocessor:
    """运动数据预处理器"""

    def __init__(self):
        # 心率异常范围
        self.hr_min = 30
        self.hr_max = 220

    def filter_heart_rate(self, hr_values: pd.Series) -> pd.Series:
        """过滤异常心率值

        Args:
            hr_values: 心率值序列

        Returns:
            过滤后的序列，异常值设为NaN
        """
        mask = (hr_values >= self.hr_min) & (hr_values <= self.hr_max)
        result = hr_values.copy()
        result[~mask] = np.nan
        return result

    def aggregate_steps(self, step_data: pd.DataFrame, freq: str = 'H') -> pd.DataFrame:
        """聚合步数数据

        Args:
            step_data: 步数数据DataFrame，必须包含 'timestamp' 和 'step_count' 列
            freq: 聚合频率，默认'H'表示小时

        Returns:
            聚合后的DataFrame
        """
        step_data['timestamp'] = pd.to_datetime(step_data['timestamp'])
        step_data.set_index('timestamp', inplace=True)
        aggregated = step_data.resample(freq).agg({
            'step_count': 'sum',
            'distance': 'sum',
            'calories': 'sum'
        })
        return aggregated.reset_index()

    def calculate_intensity(self, heart_rate: float, resting_hr: float = 60, max_hr: float = 190) -> str:
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

    def map_exercise_type(self, type_code: int) -> str:
        """映射Health Connect运动类型代码

        Args:
            type_code: Health Connect运动类型代码

        Returns:
            运动类型名称
        """
        type_mapping = {
            0: "unknown",
            1: "walking",
            2: "running",
            3: "cycling",
            4: "swimming",
            5: "yoga",
            6: "dancing",
            7: "strength_training",
            8: "hiit",
            9: "other",
        }
        return type_mapping.get(type_code, "other")
