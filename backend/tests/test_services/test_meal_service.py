# backend/tests/test_services/test_meal_service.py
# 饮食服务测试

import pytest
from app.services.meal_service import NutritionService


@pytest.fixture
def nutrition_service():
    """创建营养服务实例"""
    return NutritionService()


def test_get_nutrition(nutrition_service):
    """测试查询食物营养信息"""
    result = nutrition_service.get_nutrition("白米饭")
    assert result is not None
    assert result.name == "白米饭"
    assert result.gi == 83
    assert result.carbs > 0


def test_get_nutrition_not_found(nutrition_service):
    """测试查询不存在的食物"""
    result = nutrition_service.get_nutrition("不存在的食物")
    assert result is None


def test_search_food(nutrition_service):
    """测试搜索食物"""
    results = nutrition_service.search("米饭")
    assert len(results) > 0
    assert any("米饭" in r.name for r in results)


def test_gi_level(nutrition_service):
    """测试GI等级分类"""
    result = nutrition_service.get_nutrition("白米饭")
    assert result is not None
    assert result.gi_level == "high"  # GI=83

    result = nutrition_service.get_nutrition("糙米饭")
    assert result is not None
    assert result.gi_level == "low"  # GI=55
