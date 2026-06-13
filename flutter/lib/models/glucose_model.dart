// flutter/lib/models/glucose_model.dart
// 血糖数据模型

class GlucoseRecord {
  final int id;
  final int userId;
  final DateTime timestamp;
  final double value;
  final String? trend;
  final String source;
  final String? sensorId;
  final bool isCalibrated;

  GlucoseRecord({
    required this.id,
    required this.userId,
    required this.timestamp,
    required this.value,
    this.trend,
    this.source = 'cgm',
    this.sensorId,
    this.isCalibrated = false,
  });

  factory GlucoseRecord.fromJson(Map<String, dynamic> json) {
    return GlucoseRecord(
      id: json['id'],
      userId: json['user_id'],
      timestamp: DateTime.parse(json['timestamp']),
      value: json['value'].toDouble(),
      trend: json['trend'],
      source: json['source'] ?? 'cgm',
      sensorId: json['sensor_id'],
      isCalibrated: json['is_calibrated'] == 1,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'user_id': userId,
      'timestamp': timestamp.toIso8601String(),
      'value': value,
      'trend': trend,
      'source': source,
      'sensor_id': sensorId,
      'is_calibrated': isCalibrated ? 1 : 0,
    };
  }
}

class GlucoseStats {
  final double avgGlucose;
  final double minGlucose;
  final double maxGlucose;
  final double stdGlucose;
  final double tir;
  final double tirLow;
  final double tirHigh;
  final int count;

  GlucoseStats({
    required this.avgGlucose,
    required this.minGlucose,
    required this.maxGlucose,
    required this.stdGlucose,
    required this.tir,
    required this.tirLow,
    required this.tirHigh,
    required this.count,
  });

  factory GlucoseStats.fromJson(Map<String, dynamic> json) {
    return GlucoseStats(
      avgGlucose: json['avg_glucose'].toDouble(),
      minGlucose: json['min_glucose'].toDouble(),
      maxGlucose: json['max_glucose'].toDouble(),
      stdGlucose: json['std_glucose'].toDouble(),
      tir: json['tir'].toDouble(),
      tirLow: json['tir_low'].toDouble(),
      tirHigh: json['tir_high'].toDouble(),
      count: json['count'],
    );
  }
}

class GlucoseTrend {
  final double currentValue;
  final String trend;
  final double change30min;
  final double change60min;
  final double slope30min;
  final double slope60min;

  GlucoseTrend({
    required this.currentValue,
    required this.trend,
    required this.change30min,
    required this.change60min,
    required this.slope30min,
    required this.slope60min,
  });

  factory GlucoseTrend.fromJson(Map<String, dynamic> json) {
    return GlucoseTrend(
      currentValue: json['current_value'].toDouble(),
      trend: json['trend'],
      change30min: json['change_30min'].toDouble(),
      change60min: json['change_60min'].toDouble(),
      slope30min: json['slope_30min'].toDouble(),
      slope60min: json['slope_60min'].toDouble(),
    );
  }
}
