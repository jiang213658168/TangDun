# backend/app/algorithms/preprocessing/meal_preprocessor.py
# 饮食数据预处理器

import pandas as pd


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
