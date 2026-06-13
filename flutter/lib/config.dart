// flutter/lib/config.dart
// App配置

class AppConfig {
  // API基础URL
  static const String baseUrl = 'http://localhost:8000';

  // API版本
  static const String apiVersion = 'v1';

  // 完整API URL
  static const String apiUrl = '$baseUrl/api/$apiVersion';

  // 血糖目标范围
  static const double targetLow = 3.9;
  static const double targetHigh = 10.0;

  // 低血糖阈值
  static const double hypoThreshold = 3.9;

  // 高血糖阈值
  static const double hyperThreshold = 10.0;

  // 严重低血糖阈值
  static const double severeHypoThreshold = 3.0;

  // 严重高血糖阈值
  static const double severeHyperThreshold = 13.9;

  // CGM采样间隔 (分钟)
  static const int cgmSamplingInterval = 5;

  // 预测时域 (分钟)
  static const List<int> predictionHorizons = [30, 60, 90];
}
