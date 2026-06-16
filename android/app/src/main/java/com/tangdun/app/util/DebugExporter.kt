package com.tangdun.app.util

import android.content.Context
import android.util.Log
import com.tangdun.app.TangDunApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 调试数据导出器 — 一键导出所有数据库表+SharedPreferences+学习状态
 */
object DebugExporter {

    private const val TAG = "DebugExport"

    suspend fun exportAll(context: Context): File = withContext(Dispatchers.IO) {
        val db = TangDunApp.getDatabase(context)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        val root = JSONObject()
        root.put("export_time", sdf.format(Date()))
        root.put("app_version", "1.0.0")

        // ── 所有数据库表 ──
        try { root.put("glucose", recordsToJson(db.glucoseDao().getRecent(100000).map { r ->
            JSONObject().apply { put("ts", sdf.format(Date(r.timestamp))); put("v", r.value); put("source", r.source); put("trend", r.trend ?: ""); put("scene", r.scene) }
        })) } catch (_: Exception) {}

        try { root.put("meals", recordsToJson(db.mealDao().getRecent(5000).map { r ->
            JSONObject().apply { put("ts", sdf.format(Date(r.timestamp))); put("type", r.mealType); put("carbs", r.totalCarbs); put("cal", r.totalCalories); put("protein", r.totalProtein); put("fat", r.totalFat); put("gi", r.avgGi) }
        })) } catch (_: Exception) {}

        try { root.put("insulin", recordsToJson(db.insulinDao().getRecent(2000).map { r ->
            JSONObject().apply { put("ts", sdf.format(Date(r.timestamp))); put("type", r.insulinType); put("dose", r.doseUnits); put("site", r.injectionSite ?: ""); put("notes", r.notes ?: "") }
        })) } catch (_: Exception) {}

        try { root.put("exercise", recordsToJson(db.exerciseDao().getRecent(1000).map { r ->
            JSONObject().apply { put("ts", sdf.format(Date(r.startTime))); put("type", r.exerciseType); put("min", r.durationMin ?: 0); put("steps", r.steps ?: 0); put("hr", r.avgHeartRate ?: 0.0) }
        })) } catch (_: Exception) {}

        try { root.put("alerts", recordsToJson(db.alertDao().getRecent(500).map { r ->
            JSONObject().apply { put("ts", sdf.format(Date(r.createdAt))); put("type", r.alertType); put("severity", r.severity); put("msg", r.message ?: ""); put("value", r.glucoseValue ?: 0.0) }
        })) } catch (_: Exception) {}

        try { root.put("weight", recordsToJson(db.weightDao().getRecent(500).map { r ->
            JSONObject().apply { put("ts", sdf.format(Date(r.timestamp))); put("kg", r.weightKg) }
        })) } catch (_: Exception) {}

        // ── SharedPreferences (激活/设置/校准) ──
        val prefs = JSONObject()
        for (name in listOf("tangdun_activation_v2", "tangdun_settings", "cgm_calibration", "online_learner_params", "incremental_weights")) {
            try {
                val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val spJson = JSONObject()
                for ((k, v) in sp.all) { spJson.put(k, v.toString()) }
                prefs.put(name, spJson)
            } catch (_: Exception) {}
        }
        root.put("shared_prefs", prefs)

        // ── 自学习状态 ──
        try {
            val lm = com.tangdun.app.domain.algorithm.SelfLearningManager
            val ol = lm.getOnlineLearner()
            val inc = lm.getIncrementalLearner()
            root.put("learning", JSONObject().apply {
                put("stage", ol.getStageDescription())
                put("params", JSONObject().apply {
                    val p = ol.getPersonalParams()
                    put("fasting", p.fastingBaseline); put("peak", p.postMealPeak)
                    put("variability", p.glucoseVariability); put("dataDays", p.dataDays)
                    put("updates", p.updateCount); put("completeness", p.dataCompleteness)
                })
                put("incremental", JSONObject(inc.getStats()))
            })
        } catch (_: Exception) {}

        // ── 写入文件 ──
        val dir = File(context.getExternalFilesDir(null), "debug")
        dir.mkdirs()
        val file = File(dir, "tangdun_debug_$ts.json")
        file.writeText(root.toString(2))
        Log.i(TAG, "导出完成: ${file.absolutePath} (${file.length()/1024}KB)")
        file
    }

    private fun recordsToJson(list: List<JSONObject>): JSONArray {
        val arr = JSONArray()
        list.take(10000).forEach { arr.put(it) }
        return arr
    }
}
