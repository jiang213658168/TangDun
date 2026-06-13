// flutter/lib/screens/meal/meal_record_screen.dart
// 饮食记录页面 - 真实API调用
// 注意: 拍照功能需要image_picker插件，当前使用手动搜索

import 'package:flutter/material.dart';
import '../../services/api_service.dart';
import '../../widgets/food_card.dart';

class MealRecordScreen extends StatefulWidget {
  @override
  _MealRecordScreenState createState() => _MealRecordScreenState();
}

class _MealRecordScreenState extends State<MealRecordScreen> {
  final _apiService = ApiService();

  List<Map<String, dynamic>> recognitionResults = [];
  bool isLoading = false;
  String? errorMessage;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('记录饮食'),
        actions: [
          TextButton(
            onPressed: recognitionResults.isNotEmpty ? _saveMeal : null,
            child: Text('保存', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _buildSearchSection(),
            SizedBox(height: 16),
            if (isLoading)
              Center(child: CircularProgressIndicator())
            else if (errorMessage != null)
              Center(child: Text(errorMessage!, style: TextStyle(color: Colors.red)))
            else if (recognitionResults.isNotEmpty) ...[
              Text('搜索结果', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
              SizedBox(height: 8),
              ...recognitionResults.map((food) => FoodCard(
                name: food['name'],
                carbs: (food['carbs'] as num?)?.toDouble() ?? 0,
                calories: (food['calories'] as num?)?.toDouble() ?? 0,
                gi: (food['gi'] as num?)?.toDouble() ?? 0,
                portion: 100,
              )).toList(),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildSearchSection() {
    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('搜索食物', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            SizedBox(height: 8),
            TextField(
              decoration: InputDecoration(
                hintText: '输入食物名称，如：白米饭',
                suffixIcon: IconButton(
                  icon: Icon(Icons.search),
                  onPressed: () {
                    // 搜索逻辑在onSubmitted中
                  },
                ),
                border: OutlineInputBorder(),
              ),
              onSubmitted: (value) => _searchFood(value),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _searchFood(String keyword) async {
    if (keyword.isEmpty) return;

    setState(() {
      isLoading = true;
      errorMessage = null;
    });

    try {
      final results = await _apiService.searchFood(keyword);
      setState(() {
        recognitionResults = results.map((r) => {
          'name': r['name'],
          'carbs': r['carbs'],
          'calories': r['calories'],
          'gi': r['gi'],
          'protein': r['protein'],
          'fat': r['fat'],
          'fiber': r['fiber'],
        }).toList();
        isLoading = false;
      });
    } catch (e) {
      setState(() {
        errorMessage = '搜索失败: $e';
        isLoading = false;
      });
    }
  }

  Future<void> _saveMeal() async {
    try {
      final items = recognitionResults.map((food) => {
        'food_name': food['name'],
        'portion_grams': 100.0,
        'carbs': (food['carbs'] as num?)?.toDouble() ?? 0.0,
        'calories': (food['calories'] as num?)?.toDouble() ?? 0.0,
        'protein': (food['protein'] as num?)?.toDouble() ?? 0.0,
        'fat': (food['fat'] as num?)?.toDouble() ?? 0.0,
        'fiber': (food['fiber'] as num?)?.toDouble() ?? 0.0,
        'gi': (food['gi'] as num?)?.toDouble() ?? 0.0,
      }).toList();

      await _apiService.createMealRecord({
        'timestamp': DateTime.now().toIso8601String(),
        'items': items,
      });

      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('饮食记录已保存')),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('保存失败: $e')),
      );
    }
  }
}
