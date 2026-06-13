# backend/app/algorithms/preprocessing/cgm_preprocessor.py
# CGM数据预处理流水线
# 处理流程: 原始数据 -> 异常值检测 -> 缺失值插补 -> 噪声滤波 -> 特征提取

import numpy as np
import pandas as pd
from scipy.interpolate import CubicSpline
from filterpy.kalman import KalmanFilter
from typing import Tuple, Optional


class CGMPreprocessor:
    """CGM数据预处理流水线

    处理流程: 原始数据 -> 异常值检测 -> 缺失值插补 -> 噪声滤波 -> 特征提取
    """

    def __init__(self, sampling_interval: int = 5):
        """初始化CGM预处理器

        Args:
            sampling_interval: 采样间隔(分钟)，默认5分钟
        """
        self.sampling_interval = sampling_interval
        self.kf = self._init_kalman_filter()

    def _init_kalman_filter(self) -> KalmanFilter:
        """初始化卡尔曼滤波器

        状态向量: [血糖值, 血糖变化率]
        观测向量: [血糖值]
        """
        kf = KalmanFilter(dim_x=2, dim_z=1)
        dt = self.sampling_interval / 60.0  # 转换为小时

        # 状态转移矩阵 (匀速模型)
        kf.F = np.array([[1, dt], [0, 1]])

        # 观测矩阵
        kf.H = np.array([[1, 0]])

        # 过程噪声协方差
        kf.Q = np.array([[0.001, 0], [0, 0.01]])

        # 观测噪声协方差
        kf.R = np.array([[0.05]])

        # 初始状态协方差
        kf.P = np.array([[1.0, 0], [0, 0.1]])

        return kf

    def detect_outliers(self, glucose_series: pd.Series) -> pd.Series:
        """异常值检测 - 3sigma + MAD双重策略

        Args:
            glucose_series: 血糖值序列

        Returns:
            有效数据的布尔掩码
        """
        # 生理范围过滤 (1.0-30.0 mmol/L)
        mask = (glucose_series >= 1.0) & (glucose_series <= 30.0)

        # MAD检测 (Median Absolute Deviation)
        valid_data = glucose_series[mask]
        if len(valid_data) == 0:
            return mask

        median = valid_data.median()
        mad = (valid_data - median).abs().median() * 1.4826

        # 异常判定阈值: 3 * 1.4826 * MAD
        outlier_mask = (glucose_series - median).abs() > 3 * mad
        mask = mask & ~outlier_mask

        return mask

    def _find_gap_lengths(self, missing: pd.Series) -> list:
        """查找缺失段的起始位置和长度

        Args:
            missing: 缺失值布尔序列

        Returns:
            [(start, length), ...] 列表
        """
        gaps = []
        in_gap = False
        start = 0
        length = 0

        for i, is_missing in enumerate(missing):
            if is_missing:
                if not in_gap:
                    start = i
                    in_gap = True
                length += 1
            else:
                if in_gap:
                    gaps.append((start, length))
                    in_gap = False
                    length = 0

        if in_gap:
            gaps.append((start, length))

        return gaps

    def interpolate_missing(self, glucose_series: pd.Series) -> pd.Series:
        """缺失值插补

        - 短时间缺失 (<30分钟，即<6个采样点): 线性插值
        - 中等时间缺失 (30-120分钟): 三次样条插值
        - 长时间缺失 (>120分钟): 不插值，标记为缺失段

        Args:
            glucose_series: 血糖值序列

        Returns:
            插补后的序列
        """
        result = glucose_series.copy()
        missing = result.isna()

        if not missing.any():
            return result

        gap_lengths = self._find_gap_lengths(missing)

        for start, length in gap_lengths:
            if length <= 6:  # <30分钟: 线性插值
                # 获取前后有效值
                before_idx = start - 1
                after_idx = start + length

                if before_idx >= 0 and after_idx < len(result):
                    before_val = result.iloc[before_idx]
                    after_val = result.iloc[after_idx]
                    for i in range(length):
                        ratio = (i + 1) / (length + 1)
                        result.iloc[start + i] = before_val + ratio * (after_val - before_val)

            elif length <= 24:  # 30-120分钟: 三次样条插值
                # 获取有效数据点
                valid_idx = result.dropna().index
                if len(valid_idx) >= 4:  # 至少需要4个点进行样条插值
                    valid_values = result.dropna().values
                    try:
                        cs = CubicSpline(valid_idx, valid_values)
                        fill_idx = range(start, start + length)
                        interpolated = cs(fill_idx)
                        for i, val in enumerate(interpolated):
                            result.iloc[start + i] = val
                    except Exception:
                        pass  # 样条插值失败，保持NaN

            # >120分钟: 不插补，保持NaN

        return result

    def kalman_filter(self, glucose_values: np.ndarray) -> np.ndarray:
        """卡尔曼滤波降噪

        Args:
            glucose_values: 血糖值数组

        Returns:
            滤波后的血糖值数组
        """
        filtered = []

        # 重置滤波器状态
        self.kf.x = np.array([glucose_values[0], 0])

        for z in glucose_values:
            if not np.isnan(z):
                self.kf.predict()
                self.kf.update(np.array([z]))
            else:
                self.kf.predict()
            filtered.append(self.kf.x[0])

        return np.array(filtered)

    def extract_features(self, glucose_series: pd.Series) -> dict:
        """提取血糖特征

        Args:
            glucose_series: 血糖值序列

        Returns:
            特征字典
        """
        values = glucose_series.dropna().values

        if len(values) == 0:
            return {}

        features = {
            # 当前值
            "current_value": values[-1],

            # 变化量
            "change_30min": values[-1] - values[-6] if len(values) >= 6 else 0,
            "change_60min": values[-1] - values[-12] if len(values) >= 12 else 0,

            # 斜率 (mmol/L/min)
            "slope_30min": (values[-1] - values[-6]) / 30 if len(values) >= 6 else 0,
            "slope_60min": (values[-1] - values[-12]) / 60 if len(values) >= 12 else 0,

            # 统计特征 (最近6小时 = 72个点)
            "mean_6h": np.mean(values[-72:]) if len(values) >= 72 else np.mean(values),
            "std_6h": np.std(values[-72:]) if len(values) >= 72 else np.std(values),
            "max_6h": np.max(values[-72:]) if len(values) >= 72 else np.max(values),
            "min_6h": np.min(values[-72:]) if len(values) >= 72 else np.min(values),
            "range_6h": np.max(values[-72:]) - np.min(values[-72:]) if len(values) >= 72 else np.max(values) - np.min(values),

            # 峰值和谷值
            "recent_peak": np.max(values[-36:]) if len(values) >= 36 else np.max(values),
            "recent_trough": np.min(values[-36:]) if len(values) >= 36 else np.min(values),

            # TIR (目标范围内时间占比)
            "tir_6h": np.sum((values[-72:] >= 3.9) & (values[-72:] <= 10.0)) / len(values[-72:]) * 100 if len(values) >= 72 else np.sum((values >= 3.9) & (values <= 10.0)) / len(values) * 100,

            # 低血糖和高血糖比例
            "hypo_ratio_6h": np.sum(values[-72:] < 3.9) / len(values[-72:]) * 100 if len(values) >= 72 else np.sum(values < 3.9) / len(values) * 100,
            "hyper_ratio_6h": np.sum(values[-72:] > 10.0) / len(values[-72:]) * 100 if len(values) >= 72 else np.sum(values > 10.0) / len(values) * 100,
        }

        return features

    def compensate_lag(self, glucose_values: np.ndarray, lag_minutes: int = 10) -> np.ndarray:
        """CGM滞后补偿

        CGM测量的是组织液葡萄糖，比血糖滞后5-15分钟。
        使用卡尔曼滤波器的状态估计来补偿这个滞后。

        Args:
            glucose_values: CGM血糖值数组
            lag_minutes: 滞后时间(分钟)，默认10分钟

        Returns:
            补偿后的血糖值数组
        """
        lag_steps = lag_minutes // self.sampling_interval

        if lag_steps <= 0 or len(glucose_values) <= lag_steps:
            return glucose_values

        # 使用卡尔曼滤波器的状态估计
        # 状态向量的第二个元素是血糖变化率
        # 利用变化率来预测当前真实血糖值
        compensated = np.copy(glucose_values)

        for i in range(lag_steps, len(glucose_values)):
            # 获取滞后时间前的变化率估计
            if i >= 2:
                recent_change = glucose_values[i] - glucose_values[i - lag_steps]
                change_rate = recent_change / lag_minutes  # mmol/L/min

                # 补偿: 真实值 ≈ 测量值 + 变化率 × 滞后时间
                compensation = change_rate * lag_minutes
                compensated[i] = glucose_values[i] + compensation * 0.5  # 部分补偿，避免过拟合

        return compensated

    def mark_sensor_warmup(self, raw_data: pd.DataFrame,
                            warmup_minutes: int = 120) -> pd.DataFrame:
        """标记传感器预热期数据

        传感器预热期(通常2小时)的数据不准确，应标记为不可用。

        Args:
            raw_data: 原始数据DataFrame
            warmup_minutes: 预热时间(分钟)，默认120分钟

        Returns:
            添加了warmup标记的DataFrame
        """
        raw_data['is_warmup'] = False

        # 检查是否有传感器更换记录
        if 'sensor_change_time' in raw_data.columns:
            for idx, row in raw_data.iterrows():
                if pd.notna(row.get('sensor_change_time')):
                    change_time = row['sensor_change_time']
                    warmup_end = change_time + pd.Timedelta(minutes=warmup_minutes)
                    if row['timestamp'] < warmup_end:
                        raw_data.loc[idx, 'is_warmup'] = True

        return raw_data

    def detect_compression_low(self, glucose_series: pd.Series,
                                 threshold: float = 3.0) -> pd.Series:
        """检测压迫性低血糖

        睡觉压到传感器会导致假低血糖读数。
        特征: 血糖突然下降到很低，然后迅速恢复。

        Args:
            glucose_series: 血糖值序列
            threshold: 低血糖阈值 (mmol/L)

        Returns:
            可能是压迫性低血糖的布尔掩码
        """
        mask = pd.Series(False, index=glucose_series.index)

        for i in range(2, len(glucose_series) - 2):
            current = glucose_series.iloc[i]

            # 检查是否低于阈值
            if current < threshold:
                # 检查前后是否有快速恢复
                prev_2 = glucose_series.iloc[i - 2]
                next_2 = glucose_series.iloc[i + 2] if i + 2 < len(glucose_series) else current

                # 前后都正常，只有当前点低 -> 可能是压迫
                if prev_2 > 4.0 and next_2 > 4.0:
                    mask.iloc[i] = True

        return mask

    def process(self, raw_data: pd.DataFrame,
                 compensate_lag: bool = True,
                 lag_minutes: int = 10) -> pd.DataFrame:
        """完整预处理流水线

        Args:
            raw_data: 原始数据DataFrame，必须包含 'value' 列
            compensate_lag: 是否进行CGM滞后补偿
            lag_minutes: 滞后时间(分钟)

        Returns:
            处理后的DataFrame，新增 'filtered_value' 列
        """
        glucose = raw_data['value'].copy()

        # 1. 异常值检测
        valid_mask = self.detect_outliers(glucose)
        glucose[~valid_mask] = np.nan

        # 2. 检测压迫性低血糖
        compression_mask = self.detect_compression_low(glucose)
        glucose[compression_mask] = np.nan

        # 3. 缺失值插补
        glucose = self.interpolate_missing(glucose)

        # 4. 卡尔曼滤波
        filtered = self.kalman_filter(glucose.values)

        # 5. CGM滞后补偿
        if compensate_lag:
            filtered = self.compensate_lag(filtered, lag_minutes)

        raw_data['filtered_value'] = filtered

        return raw_data


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


class MealPreprocessor:
    """饮食数据预处理器"""

    def __init__(self):
        # 餐型自动分类时间范围
        self.meal_type_hours = {
            "breakfast": (6, 9),
            "lunch": (11, 13),
            "dinner": (17, 19),
        }

    def auto_classify_meal_type(self, timestamp: pd.Timestamp) -> str:
        """根据进食时间自动分类餐型

        Args:
            timestamp: 进食时间

        Returns:
            餐型: breakfast/lunch/dinner/snack
        """
        hour = timestamp.hour

        for meal_type, (start, end) in self.meal_type_hours.items():
            if start <= hour <= end:
                return meal_type

        return "snack"

    def calculate_weighted_gi(self, foods: list) -> float:
        """计算加权平均GI值

        Args:
            foods: 食物列表，每项包含 'gi' 和 'carbs'

        Returns:
            加权平均GI值
        """
        total_carbs = sum(food.get('carbs', 0) for food in foods)

        if total_carbs == 0:
            return 0

        weighted_gi = sum(
            food.get('gi', 0) * food.get('carbs', 0)
            for food in foods
        ) / total_carbs

        return round(weighted_gi, 1)

    def standardize_portion(self, amount: float, unit: str) -> float:
        """标准化份量为克

        Args:
            amount: 份量数值
            unit: 单位 (g/kg/ml/L/碗/盘/个/根/片)

        Returns:
            克数
        """
        unit_conversion = {
            'g': 1,
            'kg': 1000,
            'ml': 1,
            'L': 1000,
            '碗': 200,
            '盘': 250,
            '个': 100,
            '根': 100,
            '片': 30,
        }

        factor = unit_conversion.get(unit, 1)
        return amount * factor


class TimeAligner:
    """多源数据时序对齐器"""

    def __init__(self, sampling_interval: int = 5):
        """初始化时序对齐器

        Args:
            sampling_interval: CGM采样间隔(分钟)
        """
        self.sampling_interval = sampling_interval

    def align(self, cgm_data: pd.DataFrame, meal_data: pd.DataFrame = None,
              exercise_data: pd.DataFrame = None) -> pd.DataFrame:
        """对齐多源数据到统一时间轴

        Args:
            cgm_data: CGM数据，必须包含 'timestamp' 和 'value' 列
            meal_data: 饮食数据，可选
            exercise_data: 运动数据，可选

        Returns:
            对齐后的DataFrame
        """
        # 以CGM时间戳为基准
        cgm_data = cgm_data.copy()
        cgm_data['timestamp'] = pd.to_datetime(cgm_data['timestamp'])
        cgm_data.set_index('timestamp', inplace=True)

        # 重采样到固定间隔
        aligned = cgm_data.resample(f'{self.sampling_interval}T').mean()

        # 对齐饮食数据
        if meal_data is not None and not meal_data.empty:
            meal_data = meal_data.copy()
            meal_data['timestamp'] = pd.to_datetime(meal_data['timestamp'])

            # 将饮食事件映射到最近的CGM时间点
            for _, meal in meal_data.iterrows():
                nearest_idx = aligned.index.get_indexer([meal['timestamp']], method='nearest')[0]
                if 0 <= nearest_idx < len(aligned):
                    aligned.iloc[nearest_idx, aligned.columns.get_loc('meal_carbs')] = meal.get('total_carbs', 0)
                    aligned.iloc[nearest_idx, aligned.columns.get_loc('meal_gi')] = meal.get('avg_gi', 0)

        # 对齐运动数据
        if exercise_data is not None and not exercise_data.empty:
            exercise_data = exercise_data.copy()
            exercise_data['start_time'] = pd.to_datetime(exercise_data['start_time'])

            for _, exercise in exercise_data.iterrows():
                # 运动数据按时间范围映射
                mask = (aligned.index >= exercise['start_time']) & \
                       (aligned.index <= exercise.get('end_time', exercise['start_time']))
                aligned.loc[mask, 'exercise_duration'] = exercise.get('duration_min', 0)
                aligned.loc[mask, 'exercise_intensity'] = exercise.get('intensity', 0)

        return aligned.reset_index()
