# backend/tests/test_services/test_alert_service.py
# 预警服务测试

import pytest
from app.services.alert_service import AlertService


@pytest.fixture
def alert_service():
    """创建预警服务实例"""
    return AlertService()


def test_alert_service_init(alert_service):
    """测试预警服务初始化"""
    assert alert_service.ALERT_RULES is not None
    assert "low_glucose" in alert_service.ALERT_RULES
    assert "high_glucose" in alert_service.ALERT_RULES
    assert "rapid_change" in alert_service.ALERT_RULES


def test_alert_rules(alert_service):
    """测试预警规则"""
    rules = alert_service.ALERT_RULES
    assert rules["low_glucose"]["warning"] == 3.9
    assert rules["low_glucose"]["critical"] == 3.0
    assert rules["high_glucose"]["warning"] == 10.0
    assert rules["high_glucose"]["critical"] == 13.9
