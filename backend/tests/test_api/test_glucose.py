# backend/tests/test_api/test_glucose.py
# 血糖API测试

import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.database import engine, Base
from app.models.user import User
from app.database import SessionLocal

client = TestClient(app)


@pytest.fixture(autouse=True)
def setup_database():
    """测试前创建数据库表，测试后清理"""
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)


@pytest.fixture
def db_session():
    """创建数据库会话"""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


@pytest.fixture
def auth_token(db_session):
    """创建测试用户并获取Token"""
    # 创建测试用户
    user = User(openid="test_user_001", name="测试用户")
    db_session.add(user)
    db_session.commit()

    # 登录获取Token
    response = client.post("/api/v1/auth/login", params={"openid": "test_user_001"})
    assert response.status_code == 200
    return response.json()["access_token"]


def test_health_check():
    """测试健康检查接口"""
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["version"] == "1.0.0"


def test_create_glucose_record(auth_token):
    """测试创建血糖记录"""
    headers = {"Authorization": f"Bearer {auth_token}"}
    data = {
        "timestamp": "2026-06-06T10:00:00",
        "value": 7.5,
        "trend": "stable",
        "source": "cgm"
    }
    response = client.post("/api/v1/glucose/", json=data, headers=headers)
    assert response.status_code == 200
    result = response.json()
    assert result["value"] == 7.5
    assert result["trend"] == "stable"


def test_get_glucose_records(auth_token):
    """测试获取血糖记录列表"""
    headers = {"Authorization": f"Bearer {auth_token}"}

    # 先创建几条记录
    for i in range(3):
        data = {
            "timestamp": f"2026-06-06T{10+i}:00:00",
            "value": 6.0 + i * 0.5,
            "source": "cgm"
        }
        client.post("/api/v1/glucose/", json=data, headers=headers)

    # 获取记录
    response = client.get("/api/v1/glucose/", headers=headers)
    assert response.status_code == 200
    records = response.json()
    assert len(records) == 3


def test_get_latest_glucose(auth_token):
    """测试获取最新血糖记录"""
    headers = {"Authorization": f"Bearer {auth_token}"}

    # 创建记录
    data = {
        "timestamp": "2026-06-06T10:00:00",
        "value": 8.0,
        "source": "cgm"
    }
    client.post("/api/v1/glucose/", json=data, headers=headers)

    # 获取最新记录
    response = client.get("/api/v1/glucose/latest", headers=headers)
    assert response.status_code == 200
    result = response.json()
    assert result["value"] == 8.0


def test_get_glucose_stats(auth_token):
    """测试获取血糖统计信息"""
    headers = {"Authorization": f"Bearer {auth_token}"}

    # 创建多条记录
    for i in range(10):
        data = {
            "timestamp": f"2026-06-06T{8+i//2}:{30*(i%2)}:00",
            "value": 5.0 + i * 0.5,
            "source": "cgm"
        }
        client.post("/api/v1/glucose/", json=data, headers=headers)

    # 获取统计
    response = client.get("/api/v1/glucose/stats", headers=headers)
    assert response.status_code == 200
    stats = response.json()
    assert "avg_glucose" in stats
    assert "tir" in stats


def test_unauthorized_access():
    """测试未认证访问"""
    response = client.get("/api/v1/glucose/")
    assert response.status_code == 403  # HTTPBearer返回403


def test_glucose_value_validation(auth_token):
    """测试血糖值验证"""
    headers = {"Authorization": f"Bearer {auth_token}"}

    # 测试超出范围的血糖值
    data = {
        "timestamp": "2026-06-06T10:00:00",
        "value": 50.0,  # 超出生理范围
        "source": "cgm"
    }
    response = client.post("/api/v1/glucose/", json=data, headers=headers)
    assert response.status_code == 422  # 验证失败


def test_batch_create_glucose(auth_token):
    """测试批量创建血糖记录"""
    headers = {"Authorization": f"Bearer {auth_token}"}
    data = {
        "records": [
            {"timestamp": f"2026-06-06T{10+i}:00:00", "value": 6.0 + i * 0.5, "source": "cgm"}
            for i in range(5)
        ]
    }
    response = client.post("/api/v1/glucose/batch", json=data, headers=headers)
    assert response.status_code == 200
    result = response.json()
    assert result["created"] == 5
