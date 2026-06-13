# backend/app/db/init_db.py
# 数据库初始化脚本

import os
import sys
from pathlib import Path

# 添加项目根目录到Python路径
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from app.database import engine, Base
from app.models import (
    User,
    GlucoseRecord,
    MealRecord,
    MealItem,
    ExerciseRecord,
    HeartRateRecord,
    StepRecord,
    SleepRecord,
    SyncStatus,
    ModelTrainingStatus,
    PredictionRecord,
    AlertRecord,
)


def init_database():
    """初始化数据库 - 创建所有表"""
    print("正在创建数据库表...")

    # 创建所有表
    Base.metadata.create_all(bind=engine)

    print("数据库表创建完成！")

    # 创建索引
    create_indexes()

    print("数据库初始化完成！")


def create_indexes():
    """创建数据库索引"""
    from sqlalchemy import text

    indexes = [
        # 血糖记录索引
        "CREATE INDEX IF NOT EXISTS idx_glucose_user_time ON glucose_records(user_id, timestamp)",
        "CREATE INDEX IF NOT EXISTS idx_glucose_user_value ON glucose_records(user_id, value)",
        # 饮食记录索引
        "CREATE INDEX IF NOT EXISTS idx_meal_user_time ON meal_records(user_id, timestamp)",
        # 运动记录索引
        "CREATE INDEX IF NOT EXISTS idx_exercise_user_time ON exercise_records(user_id, start_time)",
        # 心率记录索引
        "CREATE INDEX IF NOT EXISTS idx_hr_user_time ON heart_rate_records(user_id, timestamp)",
        # 预测记录索引
        "CREATE INDEX IF NOT EXISTS idx_prediction_user_time ON prediction_records(user_id, prediction_time)",
        # 预警记录索引
        "CREATE INDEX IF NOT EXISTS idx_alert_user_time ON alert_records(user_id, created_at)",
        # 同步状态索引
        "CREATE INDEX IF NOT EXISTS idx_sync_user_source ON sync_status(user_id, source)",
    ]

    with engine.connect() as conn:
        for index_sql in indexes:
            try:
                conn.execute(text(index_sql))
            except Exception as e:
                print(f"创建索引时出错: {e}")
        conn.commit()

    print("索引创建完成！")


if __name__ == "__main__":
    init_database()
