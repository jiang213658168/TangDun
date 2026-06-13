package com.tangdun.app.model

import com.google.gson.annotations.SerializedName

data class PredictionResult(
    @SerializedName("prediction_time") val predictionTime: String = "",
    @SerializedName("risk_level") val riskLevel: String = "normal",
    @SerializedName("model_type_name") val modelTypeName: String = "tcn_v2",
    @SerializedName("current_glucose") val currentGlucose: Double = 0.0,
    @SerializedName("predicted_5min") val predicted5min: Double? = null,
    @SerializedName("predicted_15min") val predicted15min: Double? = null,
    @SerializedName("predicted_30min") val predicted30min: Double? = null,
    @SerializedName("predicted_60min") val predicted60min: Double? = null,
    @SerializedName("predicted_120min") val predicted120min: Double? = null,
    val curve: List<Double> = emptyList(),
    @SerializedName("upper_bound") val upperBound: Double? = null,
    @SerializedName("lower_bound") val lowerBound: Double? = null
)

data class DailyReport(
    val date: String = "",
    val tir: Double = 0.0,
    @SerializedName("tir_low") val tirLow: Double = 0.0,
    @SerializedName("tir_high") val tirHigh: Double = 0.0,
    @SerializedName("avg_glucose") val avgGlucose: Double = 0.0,
    @SerializedName("min_glucose") val minGlucose: Double = 0.0,
    @SerializedName("max_glucose") val maxGlucose: Double = 0.0,
    @SerializedName("std_glucose") val stdGlucose: Double = 0.0,
    val gri: Double = 0.0,
    @SerializedName("total_carbs") val totalCarbs: Double = 0.0,
    @SerializedName("total_calories") val totalCalories: Double = 0.0,
    @SerializedName("total_steps") val totalSteps: Int = 0
)
