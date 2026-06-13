package com.tangdun.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 胰岛素记录实体
 */
@Entity(
    tableName = "insulin_record",
    indices = [Index(value = ["timestamp"])]
)
data class InsulinRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 注射时间 */
    val timestamp: Long,

    /** 胰岛素类型 */
    val insulinType: String,  // rapid/long/mixed

    /** 剂量 (单位U) */
    val doseUnits: Double,

    /** 注射部位 */
    val injectionSite: String? = null,  // abdomen/arm/thigh/buttock

    /** 关联的饮食记录ID */
    val mealId: Long? = null,

    /** 备注 */
    val notes: String? = null,

    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 胰岛素类型中文名 */
        fun getInsulinTypeName(type: String): String = when (type) {
            "rapid" -> "速效"
            "long" -> "长效"
            "mixed" -> "预混"
            else -> type
        }

        /** 注射部位中文名 */
        fun getInjectionSiteName(site: String?): String = when (site) {
            "abdomen" -> "腹部"
            "arm" -> "手臂"
            "thigh" -> "大腿"
            "buttock" -> "臀部"
            else -> "未指定"
        }

        /** 计算IOB（胰岛素活性）- 速效胰岛素3小时衰减模型 */
        fun calculateIOB(records: List<InsulinRecord>, currentTime: Long): Double {
            val rapidHalfLife = 55.0  // 速效胰岛素半衰期(分钟)
            val longHalfLife = 420.0  // 长效胰岛素半衰期(分钟)

            var totalIOB = 0.0

            for (record in records) {
                val minutesAgo = (currentTime - record.timestamp) / 60000.0
                if (minutesAgo < 0) continue

                val halfLife = when (record.insulinType) {
                    "rapid" -> rapidHalfLife
                    "long" -> longHalfLife
                    else -> rapidHalfLife
                }

                // 指数衰减模型
                val remainingFraction = Math.pow(0.5, minutesAgo / halfLife)
                val activeInsulin = record.doseUnits * remainingFraction

                // 速效胰岛素3小时后基本无活性
                if (record.insulinType == "rapid" && minutesAgo > 180) continue
                // 长效胰岛素24小时后基本无活性
                if (record.insulinType == "long" && minutesAgo > 1440) continue

                totalIOB += activeInsulin
            }

            return totalIOB
        }
    }
}
