// flutter/lib/utils/format.dart
// 格式化工具

import 'package:intl/intl.dart';

class FormatUtils {
  // 日期格式化
  static String formatDate(DateTime date) {
    return DateFormat('yyyy-MM-dd').format(date);
  }

  static String formatDateTime(DateTime dateTime) {
    return DateFormat('yyyy-MM-dd HH:mm').format(dateTime);
  }

  static String formatTime(DateTime time) {
    return DateFormat('HH:mm').format(time);
  }

  static String formatDateChinese(DateTime date) {
    return DateFormat('yyyy年MM月dd日').format(date);
  }

  static String formatDateTimeChinese(DateTime dateTime) {
    return DateFormat('yyyy年MM月dd日 HH:mm').format(dateTime);
  }

  // 血糖值格式化
  static String formatGlucose(double value) {
    return value.toStringAsFixed(1);
  }

  static String formatGlucoseWithUnit(double value) {
    return '${value.toStringAsFixed(1)} mmol/L';
  }

  // 百分比格式化
  static String formatPercent(double value) {
    return '${value.toStringAsFixed(1)}%';
  }

  // 碳水格式化
  static String formatCarbs(double value) {
    return '${value.toStringAsFixed(1)}g';
  }

  // 热量格式化
  static String formatCalories(double value) {
    return '${value.toStringAsFixed(0)} kcal';
  }

  // 步数格式化
  static String formatSteps(int steps) {
    if (steps >= 10000) {
      return '${(steps / 10000).toStringAsFixed(1)}万步';
    }
    return '$steps步';
  }

  // 时长格式化
  static String formatDuration(int minutes) {
    if (minutes >= 60) {
      final hours = minutes ~/ 60;
      final mins = minutes % 60;
      if (mins == 0) {
        return '$hours小时';
      }
      return '$hours小时${mins}分钟';
    }
    return '$minutes分钟';
  }

  // 相对时间格式化
  static String formatRelativeTime(DateTime dateTime) {
    final now = DateTime.now();
    final difference = now.difference(dateTime);

    if (difference.inMinutes < 1) {
      return '刚刚';
    } else if (difference.inMinutes < 60) {
      return '${difference.inMinutes}分钟前';
    } else if (difference.inHours < 24) {
      return '${difference.inHours}小时前';
    } else if (difference.inDays < 7) {
      return '${difference.inDays}天前';
    } else {
      return formatDate(dateTime);
    }
  }

  // GI等级格式化
  static String formatGiLevel(double gi) {
    if (gi <= 55) {
      return '低GI';
    } else if (gi <= 69) {
      return '中GI';
    } else {
      return '高GI';
    }
  }

  // 餐型格式化
  static String formatMealType(String? mealType) {
    switch (mealType) {
      case 'breakfast':
        return '早餐';
      case 'lunch':
        return '午餐';
      case 'dinner':
        return '晚餐';
      case 'snack':
        return '加餐';
      default:
        return '未知';
    }
  }

  // 运动类型格式化
  static String formatExerciseType(String type) {
    switch (type) {
      case 'walking':
        return '步行';
      case 'running':
        return '跑步';
      case 'cycling':
        return '骑行';
      case 'swimming':
        return '游泳';
      case 'yoga':
        return '瑜伽';
      case 'dancing':
        return '跳舞';
      default:
        return type;
    }
  }

  // 运动强度格式化
  static String formatIntensity(String? intensity) {
    switch (intensity) {
      case 'low':
        return '低强度';
      case 'moderate':
        return '中等强度';
      case 'high':
        return '高强度';
      default:
        return '未知';
    }
  }

  // 血糖趋势格式化
  static String formatTrend(String? trend) {
    switch (trend) {
      case 'rising_fast':
        return '快速上升';
      case 'rising':
        return '上升';
      case 'stable':
        return '稳定';
      case 'falling':
        return '下降';
      case 'falling_fast':
        return '快速下降';
      default:
        return '未知';
    }
  }
}
