package com.tangdun.app.data.local.converter

import androidx.room.TypeConverter
import java.util.Date

/**
 * Room类型转换器
 */
class Converters {

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        return value?.joinToString(",")
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        return value?.split(",")?.filter { it.isNotEmpty() }
    }

    @TypeConverter
    fun fromDoubleList(value: List<Double>?): String? {
        return value?.joinToString(",")
    }

    @TypeConverter
    fun toDoubleList(value: String?): List<Double>? {
        return value?.split(",")?.filter { it.isNotEmpty() }?.map { it.toDouble() }
    }
}
