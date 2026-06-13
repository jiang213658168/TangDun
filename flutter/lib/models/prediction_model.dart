// flutter/lib/models/prediction_model.dart
// 预测数据模型

class PredictionResult {
  final DateTime predictionTime;
  final Map<String, PredictionHorizon> predictions;
  final String riskLevel;
  final List<double> riskLogits;
  final String modelType;
  final Map<String, double> weights;
  final PredictionExplanation? explanation;

  PredictionResult({
    required this.predictionTime,
    required this.predictions,
    required this.riskLevel,
    this.riskLogits = const [],
    required this.modelType,
    this.weights = const {},
    this.explanation,
  });

  factory PredictionResult.fromJson(Map<String, dynamic> json) {
    Map<String, PredictionHorizon> predictions = {};
    if (json['predictions'] != null) {
      (json['predictions'] as Map<String, dynamic>).forEach((key, value) {
        predictions[key] = PredictionHorizon.fromJson(value);
      });
    }

    return PredictionResult(
      predictionTime: DateTime.parse(json['prediction_time']),
      predictions: predictions,
      riskLevel: json['risk_level'] ?? 'normal',
      riskLogits: (json['risk_logits'] as List<dynamic>?)
              ?.map((e) => e.toDouble())
              .toList() ??
          [],
      modelType: json['model_type'] ?? 'fusion',
      weights: (json['weights'] as Map<String, dynamic>?)
              ?.map((k, v) => MapEntry(k, v.toDouble())) ??
          {},
      explanation: json['explanation'] != null
          ? PredictionExplanation.fromJson(json['explanation'])
          : null,
    );
  }
}

class PredictionHorizon {
  final double value;
  final double upper;
  final double lower;

  PredictionHorizon({
    required this.value,
    required this.upper,
    required this.lower,
  });

  factory PredictionHorizon.fromJson(Map<String, dynamic> json) {
    return PredictionHorizon(
      value: json['value'].toDouble(),
      upper: json['upper'].toDouble(),
      lower: json['lower'].toDouble(),
    );
  }
}

class PredictionExplanation {
  final List<TopFactor> topFactors;

  PredictionExplanation({required this.topFactors});

  factory PredictionExplanation.fromJson(Map<String, dynamic> json) {
    return PredictionExplanation(
      topFactors: (json['top_factors'] as List<dynamic>?)
              ?.map((e) => TopFactor.fromJson(e))
              .toList() ??
          [],
    );
  }
}

class TopFactor {
  final String? time;
  final double? weight;
  final String? event;
  final String? note;

  TopFactor({this.time, this.weight, this.event, this.note});

  factory TopFactor.fromJson(Map<String, dynamic> json) {
    return TopFactor(
      time: json['time'],
      weight: json['weight']?.toDouble(),
      event: json['event'],
      note: json['note'],
    );
  }
}

class AlertRecord {
  final int id;
  final String alertType;
  final String severity;
  final double? glucoseValue;
  final double? predictedValue;
  final String? message;
  final int isRead;
  final DateTime createdAt;

  AlertRecord({
    required this.id,
    required this.alertType,
    required this.severity,
    this.glucoseValue,
    this.predictedValue,
    this.message,
    this.isRead = 0,
    required this.createdAt,
  });

  factory AlertRecord.fromJson(Map<String, dynamic> json) {
    return AlertRecord(
      id: json['id'],
      alertType: json['alert_type'],
      severity: json['severity'],
      glucoseValue: json['glucose_value']?.toDouble(),
      predictedValue: json['predicted_value']?.toDouble(),
      message: json['message'],
      isRead: json['is_read'] ?? 0,
      createdAt: DateTime.parse(json['created_at']),
    );
  }
}
