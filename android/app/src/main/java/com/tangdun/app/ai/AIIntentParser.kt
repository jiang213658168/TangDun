package com.tangdun.app.ai

import java.util.Calendar

/**
 * AI 意图解析器 - 把自然语言转换为 AIIntent 列表
 *
 * 完全本地化 (不依赖网络 AI):
 *  - CREATE: 识别"记录X / 加了X / 吃了X / 打了X / 睡了X 小时 / 体重X / 血压X/X"
 *  - READ: "今天血糖 / 这周体重 / 最近胰岛素"
 *  - NAVIGATE: "打开预测 / 去设置 / 看看AI助手"
 *  - CONFIGURE: "目标范围改成X / 个人信息设置"
 *  - BULK_DELETE: "删除今天血糖 / 清空所有饮食"
 *
 * 加上 AI 服务调用作为补充 (AI 输出 ```json {"action":"create",...}``` 指令)
 */
object AIIntentParser {

    // === CREATE 关键词模式 ===

    // ★ 数字: 支持中文 + 阿拉伯数字混合 (打了十个单位 / 打了 8 个单位)
    private val numPattern = "[\\d零一二三四五六七八九十百千万半]+(?:[.,点]\\d+)?"

    /**
     * ★ 中文数字 → Double (支持 十/百/千/万/半)
     * 例: "十" → 10.0, "十五" → 15.0, "半" → 0.5
     */
    private fun chineseNumToDouble(s: String): Double? {
        if (s.isEmpty()) return null
        // 阿拉伯数字直接 parse
        val pureDigits = s.replace(Regex("[.,点]"), ".")
        pureDigits.toDoubleOrNull()?.let { return it }

        var result = 0.0
        var section = 0.0  // 当前段 (万以下)
        var found = false

        for (c in s) {
            when (c) {
                '零' -> { section = 0.0 }
                '一' -> { section = 1.0; found = true }
                '二' -> { section = 2.0; found = true }
                '三' -> { section = 3.0; found = true }
                '四' -> { section = 4.0; found = true }
                '五' -> { section = 5.0; found = true }
                '六' -> { section = 6.0; found = true }
                '七' -> { section = 7.0; found = true }
                '八' -> { section = 8.0; found = true }
                '九' -> { section = 9.0; found = true }
                '十' -> { if (result == 0.0 && section == 0.0) section = 10.0 else section += 10.0; found = true }
                '百' -> { result += section * 100; section = 0.0 }
                '千' -> { result += section * 1000; section = 0.0 }
                '万' -> { result = (result + section) * 10000; section = 0.0 }
                '半' -> { return 0.5 }
            }
        }
        result += section
        return if (found) result else null
    }

    // 血糖: "血糖6.5 / 血糖6.5 mmol/L / 测了血糖6.5 / 血糖X (餐前/餐后)"
    private val glucoseRegex = Regex("血糖\\s*($numPattern)(?:\\s*(mmol/L|mmol))?(?:\\s*(餐前|餐后|空腹|睡前))?")

    // 胰岛素: "X个单位的[速效/长效/短效/预混]" - 复用 AiRecordHelper 已有逻辑
    private val insulinRegex = Regex("打了?\\s*($numPattern)\\s*个?\\s*单位\\s*[的]?\\s*(长效|速效|短效|预混|基础|基础胰岛素|胰岛素)?")

    // 运动: "跑步30分钟 / 散步了1小时 / 走路20分钟 / 骑车40min"
    private val exerciseRegex = Regex("(跑步|快走|散步了?|走路|骑车|骑行|游泳|健身|力量|瑜伽|跳绳|爬山|打球)\\s*($numPattern)\\s*(分钟|min|小时|h)?")

    // 睡眠: "睡了8小时 / 睡眠时长7小时 / 11点睡7点起"
    private val sleepRegex = Regex("睡了?\\s*($numPattern)\\s*(小时|h|hr)")

    // 血压: "血压120/80 / 血压 120 80 / 高压120低压80"
    private val bpRegex = Regex("(?:血压|高压|收缩压)\\s*($numPattern)\\s*[/／]\\s*($numPattern)")

    // 体重: "体重70kg / 体重 70.5 / 称了70公斤"
    private val weightRegex = Regex("(?:体重|称了)\\s*($numPattern)\\s*(?:kg|公斤|千克)?")

    // 酮体: "酮体0.5 / 血酮1.2"
    private val ketoneRegex = Regex("(?:酮体|血酮)\\s*($numPattern)")

    // 用药: "吃了二甲双胍500mg / 服用了格列美脲5mg"
    private val medicationRegex = Regex("(吃了?|服用了?|用了?)\\s*([\\u4e00-\\u9fa5]{2,10})\\s*($numPattern)\\s*(mg|g|毫克|克)?")

    // 症状: "我心慌头晕 / 出现低血糖症状"
    private val symptomRegex = Regex("(心慌|手抖|出汗|饥饿感|头晕|乏力|视物模糊|口渴|多尿|恶心|腹痛|呼吸急促|意识模糊)")

    // === NAVIGATE 关键词模式 ===

    private val navKeywords = mapOf(
        // 中文 → 路由 (路由名跟 MainActivity.Screen/SubScreen 对齐)
        "首页" to "home", "主页" to "home", "回到首页" to "home", "去首页" to "home",
        "记录" to "record", "记录页" to "record",
        "预测" to "prediction", "血糖预测" to "prediction", "预测页" to "prediction",
        "报告" to "report", "周报" to "report", "月报" to "report",
        "设置" to "settings", "设置页" to "settings", "偏好设置" to "settings",
        "AI助手" to "chat", "ai助手" to "chat", "AI 助手" to "chat",
        "饮食" to "meal", "饮食记录" to "meal",
        "胰岛素" to "insulin", "胰岛素记录" to "insulin",
        "运动" to "exercise", "运动记录" to "exercise", "运动管理" to "exercise",
        "健康" to "health", "健康记录" to "health",
        "血糖" to "glucose_list"  // 注: 血糖记录列表 = HomeScreen 当前页
    )

    // === CONFIGURE 关键词模式 ===

    private val configureKeywords = mapOf(
        "目标范围" to "target_range", "血糖目标" to "target_range",
        "AI配置" to "ai_config", "API Key" to "ai_config", "API key" to "ai_config",
        "个人信息" to "personal_info", "我的信息" to "personal_info", "体重设置" to "personal_info",
        "通知" to "notification", "通知监听" to "notification",
        "数据备份" to "data_backup", "备份" to "data_backup",
        "数据共享" to "data_share", "导出" to "data_share"
    )

    // === READ / QUERY 关键词模式 ===

    private val queryKeywords = mapOf(
        "血糖" to AITarget.GLUCOSE, "血糖记录" to AITarget.GLUCOSE,
        "饮食" to AITarget.MEAL, "吃了什么" to AITarget.MEAL,
        "胰岛素" to AITarget.INSULIN,
        "运动" to AITarget.EXERCISE,
        "睡眠" to AITarget.SLEEP, "睡了多久" to AITarget.SLEEP,
        "血压" to AITarget.BP,
        "体重" to AITarget.WEIGHT,
        "酮体" to AITarget.KETONE,
        "用药" to AITarget.MEDICATION, "药" to AITarget.MEDICATION,
        "症状" to AITarget.SYMPTOM
    )

    /**
     * 主入口 (同步, 纯本地规则解析) - 兼容旧代码
     */
    fun parse(input: String): List<AIIntent> = parseLocal(input)

    /**
     * ★ v2.8 异步入口: 优先调用 AI 大模型解析, 失败/未配回退到本地规则
     *
     * 工作流程:
     *  1. 如果 aiClient 已配置 → 调用大模型 → 解析 JSON → 返回意图列表
     *  2. AI 调用失败/超时/未配置 → 回退到本地 regex 解析
     */
    suspend fun parseAsync(input: String, aiClient: AIClient? = null): Pair<List<AIIntent>, ParseSource> {
        // 1. 优先尝试 AI 大模型
        if (aiClient != null && aiClient.isConfigured()) {
            val aiIntents = aiClient.parseIntentsWithAI(input)
            if (aiIntents != null && aiIntents.isNotEmpty()) {
                return aiIntents to ParseSource.AI
            }
        }
        // 2. 回退到本地规则
        val localIntents = parseLocal(input)
        return localIntents to ParseSource.LOCAL
    }

    /**
     * 本地规则解析 (纯 regex, 不依赖网络)
     */
    private fun parseLocal(input: String): List<AIIntent> {
        val intents = mutableListOf<AIIntent>()
        val lower = input.lowercase()
        val timeAnchors = extractTimeAnchors(input)  // 用于推断事件时间

        // 1. CREATE 类: 各种记录
        intents.addAll(parseCreateIntents(input, lower, timeAnchors))

        // 1b. CREATE 饮食: 复用 AiRecordHelper.localParse 的食物识别
        intents.addAll(parseMealIntentsFromLocalParse(input, timeAnchors))

        // 2. NAVIGATE 类: 跳转
        intents.addAll(parseNavigateIntents(input))

        // 3. CONFIGURE 类: 修改设置
        intents.addAll(parseConfigureIntents(input))

        // 4. QUERY 类: 查询
        intents.addAll(parseQueryIntents(input))

        // 5. BULK_DELETE 类: 批量删除
        intents.addAll(parseBulkDeleteIntents(input))

        return intents
    }

    /** 解析来源: AI 大模型 or 本地规则 */
    enum class ParseSource { AI, LOCAL }

    /**
     * 复用 AiRecordHelper 的本地解析, 提取饮食/胰岛素/血糖记录
     * 注: AiRecordHelper 内部已有时间锚点 + 食物营养表 + 份量识别, 这里直接调
     */
    private fun parseMealIntentsFromLocalParse(input: String, anchors: List<Pair<Int, Long>>): List<AIIntent> {
        val intents = mutableListOf<AIIntent>()
        try {
            val records = com.tangdun.app.ui.chat.AiRecordHelper.localParse(input)

            records.forEach { record ->
                when (record) {
                    is com.tangdun.app.ui.chat.ParsedRecord.Meal -> {
                        intents.add(AIIntent(
                            type = AIIntentType.CREATE,
                            target = AITarget.MEAL,
                            action = "create",
                            params = mapOf(
                                "food_name" to record.food,
                                "carbs" to record.carbs,
                                "calories" to record.calories,
                                "gi" to record.gi,
                                "meal_type" to record.mealType,
                                "protein" to record.protein,
                                "fat" to record.fat,
                                "timestamp" to record.timestamp
                            ),
                            description = "记录饮食: ${record.food} ${record.carbs.toInt()}g碳水"
                        ))
                    }
                    is com.tangdun.app.ui.chat.ParsedRecord.Insulin -> {
                        intents.add(AIIntent(
                            type = AIIntentType.CREATE,
                            target = AITarget.INSULIN,
                            action = "create",
                            params = mapOf(
                                "dose" to record.dose,
                                "dose_type" to record.doseType,
                                "timestamp" to record.timestamp
                            ),
                            description = "记录胰岛素: ${record.dose}U ${record.doseType}"
                        ))
                    }
                    is com.tangdun.app.ui.chat.ParsedRecord.Glucose -> {
                        intents.add(AIIntent(
                            type = AIIntentType.CREATE,
                            target = AITarget.GLUCOSE,
                            action = "create",
                            params = mapOf(
                                "value" to record.value,
                                "scene" to record.scene,
                                "timestamp" to record.timestamp
                            ),
                            description = "记录血糖: ${record.value} mmol/L"
                        ))
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            // AiRecordHelper.localParse 失败也不影响其他解析
        }
        return intents
    }

    /**
     * CREATE 意图 - 解析所有记录类型
     */
    private fun parseCreateIntents(
        input: String,
        lower: String,
        timeAnchors: List<Pair<Int, Long>>
    ): List<AIIntent> {
        val intents = mutableListOf<AIIntent>()

        // 血糖
        glucoseRegex.findAll(input).forEach { match ->
            val value = chineseNumToDouble(match.groupValues[1]) ?: return@forEach
            val scene = when (match.groupValues[3]) {
                "餐前" -> "before_meal"
                "餐后" -> "after_meal"
                "空腹" -> "fasting"
                "睡前" -> "bedtime"
                else -> "other"
            }
            val ts = nearestTime(match.range.first, timeAnchors)
            intents.add(AIIntent(
                type = AIIntentType.CREATE,
                target = AITarget.GLUCOSE,
                action = "create",
                params = mapOf("value" to value, "scene" to scene, "timestamp" to ts, "source" to "ai"),
                description = "记录血糖 ${"%.1f".format(value)} mmol/L (${displayScene(scene)})"
            ))
        }

        // 胰岛素
        insulinRegex.findAll(input).forEach { match ->
            val dose = chineseNumToDouble(match.groupValues[1]) ?: return@forEach
            val typeText = match.groupValues[2]
            val doseType = when (typeText) {
                "长效", "基础", "基础胰岛素" -> "long"
                "速效" -> "rapid"
                "短效" -> "short"
                "预混" -> "mixed"
                else -> "rapid"
            }
            val ts = nearestTime(match.range.first, timeAnchors)
            intents.add(AIIntent(
                type = AIIntentType.CREATE,
                target = AITarget.INSULIN,
                action = "create",
                params = mapOf("dose" to dose, "dose_type" to doseType, "timestamp" to ts),
                description = "记录胰岛素 ${"%.1f".format(dose)}U ($doseType)"
            ))
        }

        // 运动
        exerciseRegex.findAll(input).forEach { match ->
            val type = match.groupValues[1]
            val num = chineseNumToDouble(match.groupValues[2]) ?: return@forEach
            val unit = match.groupValues[3]
            val minutes = if (unit.contains("小时") || unit == "h") (num * 60).toInt() else num.toInt()
            val ts = nearestTime(match.range.first, timeAnchors)
            intents.add(AIIntent(
                type = AIIntentType.CREATE,
                target = AITarget.EXERCISE,
                action = "create",
                params = mapOf(
                    "exercise_type" to mapExerciseType(type),
                    "duration_min" to minutes,
                    "intensity" to "moderate",
                    "timestamp" to ts
                ),
                description = "记录运动: $type $minutes 分钟"
            ))
        }

        // 睡眠
        sleepRegex.findAll(input).forEach { match ->
            val hours = chineseNumToDouble(match.groupValues[1]) ?: return@forEach
            val ts = nearestTime(match.range.first, timeAnchors)
            intents.add(AIIntent(
                type = AIIntentType.CREATE,
                target = AITarget.SLEEP,
                action = "create",
                params = mapOf(
                    "duration_minutes" to (hours * 60).toInt(),
                    "timestamp" to ts
                ),
                description = "记录睡眠 ${"%.1f".format(hours)} 小时"
            ))
        }

        // 血压
        bpRegex.findAll(input).forEach { match ->
            val systolic = chineseNumToDouble(match.groupValues[1])?.toInt() ?: return@forEach
            val diastolic = chineseNumToDouble(match.groupValues[2])?.toInt() ?: return@forEach
            val ts = nearestTime(match.range.first, timeAnchors)
            intents.add(AIIntent(
                type = AIIntentType.CREATE,
                target = AITarget.BP,
                action = "create",
                params = mapOf(
                    "systolic" to systolic,
                    "diastolic" to diastolic,
                    "timestamp" to ts
                ),
                description = "记录血压 $systolic/$diastolic mmHg"
            ))
        }

        // 体重
        weightRegex.findAll(input).forEach { match ->
            val weight = chineseNumToDouble(match.groupValues[1]) ?: return@forEach
            val ts = nearestTime(match.range.first, timeAnchors)
            intents.add(AIIntent(
                type = AIIntentType.CREATE,
                target = AITarget.WEIGHT,
                action = "create",
                params = mapOf("weight_kg" to weight, "timestamp" to ts),
                description = "记录体重 ${"%.1f".format(weight)} kg"
            ))
        }

        // 酮体
        ketoneRegex.findAll(input).forEach { match ->
            val level = chineseNumToDouble(match.groupValues[1]) ?: return@forEach
            val ts = nearestTime(match.range.first, timeAnchors)
            intents.add(AIIntent(
                type = AIIntentType.CREATE,
                target = AITarget.KETONE,
                action = "create",
                params = mapOf("ketone_level" to level, "timestamp" to ts),
                description = "记录酮体 ${"%.1f".format(level)} mmol/L"
            ))
        }

        // 用药
        medicationRegex.findAll(input).forEach { match ->
            val name = match.groupValues[2]
            val dose = match.groupValues[3]
            val unit = match.groupValues[4]
            val fullDose = if (unit.isNotBlank()) "$dose$unit" else dose
            val ts = nearestTime(match.range.first, timeAnchors)
            intents.add(AIIntent(
                type = AIIntentType.CREATE,
                target = AITarget.MEDICATION,
                action = "create",
                params = mapOf(
                    "medication_name" to name,
                    "dose" to fullDose,
                    "timestamp" to ts
                ),
                description = "记录用药: $name $fullDose"
            ))
        }

        // 症状 (收集所有匹配的症状)
        val symptoms = mutableListOf<String>()
        symptomRegex.findAll(input).forEach { symptoms.add(it.value) }
        if (symptoms.isNotEmpty()) {
            val ts = nearestTime(input.indexOf(symptoms.first()), timeAnchors)
            intents.add(AIIntent(
                type = AIIntentType.CREATE,
                target = AITarget.SYMPTOM,
                action = "create",
                params = mapOf(
                    "symptoms" to symptoms.joinToString(","),
                    "severity" to "mild",
                    "timestamp" to ts
                ),
                description = "记录症状: ${symptoms.joinToString("、")}"
            ))
        }

        // 饮食 (复用 AiRecordHelper 已有的逻辑, 这里简化)
        // 复杂的食物识别由 AiRecordHelper.localParse 处理
        // 这里只识别非常明显的"吃了X" + 食物名

        return intents
    }

    /**
     * NAVIGATE 意图 - 跳转页面
     */
    private fun parseNavigateIntents(input: String): List<AIIntent> {
        val intents = mutableListOf<AIIntent>()
        // 匹配: "打开X / 去X / 跳到X / 看看X / X页"
        val navRegex = Regex("(?:打开|去|跳到|看看|进入|显示)\\s*([\\u4e00-\\u9fa5a-zA-Z0-9]+(?:页|记录|助手)?)")
        navRegex.findAll(input).forEach { match ->
            val key = match.groupValues[1]
            val route = navKeywords[key] ?: navKeywords.entries.find { key.contains(it.key) }?.value
            if (route != null) {
                intents.add(AIIntent(
                    type = AIIntentType.NAVIGATE,
                    target = AITarget.PAGE,
                    action = "navigate",
                    params = mapOf("route" to route),
                    description = "跳转到 $key",
                    requiresConfirmation = false  // 导航不需要确认
                ))
            }
        }
        return intents
    }

    /**
     * CONFIGURE 意图 - 修改设置
     */
    private fun parseConfigureIntents(input: String): List<AIIntent> {
        val intents = mutableListOf<AIIntent>()
        configureKeywords.entries.forEach { (key, action) ->
            if (input.contains(key)) {
                intents.add(AIIntent(
                    type = AIIntentType.CONFIGURE,
                    target = AITarget.SETTINGS,
                    action = action,
                    params = mapOf("source_input" to input),
                    description = "修改设置: $key",
                    requiresConfirmation = false
                ))
            }
        }
        return intents
    }

    /**
     * QUERY 意图 - 查询数据
     */
    private fun parseQueryIntents(input: String): List<AIIntent> {
        val intents = mutableListOf<AIIntent>()
        // 匹配: "今天/昨天/最近/本周 + 关键词"
        val queryRegex = Regex("(今天|昨天|最近|本周|这周|上周|本月|这个月)?\\s*(血糖|饮食|胰岛素|运动|睡眠|血压|体重|酮体|用药|症状)\\s*(记录|数据|情况|趋势|怎么样|多少|平均|最高|最低|什么)?")
        queryRegex.findAll(input).forEach { match ->
            val timeScope = match.groupValues[1].ifBlank { "today" }
            val target = match.groupValues[2]
            val targetKey = queryKeywords.entries.find { target == it.key || it.key.contains(target) }?.key
            if (targetKey != null) {
                intents.add(AIIntent(
                    type = AIIntentType.READ,
                    target = queryKeywords[targetKey]!!,
                    action = "query",
                    params = mapOf("time_scope" to timeScope),
                    description = "查询 $timeScope $target 记录"
                ))
            }
        }
        return intents
    }

    /**
     * BULK_DELETE 意图 - 批量删除
     */
    private fun parseBulkDeleteIntents(input: String): List<AIIntent> {
        val intents = mutableListOf<AIIntent>()
        // 匹配: "删除/清空 + 时间 + 类型"
        val regex = Regex("(?:删除|清空|抹掉|丢掉)\\s*(今天|昨天|最近|本周|所有|全部)?\\s*(血糖|饮食|胰岛素|运动|睡眠|血压|体重|酮体|用药|症状)\\s*(记录|数据)?")
        regex.findAll(input).forEach { match ->
            val timeScope = match.groupValues[1].ifBlank { "today" }
            val targetText = match.groupValues[2]
            val targetKey = queryKeywords.entries.find { targetText == it.key }?.key
            if (targetKey != null) {
                intents.add(AIIntent(
                    type = AIIntentType.BULK_DELETE,
                    target = queryKeywords[targetKey]!!,
                    action = "bulk_delete",
                    params = mapOf("time_scope" to timeScope),
                    description = "删除 $timeScope $targetText 记录"
                ))
            }
        }
        return intents
    }

    // === 辅助函数 ===

    private fun displayScene(scene: String): String = when (scene) {
        "before_meal" -> "餐前"
        "after_meal" -> "餐后"
        "fasting" -> "空腹"
        "bedtime" -> "睡前"
        else -> "其他"
    }

    private fun mapExerciseType(type: String): String = when (type) {
        "跑步" -> "running"
        "快走" -> "walking"
        "散步", "走路" -> "walking"
        "骑车", "骑行" -> "cycling"
        "游泳" -> "swimming"
        "健身", "力量" -> "strength"
        "瑜伽" -> "yoga"
        "跳绳" -> "jumping_rope"
        "爬山" -> "hiking"
        "打球" -> "ball_sports"
        else -> "other"
    }

    /** 提取时间锚点 (同 AiRecordHelper 逻辑) */
    private fun extractTimeAnchors(input: String): List<Pair<Int, Long>> {
        val anchors = mutableListOf<Pair<Int, Long>>()
        val cal = Calendar.getInstance()
        val todayStart = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val patterns = listOf(
            Regex("(?<![\\d])(\\d{1,2}):(\\d{2})(?![\\d])") to { h: Int, m: Int -> h to m },
            Regex("(?<![\\d])(\\d{1,2})点半(?![\\d])") to { h: Int, _: Int -> h to 30 },
            Regex("(?<![\\d])(\\d{1,2})点(\\d{1,2})分(?![\\d])") to { h: Int, m: Int -> h to m },
            Regex("(?<![\\d])(\\d{1,2})点整?(?![\\d分半])") to { h: Int, _: Int -> h to 0 },
            Regex("(?<![\\d])(零点|0点)(?![\\d])") to { _: Int, _: Int -> 0 to 0 }
        )

        patterns.forEach { (regex, _) ->
            regex.findAll(input).forEach { match ->
                val pos = match.range.first
                var hour = -1
                var minute = 0
                when {
                    match.value.contains(":") -> {
                        hour = match.groupValues[1].toInt()
                        minute = match.groupValues[2].toInt()
                    }
                    match.value.contains("点半") -> {
                        hour = match.groupValues[1].toInt(); minute = 30
                    }
                    match.value.contains("点") && match.value.contains("分") -> {
                        hour = match.groupValues[1].toInt(); minute = match.groupValues[2].toInt()
                    }
                    match.value == "零点" || match.value == "0点" -> { hour = 0; minute = 0 }
                    match.value.contains("点") -> {
                        hour = match.groupValues[1].toInt(); minute = 0
                    }
                }
                if (hour in 0..23) {
                    val ts = todayStart + hour * 3600_000L + minute * 60_000L
                    anchors.add(pos to ts)
                }
            }
        }

        return anchors.sortedBy { it.first }
    }

    private fun nearestTime(pos: Int, anchors: List<Pair<Int, Long>>): Long {
        if (anchors.isEmpty()) return System.currentTimeMillis()
        return anchors.minByOrNull { kotlin.math.abs(it.first - pos) }!!.second
    }
}