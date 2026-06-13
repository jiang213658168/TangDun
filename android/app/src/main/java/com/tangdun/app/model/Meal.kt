package com.tangdun.app.model

import com.google.gson.annotations.SerializedName

data class MealRecord(
    val id: Int = 0,
    @SerializedName("user_id") val userId: Int = 0,
    val timestamp: String = "",
    @SerializedName("meal_type") val mealType: String? = null,
    @SerializedName("total_carbs") val totalCarbs: Double = 0.0,
    @SerializedName("total_calories") val totalCalories: Double = 0.0,
    @SerializedName("total_protein") val totalProtein: Double = 0.0,
    @SerializedName("total_fat") val totalFat: Double = 0.0,
    @SerializedName("avg_gi") val avgGi: Double = 0.0,
    val items: List<MealItem> = emptyList(),
    @SerializedName("created_at") val createdAt: String = ""
)

data class MealItem(
    val id: Int = 0,
    @SerializedName("food_name") val foodName: String = "",
    @SerializedName("portion_grams") val portionGrams: Double = 0.0,
    val carbs: Double = 0.0,
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double = 0.0,
    val gi: Double = 0.0
)

data class FoodNutrition(
    val id: String = "",
    val name: String = "",
    val category: String = "",
    val carbs: Double = 0.0,
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double = 0.0,
    val gi: Double = 0.0,
    @SerializedName("gi_level") val giLevel: String = "medium"
)
