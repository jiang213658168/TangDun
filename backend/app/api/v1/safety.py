# backend/app/api/v1/safety.py
# 临床安全API - 酮体监测、胰岛素剂量计算、低血糖报警

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
from typing import Optional, List
import math

from app.database import get_db
from app.api.v1.auth import get_current_user
from app.models.glucose import GlucoseRecord
from app.models.meal import MealRecord, MealItem
from app.models.insulin import InsulinRecord, EmergencyContact
from app.models.user import User

router = APIRouter()


# ==================== 酮体监测 ====================

@router.get("/ketone/check")
async def check_ketone_risk(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """检查酮症风险

    当血糖持续>13.9 mmol/L时，需要检测酮体。
    酮症酸中毒(DKA)是糖尿病急性并发症，可危及生命。
    """
    # 获取最近血糖数据
    since = datetime.now() - timedelta(hours=4)
    glucose_records = (
        db.query(GlucoseRecord)
        .filter(
            GlucoseRecord.user_id == current_user,
            GlucoseRecord.timestamp >= since,
        )
        .order_by(GlucoseRecord.timestamp.desc())
        .all()
    )

    if not glucose_records:
        return {"risk_level": "unknown", "message": "暂无血糖数据"}

    current_glucose = glucose_records[0].value

    # 检查高血糖持续时间
    high_glucose_count = sum(1 for r in glucose_records if r.value > 13.9)
    high_glucose_hours = high_glucose_count * 5 / 60  # 每5分钟一个数据点

    # 计算酮症风险
    if current_glucose > 16.7:
        risk_level = "critical"
        message = "血糖严重偏高，请立即检测酮体！如酮体阳性，请立即就医"
        action = "立即检测酮体，如酮体++以上，立即就医"
    elif current_glucose > 13.9:
        if high_glucose_hours > 2:
            risk_level = "high"
            message = f"血糖持续偏高{high_glucose_hours:.1f}小时，建议检测酮体"
            action = "检测尿酮体，如阳性请联系医生"
        else:
            risk_level = "moderate"
            message = "血糖偏高，注意监测"
            action = "多喝水，1小时后复查血糖"
    elif current_glucose > 10.0:
        risk_level = "low"
        message = "血糖略高，暂无酮症风险"
        action = "继续监测血糖"
    else:
        risk_level = "normal"
        message = "血糖正常，无酮症风险"
        action = "继续常规管理"

    # 检查是否有胰岛素遗漏
    insulin_records = (
        db.query(InsulinRecord)
        .filter(
            InsulinRecord.user_id == current_user,
            InsulinRecord.timestamp >= datetime.now() - timedelta(hours=12),
        )
        .all()
    )

    missed_insulin = False
    if not insulin_records and current_glucose > 10.0:
        missed_insulin = True
        message += "。注意：最近12小时无胰岛素记录"

    return {
        "risk_level": risk_level,
        "current_glucose": current_glucose,
        "high_glucose_hours": round(high_glucose_hours, 1),
        "message": message,
        "action": action,
        "missed_insulin": missed_insulin,
        "symptoms_to_watch": [
            "口渴加剧",
            "频繁排尿",
            "恶心呕吐",
            "腹痛",
            "呼吸急促",
            "呼气有水果味",
            "意识模糊",
        ],
    }


# ==================== 胰岛素剂量计算器 ====================

@router.get("/insulin/calculate")
async def calculate_insulin_dose(
    carbs: float = Query(..., description="碳水化合物量(g)"),
    current_glucose: float = Query(..., description="当前血糖(mmol/L)"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """胰岛素剂量计算器

    计算公式:
    - 餐前剂量 = 碳水(g) / ICR (碳水/胰岛素比)
    - 校正剂量 = (当前血糖 - 目标血糖) / ISF (敏感系数)
    - 总剂量 = 餐前剂量 + 校正剂量 - IOB (胰岛素活性)

    ICR (Insulin-to-Carb Ratio): 每单位胰岛素覆盖的碳水克数
    ISF (Insulin Sensitivity Factor): 每单位胰岛素降低的血糖值
    """
    # 获取用户信息
    user = db.query(User).filter(User.id == current_user).first()
    if not user:
        raise HTTPException(status_code=404, detail="用户不存在")

    # 计算用户特异性ICR和ISF
    weight = user.weight or 70  # 体重(kg)

    # ICR计算 (500法则)
    # 公式: ICR = 500 / 每日总胰岛素量
    # 每日总胰岛素估算: 体重(kg) * 0.5-1.0 U/kg (2型糖尿病平均0.5)
    daily_insulin = weight * 0.5
    ICR = 500 / daily_insulin  # g/U

    # ISF计算 (1800法则)
    # 公式: ISF = 1800 / 每日总胰岛素量 (mg/dL)
    # 转换为 mmol/L: ÷ 18.0182
    ISF_mgdl = 1800 / daily_insulin
    ISF = ISF_mgdl / 18.0182  # mmol/L/U

    # 目标血糖
    target_glucose = (user.target_range_low + user.target_range_high) / 2

    # 计算餐前剂量
    bolus_dose = carbs / ICR

    # 计算校正剂量
    correction_dose = max(0, (current_glucose - target_glucose) / ISF)

    # 获取IOB
    insulin_records = (
        db.query(InsulinRecord)
        .filter(
            InsulinRecord.user_id == current_user,
            InsulinRecord.timestamp >= datetime.now() - timedelta(hours=4),
        )
        .all()
    )

    iob = 0.0
    for record in insulin_records:
        minutes_ago = (datetime.now() - record.timestamp).total_seconds() / 60
        if record.insulin_type == "rapid" and minutes_ago <= 360:
            t = minutes_ago
            activity = (t / 60) * (2.718 ** (1 - t / 60)) if t > 0 else 1.0
            activity = max(0, min(1, activity))
            iob += record.dose_units * (1 - activity)

    # 计算总剂量
    total_dose = max(0, bolus_dose + correction_dose - iob)
    total_dose = round(total_dose, 1)

    # 安全检查
    warnings = []
    if total_dose > 10:
        warnings.append("单次剂量较大，请确认")
    if current_glucose < 4.0:
        warnings.append("当前血糖偏低，不建议注射胰岛素")
    if iob > 3:
        warnings.append(f"当前IOB较高({iob:.1f}U)，注意叠加风险")

    return {
        "carbs": carbs,
        "current_glucose": current_glucose,
        "target_glucose": round(target_glucose, 1),
        "ICR": ICR,
        "ISF": ISF,
        "bolus_dose": round(bolus_dose, 1),
        "correction_dose": round(correction_dose, 1),
        "iob": round(iob, 1),
        "total_dose": total_dose,
        "warnings": warnings,
        "disclaimer": "此计算仅供参考，实际用药请遵医嘱",
    }


# ==================== 低血糖报警 ====================

@router.get("/hypo/check")
async def check_hypoglycemia(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """检查低血糖状态并触发报警

    低血糖分级:
    - 1级: <3.9 mmol/L (警戒)
    - 2级: <3.0 mmol/L (严重)
    - 3级: 需要他人协助处理
    """
    # 获取最新血糖
    latest = (
        db.query(GlucoseRecord)
        .filter(GlucoseRecord.user_id == current_user)
        .order_by(GlucoseRecord.timestamp.desc())
        .first()
    )

    if not latest:
        return {"level": 0, "message": "暂无血糖数据"}

    current_glucose = latest.value

    # 获取用户信息
    user = db.query(User).filter(User.id == current_user).first()
    alert_low = user.alert_low if user else 3.9

    # 检查低血糖级别
    if current_glucose < 3.0:
        level = 3
        message = "严重低血糖！请立即补充糖分！"
        action = "立即进食15g快速碳水（如葡萄糖片、果汁），15分钟后复查"
        notify_emergency = True
    elif current_glucose < 3.9:
        level = 2
        message = "低血糖！请补充糖分"
        action = "进食15g快速碳水，15分钟后复查"
        notify_emergency = False
    elif current_glucose < alert_low:
        level = 1
        message = "血糖偏低，请注意"
        action = "进食少量碳水，30分钟后复查"
        notify_emergency = False
    else:
        level = 0
        message = "血糖正常"
        action = ""
        notify_emergency = False

    # 检查是否反复低血糖
    recent_records = (
        db.query(GlucoseRecord)
        .filter(
            GlucoseRecord.user_id == current_user,
            GlucoseRecord.timestamp >= datetime.now() - timedelta(hours=24),
        )
        .all()
    )

    hypo_count = sum(1 for r in recent_records if r.value < 3.9)
    recurring = hypo_count >= 3

    if recurring:
        message += f"。注意：24小时内已发生{hypo_count}次低血糖"

    return {
        "level": level,
        "current_glucose": current_glucose,
        "message": message,
        "action": action,
        "notify_emergency": notify_emergency,
        "recurring_hypo": recurring,
        "hypo_count_24h": hypo_count,
    }


# ==================== 反复低血糖模式识别 ====================

@router.get("/hypo/pattern")
async def analyze_hypo_pattern(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """分析低血糖模式

    识别反复低血糖的时间模式，帮助调整治疗方案。
    """
    # 获取最近7天数据
    since = datetime.now() - timedelta(days=7)
    records = (
        db.query(GlucoseRecord)
        .filter(
            GlucoseRecord.user_id == current_user,
            GlucoseRecord.timestamp >= since,
        )
        .all()
    )

    if not records:
        return {"message": "数据不足，无法分析"}

    # 按小时统计低血糖发生次数
    hourly_hypo = {}
    for hour in range(24):
        hourly_hypo[hour] = 0

    for record in records:
        if record.value < 3.9:
            hour = record.timestamp.hour
            hourly_hypo[hour] += 1

    # 找出高发时段
    high_risk_hours = []
    for hour, count in hourly_hypo.items():
        if count >= 2:  # 一周内同一小时发生2次以上
            high_risk_hours.append({"hour": hour, "count": count})

    # 按时段分类
    patterns = {
        "夜间(0-6点)": sum(hourly_hypo[h] for h in range(0, 6)),
        "上午(6-12点)": sum(hourly_hypo[h] for h in range(6, 12)),
        "下午(12-18点)": sum(hourly_hypo[h] for h in range(12, 18)),
        "晚上(18-24点)": sum(hourly_hypo[h] for h in range(18, 24)),
    }

    # 生成建议
    suggestions = []
    if patterns["夜间(0-6点)"] > 2:
        suggestions.append("夜间低血糖频繁，建议睡前加餐或调整长效胰岛素剂量")
    if patterns["上午(6-12点)"] > 2:
        suggestions.append("上午低血糖频繁，建议调整早餐前胰岛素剂量")
    if patterns["下午(12-18点)"] > 2:
        suggestions.append("下午低血糖频繁，建议调整午餐前胰岛素剂量")
    if patterns["晚上(18-24点)"] > 2:
        suggestions.append("晚间低血糖频繁，建议调整晚餐前胰岛素剂量")

    return {
        "total_hypo_7days": sum(hourly_hypo.values()),
        "hourly_distribution": hourly_hypo,
        "period_summary": patterns,
        "high_risk_hours": high_risk_hours,
        "suggestions": suggestions,
    }


# ==================== 餐前/餐后血糖对比 ====================

@router.get("/meal/glucose-comparison")
async def get_meal_glucose_comparison(
    days: int = Query(7, description="统计天数"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """餐前/餐后血糖对比

    分析每餐前后的血糖变化，评估饮食对血糖的影响。
    """
    since = datetime.now() - timedelta(days=days)

    # 获取饮食记录
    meal_records = (
        db.query(MealRecord)
        .filter(
            MealRecord.user_id == current_user,
            MealRecord.timestamp >= since,
        )
        .order_by(MealRecord.timestamp.asc())
        .all()
    )

    if not meal_records:
        return {"message": "暂无饮食记录"}

    comparisons = []
    for meal in meal_records:
        meal_time = meal.timestamp

        # 获取餐前血糖 (餐前30分钟内)
        pre_meal = (
            db.query(GlucoseRecord)
            .filter(
                GlucoseRecord.user_id == current_user,
                GlucoseRecord.timestamp >= meal_time - timedelta(minutes=30),
                GlucoseRecord.timestamp <= meal_time,
            )
            .order_by(GlucoseRecord.timestamp.desc())
            .first()
        )

        # 获取餐后血糖 (餐后1-2小时)
        post_meal = (
            db.query(GlucoseRecord)
            .filter(
                GlucoseRecord.user_id == current_user,
                GlucoseRecord.timestamp >= meal_time + timedelta(hours=1),
                GlucoseRecord.timestamp <= meal_time + timedelta(hours=3),
            )
            .order_by(GlucoseRecord.timestamp.asc())
            .first()
        )

        if pre_meal and post_meal:
            change = post_meal.value - pre_meal.value
            comparisons.append({
                "meal_time": meal_time.isoformat(),
                "meal_type": meal.meal_type,
                "carbs": meal.total_carbs,
                "pre_glucose": pre_meal.value,
                "post_glucose": post_meal.value,
                "change": round(change, 1),
                "change_percent": round(change / pre_meal.value * 100, 1),
            })

    # 统计分析
    if comparisons:
        avg_change = sum(c["change"] for c in comparisons) / len(comparisons)
        max_change = max(c["change"] for c in comparisons)
        high_spike_count = sum(1 for c in comparisons if c["change"] > 3.0)

        # 按餐型分析
        by_meal_type = {}
        for c in comparisons:
            meal_type = c["meal_type"] or "unknown"
            if meal_type not in by_meal_type:
                by_meal_type[meal_type] = []
            by_meal_type[meal_type].append(c["change"])

        meal_type_avg = {
            k: round(sum(v) / len(v), 1) for k, v in by_meal_type.items()
        }
    else:
        avg_change = 0
        max_change = 0
        high_spike_count = 0
        meal_type_avg = {}

    return {
        "days": days,
        "meal_count": len(comparisons),
        "comparisons": comparisons,
        "summary": {
            "avg_change": round(avg_change, 1),
            "max_change": round(max_change, 1),
            "high_spike_count": high_spike_count,
            "by_meal_type": meal_type_avg,
        },
    }


# ==================== 生病日规则 ====================

@router.get("/sick-day/rules")
async def get_sick_day_rules(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取生病日管理规则

    生病时血糖管理策略与平时不同。
    """
    # 获取当前血糖
    latest = (
        db.query(GlucoseRecord)
        .filter(GlucoseRecord.user_id == current_user)
        .order_by(GlucoseRecord.timestamp.desc())
        .first()
    )

    current_glucose = latest.value if latest else None

    rules = {
        "general_rules": [
            "继续注射胰岛素，即使不能正常进食",
            "每2-4小时监测一次血糖",
            "多喝水，防止脱水",
            "准备容易消化的碳水食物",
            "记录血糖、胰岛素、进食情况",
        ],
        "when_to_call_doctor": [
            "血糖持续>16.7 mmol/L",
            "血糖持续<3.9 mmol/L",
            "呕吐无法进食",
            "腹泻严重",
            "发热>38.5°C持续24小时",
            "呼吸急促",
            "意识模糊",
        ],
        "emergency_signs": [
            "酮体阳性且血糖持续升高",
            "严重脱水（口干、尿少、眼窝凹陷）",
            "意识模糊或昏迷",
            "呼吸有水果味",
        ],
        "food_suggestions": [
            "清汤、粥、面条",
            "苹果酱、香蕉",
            "饼干、面包",
            "运动饮料（补充糖分和电解质）",
        ],
    }

    if current_glucose and current_glucose > 13.9:
        rules["immediate_action"] = "当前血糖偏高，请检测酮体并考虑补充胰岛素"
    elif current_glucose and current_glucose < 3.9:
        rules["immediate_action"] = "当前血糖偏低，请补充糖分"
    else:
        rules["immediate_action"] = "继续监测血糖"

    return rules


# ==================== 升糖负荷(GL)计算 ====================

@router.get("/nutrition/gl")
async def calculate_glycemic_load(
    food_name: str = Query(..., description="食物名称"),
    portion_grams: float = Query(..., description="份量(g)"),
    current_user: int = Depends(get_current_user),
):
    """计算升糖负荷(GL)

    GL = GI × 碳水化合物量(g) / 100

    GL分级:
    - 低GL: <=10
    - 中GL: 11-19
    - 高GL: >=20
    """
    import json
    import os

    # 从营养数据库查询
    db_path = os.path.join(os.path.dirname(__file__), '..', 'data', 'food_db.json')
    try:
        with open(db_path, 'r', encoding='utf-8') as f:
            food_db = json.load(f)
    except:
        raise HTTPException(status_code=500, detail="营养数据库加载失败")

    # 查找食物
    food = None
    for f in food_db.get('foods', []):
        if f['name'] == food_name:
            food = f
            break

    if not food:
        raise HTTPException(status_code=404, detail=f"未找到食物: {food_name}")

    # 计算碳水量
    carbs_per_100g = food['nutrition_per_100g']['carbs']
    actual_carbs = carbs_per_100g * portion_grams / 100

    # 计算GL
    gi = food.get('gi', 0)
    gl = gi * actual_carbs / 100

    # 分级
    if gl <= 10:
        gl_level = "low"
        gl_label = "低GL"
    elif gl <= 19:
        gl_level = "medium"
        gl_label = "中GL"
    else:
        gl_level = "high"
        gl_label = "高GL"

    return {
        "food_name": food_name,
        "portion_grams": portion_grams,
        "gi": gi,
        "carbs_per_100g": carbs_per_100g,
        "actual_carbs": round(actual_carbs, 1),
        "gl": round(gl, 1),
        "gl_level": gl_level,
        "gl_label": gl_label,
        "explanation": f"GL = {gi} × {actual_carbs:.1f} / 100 = {gl:.1f}",
    }


# ==================== 药物提醒 ====================

@router.post("/medication/reminder")
async def create_medication_reminder(
    medication_name: str,
    reminder_time: str,  # HH:MM格式
    dosage: str,
    frequency: str = "daily",  # daily/weekly/as_needed
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """创建药物提醒

    Args:
        medication_name: 药物名称
        reminder_time: 提醒时间 (HH:MM)
        dosage: 剂量
        frequency: 频率 (daily/weekly/as_needed)
    """
    # 实际应存储到数据库
    return {
        "medication_name": medication_name,
        "reminder_time": reminder_time,
        "dosage": dosage,
        "frequency": frequency,
        "status": "created",
        "message": f"已创建{medication_name}提醒，每天{reminder_time}",
    }


@router.get("/medication/reminders")
async def get_medication_reminders(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取药物提醒列表"""
    # 实际应从数据库查询
    return {
        "reminders": [
            {
                "medication_name": "速效胰岛素",
                "reminder_time": "07:30",
                "dosage": "根据碳水计算",
                "frequency": "daily",
            },
            {
                "medication_name": "长效胰岛素",
                "reminder_time": "22:00",
                "dosage": "固定剂量",
                "frequency": "daily",
            },
        ],
    }


# ==================== 胰岛素/饮食不匹配警告 ====================

@router.get("/warnings/mismatch")
async def check_insulin_meal_mismatch(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """检查胰岛素/饮食不匹配情况

    检测以下情况:
    1. 打了胰岛素但没吃饭 -> 低血糖风险
    2. 吃了饭但没打胰岛素 -> 高血糖风险
    3. 餐后立即运动 -> 低血糖风险
    """
    warnings = []
    now = datetime.now()

    # 获取最近2小时的胰岛素记录
    insulin_records = (
        db.query(InsulinRecord)
        .filter(
            InsulinRecord.user_id == current_user,
            InsulinRecord.timestamp >= now - timedelta(hours=2),
        )
        .all()
    )

    # 获取最近2小时的饮食记录
    meal_records = (
        db.query(MealRecord)
        .filter(
            MealRecord.user_id == current_user,
            MealRecord.timestamp >= now - timedelta(hours=2),
        )
        .all()
    )

    # 获取最近1小时的运动记录
    from app.models.exercise import ExerciseRecord
    exercise_records = (
        db.query(ExerciseRecord)
        .filter(
            ExerciseRecord.user_id == current_user,
            ExerciseRecord.start_time >= now - timedelta(hours=1),
        )
        .all()
    )

    # 检查1: 打了胰岛素但没吃饭
    for insulin in insulin_records:
        if insulin.insulin_type in ["rapid", "mixed"]:
            # 检查前后30分钟是否有饮食
            has_meal = any(
                abs((meal.timestamp - insulin.timestamp).total_seconds()) < 1800
                for meal in meal_records
            )
            if not has_meal:
                minutes_ago = (now - insulin.timestamp).total_seconds() / 60
                warnings.append({
                    "type": "insulin_without_meal",
                    "severity": "high",
                    "message": f"{minutes_ago:.0f}分钟前注射了胰岛素但没有进食",
                    "action": "请尽快进食，否则可能出现低血糖",
                    "insulin_time": insulin.timestamp.isoformat(),
                    "dose": insulin.dose_units,
                })

    # 检查2: 吃了饭但没打胰岛素
    for meal in meal_records:
        if meal.total_carbs and meal.total_carbs > 20:  # 碳水>20g需要胰岛素
            # 检查前后30分钟是否有胰岛素
            has_insulin = any(
                abs((insulin.timestamp - meal.timestamp).total_seconds()) < 1800
                for insulin in insulin_records
            )
            if not has_insulin:
                minutes_ago = (now - meal.timestamp).total_seconds() / 60
                warnings.append({
                    "type": "meal_without_insulin",
                    "severity": "high",
                    "message": f"{minutes_ago:.0f}分钟前进食了{meal.total_carbs:.0f}g碳水但没有注射胰岛素",
                    "action": "请考虑注射胰岛素，否则可能出现高血糖",
                    "meal_time": meal.timestamp.isoformat(),
                    "carbs": meal.total_carbs,
                })

    # 检查3: 餐后立即运动
    for meal in meal_records:
        for exercise in exercise_records:
            # 运动开始时间在餐后30分钟内
            if (exercise.start_time - meal.timestamp).total_seconds() < 1800:
                warnings.append({
                    "type": "exercise_after_meal",
                    "severity": "medium",
                    "message": "餐后立即运动可能导致低血糖",
                    "action": "建议餐后1小时再运动，或运动前补充少量碳水",
                    "meal_time": meal.timestamp.isoformat(),
                    "exercise_time": exercise.start_time.isoformat(),
                })

    return {
        "has_warnings": len(warnings) > 0,
        "warning_count": len(warnings),
        "warnings": warnings,
    }


# ==================== CGM错误数据过滤 ====================

@router.post("/cgm/filter-errors")
async def filter_cgm_errors(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """过滤CGM错误数据

    检测并标记以下错误:
    1. 传感器预热期数据
    2. 压迫性低血糖
    3. 异常波动（传感器故障）
    4. 超出生理范围的值
    """
    # 获取最近24小时数据
    since = datetime.now() - timedelta(hours=24)
    records = (
        db.query(GlucoseRecord)
        .filter(
            GlucoseRecord.user_id == current_user,
            GlucoseRecord.timestamp >= since,
        )
        .order_by(GlucoseRecord.timestamp.asc())
        .all()
    )

    if not records:
        return {"message": "暂无数据", "errors_found": 0}

    errors = []
    values = [r.value for r in records]

    for i, record in enumerate(records):
        # 检查1: 超出生理范围
        if record.value < 1.0 or record.value > 30.0:
            errors.append({
                "type": "out_of_range",
                "timestamp": record.timestamp.isoformat(),
                "value": record.value,
                "message": "血糖值超出生理范围",
            })

        # 检查2: 压迫性低血糖（突然下降然后恢复）
        if i >= 2 and i < len(records) - 2:
            if (record.value < 3.0 and
                values[i-2] > 4.0 and values[i+2] > 4.0):
                errors.append({
                    "type": "compression_low",
                    "timestamp": record.timestamp.isoformat(),
                    "value": record.value,
                    "message": "可能是压迫性低血糖（睡觉压到传感器）",
                })

        # 检查3: 异常波动（5分钟内变化>5 mmol/L）
        if i > 0:
            change = abs(record.value - values[i-1])
            if change > 5.0:
                errors.append({
                    "type": "abnormal_fluctuation",
                    "timestamp": record.timestamp.isoformat(),
                    "value": record.value,
                    "change": round(change, 1),
                    "message": f"5分钟内变化{change:.1f} mmol/L，可能是传感器故障",
                })

    return {
        "errors_found": len(errors),
        "errors": errors,
        "total_records": len(records),
        "error_rate": round(len(errors) / len(records) * 100, 1) if records else 0,
    }


# ==================== 预测准确性实时验证 ====================

@router.get("/prediction/validate")
async def validate_prediction_accuracy(
    hours: int = Query(24, description="验证时间范围(小时)"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """实时验证预测准确性

    对比预测值和实际值，评估模型表现。
    """
    from app.models.prediction import PredictionRecord

    since = datetime.now() - timedelta(hours=hours)

    # 获取有实际值的预测记录
    predictions = (
        db.query(PredictionRecord)
        .filter(
            PredictionRecord.user_id == current_user,
            PredictionRecord.prediction_time >= since,
            PredictionRecord.actual_value.isnot(None),
        )
        .all()
    )

    if not predictions:
        return {"message": "暂无验证数据", "count": 0}

    # 计算误差
    errors = []
    for pred in predictions:
        error = abs(pred.predicted_value - pred.actual_value)
        errors.append({
            "prediction_time": pred.prediction_time.isoformat(),
            "predicted": pred.predicted_value,
            "actual": pred.actual_value,
            "error": round(error, 2),
            "horizon": pred.horizon_min,
        })

    # 统计
    import numpy as np
    error_values = [e["error"] for e in errors]

    # 按时域分组
    by_horizon = {}
    for e in errors:
        h = e["horizon"]
        if h not in by_horizon:
            by_horizon[h] = []
        by_horizon[h].append(e["error"])

    horizon_stats = {}
    for h, errs in by_horizon.items():
        horizon_stats[f"{h}min"] = {
            "mae": round(np.mean(errs), 2),
            "rmse": round(np.sqrt(np.mean(np.array(errs)**2)), 2),
            "count": len(errs),
        }

    return {
        "hours": hours,
        "total_predictions": len(predictions),
        "overall_mae": round(np.mean(error_values), 2),
        "overall_rmse": round(np.sqrt(np.mean(np.array(error_values)**2)), 2),
        "by_horizon": horizon_stats,
        "recent_errors": errors[-10:],  # 最近10条
    }


# ==================== 数据备份/恢复 ====================

@router.post("/data/backup")
async def backup_data(
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """备份用户数据

    导出所有用户数据为JSON格式。
    """
    import json

    # 获取所有数据
    glucose = db.query(GlucoseRecord).filter(GlucoseRecord.user_id == current_user).all()
    meals = db.query(MealRecord).filter(MealRecord.user_id == current_user).all()
    insulin = db.query(InsulinRecord).filter(InsulinRecord.user_id == current_user).all()

    backup = {
        "backup_time": datetime.now().isoformat(),
        "user_id": current_user,
        "glucose_count": len(glucose),
        "meal_count": len(meals),
        "insulin_count": len(insulin),
        "glucose": [
            {"timestamp": r.timestamp.isoformat(), "value": r.value}
            for r in glucose[-1000:]  # 最近1000条
        ],
        "meals": [
            {"timestamp": r.timestamp.isoformat(), "carbs": r.total_carbs}
            for r in meals[-500:]  # 最近500条
        ],
        "insulin": [
            {"timestamp": r.timestamp.isoformat(), "dose": r.dose_units, "type": r.insulin_type}
            for r in insulin[-500:]
        ],
    }

    return backup


# ==================== 快速血糖输入 ====================

@router.post("/glucose/quick")
async def quick_glucose_entry(
    value: float = Query(..., description="血糖值(mmol/L)"),
    current_user: int = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """快速血糖输入

    一键记录当前血糖值，自动填充时间戳。
    """
    now = datetime.now()

    # 判断趋势（与上一条比较）
    latest = (
        db.query(GlucoseRecord)
        .filter(GlucoseRecord.user_id == current_user)
        .order_by(GlucoseRecord.timestamp.desc())
        .first()
    )

    trend = "stable"
    if latest:
        time_diff = (now - latest.timestamp).total_seconds() / 60
        if time_diff < 30:  # 30分钟内
            change_rate = (value - latest.value) / time_diff
            if change_rate > 0.1:
                trend = "rising_fast"
            elif change_rate > 0.02:
                trend = "rising"
            elif change_rate < -0.1:
                trend = "falling_fast"
            elif change_rate < -0.02:
                trend = "falling"

    # 创建记录
    record = GlucoseRecord(
        user_id=current_user,
        timestamp=now,
        value=value,
        trend=trend,
        source="manual",
    )
    db.add(record)
    db.commit()
    db.refresh(record)

    # 检查是否需要预警
    user = db.query(User).filter(User.id == current_user).first()
    alert_low = user.alert_low if user else 3.9
    alert_high = user.alert_high if user else 10.0

    alert = None
    if value < alert_low:
        alert = {"type": "low", "message": "血糖偏低，请补充糖分"}
    elif value > alert_high:
        alert = {"type": "high", "message": "血糖偏高，请注意"}

    return {
        "id": record.id,
        "timestamp": now.isoformat(),
        "value": value,
        "trend": trend,
        "alert": alert,
    }
