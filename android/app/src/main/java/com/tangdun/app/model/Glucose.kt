package com.tangdun.app.model

import com.google.gson.annotations.SerializedName

data class GlucoseRecord(
    val id: Int = 0,
    val timestamp: String = "",
    val value: Double = 0.0,
    val trend: String? = null,
    val source: String = "cgm"
)

data class GlucoseStats(
    @SerializedName("avg_glucose") val avgGlucose: Double = 0.0,
    @SerializedName("min_glucose") val minGlucose: Double = 0.0,
    @SerializedName("max_glucose") val maxGlucose: Double = 0.0,
    @SerializedName("std_glucose") val stdGlucose: Double = 0.0,
    val tir: Double = 0.0,
    @SerializedName("tir_low") val tirLow: Double = 0.0,
    @SerializedName("tir_high") val tirHigh: Double = 0.0,
    val count: Int = 0
)

data class GlucoseTrend(
    @SerializedName("current_value") val currentValue: Double = 0.0,
    val trend: String = "stable",
    @SerializedName("change_30min") val change30min: Double = 0.0,
    @SerializedName("change_60min") val change60min: Double = 0.0,
    @SerializedName("slope_30min") val slope30min: Double = 0.0,
    @SerializedName("slope_60min") val slope60min: Double = 0.0
)

data class Alert(
    val id: Int = 0,
    @SerializedName("alert_type") val alertType: String = "",
    val severity: String = "warning",
    @SerializedName("glucose_value") val glucoseValue: Double? = null,
    @SerializedName("predicted_value") val predictedValue: Double? = null,
    val message: String? = null,
    @SerializedName("is_read") val isRead: Int = 0,
    @SerializedName("created_at") val createdAt: String = ""
)
