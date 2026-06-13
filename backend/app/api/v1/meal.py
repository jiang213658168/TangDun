# backend/app/api/v1/meal.py
# 饮食管理API路由

from fastapi import APIRouter, Depends, HTTPException, Query, UploadFile, File
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
from typing import Optional, List
import base64

from app.database import get_db
from app.api.v1.auth import get_current_user
from app.models.meal import MealRecord, MealItem
from app.schemas.meal import (
    MealRecordCreate,
    MealRecordResponse,
    MealItemResponse,
    FoodRecognitionResponse,
    FoodNutrition,
    WhatIfRequest,
    WhatIfResponse,
)
from app.services.meal_service import FoodRecognitionService, NutritionService

router = APIRouter()

# 初始化服务
food_recognition_service = FoodRecognitionService()
nutrition_service = NutritionService()


@router.post("/", response_model=MealRecordResponse)
async def create_meal_record(
    meal: MealRecordCreate,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """创建饮食记录"""
    # 计算总营养成分
    total_carbs = sum(item.carbs for item in meal.items)
    total_calories = sum(item.calories for item in meal.items)
    total_protein = sum(item.protein for item in meal.items)
    total_fat = sum(item.fat for item in meal.items)
    total_fiber = sum(item.fiber for item in meal.items)

    # 计算加权平均GI
    if total_carbs > 0:
        weighted_gi = sum(item.gi * item.carbs for item in meal.items) / total_carbs
    else:
        weighted_gi = 0

    # 自动判断餐型
    if meal.meal_type is None:
        hour = meal.timestamp.hour
        if 6 <= hour <= 9:
            meal_type = "breakfast"
        elif 11 <= hour <= 13:
            meal_type = "lunch"
        elif 17 <= hour <= 19:
            meal_type = "dinner"
        else:
            meal_type = "snack"
    else:
        meal_type = meal.meal_type

    # 创建饮食记录
    db_meal = MealRecord(
        user_id=current_user,
        timestamp=meal.timestamp,
        meal_type=meal_type,
        image_url=meal.image_url,
        total_carbs=round(total_carbs, 1),
        total_calories=round(total_calories, 1),
        total_protein=round(total_protein, 1),
        total_fat=round(total_fat, 1),
        total_fiber=round(total_fiber, 1),
        avg_gi=round(weighted_gi, 1),
    )
    db.add(db_meal)
    db.flush()  # 获取ID

    # 创建饮食明细
    for item in meal.items:
        db_item = MealItem(
            meal_id=db_meal.id,
            food_name=item.food_name,
            portion_grams=item.portion_grams,
            carbs=item.carbs,
            calories=item.calories,
            protein=item.protein,
            fat=item.fat,
            fiber=item.fiber,
            gi=item.gi,
            recognition_confidence=item.recognition_confidence,
        )
        db.add(db_item)

    db.commit()
    db.refresh(db_meal)

    # 查询明细
    items = db.query(MealItem).filter(MealItem.meal_id == db_meal.id).all()

    return MealRecordResponse(
        id=db_meal.id,
        user_id=db_meal.user_id,
        timestamp=db_meal.timestamp,
        meal_type=db_meal.meal_type,
        image_url=db_meal.image_url,
        total_carbs=db_meal.total_carbs,
        total_calories=db_meal.total_calories,
        total_protein=db_meal.total_protein,
        total_fat=db_meal.total_fat,
        total_fiber=db_meal.total_fiber,
        avg_gi=db_meal.avg_gi,
        items=[MealItemResponse.model_validate(item) for item in items],
        created_at=db_meal.created_at,
    )


@router.get("/", response_model=List[MealRecordResponse])
async def get_meal_records(
    start: Optional[datetime] = Query(None, description="开始时间"),
    end: Optional[datetime] = Query(None, description="结束时间"),
    limit: int = Query(50, le=200, description="返回记录数上限"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取饮食记录列表"""
    query = db.query(MealRecord).filter(MealRecord.user_id == current_user)

    if start:
        query = query.filter(MealRecord.timestamp >= start)
    if end:
        query = query.filter(MealRecord.timestamp <= end)

    meals = query.order_by(MealRecord.timestamp.desc()).limit(limit).all()

    results = []
    for meal in meals:
        items = db.query(MealItem).filter(MealItem.meal_id == meal.id).all()
        results.append(
            MealRecordResponse(
                id=meal.id,
                user_id=meal.user_id,
                timestamp=meal.timestamp,
                meal_type=meal.meal_type,
                image_url=meal.image_url,
                total_carbs=meal.total_carbs,
                total_calories=meal.total_calories,
                total_protein=meal.total_protein,
                total_fat=meal.total_fat,
                total_fiber=meal.total_fiber,
                avg_gi=meal.avg_gi,
                items=[MealItemResponse.model_validate(item) for item in items],
                created_at=meal.created_at,
            )
        )

    return results


@router.post("/recognize", response_model=FoodRecognitionResponse)
async def recognize_food(
    image: UploadFile = File(...),
    current_user: int = Depends(get_current_user),
):
    """食物识别接口 - 上传图片进行食物识别"""
    # 验证图片格式
    if image.content_type not in ["image/jpeg", "image/png"]:
        raise HTTPException(status_code=400, detail="仅支持jpg/png格式图片")

    # 验证图片大小（<5MB）
    contents = await image.read()
    if len(contents) > 5 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="图片大小不能超过5MB")

    # Base64编码
    image_base64 = base64.b64encode(contents).decode("utf-8")

    # 调用食物识别服务
    try:
        foods = await food_recognition_service.recognize_food(image_base64)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"食物识别失败: {str(e)}")

    return FoodRecognitionResponse(foods=foods)


@router.get("/nutrition/{food_name}", response_model=FoodNutrition)
async def get_food_nutrition(
    food_name: str,
    current_user: int = Depends(get_current_user),
):
    """查询食物营养信息"""
    nutrition = nutrition_service.get_nutrition(food_name)
    if nutrition is None:
        raise HTTPException(status_code=404, detail=f"未找到食物 '{food_name}' 的营养信息")
    return nutrition


@router.get("/nutrition/search/{keyword}", response_model=List[FoodNutrition])
async def search_food_nutrition(
    keyword: str,
    limit: int = Query(10, le=50, description="返回结果数上限"),
    current_user: int = Depends(get_current_user),
):
    """搜索食物营养信息"""
    results = nutrition_service.search(keyword, limit)
    return results


@router.post("/what-if", response_model=WhatIfResponse)
async def what_if_simulation(
    request: WhatIfRequest,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """What-if模拟接口 - 模拟进食后的血糖变化"""
    # 如果未提供当前血糖，获取最新血糖值
    current_glucose = request.current_glucose
    if current_glucose is None:
        from app.models.glucose import GlucoseRecord

        latest = (
            db.query(GlucoseRecord)
            .filter(GlucoseRecord.user_id == current_user)
            .order_by(GlucoseRecord.timestamp.desc())
            .first()
        )
        if latest:
            current_glucose = latest.value
        else:
            current_glucose = 5.5  # 默认值

    # 构造饮食数据
    meal_data = []
    for food in request.foods:
        meal_data.append(
            {
                "food_name": food.food_name,
                "carbs": food.carbs,
                "gi": food.gi,
                "portion_grams": food.portion_grams,
            }
        )

    # 调用What-if模拟服务
    from app.services.prediction_service import WhatIfService

    what_if_service = WhatIfService()
    result = await what_if_service.simulate(current_glucose, meal_data)

    return result


@router.delete("/{meal_id}")
async def delete_meal_record(
    meal_id: int,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """删除饮食记录"""
    meal = (
        db.query(MealRecord)
        .filter(MealRecord.id == meal_id, MealRecord.user_id == current_user)
        .first()
    )
    if not meal:
        raise HTTPException(status_code=404, detail="饮食记录不存在")

    # 删除明细
    db.query(MealItem).filter(MealItem.meal_id == meal_id).delete()
    db.delete(meal)
    db.commit()

    return {"message": "饮食记录已删除"}
