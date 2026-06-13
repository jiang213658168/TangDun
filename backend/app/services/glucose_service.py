# backend/app/services/glucose_service.py
# CGM数据服务 - 通过xDrip+ REST API获取血糖数据
# 主要方式: REST API
# 备用方式: 本地SQLite数据库读取

import httpx
import sqlite3
import os
from datetime import datetime, timedelta
from typing import List, Dict, Optional
from app.config import settings
from app.schemas.glucose import GlucoseCreate


class CGMDataService:
    """CGM数据服务 - 通过xDrip+ REST API获取血糖数据

    主要方式: REST API (推荐)
    备用方式: 本地SQLite数据库读取
    """

    def __init__(self):
        self.base_url = settings.XDRIPT_API_URL
        self.client = httpx.AsyncClient(timeout=10.0)
        # xDrip+本地数据库路径 (Android设备)
        self.xdrip_db_path = "/storage/emulated/0/xDrip/xDrip.db"

    async def get_latest_glucose(self, count: int = 288) -> List[Dict]:
        """获取最近的血糖数据

        Args:
            count: 数据条数，288条=24小时(每5分钟一条)

        Returns:
            血糖记录列表
        """
        try:
            # 主要方式: REST API
            return await self._get_from_rest_api(count)
        except Exception as e:
            print(f"REST API获取失败: {e}，尝试本地数据库...")
            # 备用方式: 本地SQLite数据库
            return self._get_from_local_db(count)

    async def _get_from_rest_api(self, count: int) -> List[Dict]:
        """通过REST API获取血糖数据"""
        response = await self.client.get(
            f"{self.base_url}/sgv.json",
            params={"count": count}
        )
        response.raise_for_status()
        data = response.json()

        records = []
        for item in data:
            # 单位转换: mg/dL -> mmol/L
            glucose_mmoll = item.get("sgv", 0) / 18.0182
            records.append({
                "timestamp": datetime.fromtimestamp(item["date"] / 1000),
                "value": round(glucose_mmoll, 1),
                "trend": item.get("direction", "flat"),
                "raw_data": item.get("unfiltered", None),
                "filtered_data": item.get("filtered", None),
            })
        return records

    def _get_from_local_db(self, count: int) -> List[Dict]:
        """通过本地SQLite数据库获取血糖数据 (备用方案)

        数据库文件路径: /storage/emulated/0/xDrip/xDrip.db
        表: bgreadings
        字段: calculated_value (mg/dL), timestamp (毫秒时间戳)
        """
        if not os.path.exists(self.xdrip_db_path):
            print(f"本地数据库不存在: {self.xdrip_db_path}")
            return []

        try:
            conn = sqlite3.connect(self.xdrip_db_path)
            cursor = conn.cursor()

            # 查询最近的血糖数据
            cursor.execute("""
                SELECT calculated_value, timestamp, calculated_value_slope
                FROM bgreadings
                ORDER BY timestamp DESC
                LIMIT ?
            """, (count,))

            records = []
            for row in cursor.fetchall():
                glucose_mgdl = row[0]
                timestamp_ms = row[1]
                slope = row[2]

                # 单位转换: mg/dL -> mmol/L
                glucose_mmoll = glucose_mgdl / 18.0182

                # 根据斜率判断趋势
                if slope > 0.1:
                    trend = "rising_fast"
                elif slope > 0.02:
                    trend = "rising"
                elif slope < -0.1:
                    trend = "falling_fast"
                elif slope < -0.02:
                    trend = "falling"
                else:
                    trend = "stable"

                records.append({
                    "timestamp": datetime.fromtimestamp(timestamp_ms / 1000),
                    "value": round(glucose_mmoll, 1),
                    "trend": trend,
                    "raw_data": None,
                    "filtered_data": None,
                })

            conn.close()
            return records
        except Exception as e:
            print(f"读取本地数据库失败: {e}")
            return []

    async def get_current_glucose(self) -> Optional[Dict]:
        """获取当前最新血糖值"""
        records = await self.get_latest_glucose(count=1)
        if not records:
            return None
        return records[0]

    async def get_glucose_since(self, since: datetime) -> List[Dict]:
        """获取指定时间以来的血糖数据"""
        count = int((datetime.now() - since).total_seconds() / 300) + 10
        return await self.get_latest_glucose(min(count, 288))

    def convert_to_create_schema(self, record: Dict) -> GlucoseCreate:
        """将CGM数据转换为创建记录的Schema"""
        return GlucoseCreate(
            timestamp=record["timestamp"],
            value=record["value"],
            trend=record.get("trend"),
            source="cgm",
            raw_data=record.get("raw_data"),
            filtered_data=record.get("filtered_data"),
        )

    async def close(self):
        """关闭HTTP客户端"""
        await self.client.aclose()
