// flutter/lib/screens/meal/what_if_screen.dart
// What-if模拟器页面

import 'package:flutter/material.dart';
import '../../services/api_service.dart';
import '../../widgets/prediction_chart.dart';

class WhatIfScreen extends StatefulWidget {
  @override
  _WhatIfScreenState createState() => _WhatIfScreenState();
}

class _WhatIfScreenState extends State<WhatIfScreen> {
  final _apiService = ApiService();

  // 选择的食物
  List<Map<String, dynamic>> selectedFoods = [];

  // 模拟结果
  Map<String, dynamic>? simulationResult;
  bool isLoading = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('What-if 模拟'),
      ),
      body: SingleChildScrollView(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 食物选择
            _buildFoodSelection(),

            SizedBox(height: 16),

            // 模拟按钮
            SizedBox(
              width: double.infinity,
              child: ElevatedButton(
                onPressed: selectedFoods.isNotEmpty ? _runSimulation : null,
                child: isLoading
                    ? CircularProgressIndicator(color: Colors.white)
                    : Text('模拟血糖变化'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Color(0xFF007A8C),
                  foregroundColor: Colors.white,
                  padding: EdgeInsets.symmetric(vertical: 16),
                ),
              ),
            ),

            SizedBox(height: 16),

            // 模拟结果
            if (simulationResult != null) _buildSimulationResult(),
          ],
        ),
      ),
    );
  }

  Widget _buildFoodSelection() {
    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '选择食物',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
            ),
            SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: [
                _buildFoodChip('白米饭', 51.8, 83),
                _buildFoodChip('面条', 62.5, 81),
                _buildFoodChip('糙米饭', 47.0, 55),
                _buildFoodChip('全麦面包', 41.0, 51),
                _buildFoodChip('红薯', 20.1, 77),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildFoodChip(String name, double carbs, double gi) {
    final isSelected = selectedFoods.any((f) => f['name'] == name);

    return FilterChip(
      label: Text(name),
      selected: isSelected,
      onSelected: (selected) {
        setState(() {
          if (selected) {
            selectedFoods.add({
              'name': name,
              'carbs': carbs,
              'gi': gi,
              'portion': 200,
            });
          } else {
            selectedFoods.removeWhere((f) => f['name'] == name);
          }
        });
      },
      selectedColor: Color(0xFF007A8C).withOpacity(0.2),
      checkmarkColor: Color(0xFF007A8C),
    );
  }

  Widget _buildSimulationResult() {
    return Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '模拟结果',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            SizedBox(height: 16),

            // 预测峰值
            Row(
              children: [
                Icon(Icons.trending_up, color: Colors.orange),
                SizedBox(width: 8),
                Text('预测峰值: '),
                Text(
                  '${simulationResult!['predicted_peak'].toStringAsFixed(1)} mmol/L',
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                    color: Colors.orange,
                  ),
                ),
              ],
            ),

            SizedBox(height: 8),

            // 达峰时间
            Row(
              children: [
                Icon(Icons.timer, color: Colors.grey),
                SizedBox(width: 8),
                Text('达峰时间: ${simulationResult!['peak_time']} 分钟'),
              ],
            ),

            SizedBox(height: 16),

            // 血糖曲线图
            Container(
              height: 200,
              child: PredictionChart(
                currentGlucose: 6.0,
                predictions: {
                  'horizon_30': {
                    'value': simulationResult!['predicted_peak'] * 0.8,
                    'upper': simulationResult!['predicted_peak'] * 0.9,
                    'lower': simulationResult!['predicted_peak'] * 0.7,
                  },
                  'horizon_60': {
                    'value': simulationResult!['predicted_peak'],
                    'upper': simulationResult!['predicted_peak'] * 1.1,
                    'lower': simulationResult!['predicted_peak'] * 0.9,
                  },
                  'horizon_90': {
                    'value': simulationResult!['predicted_peak'] * 0.9,
                    'upper': simulationResult!['predicted_peak'],
                    'lower': simulationResult!['predicted_peak'] * 0.8,
                  },
                },
                targetLow: 3.9,
                targetHigh: 10.0,
              ),
            ),

            SizedBox(height: 16),

            // 替代建议
            if (simulationResult!['alternatives'] != null &&
                (simulationResult!['alternatives'] as List).isNotEmpty)
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '替代建议',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                  ),
                  SizedBox(height: 8),
                  ...(simulationResult!['alternatives'] as List)
                      .map((alt) => ListTile(
                            leading: Icon(Icons.lightbulb, color: Colors.green),
                            title: Text(alt['suggestion']),
                            subtitle: Text(
                              '预计峰值降至 ${alt['expected_peak'].toStringAsFixed(1)} mmol/L',
                            ),
                          ))
                      .toList(),
                ],
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _runSimulation() async {
    setState(() {
      isLoading = true;
    });

    try {
      final result = await _apiService.whatIfSimulation({
        'foods': selectedFoods.map((f) => {
          'food_name': f['name'],
          'portion_grams': f['portion'] ?? 200,
          'carbs': f['carbs'],
          'gi': f['gi'],
        }).toList(),
        'current_glucose': 6.0,
      });

      setState(() {
        simulationResult = result;
      });
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('模拟失败: $e')),
      );
    } finally {
      setState(() {
        isLoading = false;
      });
    }
  }
}
