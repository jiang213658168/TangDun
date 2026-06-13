# backend/app/config.py
# 配置管理模块 - 使用pydantic-settings管理所有配置项

from pydantic_settings import BaseSettings
from typing import List


class Settings(BaseSettings):
    """糖盾系统配置类"""

    # 数据库配置
    DATABASE_URL: str = "sqlite:///./data/tangdun.db"

    # JWT认证配置
    SECRET_KEY: str = "tangdun-secret-key-2026-change-in-production"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_DAYS: int = 7

    # xDrip+连接配置
    XDRIPT_API_URL: str = "http://localhost:17580"

    # 百度AI食物识别API配置 - 从环境变量读取
    BAIDU_AI_APP_ID: str = ""
    BAIDU_AI_API_KEY: str = ""
    BAIDU_AI_SECRET_KEY: str = ""

    # 微信小程序配置
    WX_APPID: str = ""
    WX_SECRET: str = ""

    # CORS配置
    ALLOWED_ORIGINS: List[str] = ["*"]

    # Bergman模型默认参数
    BERGMAN_DEFAULT_P1: float = 0.03
    BERGMAN_DEFAULT_P2: float = 0.02
    BERGMAN_DEFAULT_P3: float = 0.00001
    BERGMAN_DEFAULT_N: float = 0.26
    BERGMAN_DEFAULT_GAMMA: float = 0.0041

    # 预测参数
    PREDICTION_HORIZONS: List[int] = [30, 60, 90]  # 预测时域（分钟）
    CGM_SAMPLING_INTERVAL: int = 5  # CGM采样间隔（分钟）

    # 食物识别配置
    FOOD_RECOGNITION_TOP_NUM: int = 5  # 返回Top-N识别结果

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


# 全局配置实例
settings = Settings()
