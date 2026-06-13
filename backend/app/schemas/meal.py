# backend/app/schemas/meal.py
# 饮食相关的Pydantic模型

from pydantic import BaseModel, Field, ConfigDict
from datetime import datetime
from typing import Optional, List


class MealItemBase(BaseModel):
    """饮食明细基础模型"""
    food_name: str = Field(..., description="食物名称")
    portion_grams: float = Field(..., gt=0, description="份量(克)")
    carbs: float = Field(default=0, ge=0, description="碳水化合物(g)")
    calories: float = Field(default=0, ge=0, description="热量(kcal)")
    protein: float = Field(default=0, ge=0, description="蛋白质(g)")
    fat: float = Field(default=0, ge=0, description="脂肪(g)")
    fiber: float = Field(default=0, ge=0, description="膳食纤维(g)")
    gi: float = Field(default=0, ge=0, le=100, description="GI值")
    recognition_confidence: Optional[float] = Field(None, description="识别置信度")


class MealItemCreate(MealItemBase):
    """创建饮食明细请求模型"""
    pass


class MealItemResponse(MealItemBase):
    """饮食明细响应模型"""
    model_config = ConfigDict(from_attributes=True)

    id: int = Field(..., description="记录ID")
    meal_id: int = Field(..., description="关联的饮食记录ID")
    created_at: datetime = Field(..., description="创建时间")


class MealRecordBase(BaseModel):
    """饮食记录基础模型"""
    timestamp: datetime = Field(..., description="进食时间")
    meal_type: Optional[str] = Field(None, description="餐型: breakfast/lunch/dinner/snack")


class MealRecordCreate(MealRecordBase):
    """创建饮食记录请求模型"""
    items: List[MealItemCreate] = Field(..., description="饮食明细列表")
    image_url: Optional[str] = Field(None, description="拍照图片URL")


class MealRecordResponse(MealRecordBase):
    """饮食记录响应模型"""
    model_config = ConfigDict(from_attributes=True)

    id: int = Field(..., description="记录ID")
    user_id: int = Field(..., description="用户ID")
    image_url: Optional[str] = Field(None, description="拍照图片URL")
    total_carbs: float = Field(default=0, description="总碳水化合物(g)")
    total_calories: float = Field(default=0, description="总热量(kcal)")
    total_protein: float = Field(default=0, description="总蛋白质(g)")
    total_fat: float = Field(default=0, description="总脂肪(g)")
    total_fiber: float = Field(default=0, description="总膳食纤维(g)")
    avg_gi: float = Field(default=0, description="加权平均GI值")
    items: List[MealItemResponse] = Field(default=[], description="饮食明细列表")
    created_at: datetime = Field(..., description="创建时间")


class FoodRecognitionResult(BaseModel):
    """食物识别结果模型"""
    name: str = Field(..., description="食物名称")
    confidence: float = Field(..., description="识别置信度")
    calories_per_100g: Optional[float] = Field(None, description="每100g热量(kcal)")


class FoodRecognitionResponse(BaseModel):
    """食物识别响应模型"""
    foods: List[FoodRecognitionResult] = Field(..., description="识别结果列表")
    image_url: Optional[str] = Field(None, description="图片URL")


class FoodNutrition(BaseModel):
    """食物营养信息模型"""
    id: str = Field(..., description="食物ID")
    name: str = Field(..., description="食物名称")
    category: str = Field(..., description="食物分类")
    carbs: float = Field(..., description="每100g碳水化合物(g)")
    calories: float = Field(..., description="每100g热量(kcal)")
    protein: float = Field(..., description="每100g蛋白质(g)")
    fat: float = Field(..., description="每100g脂肪(g)")
    fiber: float = Field(..., description="每100g膳食纤维(g)")
    gi: float = Field(..., description="GI值")
    gi_level: str = Field(..., description="GI等级: low/medium/high")


class WhatIfRequest(BaseModel):
    """What-if模拟请求模型"""
    foods: List[MealItemCreate] = Field(..., description="食物列表")
    current_glucose: Optional[float] = Field(None, description="当前血糖值 mmol/L")


class WhatIfResponse(BaseModel):
    """What-if模拟响应模型"""
    predicted_peak: float = Field(..., description="预测峰值血糖 mmol/L")
    peak_time: int = Field(..., description="达峰时间(分钟)")
    glucose_curve: List[dict] = Field(..., description="预测血糖曲线")
    alternatives: List[dict] = Field(default=[], description="替代食物建议")
