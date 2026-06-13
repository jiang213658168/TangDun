// flutter/lib/services/auth_service.dart
// 登录鉴权服务

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'api_service.dart';

class AuthService {
  static final AuthService _instance = AuthService._internal();
  factory AuthService() => _instance;

  final _storage = FlutterSecureStorage();
  final _apiService = ApiService();

  AuthService._internal();

  // 当前用户ID
  String? _currentUserId;

  String? get currentUserId => _currentUserId;

  // 检查是否已登录
  Future<bool> isLoggedIn() async {
    final token = await _storage.read(key: 'jwt_token');
    return token != null && token.isNotEmpty;
  }

  // 登录
  Future<bool> login(String openid) async {
    try {
      final token = await _apiService.login(openid);
      _currentUserId = openid;
      return true;
    } catch (e) {
      print('登录失败: $e');
      return false;
    }
  }

  // 登出
  Future<void> logout() async {
    await _storage.delete(key: 'jwt_token');
    _currentUserId = null;
  }

  // 获取Token
  Future<String?> getToken() async {
    return await _storage.read(key: 'jwt_token');
  }

  // 设备码登录 (Android同步App使用)
  Future<bool> deviceLogin(String deviceId) async {
    try {
      final token = await _apiService.login(deviceId);
      _currentUserId = deviceId;
      return true;
    } catch (e) {
      print('设备登录失败: $e');
      return false;
    }
  }
}
