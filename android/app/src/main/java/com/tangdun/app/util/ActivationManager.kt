package com.tangdun.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 糖盾激活管理器 v2
 * AES-128-CBC 加密 → 解密即验证 (加密本身保障安全)
 */
class ActivationManager(context: Context) {

    companion object {
        private const val PREFS = "tangdun_activation_v2"
        private val AES_KEY = SecretKeySpec("TangDun@2026!Key".toByteArray(), "AES")
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Result(val ok: Boolean, val msg: String)

    fun isActivated(): Boolean = (prefs.getString("code", "") ?: "").isNotEmpty()
    fun isAdmin(): Boolean = prefs.getString("type", "") == "admin"

    fun isExpired(): Boolean {
        val exp = prefs.getLong("expiry", 0)
        return exp > 0 && System.currentTimeMillis() / 1000 > exp
    }

    fun canAccess(feature: String): Boolean {
        if (!isActivated() || isExpired()) return false
        if (isAdmin()) return true
        val limit = prefs.getInt("limit_$feature", 0)
        if (limit == -1) return true
        if (limit == 0) return false
        checkDate(feature)
        return prefs.getInt("used_$feature", 0) < limit
    }

    fun recordUse(feature: String) { checkDate(feature); prefs.edit().putInt("used_$feature", prefs.getInt("used_$feature", 0) + 1).apply() }
    fun getRemaining(feature: String): Int { val l = prefs.getInt("limit_$feature", 0); return if (l == -1) Int.MAX_VALUE else maxOf(0, l - prefs.getInt("used_$feature", 0)) }

    fun activate(rawCode: String): Result {
        return try {
            // 清理输入: 去空格/换行/不可见字符
            val clean = rawCode.trim().replace(Regex("\\s+"), "")
            if (!clean.startsWith("TD2.")) return Result(false, "格式错误，请输入TD2.开头的激活码 (当前: ${clean.take(10)}...)")

            // 去掉分隔符，用URL_SAFE模式直接解码(自动识别 - _ 和无padding)
            val b64 = clean.removePrefix("TD2.").replace(".", "")
            val decoded = Base64.decode(b64, Base64.URL_SAFE)
            if (decoded.size < 32) return Result(false, "激活码数据不完整(${decoded.size}字节)")

            // AES-128-CBC 解密
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, AES_KEY, IvParameterSpec(decoded, 0, 16))
            val plain = String(cipher.doFinal(decoded, 16, decoded.size - 16))
            android.util.Log.d("Activation", "plain=$plain")

            val json = JSONObject(plain)
            val typ = json.optString("t", "user")
            val exp = json.optLong("exp", 0)
            val flg = json.optJSONObject("flg") ?: JSONObject()
            val vf = json.optLong("vf", 0)
            val vu = json.optLong("vu", 0)

            // 检查激活窗口
            val now = System.currentTimeMillis() / 1000
            if (now < vf) {
                val df = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                return Result(false, "激活码尚未生效，${df.format(java.util.Date(vf * 1000))}起")
            }
            if (now > vu) return Result(false, "激活码已过期")

            // 保存
            prefs.edit().putString("code", rawCode).putString("type", typ).putLong("expiry", exp).apply()
            for (k in listOf("chat", "photo", "predict", "report", "export"))
                prefs.edit().putInt("limit_$k", flg.optInt(k, 10)).apply()
            resetDate()

            val msg = if (typ == "admin") "管理员已激活 (永久)"
            else "激活成功，有效期至 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(exp * 1000))}"
            Log.i("Activation", msg)
            Result(true, msg)
        } catch (e: Exception) {
            Log.e("Activation", "activate error", e)
            Result(false, "激活码无效 (${e.message?.take(30)})")
        }
    }

    private fun checkDate(feature: String) {
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val last = prefs.getString("date_$feature", "")
        if (last != today) prefs.edit().putString("date_$feature", today).putInt("used_$feature", 0).apply()
    }

    private fun resetDate() {
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val e = prefs.edit()
        for (f in listOf("chat", "photo", "predict", "report", "export")) { e.putString("date_$f", today); e.putInt("used_$f", 0) }
        e.apply()
    }
}
