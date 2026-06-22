package com.tangdun.app.domain.algorithm

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.util.*

/**
 * TCN模型预测器
 *
 * 使用ONNX Runtime在本地运行TCN模型
 * 模型性能: MAE 0.612 mmol/L, Clarke A区 92.5% (v3 训练结果)
 */
class TCNPredictor(private val context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var isLoaded = false

    /**
     * 加载ONNX模型
     */
    fun loadModel(): Boolean {
        return try {
            val assets = context.assets
            val files = assets.list("")?.toList() ?: emptyList()
            android.util.Log.i("TCNPredictor", "Assets files: $files")
            val hasModel = files.any { it == "model_curve_v2.onnx" }
            android.util.Log.i("TCNPredictor", "model_curve_v2.onnx exists in assets: $hasModel")

            if (!hasModel) {
                android.util.Log.e("TCNPredictor", "ONNX模型文件不在assets中!")
                isLoaded = false
                return false
            }

            val modelBytes = assets.open("model_curve_v2.onnx").readBytes()
            android.util.Log.i("TCNPredictor", "Model size: ${modelBytes.size} bytes")

            val sessionOptions = ai.onnxruntime.OrtSession.SessionOptions()
            session = env.createSession(modelBytes, sessionOptions)
            android.util.Log.i("TCNPredictor", "ONNX session created: input=${session?.inputNames}, output=${session?.outputNames}")
            isLoaded = true
            true
        } catch (e: Exception) {
            android.util.Log.e("TCNPredictor", "ONNX加载失败: ${e.javaClass.simpleName}: ${e.message}", e)
            isLoaded = false
            false
        }
    }

    /**
     * 使用TCN模型预测
     *
     * @param features 15维特征向量
     * @return 4个曲线参数 [a, b, c, d]
     */
    fun predict(features: FloatArray): FloatArray? {
        if (!isLoaded || session == null) return null

        return try {
            // 创建输入张量 [1, 15]
            val inputTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(features),
                longArrayOf(1, features.size.toLong())
            )

            // 推理 + 必须在 close() 前取出 value (ONNX 1.16 close 后 native buffer 失效)
            val results = session?.run(Collections.singletonMap("input", inputTensor))
            val output = results?.get(0)?.value as? Array<FloatArray>
            val firstOut = output?.firstOrNull()  // ★ v3.0.18 修: 必须在 close 之前取出来

            inputTensor.close()
            results?.close()

            firstOut
        } catch (e: Exception) {
            android.util.Log.e("TCNPredictor", "ONNX推理失败: ${e.message}", e)
            null
        }
    }

    /**
     * 生成预测曲线
     *
     * G(t) = current * (1 + a*t³ + b*t² + c*t + d)
     *
     * @param params 4个曲线参数
     * @param currentValue 当前血糖值
     * @param numPoints 曲线点数（默认25，对应0-120分钟）
     * @return 预测曲线
     */
    fun generateCurve(params: FloatArray, currentValue: Double, numPoints: Int = 25): List<Double> {
        val a = params[0].toDouble()
        val b = params[1].toDouble()
        val c = params[2].toDouble()
        val d = params[3].toDouble()

        return (0 until numPoints).map { i ->
            val t = i.toDouble() / (numPoints - 1)
            val relativeChange = a * t * t * t + b * t * t + c * t + d
            currentValue * (1 + relativeChange)
        }
    }

    /**
     * 完整预测流程
     *
     * @param features 15维特征
     * @param currentValue 当前血糖值
     * @return 预测结果，失败返回null
     */
    fun fullPredict(features: FloatArray, currentValue: Double): PredictionResult? {
        val params = predict(features) ?: return null
        val curve = generateCurve(params, currentValue)

        return PredictionResult(
            params = params,
            curve = curve,
            predicted5min = curve.getOrNull(1),
            predicted15min = curve.getOrNull(3),
            predicted30min = curve.getOrNull(6),
            predicted60min = curve.getOrNull(12),
            predicted120min = curve.getOrNull(24)
        )
    }

    /**
     * 释放资源
     */
    fun close() {
        session?.close()
        session = null
        isLoaded = false
    }

    data class PredictionResult(
        val params: FloatArray,
        val curve: List<Double>,
        val predicted5min: Double?,
        val predicted15min: Double?,
        val predicted30min: Double?,
        val predicted60min: Double?,
        val predicted120min: Double?
    )
}
