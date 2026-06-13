// flutter/lib/services/api_service.dart
// HTTP请求封装

import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../config.dart';

class ApiService {
  static final ApiService _instance = ApiService._internal();
  factory ApiService() => _instance;

  late Dio _dio;
  final _storage = FlutterSecureStorage();

  ApiService._internal() {
    _dio = Dio(BaseOptions(
      baseUrl: AppConfig.apiUrl,
      connectTimeout: Duration(seconds: 10),
      receiveTimeout: Duration(seconds: 10),
    ));

    // 请求拦截器：自动添加JWT Token
    _dio.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) async {
        final token = await _storage.read(key: 'jwt_token');
        if (token != null) {
          options.headers['Authorization'] = 'Bearer $token';
        }
        handler.next(options);
      },
      onError: (error, handler) async {
        if (error.response?.statusCode == 401) {
          // Token过期，清除并跳转登录页
          await _storage.delete(key: 'jwt_token');
        }
        handler.next(error);
      },
    ));
  }

  // 登录
  Future<String> login(String openid) async {
    final response = await _dio.post('/auth/login', queryParameters: {'openid': openid});
    final token = response.data['access_token'];
    await _storage.write(key: 'jwt_token', value: token);
    return token;
  }

  // 获取用户信息
  Future<Map<String, dynamic>> getUserInfo() async {
    final response = await _dio.get('/auth/me');
    return response.data;
  }

  // 更新用户信息
  Future<void> updateUserInfo(Map<String, dynamic> data) async {
    await _dio.put('/auth/me', queryParameters: data);
  }

  // 获取最新血糖
  Future<Map<String, dynamic>> getLatestGlucose() async {
    final response = await _dio.get('/glucose/latest');
    return response.data;
  }

  // 获取血糖列表
  Future<List<dynamic>> getGlucoseRecords({
    DateTime? start,
    DateTime? end,
    int limit = 288,
  }) async {
    final response = await _dio.get('/glucose/', queryParameters: {
      if (start != null) 'start': start.toIso8601String(),
      if (end != null) 'end': end.toIso8601String(),
      'limit': limit,
    });
    return response.data;
  }

  // 获取血糖统计
  Future<Map<String, dynamic>> getGlucoseStats({
    DateTime? start,
    DateTime? end,
  }) async {
    final response = await _dio.get('/glucose/stats', queryParameters: {
      if (start != null) 'start': start.toIso8601String(),
      if (end != null) 'end': end.toIso8601String(),
    });
    return response.data;
  }

  // 获取血糖趋势
  Future<Map<String, dynamic>> getGlucoseTrend() async {
    final response = await _dio.get('/glucose/trend');
    return response.data;
  }

  // 创建饮食记录
  Future<Map<String, dynamic>> createMealRecord(Map<String, dynamic> data) async {
    final response = await _dio.post('/meal/', data: data);
    return response.data;
  }

  // 获取饮食记录
  Future<List<dynamic>> getMealRecords({
    DateTime? start,
    DateTime? end,
    int limit = 50,
  }) async {
    final response = await _dio.get('/meal/', queryParameters: {
      if (start != null) 'start': start.toIso8601String(),
      if (end != null) 'end': end.toIso8601String(),
      'limit': limit,
    });
    return response.data;
  }

  // 食物识别
  Future<Map<String, dynamic>> recognizeFood(String imagePath) async {
    final formData = FormData.fromMap({
      'image': await MultipartFile.fromFile(imagePath),
    });
    final response = await _dio.post('/meal/recognize', data: formData);
    return response.data;
  }

  // 查询食物营养
  Future<Map<String, dynamic>> getFoodNutrition(String foodName) async {
    final response = await _dio.get('/meal/nutrition/$foodName');
    return response.data;
  }

  // 搜索食物
  Future<List<dynamic>> searchFood(String keyword) async {
    final response = await _dio.get('/meal/nutrition/search/$keyword');
    return response.data;
  }

  // What-if模拟
  Future<Map<String, dynamic>> whatIfSimulation(Map<String, dynamic> data) async {
    final response = await _dio.post('/meal/what-if', data: data);
    return response.data;
  }

  // 获取运动记录
  Future<List<dynamic>> getExerciseRecords({
    DateTime? start,
    DateTime? end,
    int limit = 50,
  }) async {
    final response = await _dio.get('/exercise/', queryParameters: {
      if (start != null) 'start': start.toIso8601String(),
      if (end != null) 'end': end.toIso8601String(),
      'limit': limit,
    });
    return response.data;
  }

  // 获取运动统计
  Future<Map<String, dynamic>> getExerciseStats({
    DateTime? start,
    DateTime? end,
  }) async {
    final response = await _dio.get('/exercise/stats', queryParameters: {
      if (start != null) 'start': start.toIso8601String(),
      if (end != null) 'end': end.toIso8601String(),
    });
    return response.data;
  }

  // 获取运动处方
  Future<Map<String, dynamic>> getExercisePrescription({
    double? currentGlucose,
  }) async {
    final response = await _dio.get('/exercise/prescription', queryParameters: {
      if (currentGlucose != null) 'current_glucose': currentGlucose,
    });
    return response.data;
  }

  // 获取预测结果
  Future<Map<String, dynamic>> getPrediction({int horizon = 60}) async {
    final response = await _dio.get('/prediction/', queryParameters: {
      'horizon': horizon,
    });
    return response.data;
  }

  // 获取预测准确率
  Future<Map<String, dynamic>> getPredictionAccuracy() async {
    final response = await _dio.get('/prediction/accuracy');
    return response.data;
  }

  // 获取预警列表
  Future<List<dynamic>> getAlerts({int? isRead}) async {
    final response = await _dio.get('/prediction/alerts', queryParameters: {
      if (isRead != null) 'is_read': isRead,
    });
    return response.data;
  }

  // 标记预警已读
  Future<void> markAlertRead(int alertId) async {
    await _dio.put('/prediction/alerts/$alertId/read');
  }

  // 获取日报告
  Future<Map<String, dynamic>> getDailyReport({DateTime? date}) async {
    final response = await _dio.get('/report/daily', queryParameters: {
      if (date != null) 'report_date': date.toIso8601String().split('T')[0],
    });
    return response.data;
  }

  // 获取周报告
  Future<Map<String, dynamic>> getWeeklyReport({DateTime? startDate}) async {
    final response = await _dio.get('/report/weekly', queryParameters: {
      if (startDate != null) 'start_date': startDate.toIso8601String().split('T')[0],
    });
    return response.data;
  }

  // 获取月报告
  Future<Map<String, dynamic>> getMonthlyReport({int? year, int? month}) async {
    final response = await _dio.get('/report/monthly', queryParameters: {
      if (year != null) 'year': year,
      if (month != null) 'month': month,
    });
    return response.data;
  }

  // 导出CSV
  Future<Response> exportCsv({
    required DateTime start,
    required DateTime end,
    required String dataType,
  }) async {
    final response = await _dio.get(
      '/report/export/csv',
      queryParameters: {
        'start': start.toIso8601String().split('T')[0],
        'end': end.toIso8601String().split('T')[0],
        'data_type': dataType,
      },
      options: Options(responseType: ResponseType.bytes),
    );
    return response;
  }

  // 获取同步状态
  Future<List<dynamic>> getSyncStatus() async {
    final response = await _dio.get('/health/sync-status');
    return response.data;
  }
}
