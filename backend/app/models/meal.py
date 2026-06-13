# backend/app/models/meal.py
# 饮食记录数据模型

from sqlalchemy import Column, Integer, String, Float, DateTime, ForeignKey
from sqlalchemy.sql import func
from app.database import Base


class MealRecord(Base):
    """饮食记录表模型 - 一顿饭的记录"""

    __tablename__ = "meal_records"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, comment="用户ID")
    timestamp = Column(DateTime, nullable=False, comment="进食时间")
    meal_type = Column(String, comment="餐型: breakfast/lunch/dinner/snack")
    image_url = Column(String, comment="拍照图片URL")
    total_carbs = Column(Float, default=0, comment="总碳水化合物(g)")
    total_calories = Column(Float, default=0, comment="总热量(kcal)")
    total_protein = Column(Float, default=0, comment="总蛋白质(g)")
    total_fat = Column(Float, default=0, comment="总脂肪(g)")
    total_fiber = Column(Float, default=0, comment="总膳食纤维(g)")
    avg_gi = Column(Float, default=0, comment="加权平均GI值")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")


class MealItem(Base):
    """饮食明细表模型 - 一顿饭中的每种食物"""

    __tablename__ = "meal_items"

    id = Column(Integer, primary_key=True, autoincrement=True)
    meal_id = Column(Integer, ForeignKey("meal_records.id"), nullable=False, comment="关联meal_records.id")
    food_name = Column(String, nullable=False, comment="食物名称")
    portion_grams = Column(Float, nullable=False, comment="份量(克)")
    carbs = Column(Float, default=0, comment="碳水化合物(g)")
    calories = Column(Float, default=0, comment="热量(kcal)")
    protein = Column(Float, default=0, comment="蛋白质(g)")
    fat = Column(Float, default=0, comment="脂肪(g)")
    fiber = Column(Float, default=0, comment="膳食纤维(g)")
    gi = Column(Float, default=0, comment="GI值")
    recognition_confidence = Column(Float, comment="识别置信度")
    created_at = Column(DateTime, server_default=func.now(), comment="创建时间")
