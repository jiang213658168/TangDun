# backend/app/api/v1/auth.py
# 认证API路由

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from jose import jwt, JWTError
from datetime import datetime, timedelta
from pydantic import BaseModel
from typing import Optional
from sqlalchemy.orm import Session

from app.config import settings
from app.database import get_db
from app.models.user import User

router = APIRouter()
security = HTTPBearer()


class TokenResponse(BaseModel):
    """Token响应模型"""
    access_token: str
    token_type: str = "bearer"
    expires_in: int


class UserInfo(BaseModel):
    """用户信息响应模型"""
    id: int
    openid: str
    name: Optional[str] = None
    avatar_url: Optional[str] = None
    diabetes_type: int = 2
    target_range_low: float = 3.9
    target_range_high: float = 10.0
    alert_low: float = 3.9
    alert_high: float = 10.0


def create_access_token(user_id: int) -> str:
    """创建JWT Access Token"""
    expires_delta = timedelta(days=settings.ACCESS_TOKEN_EXPIRE_DAYS)
    expire = datetime.utcnow() + expires_delta
    to_encode = {"sub": str(user_id), "exp": expire}
    return jwt.encode(to_encode, settings.SECRET_KEY, algorithm=settings.ALGORITHM)


async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security),
    db: Session = Depends(get_db),
) -> int:
    """从JWT Token中提取当前用户ID"""
    try:
        payload = jwt.decode(
            credentials.credentials,
            settings.SECRET_KEY,
            algorithms=[settings.ALGORITHM],
        )
        user_id = payload.get("sub")
        if user_id is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="无效的认证凭据",
            )
    except JWTError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token已过期或无效",
        )

    # 验证用户是否存在
    user = db.query(User).filter(User.id == int(user_id)).first()
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="用户不存在",
        )

    return int(user_id)


@router.post("/login", response_model=TokenResponse)
async def login(openid: str, db: Session = Depends(get_db)):
    """用户登录接口 - 使用OpenID登录"""
    # 查找或创建用户
    user = db.query(User).filter(User.openid == openid).first()
    if user is None:
        # 创建新用户
        user = User(openid=openid)
        db.add(user)
        db.commit()
        db.refresh(user)

    # 创建Token
    access_token = create_access_token(user.id)
    return TokenResponse(
        access_token=access_token,
        expires_in=settings.ACCESS_TOKEN_EXPIRE_DAYS * 86400,
    )


@router.get("/me", response_model=UserInfo)
async def get_user_info(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取当前用户信息"""
    user = db.query(User).filter(User.id == current_user).first()
    if user is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    return UserInfo(
        id=user.id,
        openid=user.openid,
        name=user.name,
        avatar_url=user.avatar_url,
        diabetes_type=user.diabetes_type,
        target_range_low=user.target_range_low,
        target_range_high=user.target_range_high,
        alert_low=user.alert_low,
        alert_high=user.alert_high,
    )


@router.put("/me")
async def update_user_info(
    name: Optional[str] = None,
    diabetes_type: Optional[int] = None,
    target_range_low: Optional[float] = None,
    target_range_high: Optional[float] = None,
    alert_low: Optional[float] = None,
    alert_high: Optional[float] = None,
    weight: Optional[float] = None,
    height: Optional[float] = None,
    age: Optional[int] = None,
    gender: Optional[int] = None,
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """更新当前用户信息"""
    user = db.query(User).filter(User.id == current_user).first()
    if user is None:
        raise HTTPException(status_code=404, detail="用户不存在")

    if name is not None:
        user.name = name
    if diabetes_type is not None:
        user.diabetes_type = diabetes_type
    if target_range_low is not None:
        user.target_range_low = target_range_low
    if target_range_high is not None:
        user.target_range_high = target_range_high
    if alert_low is not None:
        user.alert_low = alert_low
    if alert_high is not None:
        user.alert_high = alert_high
    if weight is not None:
        user.weight = weight
    if height is not None:
        user.height = height
    if age is not None:
        user.age = age
    if gender is not None:
        user.gender = gender

    db.commit()
    return {"message": "用户信息更新成功"}
