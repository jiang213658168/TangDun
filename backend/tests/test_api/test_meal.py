# backend/tests/test_api/test_meal.py
# 饮食API测试

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
    user = User(openid="test_user_001", name="测试用户")
    db_session.add(user)
    db_session.commit()

    response = client.post("/api/v1/auth/login", params={"openid": "test_user_001"})
    assert response.status_code == 200
    return response.json()["access_token"]


def test_create_meal_record(auth_token):
    """测试创建饮食记录"""
    headers = {"Authorization": f"Bearer {auth_token}"}
    data = {
        "timestamp": "2026-06-06T12:00:00",
        "meal_type": "lunch",
        "items": [
            {
                "food_name": "白米饭",
                "portion_grams": 200,
                "carbs": 51.8,
                "calories": 232,
                "protein": 5.2,
                "fat": 0.6,
                "fiber": 0.6,
                "gi": 83
            },
            {
                "food_name": "鸡肉",
                "portion_grams": 100,
                "carbs": 1.3,
                "calories": 167,
                "protein": 19.3,
                "fat": 9.4,
                "fiber": 0,
                "gi": 0
            }
        ]
    }
    response = client.post("/api/v1/meal/", json=data, headers=headers)
    assert response.status_code == 200
    result = response.json()
    assert result["total_carbs"] > 0
    assert len(result["items"]) == 2


def test_get_meal_records(auth_token):
    """测试获取饮食记录列表"""
    headers = {"Authorization": f"Bearer {auth_token}"}

    # 创建记录
    data = {
        "timestamp": "2026-06-06T12:00:00",
        "meal_type": "lunch",
        "items": [
            {"food_name": "面条", "portion_grams": 250, "carbs": 62.5, "gi": 81}
        ]
    }
    client.post("/api/v1/meal/", json=data, headers=headers)

    # 获取记录
    response = client.get("/api/v1/meal/", headers=headers)
    assert response.status_code == 200
    records = response.json()
    assert len(records) == 1


def test_get_food_nutrition(auth_token):
    """测试查询食物营养信息"""
    headers = {"Authorization": f"Bearer {auth_token}"}
    response = client.get("/api/v1/meal/nutrition/白米饭", headers=headers)
    assert response.status_code == 200
    nutrition = response.json()
    assert nutrition["name"] == "白米饭"
    assert nutrition["gi"] == 83


def test_search_food(auth_token):
    """测试搜索食物"""
    headers = {"Authorization": f"Bearer {auth_token}"}
    response = client.get("/api/v1/meal/nutrition/search/米饭", headers=headers)
    assert response.status_code == 200
    results = response.json()
    assert len(results) > 0


def test_auto_classify_meal_type(auth_token):
    """测试自动分类餐型"""
    headers = {"Authorization": f"Bearer {auth_token}"}

    # 早餐时间
    data = {
        "timestamp": "2026-06-06T08:00:00",
        "items": [{"food_name": "鸡蛋", "portion_grams": 60, "carbs": 0.7, "gi": 0}]
    }
    response = client.post("/api/v1/meal/", json=data, headers=headers)
    assert response.status_code == 200
    assert response.json()["meal_type"] == "breakfast"

    # 午餐时间
    data = {
        "timestamp": "2026-06-06T12:00:00",
        "items": [{"food_name": "面条", "portion_grams": 250, "carbs": 62.5, "gi": 81}]
    }
    response = client.post("/api/v1/meal/", json=data, headers=headers)
    assert response.status_code == 200
    assert response.json()["meal_type"] == "lunch"


def test_calculate_weighted_gi(auth_token):
    """测试加权GI计算"""
    headers = {"Authorization": f"Bearer {auth_token}"}
    data = {
        "timestamp": "2026-06-06T12:00:00",
        "items": [
            {"food_name": "白米饭", "portion_grams": 200, "carbs": 51.8, "gi": 83},
            {"food_name": "西兰花", "portion_grams": 200, "carbs": 8.6, "gi": 15}
        ]
    }
    response = client.post("/api/v1/meal/", json=data, headers=headers)
    assert response.status_code == 200
    result = response.json()
    # 加权GI = (83*51.8 + 15*8.6) / (51.8 + 8.6) ≈ 73.3
    assert result["avg_gi"] > 70


def test_what_if_simulation(auth_token):
    """测试What-if模拟"""
    headers = {"Authorization": f"Bearer {auth_token}"}
    data = {
        "foods": [
            {"food_name": "白米饭", "portion_grams": 200, "carbs": 51.8, "gi": 83}
        ],
        "current_glucose": 6.0
    }
    response = client.post("/api/v1/meal/what-if", json=data, headers=headers)
    assert response.status_code == 200
    result = response.json()
    assert "predicted_peak" in result
    assert "peak_time" in result
    assert "glucose_curve" in result
