# backend/tests/test_services/test_glucose_service.py
# 血糖服务测试

import pytest
from app.services.glucose_service import CGMDataService


def test_cgm_service_init():
    """测试CGM服务初始化"""
    service = CGMDataService()
    assert service.base_url is not None
    assert service.client is not None


def test_convert_to_create_schema():
    """测试数据转换"""
    service = CGMDataService()
    record = {
        "timestamp": "2026-06-06T10:00:00",
        "value": 7.5,
        "trend": "stable",
        "raw_data": 100.0,
        "filtered_data": 98.0,
    }
    schema = service.convert_to_create_schema(record)
    assert schema.value == 7.5
    assert schema.trend == "stable"
    assert schema.source == "cgm"
