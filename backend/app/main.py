# backend/app/main.py
# 糖盾系统 - FastAPI应用入口

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from app.config import settings
from app.database import engine, Base
from app.api.v1 import glucose, meal, exercise, prediction, report, health_sync, auth, insulin, safety, personalization

# 创建数据库表
Base.metadata.create_all(bind=engine)


# 自定义异常类
class TangDunException(Exception):
    """糖盾系统自定义异常"""
    def __init__(self, code: str, message: str, detail: str = ""):
        self.code = code
        self.message = message
        self.detail = detail


# 创建FastAPI应用实例
app = FastAPI(
    title="糖盾 API",
    description="基于多源数据融合的糖尿病智能健康管理系统",
    version="1.0.0",
    docs_url="/api/docs",
    redoc_url="/api/redoc",
)

# CORS中间件配置
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# 全局异常处理器
@app.exception_handler(TangDunException)
async def tangdun_exception_handler(request: Request, exc: TangDunException):
    """处理自定义异常"""
    return JSONResponse(
        status_code=400,
        content={
            "error": {
                "code": exc.code,
                "message": exc.message,
                "detail": exc.detail,
            }
        },
    )


# 注册路由
app.include_router(auth.router, prefix="/api/v1/auth", tags=["认证"])
app.include_router(glucose.router, prefix="/api/v1/glucose", tags=["血糖"])
app.include_router(meal.router, prefix="/api/v1/meal", tags=["饮食"])
app.include_router(exercise.router, prefix="/api/v1/exercise", tags=["运动"])
app.include_router(prediction.router, prefix="/api/v1/prediction", tags=["预测"])
app.include_router(report.router, prefix="/api/v1/report", tags=["报告"])
app.include_router(health_sync.router, prefix="/api/v1/health", tags=["健康数据同步"])
app.include_router(insulin.router, prefix="/api/v1/insulin", tags=["胰岛素管理"])
app.include_router(safety.router, prefix="/api/v1/safety", tags=["临床安全"])
app.include_router(personalization.router, prefix="/api/v1/personalization", tags=["个人化校准"])


@app.get("/api/v1/health")
async def health_check():
    """健康检查接口"""
    return {"status": "ok", "version": "1.0.0", "system": "糖盾糖尿病智能健康管理系统"}
