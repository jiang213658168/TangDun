package com.tangdun.app.ai

import java.util.UUID

/**
 * AI 助手权限意图模型
 *
 * 设计目标: 让 AI 能像人一样操作 app 的所有功能
 * 支持 8 种操作类型,覆盖 app 内 10 种记录类型 + 导航 + 配置 + 数据导入导出
 */

// 8 种操作类型
enum class AIIntentType {
    CREATE,      // 创建记录 (10 种类型)
    READ,        // 查询记录 + 统计
    UPDATE,      // 修改记录
    DELETE,      // 删除单条
    BULK_DELETE, // 批量删除 (今天/本周/某类型全部)
    NAVIGATE,    // 跳转页面
    CONFIGURE,   // 修改设置
    EXPORT,      // 导出数据
    IMPORT,      // 导入数据
}

// 10 种记录类型 + 特殊目标
object AITarget {
    const val GLUCOSE = "glucose"          // 血糖
    const val INSULIN = "insulin"          // 胰岛素
    const val MEAL = "meal"               // 饮食
    const val EXERCISE = "exercise"        // 运动
    const val SLEEP = "sleep"             // 睡眠
    const val BP = "bp"                   // 血压
    const val WEIGHT = "weight"           // 体重
    const val KETONE = "ketone"           // 酮体
    const val MEDICATION = "medication"   // 用药
    const val SYMPTOM = "symptom"         // 症状
    const val SETTINGS = "settings"       // 设置
    const val PAGE = "page"               // 页面导航
}

// 单个意图
data class AIIntent(
    val id: String = UUID.randomUUID().toString(),
    val type: AIIntentType,
    val target: String,                  // glucose/insulin/.../page/settings
    val action: String,                  // create/update/delete/query/navigate/configure/set...
    val params: Map<String, Any> = emptyMap(),
    val description: String,             // 给用户看的中文描述
    val requiresConfirmation: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** 把意图序列化成 JSON (用于 AI 输出协议) */
    fun toJson(): String {
        val obj = org.json.JSONObject()
        obj.put("intent_id", id)
        obj.put("type", type.name)
        obj.put("target", target)
        obj.put("action", action)
        val paramsObj = org.json.JSONObject()
        params.forEach { (k, v) -> paramsObj.put(k, v) }
        obj.put("params", paramsObj)
        obj.put("description", description)
        obj.put("requires_confirmation", requiresConfirmation)
        return obj.toString()
    }
}

// 执行结果
data class AIExecutionResult(
    val intentId: String = UUID.randomUUID().toString(),
    val success: Boolean,
    val message: String,                  // 给用户看的消息
    val data: Any? = null,                // 查询结果数据
    val affectedIds: List<Long> = emptyList(),
    val navigateTo: String? = null,       // 导航目标
    val refreshDataSources: List<String> = emptyList(),  // 需要刷新的数据源 (glucose/meal/...)
    val timestamp: Long = System.currentTimeMillis()
)