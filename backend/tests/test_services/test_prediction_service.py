# backend/tests/test_services/test_prediction_service.py
# 预测服务测试

import pytest
from app.services.prediction_service import WhatIfService


@pytest.fixture
def what_if_service():
    """创建What-if服务实例"""
    return WhatIfService()


@pytest.mark.asyncio
async def test_what_if_simulation(what_if_service):
    """测试What-if模拟"""
    current_glucose = 6.0
    meal_data = [
        {"food_name": "白米饭", "carbs": 51.8, "gi": 83, "portion_grams": 200},
    ]

    result = await what_if_service.simulate(current_glucose, meal_data)

    assert "predicted_peak" in result
    assert "peak_time" in result
    assert "glucose_curve" in result
    assert "alternatives" in result

    # 峰值应该高于初始值
    assert result["predicted_peak"] > current_glucose

    # 达峰时间应该在合理范围内
    assert 30 <= result["peak_time"] <= 120

    # 血糖曲线应该有数据
    assert len(result["glucose_curve"]) > 0
