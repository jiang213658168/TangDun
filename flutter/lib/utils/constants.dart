// flutter/lib/utils/constants.dart
// 常量定义

class Constants {
  // 血糖趋势
  static const String TREND_RISING_FAST = 'rising_fast';
  static const String TREND_RISING = 'rising';
  static const String TREND_STABLE = 'stable';
  static const String TREND_FALLING = 'falling';
  static const String TREND_FALLING_FAST = 'falling_fast';

  // 血糖范围
  static const double HYPOGLYCEMIA = 3.9;
  static const double NORMAL_LOW = 3.9;
  static const double NORMAL_HIGH = 10.0;
  static const double HYPERGLYCEMIA = 10.0;
  static const double SEVERE_HYPO = 3.0;
  static const double SEVERE_HYPER = 13.9;

  // 运动MET值
  static const double MET_WALKING = 3.0;
  static const double MET_RUNNING = 8.0;
  static const double MET_CYCLING = 6.0;
  static const double MET_SWIMMING = 7.0;
  static const double MET_YOGA = 2.5;
  static const double MET_DANCING = 5.0;

  // 餐型
  static const String MEAL_BREAKFAST = 'breakfast';
  static const String MEAL_LUNCH = 'lunch';
  static const String MEAL_DINNER = 'dinner';
  static const String MEAL_SNACK = 'snack';

  // 预警类型
  static const String ALERT_LOW_GLUCOSE = 'low_glucose';
  static const String ALERT_HIGH_GLUCOSE = 'high_glucose';
  static const String ALERT_RAPID_CHANGE = 'rapid_change';

  // 预警严重程度
  static const String SEVERITY_WARNING = 'warning';
  static const String SEVERITY_CRITICAL = 'critical';

  // 风险等级
  static const String RISK_NORMAL = 'normal';
  static const String RISK_LOW = 'low_risk';
  static const String RISK_HIGH = 'high_risk';

  // GI等级
  static const String GI_LOW = 'low';
  static const String GI_MEDIUM = 'medium';
  static const String GI_HIGH = 'high';
}
