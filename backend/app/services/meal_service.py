# backend/app/services/meal_service.py
# 饮食服务模块 - 食物识别和营养查询

import httpx
import json
import os
from typing import List, Optional, Dict
from app.config import settings
from app.schemas.meal import FoodRecognitionResult, FoodNutrition


class FoodRecognitionService:
    """食物识别服务 - 调用百度AI食物识别API"""

    def __init__(self):
        self.api_key = settings.BAIDU_AI_API_KEY
        self.secret_key = settings.BAIDU_AI_SECRET_KEY
        self.access_token = None
        self.token_expires = 0

    async def _get_access_token(self) -> str:
        """获取百度AI Access Token"""
        import time

        # 如果Token未过期，直接返回
        if self.access_token and time.time() < self.token_expires:
            return self.access_token

        token_url = "https://aip.baidubce.com/oauth/2.0/token"
        async with httpx.AsyncClient() as client:
            response = await client.post(
                token_url,
                params={
                    "grant_type": "client_credentials",
                    "client_id": self.api_key,
                    "client_secret": self.secret_key,
                },
            )
            response.raise_for_status()
            data = response.json()

        self.access_token = data["access_token"]
        self.token_expires = time.time() + data.get("expires_in", 2592000) - 300
        return self.access_token

    async def recognize_food(self, image_base64: str) -> List[FoodRecognitionResult]:
        """识别食物图片

        Args:
            image_base64: Base64编码的图片数据

        Returns:
            识别结果列表，每项包含食物名称、置信度
        """
        # 获取Access Token
        access_token = await self._get_access_token()

        # 调用百度AI菜品识别API
        api_url = "https://aip.baidubce.com/api/v1/solution/direct/imagerecognition/combination"
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.post(
                api_url,
                params={"access_token": access_token},
                json={
                    "image": image_base64,
                    "top_num": settings.FOOD_RECOGNITION_TOP_NUM,
                },
            )
            response.raise_for_status()
            result = response.json()

        # 解析识别结果
        foods = []
        for item in result.get("result", []):
            foods.append(
                FoodRecognitionResult(
                    name=item.get("name", "未知食物"),
                    confidence=float(item.get("probability", 0)),
                    calories_per_100g=item.get("calorie", None),
                )
            )

        # 如果没有识别结果，返回默认提示
        if not foods:
            foods.append(
                FoodRecognitionResult(
                    name="未能识别食物",
                    confidence=0.0,
                    calories_per_100g=None,
                )
            )

        return foods


class NutritionService:
    """营养查询服务 - 查询食物营养数据库"""

    def __init__(self):
        self.food_db = self._load_food_db()

    def _load_food_db(self) -> Dict:
        """加载食物营养数据库"""
        db_path = os.path.join(os.path.dirname(__file__), "..", "data", "food_db.json")
        try:
            with open(db_path, "r", encoding="utf-8") as f:
                return json.load(f)
        except FileNotFoundError:
            # 如果文件不存在，返回空数据
            return {"foods": []}

    def get_nutrition(self, food_name: str) -> Optional[FoodNutrition]:
        """查询食物营养信息

        Args:
            food_name: 食物名称

        Returns:
            食物营养信息，如果未找到返回None
        """
        for food in self.food_db.get("foods", []):
            if food["name"] == food_name:
                return self._convert_to_nutrition(food)
        return None

    def search(self, keyword: str, limit: int = 10) -> List[FoodNutrition]:
        """搜索食物营养信息

        Args:
            keyword: 搜索关键词
            limit: 返回结果数上限

        Returns:
            匹配的食物营养信息列表
        """
        results = []
        keyword = keyword.lower()

        for food in self.food_db.get("foods", []):
            if keyword in food["name"].lower() or keyword in food.get("category", "").lower():
                results.append(self._convert_to_nutrition(food))
                if len(results) >= limit:
                    break

        return results

    def _convert_to_nutrition(self, food: Dict) -> FoodNutrition:
        """将食物数据转换为FoodNutrition模型"""
        nutrition = food.get("nutrition_per_100g", {})
        gi = food.get("gi", 0)

        # 判断GI等级
        if gi <= 55:
            gi_level = "low"
        elif gi <= 69:
            gi_level = "medium"
        else:
            gi_level = "high"

        return FoodNutrition(
            id=food.get("id", ""),
            name=food.get("name", ""),
            category=food.get("category", ""),
            carbs=nutrition.get("carbs", 0),
            calories=nutrition.get("calories", 0),
            protein=nutrition.get("protein", 0),
            fat=nutrition.get("fat", 0),
            fiber=nutrition.get("fiber", 0),
            gi=gi,
            gi_level=gi_level,
        )
