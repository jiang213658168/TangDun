// flutter/lib/widgets/food_card.dart
// 食物卡片组件

import 'package:flutter/material.dart';

class FoodCard extends StatelessWidget {
  final String name;
  final double carbs;
  final double calories;
  final double gi;
  final double portion;
  final double? confidence;
  final VoidCallback? onTap;

  const FoodCard({
    Key? key,
    required this.name,
    required this.carbs,
    required this.calories,
    required this.gi,
    required this.portion,
    this.confidence,
    this.onTap,
  }) : super(key: key);

  Color _getGiColor(double gi) {
    if (gi <= 55) {
      return Colors.green;
    } else if (gi <= 69) {
      return Colors.orange;
    } else {
      return Colors.red;
    }
  }

  String _getGiLabel(double gi) {
    if (gi <= 55) {
      return '低GI';
    } else if (gi <= 69) {
      return '中GI';
    } else {
      return '高GI';
    }
  }

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: EdgeInsets.only(bottom: 8),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: EdgeInsets.all(12),
          child: Row(
            children: [
              // 食物图片占位
              Container(
                width: 60,
                height: 60,
                decoration: BoxDecoration(
                  color: Colors.grey[200],
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Icon(
                  Icons.restaurant,
                  color: Colors.grey[400],
                ),
              ),
              SizedBox(width: 12),
              // 食物信息
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          name,
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        Container(
                          padding: EdgeInsets.symmetric(
                            horizontal: 8,
                            vertical: 2,
                          ),
                          decoration: BoxDecoration(
                            color: _getGiColor(gi).withOpacity(0.1),
                            borderRadius: BorderRadius.circular(12),
                            border: Border.all(
                              color: _getGiColor(gi),
                            ),
                          ),
                          child: Text(
                            '${_getGiLabel(gi)} $gi',
                            style: TextStyle(
                              fontSize: 10,
                              color: _getGiColor(gi),
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ),
                      ],
                    ),
                    SizedBox(height: 4),
                    Text(
                      '${portion.toStringAsFixed(0)}g',
                      style: TextStyle(
                        fontSize: 12,
                        color: Colors.grey[600],
                      ),
                    ),
                    SizedBox(height: 4),
                    Row(
                      children: [
                        _buildNutrient('碳水', '${carbs.toStringAsFixed(1)}g'),
                        SizedBox(width: 12),
                        _buildNutrient('热量', '${calories.toStringAsFixed(0)}kcal'),
                      ],
                    ),
                    if (confidence != null)
                      Padding(
                        padding: EdgeInsets.only(top: 4),
                        child: Text(
                          '识别置信度: ${(confidence! * 100).toStringAsFixed(0)}%',
                          style: TextStyle(
                            fontSize: 10,
                            color: Colors.grey[500],
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildNutrient(String label, String value) {
    return Row(
      children: [
        Text(
          '$label: ',
          style: TextStyle(
            fontSize: 12,
            color: Colors.grey[600],
          ),
        ),
        Text(
          value,
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.bold,
          ),
        ),
      ],
    );
  }
}
