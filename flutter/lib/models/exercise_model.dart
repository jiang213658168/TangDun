// flutter/lib/models/exercise_model.dart
// 运动数据模型

class ExerciseRecord {
  final int id;
  final int userId;
  final DateTime startTime;
  final DateTime? endTime;
  final String exerciseType;
  final int? durationMin;
  final double? avgHeartRate;
  final double? maxHeartRate;
  final int? steps;
  final double? caloriesBurned;
  final String? intensity;
  final String source;

  ExerciseRecord({
    required this.id,
    required this.userId,
    required this.startTime,
    this.endTime,
    required this.exerciseType,
    this.durationMin,
    this.avgHeartRate,
    this.maxHeartRate,
    this.steps,
    this.caloriesBurned,
    this.intensity,
    this.source = 'huawei_watch',
  });

  factory ExerciseRecord.fromJson(Map<String, dynamic> json) {
    return ExerciseRecord(
      id: json['id'],
      userId: json['user_id'],
      startTime: DateTime.parse(json['start_time']),
      endTime: json['end_time'] != null ? DateTime.parse(json['end_time']) : null,
      exerciseType: json['exercise_type'] ?? 'other',
      durationMin: json['duration_min'],
      avgHeartRate: json['avg_heart_rate']?.toDouble(),
      maxHeartRate: json['max_heart_rate']?.toDouble(),
      steps: json['steps'],
      caloriesBurned: json['calories_burned']?.toDouble(),
      intensity: json['intensity'],
      source: json['source'] ?? 'huawei_watch',
    );
  }
}

class ExerciseStats {
  final int totalDurationMin;
  final int totalSteps;
  final double totalCalories;
  final double? avgHeartRate;
  final int exerciseCount;

  ExerciseStats({
    required this.totalDurationMin,
    required this.totalSteps,
    required this.totalCalories,
    this.avgHeartRate,
    required this.exerciseCount,
  });

  factory ExerciseStats.fromJson(Map<String, dynamic> json) {
    return ExerciseStats(
      totalDurationMin: json['total_duration_min'] ?? 0,
      totalSteps: json['total_steps'] ?? 0,
      totalCalories: json['total_calories']?.toDouble() ?? 0,
      avgHeartRate: json['avg_heart_rate']?.toDouble(),
      exerciseCount: json['exercise_count'] ?? 0,
    );
  }
}

class ExercisePrescription {
  final String exerciseType;
  final int durationMin;
  final String intensity;
  final double expectedGlucoseDrop;
  final String notes;

  ExercisePrescription({
    required this.exerciseType,
    required this.durationMin,
    required this.intensity,
    required this.expectedGlucoseDrop,
    required this.notes,
  });

  factory ExercisePrescription.fromJson(Map<String, dynamic> json) {
    return ExercisePrescription(
      exerciseType: json['exercise_type'],
      durationMin: json['duration_min'],
      intensity: json['intensity'],
      expectedGlucoseDrop: json['expected_glucose_drop']?.toDouble() ?? 0,
      notes: json['notes'] ?? '',
    );
  }
}
