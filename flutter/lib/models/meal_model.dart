// flutter/lib/models/meal_model.dart
// 饮食数据模型

class MealRecord {
  final int id;
  final int userId;
  final DateTime timestamp;
  final String? mealType;
  final String? imageUrl;
  final double totalCarbs;
  final double totalCalories;
  final double totalProtein;
  final double totalFat;
  final double totalFiber;
  final double avgGi;
  final List<MealItem> items;

  MealRecord({
    required this.id,
    required this.userId,
    required this.timestamp,
    this.mealType,
    this.imageUrl,
    this.totalCarbs = 0,
    this.totalCalories = 0,
    this.totalProtein = 0,
    this.totalFat = 0,
    this.totalFiber = 0,
    this.avgGi = 0,
    this.items = const [],
  });

  factory MealRecord.fromJson(Map<String, dynamic> json) {
    return MealRecord(
      id: json['id'],
      userId: json['user_id'],
      timestamp: DateTime.parse(json['timestamp']),
      mealType: json['meal_type'],
      imageUrl: json['image_url'],
      totalCarbs: json['total_carbs']?.toDouble() ?? 0,
      totalCalories: json['total_calories']?.toDouble() ?? 0,
      totalProtein: json['total_protein']?.toDouble() ?? 0,
      totalFat: json['total_fat']?.toDouble() ?? 0,
      totalFiber: json['total_fiber']?.toDouble() ?? 0,
      avgGi: json['avg_gi']?.toDouble() ?? 0,
      items: (json['items'] as List<dynamic>?)
              ?.map((item) => MealItem.fromJson(item))
              .toList() ??
          [],
    );
  }
}

class MealItem {
  final int id;
  final int mealId;
  final String foodName;
  final double portionGrams;
  final double carbs;
  final double calories;
  final double protein;
  final double fat;
  final double fiber;
  final double gi;
  final double? recognitionConfidence;

  MealItem({
    required this.id,
    required this.mealId,
    required this.foodName,
    required this.portionGrams,
    this.carbs = 0,
    this.calories = 0,
    this.protein = 0,
    this.fat = 0,
    this.fiber = 0,
    this.gi = 0,
    this.recognitionConfidence,
  });

  factory MealItem.fromJson(Map<String, dynamic> json) {
    return MealItem(
      id: json['id'],
      mealId: json['meal_id'],
      foodName: json['food_name'],
      portionGrams: json['portion_grams'].toDouble(),
      carbs: json['carbs']?.toDouble() ?? 0,
      calories: json['calories']?.toDouble() ?? 0,
      protein: json['protein']?.toDouble() ?? 0,
      fat: json['fat']?.toDouble() ?? 0,
      fiber: json['fiber']?.toDouble() ?? 0,
      gi: json['gi']?.toDouble() ?? 0,
      recognitionConfidence: json['recognition_confidence']?.toDouble(),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'food_name': foodName,
      'portion_grams': portionGrams,
      'carbs': carbs,
      'calories': calories,
      'protein': protein,
      'fat': fat,
      'fiber': fiber,
      'gi': gi,
      'recognition_confidence': recognitionConfidence,
    };
  }
}

class FoodNutrition {
  final String id;
  final String name;
  final String category;
  final double carbs;
  final double calories;
  final double protein;
  final double fat;
  final double fiber;
  final double gi;
  final String giLevel;

  FoodNutrition({
    required this.id,
    required this.name,
    required this.category,
    required this.carbs,
    required this.calories,
    required this.protein,
    required this.fat,
    required this.fiber,
    required this.gi,
    required this.giLevel,
  });

  factory FoodNutrition.fromJson(Map<String, dynamic> json) {
    return FoodNutrition(
      id: json['id'],
      name: json['name'],
      category: json['category'],
      carbs: json['carbs'].toDouble(),
      calories: json['calories'].toDouble(),
      protein: json['protein'].toDouble(),
      fat: json['fat'].toDouble(),
      fiber: json['fiber'].toDouble(),
      gi: json['gi'].toDouble(),
      giLevel: json['gi_level'],
    );
  }
}
