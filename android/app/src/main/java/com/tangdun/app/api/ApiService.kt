package com.tangdun.app.api

import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ===== 认证 =====
    @POST("auth/login")
    suspend fun login(@Query("openid") openid: String): Response<LoginResponse>

    @GET("auth/me")
    suspend fun getUserInfo(): Response<Map<String, Any>>

    // ===== 血糖 =====
    @GET("glucose/latest")
    suspend fun getLatestGlucose(): Response<Map<String, Any>>

    @GET("glucose/")
    suspend fun getGlucoseRecords(
        @Query("start") start: String? = null,
        @Query("end") end: String? = null,
        @Query("limit") limit: Int = 288
    ): Response<List<Map<String, Any>>>

    @GET("glucose/stats")
    suspend fun getGlucoseStats(
        @Query("start") start: String? = null,
        @Query("end") end: String? = null
    ): Response<Map<String, Any>>

    @GET("glucose/trend")
    suspend fun getGlucoseTrend(): Response<Map<String, Any>>

    // ===== 饮食 =====
    @POST("meal/")
    suspend fun createMealRecord(@Body data: Map<String, Any>): Response<Map<String, Any>>

    @GET("meal/")
    suspend fun getMealRecords(
        @Query("start") start: String? = null,
        @Query("end") end: String? = null,
        @Query("limit") limit: Int = 50
    ): Response<List<Map<String, Any>>>

    @GET("meal/nutrition/search/{keyword}")
    suspend fun searchFood(@Path("keyword") keyword: String): Response<List<Map<String, Any>>>

    @GET("meal/nutrition/{food_name}")
    suspend fun getFoodNutrition(@Path("food_name") foodName: String): Response<Map<String, Any>>

    @POST("meal/what-if")
    suspend fun whatIfSimulation(@Body data: Map<String, Any>): Response<Map<String, Any>>

    // ===== 运动 =====
    @GET("exercise/")
    suspend fun getExerciseRecords(
        @Query("start") start: String? = null,
        @Query("end") end: String? = null,
        @Query("limit") limit: Int = 50
    ): Response<List<Map<String, Any>>>

    @GET("exercise/stats")
    suspend fun getExerciseStats(
        @Query("start") start: String? = null,
        @Query("end") end: String? = null
    ): Response<Map<String, Any>>

    @GET("exercise/prescription")
    suspend fun getExercisePrescription(
        @Query("current_glucose") currentGlucose: Double? = null
    ): Response<Map<String, Any>>

    // ===== 预测 =====
    @GET("prediction/")
    suspend fun getPrediction(@Query("horizon") horizon: Int = 120): Response<Map<String, Any>>

    @GET("prediction/accuracy")
    suspend fun getPredictionAccuracy(): Response<Map<String, Any>>

    @GET("prediction/alerts")
    suspend fun getAlerts(@Query("is_read") isRead: Int? = null): Response<List<Map<String, Any>>>

    @PUT("prediction/alerts/{alert_id}/read")
    suspend fun markAlertRead(@Path("alert_id") alertId: Int): Response<Map<String, Any>>

    // ===== 报告 =====
    @GET("report/daily")
    suspend fun getDailyReport(@Query("report_date") date: String? = null): Response<Map<String, Any>>

    @GET("report/weekly")
    suspend fun getWeeklyReport(@Query("start_date") startDate: String? = null): Response<Map<String, Any>>

    @GET("report/monthly")
    suspend fun getMonthlyReport(
        @Query("year") year: Int? = null,
        @Query("month") month: Int? = null
    ): Response<Map<String, Any>>

    // ===== 健康同步 =====
    @POST("health/sync")
    suspend fun syncHealthData(@Body data: Map<String, Any>): Response<Map<String, Any>>
}

data class LoginResponse(
    val access_token: String,
    val token_type: String
)
