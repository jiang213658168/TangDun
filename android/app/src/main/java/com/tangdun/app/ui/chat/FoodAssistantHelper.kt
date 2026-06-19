package com.tangdun.app.ui.chat

import android.content.Context
import android.util.Log
import com.tangdun.app.TangDunApp
import com.tangdun.app.data.remote.AiChatService
import com.tangdun.app.data.remote.FoodNutritionAi
import com.tangdun.app.data.remote.FoodNutritionAi.NutritionInfo
import com.tangdun.app.domain.algorithm.DallaManModel
import com.tangdun.app.domain.algorithm.SelfLearningManager
import com.tangdun.app.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.pow

/**
 * AI 饮食助手 (v2.0)
 *
 * 三个核心能力:
 *  1. simulateMealImpact: "如果吃这个会怎样" → DallaMan 7 室仿真 + 曲线预测
 *  2. recommendMeal:      "AI 推荐吃什么"   → 个性化上下文 + AI 推荐
 *  3. discussMealRecipe:  "AI 讨论 + 做法"  → 用户想法 + AI 给克数/做法/建议
 */
class FoodAssistantHelper(private val context: Context) {

    companion object { private const val TAG = "FoodAssistant" }

    private val settingsManager = SettingsManager(context)
    private val foodAi = FoodNutritionAi(context)

    // ════════════════════════════════════════════════════════
    // 核心 1: "如果吃这个会怎样" 仿真预测
    // ════════════════════════════════════════════════════════

    suspend fun simulateMealImpact(
        foodName: String,
        portionGrams: Double? = null,
        targetLow: Double = 3.9,
        targetHigh: Double = 10.0
    ): MealImpactResult = withContext(Dispatchers.Default) {
        try {
            val nutrition = foodAi.getNutritionInfo(foodName)
                ?: return@withContext MealImpactResult(
                    foodName = foodName, success = false,
                    errorMessage = "无法获取「$foodName」的营养信息, 请尝试更具体的食物名"
                )

            val effectivePortion = portionGrams ?: nutrition.portionGrams
            val ratio = effectivePortion / nutrition.portionGrams.coerceAtLeast(1.0)
            val effectiveCarbs = nutrition.carbs * ratio
            val effectiveGi = nutrition.gi
            val effectiveCalories = nutrition.calories * ratio

            Log.i(TAG, "「$foodName」 ${effectivePortion}g: 碳水=${effectiveCarbs}g GI=${effectiveGi}")

            val ctx = buildUserContext()
            val currentGlucose = ctx.currentGlucose ?: 5.5
            val userWeight = ctx.bodyWeight
            val isf = ctx.isf

            val dallaMan = DallaManModel()
            val giAdjusted = effectiveGi.coerceIn(30.0, 90.0)
            val dalleParams = DallaManModel.Parameters.forUser(
                bodyWeight = userWeight,
                fastingGlucose = ctx.fastingBaseline.coerceAtLeast(4.5),
                isf = isf,
                basalInsulin = (ctx.iob + 8.0).coerceIn(4.0, 30.0),
                sigma = if (settingsManager.getDiabetesType() == 1) 0.0 else 3.0,
                activityLevel = ctx.activityLevel
            )

            val giFactor = (giAdjusted / 50.0).coerceIn(0.7, 1.5)
            val paramsWithGI = dalleParams.copy(
                kStomach = (dalleParams.kStomach * giFactor).coerceIn(0.025, 0.080)
            )

            val meals = listOf(
                DallaManModel.MealInput(
                    timeMinutes = 15.0,
                    carbsGrams = effectiveCarbs,
                    gi = effectiveGi
                )
            )
            val initialInsulin = max(ctx.iob * 15.0, 5.0)

            // 直接调 predict(支持自定义 params), 内部算峰值
            val mainCurve = dallaMan.predict(
                currentGlucose = currentGlucose,
                currentInsulin = initialInsulin,
                meals = meals,
                horizonMinutes = 180,
                stepMinutes = 5,
                params = paramsWithGI
            )
            // 低 GI 替代: 慢胃排空
            val lowGiParams = paramsWithGI.copy(kStomach = (paramsWithGI.kStomach * 0.7).coerceIn(0.025, 0.080))
            val lowGiCurve = dallaMan.predict(
                currentGlucose = currentGlucose,
                currentInsulin = initialInsulin,
                meals = meals,
                horizonMinutes = 180,
                stepMinutes = 5,
                params = lowGiParams
            )

            val peakValue = mainCurve.max()
            val peakIndex = mainCurve.indexOf(peakValue)
            val peakTime = peakIndex * 5
            val lowGiPeak = lowGiCurve.max()

            val delta = peakValue - currentGlucose
            val willExceedHigh = peakValue > targetHigh
            val willGoLow = peakValue < targetLow
            val exceedsThreshold = peakValue > 13.9 || peakValue < 3.0

            val advice = aiAnalysisImpact(
                foodName = foodName,
                portionGrams = effectivePortion,
                carbs = effectiveCarbs,
                gi = effectiveGi,
                calories = effectiveCalories,
                currentGlucose = currentGlucose,
                peakValue = peakValue,
                peakTimeMinutes = peakTime,
                delta = delta,
                willExceedHigh = willExceedHigh,
                willGoLow = willGoLow,
                exceedsThreshold = exceedsThreshold,
                targetLow = targetLow,
                targetHigh = targetHigh,
                ctx = ctx
            )

            MealImpactResult(
                foodName = foodName, success = true,
                portionGrams = effectivePortion, carbs = effectiveCarbs,
                gi = effectiveGi, calories = effectiveCalories,
                currentGlucose = currentGlucose, peakValue = peakValue,
                peakTimeMinutes = peakTime, delta = delta,
                willExceedHigh = willExceedHigh, willGoLow = willGoLow,
                exceedsThreshold = exceedsThreshold,
                curve = mainCurve,
                lowGiAlternativeCurve = lowGiCurve,
                lowGiPeak = lowGiPeak,
                peakReduction = peakValue - lowGiPeak,
                aiAdvice = advice
            )
        } catch (e: Exception) {
            Log.e(TAG, "simulateMealImpact failed: ${e.message}", e)
            MealImpactResult(
                foodName = foodName, success = false,
                errorMessage = "预测失败: ${e.message}"
            )
        }
    }

    private suspend fun aiAnalysisImpact(
        foodName: String, portionGrams: Double, carbs: Double, gi: Double,
        calories: Double, currentGlucose: Double, peakValue: Double,
        peakTimeMinutes: Int, delta: Double, willExceedHigh: Boolean,
        willGoLow: Boolean, exceedsThreshold: Boolean,
        targetLow: Double, targetHigh: Double, ctx: UserContext
    ): String = withContext(Dispatchers.IO) {
        // Build prompt using string concat (avoid nested string templates)
        val promptLines = mutableListOf<String>()
        promptLines += "你是一位糖尿病饮食顾问。用户问「如果吃${foodName} ${portionGrams.toInt()}g, 血糖会怎样？」"
        promptLines += ""
        promptLines += "## 仿真结果 (基于 Dalla Man 7 隔室生理模型)"
        promptLines += "- 当前血糖: ${"%.1f".format(currentGlucose)} mmol/L"
        promptLines += "- 预计峰值: ${"%.1f".format(peakValue)} mmol/L (餐后 ${peakTimeMinutes} 分钟)"
        promptLines += "- 血糖变化: ${if (delta >= 0) "+" else ""}${"%.1f".format(delta)} mmol/L"
        promptLines += "- 是否超目标上限 (${targetHigh}): ${if (willExceedHigh) "⚠️ 是" else "否"}"
        promptLines += "- 是否低血糖 (<${targetLow}): ${if (willGoLow) "⚠️ 是" else "否"}"
        promptLines += "- 是否危险区 (>13.9 或 <3.0): ${if (exceedsThreshold) "🚨 是" else "否"}"
        promptLines += ""
        promptLines += "## 食物营养"
        promptLines += "- 份量: ${portionGrams.toInt()} g"
        promptLines += "- 碳水: ${"%.1f".format(carbs)} g"
        promptLines += "- GI 升糖指数: ${gi.toInt()}"
        promptLines += "- 热量: ${"%.0f".format(calories)} kcal"
        promptLines += ""
        promptLines += "## 用户上下文"
        promptLines += "- 体重: ${ctx.bodyWeight} kg, ISF: ${"%.1f".format(ctx.isf)} mmol/L per U"
        promptLines += "- 当前 IOB: ${"%.1f".format(ctx.iob)} U"
        promptLines += "- 24h 已吃碳水: ${"%.0f".format(ctx.todayCarbs)} g"
        promptLines += "- 血糖变异度: ${"%.1f".format(ctx.variability)}%"
        promptLines += ""
        promptLines += "请用 4-6 句话回答, 包含:"
        promptLines += "1. 直接告知预测结果 (会不会超)"
        promptLines += "2. 是否建议现在吃 (如果快超)"
        promptLines += "3. 给具体克数建议 (比如减半到 ${(portionGrams / 2).toInt()}g)"
        promptLines += "4. 餐后多久运动多久能帮助降糖 (如果会超)"
        promptLines += "5. 必要时建议先打多少单位胰岛素"
        promptLines += ""
        promptLines += "语气温和专业, 不要堆术语, 像朋友聊天。"

        val prompt = promptLines.joinToString("\n")

        try {
            val aiService = AiChatService(context)
            val response = aiService.sendMessage(
                listOf(com.tangdun.app.data.remote.ChatMessageDto("user", prompt))
            )
            response.getOrNull() ?: "仿真完成, 但 AI 建议生成失败。"
        } catch (e: Exception) {
            Log.w(TAG, "AI 分析失败: ${e.message}")
            buildString {
                append("如果现在吃 ${foodName} ${portionGrams.toInt()}g, ")
                append("预计 ${peakTimeMinutes} 分钟后血糖达 ${"%.1f".format(peakValue)} mmol/L ")
                append("(变化 ${if (delta >= 0) "+" else ""}${"%.1f".format(delta)} mmol/L)。")
                if (willExceedHigh) {
                    append("\n\n⚠️ 会超过目标上限 ${targetHigh} mmol/L。")
                    if (peakValue > 13.9) {
                        append("\n🚨 进入危险区, 建议减量或换低 GI 食物。")
                    }
                    if (ctx.iob < 0.5) {
                        val needed = (carbs / 10.0).pow(0.8)
                        append("\n💉 如果一定要吃, 建议先打 ~${"%.1f".format(needed)}U 速效胰岛素。")
                    }
                } else if (willGoLow) {
                    append("\n\n⚠️ 注意低血糖风险。")
                } else {
                    append("\n\n✅ 血糖在安全范围内。")
                }
            }
        }
    }


    // ════════════════════════════════════════════════════════
    // 核心 2: AI 推荐吃什么
    // ════════════════════════════════════════════════════════

    suspend fun recommendMeal(): MealRecommendation = withContext(Dispatchers.Default) {
        val ctx = buildUserContext()
        val prompt = buildRecommendationPrompt(ctx)

        val aiService = AiChatService(context)
        val response = aiService.sendMessage(
            listOf(com.tangdun.app.data.remote.ChatMessageDto("user", prompt))
        )

        MealRecommendation(
            aiText = response.getOrNull() ?: "推荐生成失败, 请检查 AI 服务配置",
            userContext = ctx,
            success = response.isSuccess
        )
    }

    private fun buildRecommendationPrompt(ctx: UserContext): String {
        // 用 list + joinToString 构建 prompt, 完全避免三引号字符串嵌套
        val isLow = ctx.currentGlucose != null && ctx.currentGlucose < 4.5
        val isLowText = if (isLow) "是" else "否"

        val currentG = ctx.currentGlucose?.let { String.format("%.1f", it) + " mmol/L" } ?: "未知"
        val avgG = ctx.avgGlucose?.let { String.format("%.1f", it) + " mmol/L" } ?: "未知"

        val lines = mutableListOf<String>()
        lines += "你是一位糖尿病饮食顾问。请根据用户的实时状态推荐 3-5 种适合现在吃的食物选项。"
        lines += ""
        lines += "## 用户实时数据"
        lines += "- 当前血糖: $currentG (${trendText(ctx.currentGlucose)})"
        lines += "- 当前 IOB (体内活性胰岛素): ${"%.1f".format(ctx.iob)} U"
        lines += "- 24h 已摄入碳水: ${"%.0f".format(ctx.todayCarbs)} g"
        lines += "- 体重: ${ctx.bodyWeight} kg"
        lines += "- ISF 胰岛素敏感因子: ${"%.1f".format(ctx.isf)} mmol/L per U"
        lines += "- CR 碳水系数: ${"%.0f".format(ctx.carbRatio)} g per U"
        lines += "- 血糖变异度 CV: ${"%.1f".format(ctx.variability)}%"
        lines += "- 平均血糖: $avgG"
        lines += ""
        lines += "## 要求"
        lines += "根据上面的数据:"
        lines += "1. 如果血糖偏低 ($isLowText) → 优先推荐快速升糖但量小的食物"
        lines += "2. 如果血糖正常 → 推荐 GI ≤ 55 的食物, 每餐碳水 30-50g"
        lines += "3. 如果血糖偏高 → 推荐极低碳水或纯蛋白/蔬菜, 强调先降糖再吃"
        lines += "4. 根据 24h 已摄入碳水 → 调整本次推荐量 (避免一天超过 200g)"
        lines += "5. 根据 IOB → 判断现在能不能加餐 (IOB > 2U 时避免再加碳水)"
        lines += ""
        lines += "## 输出格式"
        lines += "对每种推荐:"
        lines += "- 食物名 + 份量"
        lines += "- 预估碳水"
        lines += "- 一句话推荐理由 (基于用户数据)"
        lines += "- 风险提示 (如有)"
        lines += ""
        lines += "控制在 250 字以内, 实用为主, 给出具体克数。"

        return lines.joinToString("\n")
    }

    private fun trendText(g: Double?): String = when {
        g == null -> "未知"
        g < 3.9 -> "偏低"
        g < 7.0 -> "正常偏低"
        g < 10.0 -> "正常"
        g < 13.9 -> "偏高"
        else -> "很高"
    }


    // ════════════════════════════════════════════════════════
    // 核心 3: AI 讨论 + 个性化做法
    // ════════════════════════════════════════════════════════

    suspend fun discussMealRecipe(foodName: String, additionalContext: String? = null): MealRecipe = withContext(Dispatchers.Default) {
        val ctx = buildUserContext()
        val nutrition = foodAi.getNutritionInfo(foodName)

        val prompt = buildRecipePrompt(foodName, ctx, nutrition, additionalContext)

        val aiService = AiChatService(context)
        val response = aiService.sendMessage(
            listOf(com.tangdun.app.data.remote.ChatMessageDto("user", prompt))
        )

        MealRecipe(
            foodName = foodName,
            aiText = response.getOrNull() ?: "做法生成失败, 请检查 AI 服务配置",
            userContext = ctx,
            nutrition = nutrition,
            success = response.isSuccess
        )
    }

    private fun buildRecipePrompt(
        foodName: String,
        ctx: UserContext,
        nutrition: NutritionInfo?,
        additionalContext: String?
    ): String {
        val lines = mutableListOf<String>()
        lines += "你是一位糖尿病饮食顾问。用户说「我想吃 $foodName」${additionalContext ?: ""}"
        lines += ""
        lines += "请提供:"
        lines += "1. 健康做法建议: 用什么烹饪方式更健康 (少油/少糖/蒸煮优于煎炸)"
        lines += "2. 推荐克数: 根据用户个性化数据给出具体克数, 而非模糊建议"
        lines += "3. 营养估算: 碳水/蛋白/脂肪/热量"
        lines += "4. 风险与建议:"
        lines += "   - 这道菜对血糖的影响 (高/中/低 GI?)"
        lines += "   - 建议的进食顺序 (先吃菜→肉→主食能减缓升糖)"
        lines += "   - 餐后多久测血糖"
        lines += "   - 必要时的胰岛素补偿建议"
        lines += ""

        if (nutrition != null) {
            lines += "## 食物营养 (AI 估算)"
            lines += "- 每 100g 碳水: ${"%.1f".format(nutrition.carbs)} g"
            lines += "- 每 100g 蛋白质: ${"%.1f".format(nutrition.protein)} g"
            lines += "- 每 100g 脂肪: ${"%.1f".format(nutrition.fat)} g"
            lines += "- 每 100g 热量: ${"%.0f".format(nutrition.calories)} kcal"
            lines += "- GI 升糖指数: ${nutrition.gi.toInt()}"
        } else {
            lines += "## 食物营养: 未知, 请根据常识估算"
        }
        lines += ""
        lines += "## 用户实时数据"
        lines += "- 当前血糖: ${ctx.currentGlucose?.let { String.format("%.1f", it) + " mmol/L" } ?: "未知"}"
        lines += "- IOB: ${"%.1f".format(ctx.iob)} U"
        lines += "- 体重: ${ctx.bodyWeight} kg"
        lines += "- ISF: ${"%.1f".format(ctx.isf)} mmol/L per U"
        lines += "- CR (碳水系数): ${"%.0f".format(ctx.carbRatio)} g per U"
        lines += "- 24h 已摄入碳水: ${"%.0f".format(ctx.todayCarbs)} g"
        lines += "- 血糖变异度 CV: ${"%.1f".format(ctx.variability)}%"
        lines += ""
        lines += "请用 4-8 句话, 像专业营养师给朋友的建议一样回答, 给出具体克数而非适量。"

        return lines.joinToString("\n")
    }


    // ════════════════════════════════════════════════════════
    // 上下文构建
    // ════════════════════════════════════════════════════════

    suspend fun buildUserContext(): UserContext = withContext(Dispatchers.IO) {
        val db = TangDunApp.getDatabase(context)
        val now = System.currentTimeMillis()
        val oneDayAgo = now - 24 * 3600 * 1000L

        val latestGlucose = db.glucoseDao().getLatest()
        val recentGlucose = db.glucoseDao().getRecent(50)
        val recentMeals = db.mealDao().getByTimeRange(oneDayAgo, now)
        val recentInsulin = db.insulinDao().getSince(oneDayAgo)

        val iob = recentInsulin.filter {
            it.insulinType == "rapid" || it.insulinType == "short"
        }.fold(0.0) { acc, r ->
            val m = (now - r.timestamp) / 60000.0
            if (m in 0.0..240.0) acc + r.doseUnits * 0.5.pow(m / 55.0) else acc
        }

        val todayCarbs = recentMeals.sumOf { it.totalCarbs }

        val avgGlucose = if (recentGlucose.isNotEmpty()) recentGlucose.map { it.value }.average() else null

        var fastingBaseline = 5.5
        var variability = 1.5
        try {
            val params = SelfLearningManager.getOnlineLearner().getPersonalParams()
            fastingBaseline = params.fastingBaseline
            variability = params.glucoseVariability
        } catch (_: Exception) {}

        val weight = settingsManager.getWeightKg().toDouble().coerceAtLeast(35.0)
        val isf = settingsManager.getInsulinSensitivity().toDouble().coerceIn(0.3, 5.0)
        val carbRatio = settingsManager.getCarbRatio().toDouble().coerceIn(5.0, 30.0)

        UserContext(
            currentGlucose = latestGlucose?.value,
            iob = iob,
            todayCarbs = todayCarbs,
            avgGlucose = avgGlucose,
            fastingBaseline = fastingBaseline,
            variability = variability,
            bodyWeight = weight,
            isf = isf,
            carbRatio = carbRatio,
            activityLevel = 0.5
        )
    }
}


// ════════════════════════════════════════════════════════
// 数据类
// ════════════════════════════════════════════════════════

data class UserContext(
    val currentGlucose: Double?,
    val iob: Double,
    val todayCarbs: Double,
    val avgGlucose: Double?,
    val fastingBaseline: Double,
    val variability: Double,
    val bodyWeight: Double,
    val isf: Double,
    val carbRatio: Double,
    val activityLevel: Double
)

data class MealImpactResult(
    val foodName: String,
    val success: Boolean,
    val portionGrams: Double = 0.0,
    val carbs: Double = 0.0,
    val gi: Double = 0.0,
    val calories: Double = 0.0,
    val currentGlucose: Double = 0.0,
    val peakValue: Double = 0.0,
    val peakTimeMinutes: Int = 0,
    val delta: Double = 0.0,
    val willExceedHigh: Boolean = false,
    val willGoLow: Boolean = false,
    val exceedsThreshold: Boolean = false,
    val curve: List<Double> = emptyList(),
    val lowGiAlternativeCurve: List<Double> = emptyList(),
    val lowGiPeak: Double = 0.0,
    val peakReduction: Double = 0.0,
    val aiAdvice: String = "",
    val errorMessage: String = ""
)

data class MealRecommendation(
    val aiText: String,
    val userContext: UserContext,
    val success: Boolean
)

data class MealRecipe(
    val foodName: String,
    val aiText: String,
    val userContext: UserContext,
    val nutrition: NutritionInfo?,
    val success: Boolean
)
