// flutter/lib/screens/meal/meal_screen.dart
// 饮食模块主页 - 真实API调用

import 'package:flutter/material.dart';
import '../../services/api_service.dart';

class MealScreen extends StatefulWidget {
  @override
  _MealScreenState createState() => _MealScreenState();
}

class _MealScreenState extends State<MealScreen> {
  final _apiService = ApiService();

  // 真实数据
  List<dynamic> mealRecords = [];
  double totalCarbs = 0;
  double targetCarbs = 200;
  bool isLoading = true;
  String? errorMessage;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() {
      isLoading = true;
      errorMessage = null;
    });

    try {
      final now = DateTime.now();
      final startOfDay = DateTime(now.year, now.month, now.day);

      final records = await _apiService.getMealRecords(
        start: startOfDay,
        limit: 50,
      );

      double carbs = 0;
      for (var record in records) {
        carbs += (record['total_carbs'] as num?)?.toDouble() ?? 0;
      }

      setState(() {
        mealRecords = records;
        totalCarbs = carbs;
        isLoading = false;
      });
    } catch (e) {
      setState(() {
        errorMessage = '加载数据失败: $e';
        isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('饮食管理')),
      body: isLoading
          ? Center(child: CircularProgressIndicator())
          : errorMessage != null
              ? Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(errorMessage!, style: TextStyle(color: Colors.red)),
                      SizedBox(height: 16),
                      ElevatedButton(onPressed: _loadData, child: Text('重试')),
                    ],
                  ),
                )
              : RefreshIndicator(
                  onRefresh: _loadData,
                  child: SingleChildScrollView(
                    padding: EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        _buildCarbCounter(),
                        SizedBox(height: 16),
                        _buildPhotoButton(),
                        SizedBox(height: 16),
                        _buildMealRecords(),
                      ],
                    ),
                  ),
                ),
    );
  }

  Widget _buildCarbCounter() {
    final progress = totalCarbs / targetCarbs;

    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('今日碳水摄入', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            SizedBox(height: 12),
            LinearProgressIndicator(
              value: progress.clamp(0, 1),
              backgroundColor: Colors.grey[200],
              valueColor: AlwaysStoppedAnimation<Color>(
                progress > 0.8 ? Colors.orange : Color(0xFF007A8C),
              ),
              minHeight: 10,
            ),
            SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '${totalCarbs.toStringAsFixed(0)}g',
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: Color(0xFF007A8C)),
                ),
                Text('目标: ${targetCarbs.toStringAsFixed(0)}g', style: TextStyle(fontSize: 14, color: Colors.grey[600])),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPhotoButton() {
    return SizedBox(
      width: double.infinity,
      child: ElevatedButton.icon(
        onPressed: () {
          Navigator.pushNamed(context, '/meal/record');
        },
        icon: Icon(Icons.camera_alt),
        label: Text('拍照记录饮食'),
        style: ElevatedButton.styleFrom(
          backgroundColor: Color(0xFF007A8C),
          foregroundColor: Colors.white,
          padding: EdgeInsets.symmetric(vertical: 16),
        ),
      ),
    );
  }

  Widget _buildMealRecords() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('今日饮食记录', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
        SizedBox(height: 12),
        if (mealRecords.isEmpty)
          Center(child: Text('暂无饮食记录', style: TextStyle(color: Colors.grey)))
        else
          ...mealRecords.map((meal) => _buildMealCard(meal)).toList(),
      ],
    );
  }

  Widget _buildMealCard(Map<String, dynamic> meal) {
    final items = meal['items'] as List<dynamic>? ?? [];
    final foodNames = items.map((item) => item['food_name'] as String).toList();

    return Card(
      margin: EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '${_getMealTypeName(meal['meal_type'])} - ${_formatTime(meal['timestamp'])}',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                ),
                Text(
                  '${(meal['total_carbs'] as num?)?.toStringAsFixed(0) ?? '0'}g 碳水',
                  style: TextStyle(fontSize: 14, color: Color(0xFF007A8C), fontWeight: FontWeight.bold),
                ),
              ],
            ),
            SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: foodNames
                  .map((name) => Chip(label: Text(name), backgroundColor: Colors.grey[100]))
                  .toList(),
            ),
            SizedBox(height: 8),
            Text(
              '热量: ${(meal['total_calories'] as num?)?.toStringAsFixed(0) ?? '0'} kcal',
              style: TextStyle(fontSize: 12, color: Colors.grey[600]),
            ),
          ],
        ),
      ),
    );
  }

  String _getMealTypeName(String? type) {
    switch (type) {
      case 'breakfast': return '早餐';
      case 'lunch': return '午餐';
      case 'dinner': return '晚餐';
      case 'snack': return '加餐';
      default: return '未知';
    }
  }

  String _formatTime(String? timestamp) {
    if (timestamp == null) return '';
    final dt = DateTime.parse(timestamp);
    return '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
  }
}
