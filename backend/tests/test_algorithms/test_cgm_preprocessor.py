# backend/tests/test_algorithms/test_cgm_preprocessor.py
# CGM预处理测试

import pytest
import numpy as np
import pandas as pd
from app.algorithms.preprocessing.cgm_preprocessor import CGMPreprocessor


@pytest.fixture
def preprocessor():
    """创建CGM预处理器实例"""
    return CGMPreprocessor(sampling_interval=5)


@pytest.fixture
def sample_glucose_data():
    """创建样本血糖数据"""
    timestamps = pd.date_range(start='2026-06-06', periods=100, freq='5T')
    values = np.random.normal(7.0, 1.5, 100)
    # 添加一些异常值
    values[10] = 35.0  # 超出范围
    values[20] = 0.5   # 超出范围
    # 添加一些缺失值
    values[30:35] = np.nan

    return pd.DataFrame({
        'timestamp': timestamps,
        'value': values
    })


def test_detect_outliers(preprocessor, sample_glucose_data):
    """测试异常值检测"""
    glucose = sample_glucose_data['value']
    mask = preprocessor.detect_outliers(glucose)

    # 异常值应该被检测出来
    assert mask[10] == False  # 35.0应该被检测为异常
    assert mask[20] == False  # 0.5应该被检测为异常

    # 正常值应该保留
    assert mask[0] == True


def test_interpolate_missing(preprocessor, sample_glucose_data):
    """测试缺失值插补"""
    glucose = sample_glucose_data['value'].copy()
    # 人为添加缺失值
    glucose[50:55] = np.nan  # 短缺失 (<30分钟)

    result = preprocessor.interpolate_missing(glucose)

    # 短缺失应该被插补
    assert not result[50:55].isna().any()


def test_kalman_filter(preprocessor):
    """测试卡尔曼滤波"""
    # 创建带噪声的信号
    t = np.linspace(0, 10, 100)
    signal = 7 + 2 * np.sin(t)
    noise = np.random.normal(0, 0.5, 100)
    noisy_signal = signal + noise

    # 滤波
    filtered = preprocessor.kalman_filter(noisy_signal)

    # 滤波后的信号应该有正确的长度
    assert len(filtered) == len(noisy_signal)

    # 滤波后的值应该在合理范围内
    assert all(1.0 < v < 30.0 for v in filtered)


def test_extract_features(preprocessor, sample_glucose_data):
    """测试特征提取"""
    features = preprocessor.extract_features(sample_glucose_data['value'])

    # 应该包含所有特征
    assert 'current_value' in features
    assert 'change_30min' in features
    assert 'change_60min' in features
    assert 'slope_30min' in features
    assert 'slope_60min' in features
    assert 'mean_6h' in features
    assert 'std_6h' in features
    assert 'tir_6h' in features


def test_full_pipeline(preprocessor, sample_glucose_data):
    """测试完整预处理流水线"""
    result = preprocessor.process(sample_glucose_data)

    # 应该添加filtered_value列
    assert 'filtered_value' in result.columns

    # filtered_value应该没有NaN
    assert not result['filtered_value'].isna().any()


def test_empty_data(preprocessor):
    """测试空数据处理"""
    empty_series = pd.Series([], dtype=float)
    features = preprocessor.extract_features(empty_series)
    assert features == {}


def test_single_value(preprocessor):
    """测试单个值处理"""
    single_value = pd.Series([7.0])
    features = preprocessor.extract_features(single_value)
    assert features['current_value'] == 7.0


def test_boundary_values(preprocessor):
    """测试边界值处理"""
    # 测试生理范围边界的值
    values = pd.Series([1.0, 5.0, 10.0, 25.0])
    mask = preprocessor.detect_outliers(values)

    # 边界值应该被接受
    assert mask[0] == True  # 1.0
    assert mask[1] == True  # 5.0
    assert mask[2] == True  # 10.0


def test_mad_detection(preprocessor):
    """测试MAD异常值检测"""
    # 创建有明显异常值的数据
    values = pd.Series([5.0, 5.1, 4.9, 5.0, 5.1, 100.0, 5.0, 4.9])
    mask = preprocessor.detect_outliers(values)

    # 100.0应该被检测为异常
    assert mask[5] == False
