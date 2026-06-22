package com.tangdun.app.domain.algorithm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tangdun.app.data.local.dao.GlucoseDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

/**
 * TCN 模型的在线增量学习器
 *
 * 核心思路：
 *   TCN 是预训练好的大模型（ONNX, MAE 0.552），部署后权重冻结。
 *   本学习器在 TCN 之上做在线残差学习：
 *     每次 TCN 预测后，等待实际血糖值到达
 *     计算残差 = 实际值 - TCN预测值
 *     用随机梯度下降(SGD)更新轻量修正模型
 *
 * 修正模型：15维 → 4维残差修正参数（与TCN输出格式一致）
 *   实际是两层小网络：W1(15×16) + ReLU + W2(16×4) = 304个参数
 *   每次更新只需几百次浮点运算，不耗电
 *
 * 效果：
 *   使用1周 → 模型开始学习你的个人偏差模式
 *   使用1月 → 残差修正有效，总体误差显著下降
 *   使用3月 → 接近完全个性化
 */
class IncrementalLearner(private val context: Context) {

    companion object {
        private const val TAG = "IncrementalLearner"
        private const val PREFS_NAME = "incremental_weights"
        private const val INPUT_DIM = 15
        private const val HIDDEN_DIM = 16
        private const val OUTPUT_DIM = 4  // 与 TCN 输出格式一致: [a,b,c,d]

        // SGD 超参数
        private const val LEARNING_RATE = 0.001f
        private const val MOMENTUM = 0.9f
        private const val WEIGHT_DECAY = 0.0001f  // L2 正则化，防止过拟合
    }

    // ── 模型参数 ──
    // W1: [INPUT_DIM × HIDDEN_DIM], b1: [HIDDEN_DIM]
    // W2: [HIDDEN_DIM × OUTPUT_DIM], b2: [OUTPUT_DIM]
    private var w1: Array<FloatArray>  // [15][16]
    private var b1: FloatArray          // [16]
    private var w2: Array<FloatArray>  // [16][4]
    private var b2: FloatArray          // [4]

    // 动量（SGD with momentum）
    private var vW1: Array<FloatArray>
    private var vB1: FloatArray
    private var vW2: Array<FloatArray>
    private var vB2: FloatArray

    // 统计
    private var updateCount = 0
    private var totalLoss = 0.0
    private var lastLoss = 0.0

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        updateCount = prefs.getInt("updates", 0)
        totalLoss = prefs.getFloat("total_loss", 0f).toDouble()
        lastLoss = prefs.getFloat("last_loss", 0f).toDouble()
        // 检测权重污染: 损失异常大→完全重置
        val corrupted = lastLoss > 50.0 || totalLoss > 5000.0  // ★ v3.0.12: MSE 范围 0-10 算正常, > 50 是旧 SSE 数据
        if (corrupted) {
            Log.w(TAG, "检测到异常损失, 完全重置: last=$lastLoss total=$totalLoss")
            updateCount = 0; totalLoss = 0.0; lastLoss = 0.0
        }

        // 加载已训练的权重，或初始化（Xavier初始化）
        w1 = Array(INPUT_DIM) { i ->
            FloatArray(HIDDEN_DIM) { j ->
                val saved = prefs.getFloat("w1_${i}_$j", Float.NaN)
                if (corrupted || saved.isNaN()) xavierInit(INPUT_DIM, HIDDEN_DIM) else saved
            }
        }
        b1 = FloatArray(HIDDEN_DIM) {
            val saved = prefs.getFloat("b1_$it", Float.NaN)
            if (corrupted || saved.isNaN()) 0f else saved
        }
        w2 = Array(HIDDEN_DIM) { i ->
            FloatArray(OUTPUT_DIM) { j ->
                val saved = prefs.getFloat("w2_${i}_$j", Float.NaN)
                if (corrupted || saved.isNaN()) xavierInit(HIDDEN_DIM, OUTPUT_DIM) else saved
            }
        }
        b2 = FloatArray(OUTPUT_DIM) {
            val saved = prefs.getFloat("b2_$it", Float.NaN)
            if (corrupted || saved.isNaN()) 0f else saved
        }

        vW1 = Array(INPUT_DIM) { FloatArray(HIDDEN_DIM) }
        vB1 = FloatArray(HIDDEN_DIM)
        vW2 = Array(HIDDEN_DIM) { FloatArray(OUTPUT_DIM) }
        vB2 = FloatArray(OUTPUT_DIM)

        Log.i(TAG, "增量学习器初始化: ${updateCount}次更新, last_loss=$lastLoss")
    }

    /**
     * 前向传播：输入15维特征 → 输出4维残差修正 [Δa, Δb, Δc, Δd]
     */
    fun forward(features: FloatArray): FloatArray {
        // layer 1: h = ReLU(W1 × x + b1)
        val hidden = FloatArray(HIDDEN_DIM)
        for (j in 0 until HIDDEN_DIM) {
            var sum = b1[j]
            for (i in 0 until INPUT_DIM) sum += w1[i][j] * features[i]
            hidden[j] = maxOf(0f, sum)  // ReLU
        }

        // layer 2: y = W2 × h + b2 (线性输出，残差可正可负)
        val output = FloatArray(OUTPUT_DIM)
        for (j in 0 until OUTPUT_DIM) {
            var sum = b2[j]
            for (i in 0 until HIDDEN_DIM) sum += w2[i][j] * hidden[i]
            output[j] = sum
        }

        return output  // [Δa, Δb, Δc, Δd]
    }

    /**
     * 反向传播 + SGD 更新（单样本）
     *
     * @param features 15维输入特征
     * @param residual 4维残差目标 [实际a-TCNa, 实际b-TCNb, 实际c-TCNc, 实际d-TCNd]
     * @return 本次更新的 loss
     */
    fun trainStep(features: FloatArray, residual: FloatArray): Float {
        // ── Forward pass（保存中间值用于反向传播）──
        val hiddenPreAct = FloatArray(HIDDEN_DIM)
        val hidden = FloatArray(HIDDEN_DIM)
        for (j in 0 until HIDDEN_DIM) {
            var sum = b1[j]
            for (i in 0 until INPUT_DIM) sum += w1[i][j] * features[i]
            hiddenPreAct[j] = sum
            hidden[j] = maxOf(0f, sum)
        }

        val output = FloatArray(OUTPUT_DIM)
        for (j in 0 until OUTPUT_DIM) {
            var sum = b2[j]
            for (i in 0 until HIDDEN_DIM) sum += w2[i][j] * hidden[i]
            output[j] = sum
        }

        // ── Loss: MSE (Mean Squared Error per sample, 4个维度平均) + L2正则 ──
        // ★ v3.0.12 修: 之前是 SSE (4个维度求和没除以 N), 正常 err~0.5→ loss=1; 大误差 err~4 → loss=65 异常爆炸
        //   改 MSE 后: 正常 loss ∈ [0.01, 2.0], 大误差 loss ∈ [2.0, 10.0], 不再误以为模型坏掉
        var sumSq = 0f
        val dOutput = FloatArray(OUTPUT_DIM)
        for (j in 0 until OUTPUT_DIM) {
            val err = output[j] - residual[j]
            dOutput[j] = 2f * err / OUTPUT_DIM  // ★ 关键: 平均梯度, 否则大尺度时梯度爆炸
            sumSq += err * err
        }
        val loss = sumSq / OUTPUT_DIM  // MSE per sample (≈ 0.01-2.0 正常范围)
        // L2 正则化项（不计入 loss，只用于梯度）
        val l2Reg = WEIGHT_DECAY

        // ── Backward: layer 2 ──
        // ★ 先计算dHidden（使用旧w2），再更新w2（链式法则要求）
        val dHidden = FloatArray(HIDDEN_DIM)
        for (i in 0 until HIDDEN_DIM) {
            var grad = 0f
            for (j in 0 until OUTPUT_DIM) {
                grad += dOutput[j] * w2[i][j]  // 使用更新前的w2
            }
            dHidden[i] = grad
        }
        // 更新 w2（隐藏层→输出层）
        for (i in 0 until HIDDEN_DIM) {
            for (j in 0 until OUTPUT_DIM) {
                val dw = dOutput[j] * hidden[i] + l2Reg * w2[i][j]
                vW2[i][j] = MOMENTUM * vW2[i][j] - LEARNING_RATE * dw
                w2[i][j] += vW2[i][j]
            }
        }
        // 更新 b2（偏置通常不加L2正则）
        for (j in 0 until OUTPUT_DIM) {
            vB2[j] = MOMENTUM * vB2[j] - LEARNING_RATE * dOutput[j]
            b2[j] += vB2[j]
        }

        // ── Backward: layer 1 (ReLU gradient) ──
        for (i in 0 until INPUT_DIM) {
            for (j in 0 until HIDDEN_DIM) {
                if (hiddenPreAct[j] <= 0) continue  // ReLU: grad=0
                val dw = dHidden[j] * features[i] + l2Reg * w1[i][j]
                vW1[i][j] = MOMENTUM * vW1[i][j] - LEARNING_RATE * dw
                w1[i][j] += vW1[i][j]
            }
        }
        for (j in 0 until HIDDEN_DIM) {
            if (hiddenPreAct[j] <= 0) continue
            vB1[j] = MOMENTUM * vB1[j] - LEARNING_RATE * dHidden[j]
            b1[j] += vB1[j]
        }

        // ── 更新统计 ──
        updateCount++
        totalLoss += loss
        lastLoss = loss.toDouble()

        return loss
    }

    /**
     * 从实际血糖数据构造训练样本
     *
     * 当 TCN 预测了 30 分钟后的血糖，而真实值到达时：
     *   真是残差 = 实际曲线参数 - TCN预测曲线参数
     *
     * 我们无法直接得到"实际曲线参数"，但可以用最优化反推：
     *   找到使 G(t) = G0 × (1 + a·t³ + b·t² + c·t + d) 最接近实际值的 [a,b,c,d]
     */
    fun learnFromActual(
        features: FloatArray,
        tcnParams: FloatArray,
        currentGlucose: Double,
        actualFutureGlucose: Double,
        minutesAhead: Int
    ) {
        if (minutesAhead <= 0) return
        // 防止除零: 血糖≤0或异常值→跳过
        if (currentGlucose < 1.0 || actualFutureGlucose < 1.0) return

        // 反推"实际应有的参数"
        // G(t) = G0 × (1 + a·t³ + b·t² + c·t + d)
        // 假设 TCN 的曲线形状大致正确，只修正偏移
        // 简化：残留 = (实际值 - TCN预测值) 分配到4个参数
        val t = minutesAhead.toDouble() / 120.0  // 归一化到[0,1]
        val tcnValue = currentGlucose * (1.0 +
            tcnParams[0] * t*t*t +
            tcnParams[1] * t*t +
            tcnParams[2] * t +
            tcnParams[3])
        val error = actualFutureGlucose - tcnValue
        val errorRatio = error / currentGlucose

        // 把误差分配到4个参数（按t³,t²,t,1的权重）
        val t3 = t*t*t; val t2 = t*t; val total = t3 + t2 + t + 1.0
        val residual = floatArrayOf(
            (errorRatio * t3 / total).toFloat(),
            (errorRatio * t2 / total).toFloat(),
            (errorRatio * t / total).toFloat(),
            (errorRatio * 1 / total).toFloat()
        )

        val loss = trainStep(features, residual)

        // 每100次更新保存一次权重
        if (updateCount % 100 == 0) saveWeights()

        Log.d(TAG, "增量学习: TCN预测${String.format("%.1f", tcnValue)} vs " +
            "实际${String.format("%.1f", actualFutureGlucose)}, " +
            "误差${String.format("%.1f", error)}, loss=${String.format("%.4f", loss)}")
    }

    /**
     * 定期从数据库找训练样本
     *
     * 策略：找出 "TCN预测时" → "实际值到达" 的配对
     * 我们用简化方案：找任意两个相隔5-120分钟的血糖点，作为训练对
     *
     * @param tcnPredictFn TCN 推断回调: 接收 (features, currentGlucose) → FloatArray(4) 的 [a,b,c,d]
     *                     修复前: tcnParams 永远传 0, 增量学习器错把"血糖变化"当 TCN 误差学习
     *                     修复后: 调用方传入真实 TCN 推理结果
     * @param bolusHistory 可选: 24h 速效/短效胰岛素历史 288 点稀疏数组 (用于特征 11-12)
     * @param carbHistory  可选: 24h 碳水历史 288 点稀疏数组 (用于特征 13)
     */
    suspend fun periodicLearn(
        glucoseDao: GlucoseDao,
        tcnPredictFn: ((features: FloatArray, currentGlucose: Double) -> FloatArray?)? = null,
        bolusHistory: DoubleArray? = null,
        carbHistory: DoubleArray? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val records = glucoseDao.getRecent(1000)
            if (records.size < 20) return@withContext

            // ★ getRecent返回DESC(新→旧)→reversed()变时间序(旧→新)
            val recent = records.takeLast(288).reversed()
            if (recent.size < 50) return@withContext

            val extractor = FeatureExtractor()
            val glucoseHistory = recent.map { it.value }.toDoubleArray()
            val idx = glucoseHistory.size - 7  // 倒数第7点=30min前 (6×5=30)
            if (idx < 10) return@withContext

            // ★ v3.0.9 修: 传入饮食/胰岛素 288 点稀疏数组 (即使为空也让 extractor 知道)
            val features = extractor.extract(
                glucoseHistory, idx,
                bolusHistory = bolusHistory,
                carbHistory = carbHistory
            )
            val currentGlucose = glucoseHistory[idx]

            // 实际值: 30分钟后=最后一个点
            val actualIdx = glucoseHistory.size - 1
            val actualGlucose = glucoseHistory[actualIdx]

            // 验证数据有效性 (防止异常值致损失爆炸)
            if (currentGlucose in 1.0..30.0 && actualGlucose in 1.0..30.0) {
                // ★ 修复: 用真实 TCN 推理拿 tcnParams, 而不是写死 [0,0,0,0]
                val tcnParams: FloatArray = if (tcnPredictFn != null) {
                    tcnPredictFn(features, currentGlucose) ?: floatArrayOf(0f, 0f, 0f, 0f)
                } else {
                    // 降级: 无 TCN 时用 [0,0,0,0], 即"假设 TCN 预测直线", 仍能学到残差但准确度低
                    floatArrayOf(0f, 0f, 0f, 0f)
                }
                learnFromActual(
                    features = features,
                    tcnParams = tcnParams,
                    currentGlucose = currentGlucose,
                    actualFutureGlucose = actualGlucose,
                    minutesAhead = 30
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "定期学习失败: ${e.message}")
        }
    }

    /**
     * ★ v3.0.17 按数据量批量学习 — 解决"导入 N 条只学 1 次"问题
     *
     * 之前: 批量导入 2592 条 (9天 CGM) 只触发 1 次 runIncrementalLearn → 只 +1 次更新
     * 现在: 一次性循环 N/12 个样本对 (5min 步长), 跳过 invalid 数据点
     *
     * @param glucoseDao 数据库 DAO (拉取最近 1000 条)
     * @param sampleStep 采样间隔 (默认 6 = 30min 间隔), 防止密集数据爆炸训练次数
     * @param tcnPredictFn TCN 推理回调
     * @param bolusHistory 24h 胰岛素稀疏数组
     * @param carbHistory  24h 碳水稀疏数组
     * @return 实际训练的样本数
     */
    suspend fun batchLearnByVolume(
        glucoseDao: GlucoseDao,
        sampleStep: Int = 6,
        tcnPredictFn: ((features: FloatArray, currentGlucose: Double) -> FloatArray?)? = null,
        bolusHistory: DoubleArray? = null,
        carbHistory: DoubleArray? = null
    ): Int = withContext(Dispatchers.IO) {
        var trainedCount = 0
        try {
            val records = glucoseDao.getRecent(1000)
            if (records.size < 20) return@withContext 0

            // getRecent返回DESC(新→旧)→reversed()变时间序(旧→新)
            val recent = records.takeLast(288).reversed()
            if (recent.size < 50) return@withContext 0

            val extractor = FeatureExtractor()
            val glucoseHistory = recent.map { it.value }.toDoubleArray()
            val n = glucoseHistory.size
            if (n < 50) return@withContext 0

            // 从 idx = 24 (2h 前) 开始, 每 step 一个样本, 直到 idx = n - 8 (留 40min 验证窗口)
            var idx = 24
            val maxIdx = n - 8
            while (idx < maxIdx) {
                val currentGlucose = glucoseHistory[idx]
                val actualIdx = idx + 6  // 30min 后
                if (actualIdx < n) {
                    val actualGlucose = glucoseHistory[actualIdx]
                    if (currentGlucose in 1.0..30.0 && actualGlucose in 1.0..30.0) {
                        val features = extractor.extract(
                            glucoseHistory, idx,
                            bolusHistory = bolusHistory,
                            carbHistory = carbHistory
                        )
                        val tcnParams: FloatArray = if (tcnPredictFn != null) {
                            tcnPredictFn(features, currentGlucose) ?: floatArrayOf(0f, 0f, 0f, 0f)
                        } else {
                            floatArrayOf(0f, 0f, 0f, 0f)
                        }
                        learnFromActual(
                            features = features,
                            tcnParams = tcnParams,
                            currentGlucose = currentGlucose,
                            actualFutureGlucose = actualGlucose,
                            minutesAhead = 30
                        )
                        trainedCount++
                    }
                }
                idx += sampleStep
            }
            Log.i(TAG, "批量按量学习完成: $trainedCount 个样本对 (步长${sampleStep})")
        } catch (e: Exception) {
            Log.e(TAG, "批量按量学习失败: ${e.message}")
        }
        trainedCount
    }

    /** Xavier 初始化 */
    private fun xavierInit(fanIn: Int, fanOut: Int): Float {
        val std = sqrt(2.0 / (fanIn + fanOut)).toFloat()
        return (kotlin.random.Random.nextFloat() * 2f - 1f) * std
    }

    /** 持久化权重 */
    private fun saveWeights() {
        val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        for (i in 0 until INPUT_DIM)
            for (j in 0 until HIDDEN_DIM)
                edit.putFloat("w1_${i}_$j", w1[i][j])
        for (j in 0 until HIDDEN_DIM) edit.putFloat("b1_$j", b1[j])
        for (i in 0 until HIDDEN_DIM)
            for (j in 0 until OUTPUT_DIM)
                edit.putFloat("w2_${i}_$j", w2[i][j])
        for (j in 0 until OUTPUT_DIM) edit.putFloat("b2_$j", b2[j])
        edit.putInt("updates", updateCount)
        edit.putFloat("total_loss", totalLoss.toFloat())
        edit.putFloat("last_loss", lastLoss.toFloat())
        edit.apply()
    }

    fun getStats(): Map<String, Any> = mapOf(
        "updates" to updateCount,
        "total_loss" to String.format("%.4f", totalLoss),
        "last_loss" to String.format("%.4f", lastLoss),
        "avg_loss" to String.format("%.4f", if (updateCount > 0) totalLoss / updateCount else 0.0)
    )
}
