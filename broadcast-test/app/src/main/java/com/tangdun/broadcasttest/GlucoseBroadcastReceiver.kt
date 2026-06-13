package com.tangdun.broadcasttest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * 血糖广播接收器（测试版）
 *
 * 监听所有CGM广播并详细记录extras内容
 * 用于诊断欧泰健康/xDrip+的广播格式
 *
 * 监听Action:
 * - com.microtechmd.cgms.aidex.action.BgEstimate (欧泰/Aidex)
 * - com.eveningoutpost.dexdrip.BgEstimate (xDrip+)
 * - com.eveningoutpost.dexdrip.BgReading (xDrip+ Follower)
 * - com.fanqies.tomatofn.BgEstimate (Tomato)
 * - com.cgm.glucose (通用CGM)
 */
class GlucoseBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GlucoseReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())

        Log.d(TAG, "═══════════════════════════════")
        Log.d(TAG, "[$time] 收到广播!")
        Log.d(TAG, "Action: $action")

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("──────────────────────────────")
        sb.appendLine("[$time] 收到广播!")
        sb.appendLine("Action: $action")
        sb.appendLine()

        val extras = intent.extras
        if (extras != null && !extras.isEmpty) {
            sb.appendLine("Extras (${extras.size()}) keys:")

            var foundGlucose = false
            for (key in extras.keySet().sorted()) {
                try {
                    val value = extras.get(key)
                    val type = value?.javaClass?.simpleName ?: "null"
                    sb.append("  $key = $value ($type)")

                    // 标记可能的血糖值
                    val numValue = when (value) {
                        is Double -> value
                        is Float -> value.toDouble()
                        else -> null
                    }
                    if (numValue != null) {
                        if (numValue in 20.0..600.0) {
                            val mmol = numValue / 18.0
                            sb.append(" ← 可能是血糖 ${String.format("%.1f", mmol)} mmol/L")
                            foundGlucose = true
                        } else if (numValue in 1.0..33.0) {
                            sb.append(" ← 可能是血糖 ${String.format("%.1f", numValue)} mmol/L")
                            foundGlucose = true
                        }
                    }
                    sb.appendLine()
                } catch (e: Exception) {
                    sb.appendLine("  $key = [解析失败: ${e.message}]")
                }
            }

            if (!foundGlucose) {
                sb.appendLine()
                sb.appendLine("⚠ 未找到明显血糖值，需要手动确认格式")
            }
        } else {
            sb.appendLine("注意: extras为空！")
            sb.appendLine("可能原因：发送方使用了flags而非extras传递数据")
        }

        sb.appendLine("──────────────────────────────")

        Log.d(TAG, sb.toString())

        // 转发给MainActivity显示
        val broadcastIntent = Intent("com.tangdun.broadcasttest.GLUCOSE_RECEIVED").apply {
            putExtra("log", sb.toString())
            putExtra("action", action)
            putExtra("time", time)
        }
        context.sendBroadcast(broadcastIntent)
    }
}
