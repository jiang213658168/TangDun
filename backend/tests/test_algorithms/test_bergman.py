# backend/tests/test_algorithms/test_bergman.py
# Bergman模型测试

import pytest
import numpy as np
from app.algorithms.bergman.model import BergmanModel
from app.algorithms.bergman.parameters import BergmanParameters


@pytest.fixture
def bergman_model():
    """创建Bergman模型实例"""
    return BergmanModel()


@pytest.fixture
def default_params():
    """创建默认参数"""
    return BergmanParameters()


def test_model_initialization(bergman_model):
    """测试模型初始化"""
    assert bergman_model.params is not None
    assert bergman_model.params.p1 == 0.03
    assert bergman_model.params.p2 == 0.02


def test_predict_no_meals(bergman_model):
    """测试无饮食情况下的预测"""
    initial_state = (6.0, 0, 10.0)  # G, X, I
    time_points = np.arange(0, 120, 5)  # 2小时

    prediction = bergman_model.predict(initial_state, time_points)

    # 预测结果应该有正确的长度
    assert len(prediction) == len(time_points)

    # 血糖值应该在合理范围内
    assert all(1.0 < g < 30.0 for g in prediction)


def test_predict_with_meals(bergman_model):
    """测试有饮食情况下的预测"""
    initial_state = (6.0, 0, 10.0)
    time_points = np.arange(0, 120, 5)

    meals = [
        {'time': 0, 'carbs': 50}  # 50g碳水
    ]

    prediction = bergman_model.predict(initial_state, time_points, meals=meals)

    # 进食后血糖应该上升
    assert prediction[12] > prediction[0]  # 60分钟后应该上升


def test_predict_with_exercises(bergman_model):
    """测试有运动情况下的预测"""
    initial_state = (8.0, 0, 10.0)
    time_points = np.arange(0, 120, 5)

    exercises = [
        {'start_time': 0, 'duration': 30, 'met': 3.0}  # 30分钟步行
    ]

    prediction = bergman_model.predict(initial_state, time_points, exercises=exercises)

    # 运动后血糖应该下降
    assert prediction[-1] < prediction[0]


def test_what_if_simulation(bergman_model):
    """测试What-if模拟"""
    result = bergman_model.what_if_simulation(
        current_glucose=6.0,
        current_insulin=10.0,
        meal_plan=[{'time': 0, 'carbs': 50}],
        horizon=120
    )

    assert 'glucose_curve' in result
    assert 'peak_value' in result
    assert 'peak_time' in result
    assert 'risk_level' in result

    # 峰值应该高于初始值
    assert result['peak_value'] > 6.0


def test_risk_level_calculation(bergman_model):
    """测试风险等级计算"""
    assert bergman_model.calculate_risk_level(6.0) == 'normal'
    assert bergman_model.calculate_risk_level(3.5) == 'low_risk'
    assert bergman_model.calculate_risk_level(11.0) == 'high_risk'


def test_diet_input(bergman_model):
    """测试饮食输入函数"""
    meals = [{'time': 0, 'carbs': 50}]

    # 进食时应该有输入
    D = bergman_model._diet_input(30, meals)
    assert D > 0

    # 进食前应该没有输入
    D = bergman_model._diet_input(-10, meals)
    assert D == 0


def test_exercise_input(bergman_model):
    """测试运动消耗函数"""
    exercises = [{'start_time': 0, 'duration': 30, 'met': 3.0}]

    # 运动中应该有消耗
    E = bergman_model._exercise_input(15, exercises)
    assert E > 0

    # 运动前应该没有消耗
    E = bergman_model._exercise_input(-10, exercises)
    assert E == 0

    # 运动后应该有EPOC效应
    E = bergman_model._exercise_input(60, exercises)
    assert E > 0


def test_parameters_to_array(default_params):
    """测试参数转换为数组"""
    arr = default_params.to_array()
    assert len(arr) == 5
    assert arr[0] == 0.03  # p1


def test_parameters_from_array():
    """测试从数组创建参数"""
    arr = np.array([0.03, 0.02, 0.00001, 0.26, 0.0041])
    params = BergmanParameters.from_array(arr)
    assert params.p1 == 0.03
    assert params.p2 == 0.02


def test_parameters_to_dict(default_params):
    """测试参数转换为字典"""
    d = default_params.to_dict()
    assert 'p1' in d
    assert 'p2' in d
    assert d['p1'] == 0.03


def test_parameters_from_dict():
    """测试从字典创建参数"""
    d = {
        'p1': 0.03,
        'p2': 0.02,
        'p3': 0.00001,
        'n': 0.26,
        'gamma': 0.0041
    }
    params = BergmanParameters.from_dict(d)
    assert params.p1 == 0.03


def test_predict_with_confidence(bergman_model):
    """测试带置信区间的预测"""
    initial_state = (6.0, 0, 10.0)
    time_points = np.arange(0, 60, 5)

    result = bergman_model.predict_with_confidence(
        initial_state, time_points, n_samples=10
    )

    assert 'prediction' in result
    assert 'lower' in result
    assert 'upper' in result
    assert 'std' in result

    # 置信区间应该合理
    assert all(result['lower'] <= result['prediction'])
    assert all(result['prediction'] <= result['upper'])
