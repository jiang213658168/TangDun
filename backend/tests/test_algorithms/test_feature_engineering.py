# backend/tests/test_algorithms/test_feature_engineering.py
# 特征工程测试

import pytest
import numpy as np
import pandas as pd
from datetime import datetime, timedelta
from app.algorithms.features.feature_engineering import FeatureEngineering


@pytest.fixture
def feature_engineering():
    """创建特征工程实例"""
    return FeatureEngineering()


@pytest.fixture
def sample_glucose_data():
    """创建样本血糖数据"""
    timestamps = pd.date_range(start='2026-06-06 08:00:00', periods=100, freq='5T')
    values = np.random.normal(7.0, 1.0, 100)
    return pd.DataFrame({
        'timestamp': timestamps,
        'value': values
    })


@pytest.fixture
def sample_meal_data():
    """创建样本饮食数据"""
    return pd.DataFrame({
        'timestamp': [datetime(2026, 6, 6, 8, 0), datetime(2026, 6, 6, 12, 0)],
        'total_carbs': [45.0, 65.0],
        'total_calories': [350, 520],
        'total_protein': [15, 25],
        'total_fat': [10, 20],
        'total_fiber': [3, 5],
        'avg_gi': [70, 60]
    })


@pytest.fixture
def sample_exercise_data():
    """创建样本运动数据"""
    return pd.DataFrame({
        'start_time': [datetime(2026, 6, 6, 7, 30)],
        'end_time': [datetime(2026, 6, 6, 8, 0)],
        'exercise_type': ['walking'],
        'duration_min': [30],
        'avg_heart_rate': [95],
        'max_heart_rate': [120],
        'steps': [3500]
    })


def test_extract_glucose_features(feature_engineering, sample_glucose_data):
    """测试血糖特征提取"""
    features = feature_engineering.extract_glucose_features(sample_glucose_data)

    # 应该有22维特征
    assert len(features) == 22

    # 特征值应该是数值
    assert all(isinstance(f, (int, float, np.floating)) for f in features)

    # 当前值应该是最后一个值
    assert features[0] == sample_glucose_data['value'].iloc[-1]


def test_extract_meal_features(feature_engineering, sample_meal_data):
    """测试饮食特征提取"""
    current_time = datetime(2026, 6, 6, 13, 0)
    features = feature_engineering.extract_meal_features(sample_meal_data, current_time)

    # 应该有8维特征
    assert len(features) == 8

    # 最近4小时应该包含午餐
    assert features[0] > 0  # 总碳水


def test_extract_exercise_features(feature_engineering, sample_exercise_data):
    """测试运动特征提取"""
    current_time = datetime(2026, 6, 6, 9, 0)
    features = feature_engineering.extract_exercise_features(sample_exercise_data, current_time)

    # 应该有7维特征
    assert len(features) == 7

    # 最近4小时应该包含运动
    assert features[0] > 0  # 总时长


def test_extract_temporal_features(feature_engineering):
    """测试时序特征提取"""
    # 工作日上午
    timestamp = datetime(2026, 6, 6, 10, 0)  # 周六
    features = feature_engineering.extract_temporal_features(timestamp)

    # 应该有6维特征 (新增黎明现象和昼夜节律编码)
    assert len(features) == 6

    assert features[0] == 10  # 小时
    assert features[1] == 5   # 星期六
    assert features[2] == 0   # 非工作日
    assert features[3] == 0   # 非用餐时间
    assert features[4] == 0   # 非黎明时段


def test_extract_all_features(feature_engineering, sample_glucose_data,
                               sample_meal_data, sample_exercise_data):
    """测试提取所有特征"""
    current_time = datetime(2026, 6, 6, 13, 0)
    features = feature_engineering.extract_all_features(
        sample_glucose_data, sample_meal_data, sample_exercise_data, current_time
    )

    # 应该有43维特征 (22+8+7+6)
    assert len(features) == 43


def test_fit_scaler(feature_engineering):
    """测试标准化参数拟合"""
    # 创建特征矩阵 (43维)
    feature_matrix = np.random.randn(100, 43)

    feature_engineering.fit_scaler(feature_matrix)

    assert feature_engineering.mean is not None
    assert feature_engineering.std is not None
    assert len(feature_engineering.mean) == 43


def test_transform(feature_engineering):
    """测试标准化变换"""
    # 拟合标准化参数
    feature_matrix = np.random.randn(100, 41)
    feature_engineering.fit_scaler(feature_matrix)

    # 变换
    features = feature_matrix[0]
    transformed = feature_engineering.transform(features)

    # 变换后的均值应该接近0 (放宽阈值)
    assert abs(np.mean(transformed)) < 0.5


def test_inverse_transform(feature_engineering):
    """测试反标准化变换"""
    # 拟合标准化参数
    feature_matrix = np.random.randn(100, 41)
    feature_engineering.fit_scaler(feature_matrix)

    # 变换和反变换
    features = feature_matrix[0]
    transformed = feature_engineering.transform(features)
    inverse = feature_engineering.inverse_transform(transformed)

    # 应该恢复原始值
    np.testing.assert_array_almost_equal(features, inverse, decimal=10)


def test_empty_glucose_data(feature_engineering):
    """测试空血糖数据"""
    empty_data = pd.DataFrame(columns=['timestamp', 'value'])
    features = feature_engineering.extract_glucose_features(empty_data)

    assert len(features) == 22
    assert all(f == 0 for f in features)


def test_empty_meal_data(feature_engineering):
    """测试空饮食数据"""
    empty_data = pd.DataFrame(columns=['timestamp', 'total_carbs', 'total_calories',
                                        'total_protein', 'total_fat', 'total_fiber', 'avg_gi'])
    current_time = datetime(2026, 6, 6, 13, 0)
    features = feature_engineering.extract_meal_features(empty_data, current_time)

    assert len(features) == 8
    assert all(f == 0 for f in features)


def test_save_load_scaler(feature_engineering, tmp_path):
    """测试保存和加载标准化参数"""
    # 拟合标准化参数
    feature_matrix = np.random.randn(100, 41)
    feature_engineering.fit_scaler(feature_matrix)

    # 保存
    filepath = str(tmp_path / "scaler.json")
    feature_engineering.save_scaler(filepath)

    # 加载到新实例
    new_fe = FeatureEngineering()
    new_fe.load_scaler(filepath)

    # 应该有相同的参数
    np.testing.assert_array_almost_equal(feature_engineering.mean, new_fe.mean)
    np.testing.assert_array_almost_equal(feature_engineering.std, new_fe.std)
