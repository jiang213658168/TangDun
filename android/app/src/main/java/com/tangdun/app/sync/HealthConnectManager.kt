package com.tangdun.app.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import android.util.Log
import java.time.temporal.ChronoUnit

class HealthConnectManager(private val context: Context) {
    companion object { private const val TAG = "HealthConnect" }

    private val healthConnectClient = HealthConnectClient.getOrCreate(context)

    /**
     * 读取血糖数据
     *
     * 从Health Connect读取CGM或指尖血糖数据
     * 数据来源：Dexcom、Libre、指尖血糖仪等
     */
    suspend fun readBloodGlucose(startTime: Instant, endTime: Instant): List<Map<String, Any>> {
        return try {
            val request = ReadRecordsRequest(
                recordType = BloodGlucoseRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val response = healthConnectClient.readRecords(request)
            response.records.map { record ->
                mapOf(
                    "timestamp" to record.time.toEpochMilli(),
                    "glucose" to record.level.inMilligramsPerDeciliter,  // mg/dL
                    "glucose_mmol" to record.level.inMillimolesPerLiter,  // mmol/L
                    "source" to "health_connect",
                    "type" to "blood_glucose"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取健康数据失败: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun readHeartRate(startTime: Instant, endTime: Instant): List<Map<String, Any>> {
        val request = ReadRecordsRequest(
            recordType = HeartRateRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
        )
        val response = healthConnectClient.readRecords(request)
        return response.records.flatMap { record ->
            record.samples.map { sample ->
                mapOf(
                    "timestamp" to sample.time.toEpochMilli(),
                    "heart_rate" to sample.beatsPerMinute,
                    "type" to "heart_rate"
                )
            }
        }
    }

    suspend fun readSteps(startTime: Instant, endTime: Instant): List<Map<String, Any>> {
        val request = ReadRecordsRequest(
            recordType = StepsRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
        )
        val response = healthConnectClient.readRecords(request)
        return response.records.map { record ->
            mapOf(
                "timestamp" to record.startTime.toEpochMilli(),
                "steps" to record.count,
                "type" to "steps"
            )
        }
    }

    suspend fun readExercise(startTime: Instant, endTime: Instant): List<Map<String, Any>> {
        val request = ReadRecordsRequest(
            recordType = ExerciseSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
        )
        val response = healthConnectClient.readRecords(request)
        return response.records.map { record ->
            mapOf(
                "start_time" to record.startTime.toEpochMilli(),
                "end_time" to record.endTime.toEpochMilli(),
                "exercise_type" to record.exerciseType,
                "duration_min" to ChronoUnit.MINUTES.between(record.startTime, record.endTime),
                "type" to "exercise"
            )
        }
    }

    suspend fun readSleep(startTime: Instant, endTime: Instant): List<Map<String, Any>> {
        val request = ReadRecordsRequest(
            recordType = SleepSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
        )
        val response = healthConnectClient.readRecords(request)
        return response.records.map { record ->
            mapOf(
                "start_time" to record.startTime.toEpochMilli(),
                "end_time" to record.endTime.toEpochMilli(),
                "duration_min" to ChronoUnit.MINUTES.between(record.startTime, record.endTime),
                "type" to "sleep"
            )
        }
    }

    suspend fun readAllData(startTime: Instant, endTime: Instant): Map<String, List<Map<String, Any>>> {
        return mapOf(
            "blood_glucose" to readBloodGlucose(startTime, endTime),
            "heart_rate" to readHeartRate(startTime, endTime),
            "steps" to readSteps(startTime, endTime),
            "exercise" to readExercise(startTime, endTime),
            "sleep" to readSleep(startTime, endTime)
        )
    }
}
