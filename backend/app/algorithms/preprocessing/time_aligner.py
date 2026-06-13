# backend/app/algorithms/preprocessing/time_aligner.py
# 多源数据时序对齐器

import pandas as pd


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
