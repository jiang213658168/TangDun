// flutter/lib/services/storage_service.dart
// 本地存储服务

import 'dart:convert';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class StorageService {
  static final StorageService _instance = StorageService._internal();
  factory StorageService() => _instance;

  final _storage = FlutterSecureStorage();

  StorageService._internal();

  // 存储字符串
  Future<void> setString(String key, String value) async {
    await _storage.write(key: key, value: value);
  }

  // 读取字符串
  Future<String?> getString(String key) async {
    return await _storage.read(key: key);
  }

  // 存储整数
  Future<void> setInt(String key, int value) async {
    await _storage.write(key: key, value: value.toString());
  }

  // 读取整数
  Future<int?> getInt(String key) async {
    final value = await _storage.read(key: key);
    return value != null ? int.tryParse(value) : null;
  }

  // 存储浮点数
  Future<void> setDouble(String key, double value) async {
    await _storage.write(key: key, value: value.toString());
  }

  // 读取浮点数
  Future<double?> getDouble(String key) async {
    final value = await _storage.read(key: key);
    return value != null ? double.tryParse(value) : null;
  }

  // 存储布尔值
  Future<void> setBool(String key, bool value) async {
    await _storage.write(key: key, value: value.toString());
  }

  // 读取布尔值
  Future<bool?> getBool(String key) async {
    final value = await _storage.read(key: key);
    return value != null ? value.toLowerCase() == 'true' : null;
  }

  // 存储JSON对象
  Future<void> setJson(String key, Map<String, dynamic> value) async {
    await _storage.write(key: key, value: jsonEncode(value));
  }

  // 读取JSON对象
  Future<Map<String, dynamic>?> getJson(String key) async {
    final value = await _storage.read(key: key);
    if (value == null) return null;
    try {
      return jsonDecode(value) as Map<String, dynamic>;
    } catch (e) {
      return null;
    }
  }

  // 存储JSON数组
  Future<void> setJsonList(String key, List<dynamic> value) async {
    await _storage.write(key: key, value: jsonEncode(value));
  }

  // 读取JSON数组
  Future<List<dynamic>?> getJsonList(String key) async {
    final value = await _storage.read(key: key);
    if (value == null) return null;
    try {
      return jsonDecode(value) as List<dynamic>;
    } catch (e) {
      return null;
    }
  }

  // 删除指定key
  Future<void> remove(String key) async {
    await _storage.delete(key: key);
  }

  // 清空所有存储
  Future<void> clear() async {
    await _storage.deleteAll();
  }

  // 检查key是否存在
  Future<bool> containsKey(String key) async {
    return await _storage.containsKey(key: key);
  }

  // 获取所有key
  Future<Map<String, String>> getAll() async {
    return await _storage.readAll();
  }
}
