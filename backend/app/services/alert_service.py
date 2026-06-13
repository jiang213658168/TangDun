# backend/app/services/alert_service.py
# 预警服务模块

from datetime import datetime, timedelta
from typing import List, Dict, Optional
from sqlalchemy.orm import Session

from app.models.prediction import AlertRecord
from app.models.glucose import GlucoseRecord
from app.models.user import User


class AlertService:
    """预警服务类"""

    # 预警规则定义
    ALERT_RULES = {
        "low_glucose": {
            "warning": 3.9,
            "critical": 3.0,
        },
        "high_glucose": {
            "warning": 10.0,
            "critical": 13.9,
        },
        "rapid_change": {
            "warning": 0.1,  # mmol/L/min
            "critical": 0.2,
        },
    }

    def check_glucose_alerts(self, user_id: int, db: Session) -> List[Dict]:
        """检查血糖预警

        Args:
            user_id: 用户ID
            db: 数据库会话

        Returns:
            预警列表
        """
        alerts = []

        # 获取用户信息
        user = db.query(User).filter(User.id == user_id).first()
        if not user:
            return alerts

        # 获取最新血糖值
        latest = (
            db.query(GlucoseRecord)
            .filter(GlucoseRecord.user_id == user_id)
            .order_by(GlucoseRecord.timestamp.desc())
            .first()
        )

        if not latest:
            return alerts

        current_value = latest.value
        alert_low = user.alert_low or 3.9
        alert_high = user.alert_high or 10.0

        # 检查低血糖
        if current_value < alert_low:
            severity = "critical" if current_value < 3.0 else "warning"
            alerts.append({
                "alert_type": "low_glucose",
                "severity": severity,
                "glucose_value": current_value,
                "message": f"血糖偏低: {current_value:.1f} mmol/L",
            })

        # 检查高血糖
        if current_value > alert_high:
            severity = "critical" if current_value > 13.9 else "warning"
            alerts.append({
                "alert_type": "high_glucose",
                "severity": severity,
                "glucose_value": current_value,
                "message": f"血糖偏高: {current_value:.1f} mmol/L",
            })

        # 检查快速变化
        change_rate = self._calculate_change_rate(user_id, db)
        if change_rate and abs(change_rate) > 0.1:
            severity = "critical" if abs(change_rate) > 0.2 else "warning"
            direction = "上升" if change_rate > 0 else "下降"
            alerts.append({
                "alert_type": "rapid_change",
                "severity": severity,
                "glucose_value": current_value,
                "message": f"血糖快速{direction}: {abs(change_rate):.2f} mmol/L/min",
            })

        return alerts

    def _calculate_change_rate(self, user_id: int, db: Session) -> Optional[float]:
        """计算血糖变化率

        Args:
            user_id: 用户ID
            db: 数据库会话

        Returns:
            变化率 (mmol/L/min)
        """
        # 获取最近30分钟的数据
        since = datetime.now() - timedelta(minutes=30)
        records = (
            db.query(GlucoseRecord)
            .filter(
                GlucoseRecord.user_id == user_id,
                GlucoseRecord.timestamp >= since,
            )
            .order_by(GlucoseRecord.timestamp.asc())
            .all()
        )

        if len(records) < 2:
            return None

        # 计算线性回归斜率
        times = [(r.timestamp - records[0].timestamp).total_seconds() / 60 for r in records]
        values = [r.value for r in records]

        if len(times) < 2:
            return None

        # 简单线性回归
        n = len(times)
        sum_x = sum(times)
        sum_y = sum(values)
        sum_xy = sum(t * v for t, v in zip(times, values))
        sum_x2 = sum(t * t for t in times)

        denominator = n * sum_x2 - sum_x * sum_x
        if denominator == 0:
            return None

        slope = (n * sum_xy - sum_x * sum_y) / denominator
        return slope

    def create_alert(self, user_id: int, alert_data: Dict,
                      db: Session) -> AlertRecord:
        """创建预警记录

        Args:
            user_id: 用户ID
            alert_data: 预警数据
            db: 数据库会话

        Returns:
            预警记录
        """
        # 检查是否需要限频 (同一类型5分钟内不重复发送)
        recent_alert = (
            db.query(AlertRecord)
            .filter(
                AlertRecord.user_id == user_id,
                AlertRecord.alert_type == alert_data["alert_type"],
                AlertRecord.created_at >= datetime.now() - timedelta(minutes=5),
            )
            .first()
        )

        if recent_alert:
            return None

        alert = AlertRecord(
            user_id=user_id,
            alert_type=alert_data["alert_type"],
            severity=alert_data["severity"],
            glucose_value=alert_data.get("glucose_value"),
            predicted_value=alert_data.get("predicted_value"),
            message=alert_data.get("message"),
        )
        db.add(alert)
        db.commit()
        db.refresh(alert)

        return alert

    def get_unread_alerts(self, user_id: int, db: Session) -> List[AlertRecord]:
        """获取未读预警

        Args:
            user_id: 用户ID
            db: 数据库会话

        Returns:
            未读预警列表
        """
        return (
            db.query(AlertRecord)
            .filter(
                AlertRecord.user_id == user_id,
                AlertRecord.is_read == 0,
            )
            .order_by(AlertRecord.created_at.desc())
            .all()
        )

    def mark_alert_read(self, alert_id: int, user_id: int,
                         db: Session) -> bool:
        """标记预警为已读

        Args:
            alert_id: 预警ID
            user_id: 用户ID
            db: 数据库会话

        Returns:
            是否成功
        """
        alert = (
            db.query(AlertRecord)
            .filter(
                AlertRecord.id == alert_id,
                AlertRecord.user_id == user_id,
            )
            .first()
        )

        if not alert:
            return False

        alert.is_read = 1
        db.commit()
        return True

    def mark_all_read(self, user_id: int, db: Session) -> int:
        """标记所有预警为已读

        Args:
            user_id: 用户ID
            db: 数据库会话

        Returns:
            更新的记录数
        """
        count = (
            db.query(AlertRecord)
            .filter(
                AlertRecord.user_id == user_id,
                AlertRecord.is_read == 0,
            )
            .update({"is_read": 1})
        )
        db.commit()
        return count
