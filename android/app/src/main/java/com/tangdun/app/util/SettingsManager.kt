package com.tangdun.app.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 设置管理器
 *
 * 存储用户配置：
 * - 百度AI API配置
 * - 血糖目标范围
 * - 其他个性化设置
 */
class SettingsManager(private val context: Context) {

    private val sharedPref: SharedPreferences =
        context.getSharedPreferences("tangdun_settings", Context.MODE_PRIVATE)

    // ===== 百度AI配置 =====

    private val _baiduApiKey = MutableStateFlow(getBaiduApiKey())
    val baiduApiKey: StateFlow<String> = _baiduApiKey

    private val _baiduSecretKey = MutableStateFlow(getBaiduSecretKey())
    val baiduSecretKey: StateFlow<String> = _baiduSecretKey

    fun getBaiduApiKey(): String {
        return sharedPref.getString("baidu_api_key", DEFAULT_BAIDU_API_KEY) ?: DEFAULT_BAIDU_API_KEY
    }

    fun getBaiduSecretKey(): String {
        return sharedPref.getString("baidu_secret_key", DEFAULT_BAIDU_SECRET_KEY) ?: DEFAULT_BAIDU_SECRET_KEY
    }

    companion object {
        // 百度AI配置
        private const val DEFAULT_BAIDU_API_KEY = "8FrGn0fkFjleEnUBY2c317j8"
        private const val DEFAULT_BAIDU_SECRET_KEY = "7kS71cEhQcq54No6ZxQssNI7fXsor1Pc"

        // AI对话配置 (兼容OpenAI的API)
        private const val DEFAULT_AI_PROVIDER = "openai"
        private const val DEFAULT_OPENAI_API_KEY = "tp-c46u5ce4kpsricwt4e6j7l3i2ncmc2stfh8g1qoprm1yisn9"
        private const val DEFAULT_OPENAI_BASE_URL = "https://token-plan-cn.xiaomimimo.com/v1"
    }

    fun setBaiduApiConfig(apiKey: String, secretKey: String) {
        sharedPref.edit()
            .putString("baidu_api_key", apiKey)
            .putString("baidu_secret_key", secretKey)
            .apply()
        _baiduApiKey.value = apiKey
        _baiduSecretKey.value = secretKey
    }

    fun isBaiduApiConfigured(): Boolean {
        return getBaiduApiKey().isNotEmpty() && getBaiduSecretKey().isNotEmpty()
    }

    // ===== 血糖单位 =====

    /** 血糖单位: "mmol" 或 "mgdl" */
    fun getGlucoseUnit(): String {
        return sharedPref.getString("glucose_unit", "mmol") ?: "mmol"
    }

    fun setGlucoseUnit(unit: String) {
        sharedPref.edit().putString("glucose_unit", unit).apply()
    }

    /** mmol/L 转 mg/dL */
    fun mmolToMgdl(mmol: Double): Double = mmol * 18.0

    /** mg/dL 转 mmol/L */
    fun mgdlToMmol(mgdl: Double): Double = mgdl / 18.0

    /** 格式化血糖值显示 */
    fun formatGlucose(mmol: Double): String {
        return if (getGlucoseUnit() == "mgdl") {
            "${mmolToMgdl(mmol).toInt()} mg/dL"
        } else {
            "${String.format("%.1f", mmol)} mmol/L"
        }
    }

    // ===== 血糖目标范围（响应式）=====

    private val _targetLow = MutableStateFlow(sharedPref.getFloat("target_low", 3.9f))
    val targetLow: StateFlow<Float> = _targetLow

    private val _targetHigh = MutableStateFlow(sharedPref.getFloat("target_high", 10.0f))
    val targetHigh: StateFlow<Float> = _targetHigh

    fun getTargetLow(): Float = _targetLow.value
    fun getTargetHigh(): Float = _targetHigh.value

    fun setTargetRange(low: Float, high: Float) {
        sharedPref.edit().putFloat("target_low", low).putFloat("target_high", high).apply()
        _targetLow.value = low; _targetHigh.value = high
    }

    private val _severeLow = MutableStateFlow(sharedPref.getFloat("severe_low", 3.0f))
    val severeLow: StateFlow<Float> = _severeLow
    fun getSevereLow(): Float = _severeLow.value
    fun setSevereLow(v: Float) { sharedPref.edit().putFloat("severe_low", v).apply(); _severeLow.value = v }

    private val _severeHigh = MutableStateFlow(sharedPref.getFloat("severe_high", 13.9f))
    val severeHigh: StateFlow<Float> = _severeHigh
    fun getSevereHigh(): Float = _severeHigh.value
    fun setSevereHigh(v: Float) { sharedPref.edit().putFloat("severe_high", v).apply(); _severeHigh.value = v }

    // ===== 用户信息 =====

    fun getUserName(): String = sharedPref.getString("user_name", "") ?: ""
    fun setUserName(name: String) { sharedPref.edit().putString("user_name", name).apply() }

    fun getWeightKg(): Float = sharedPref.getFloat("weight_kg", 60f)
    fun setWeightKg(w: Float) { sharedPref.edit().putFloat("weight_kg", w).apply() }

    fun getHeightCm(): Int = sharedPref.getInt("height_cm", 165)
    fun setHeightCm(h: Int) { sharedPref.edit().putInt("height_cm", h).apply() }

    fun getDiabetesType(): Int {
        return sharedPref.getInt("diabetes_type", 1)  // 1=1型, 2=2型
    }

    fun setDiabetesType(type: Int) {
        sharedPref.edit().putInt("diabetes_type", type).apply()
    }

    // ===== CGM配置 =====

    fun getCgmDeviceAddress(): String {
        return sharedPref.getString("cgm_device_address", "") ?: ""
    }

    fun setCgmDeviceAddress(address: String) {
        sharedPref.edit().putString("cgm_device_address", address).apply()
    }

    // ===== 通知设置 =====

    fun isAlertEnabled(): Boolean {
        return sharedPref.getBoolean("alert_enabled", true)
    }

    fun setAlertEnabled(enabled: Boolean) {
        sharedPref.edit().putBoolean("alert_enabled", enabled).apply()
    }

    fun isSoundEnabled(): Boolean {
        return sharedPref.getBoolean("alert_sound", true)
    }

    fun setSoundEnabled(enabled: Boolean) {
        sharedPref.edit().putBoolean("alert_sound", enabled).apply()
    }

    fun isVibrationEnabled(): Boolean {
        return sharedPref.getBoolean("alert_vibration", true)
    }

    fun setVibrationEnabled(enabled: Boolean) {
        sharedPref.edit().putBoolean("alert_vibration", enabled).apply()
    }

    // ===== 紧急联系人 =====

    fun getEmergencyContactName(): String {
        return sharedPref.getString("emergency_name", "") ?: ""
    }

    fun getEmergencyContactPhone(): String {
        return sharedPref.getString("emergency_phone", "") ?: ""
    }

    fun setEmergencyContact(name: String, phone: String) {
        sharedPref.edit()
            .putString("emergency_name", name)
            .putString("emergency_phone", phone)
            .apply()
    }

    fun hasEmergencyContact(): Boolean {
        return getEmergencyContactName().isNotEmpty() && getEmergencyContactPhone().isNotEmpty()
    }

    // ===== 用药提醒 =====

    fun isInsulinReminderEnabled(): Boolean {
        return sharedPref.getBoolean("insulin_reminder", false)
    }

    fun setInsulinReminderEnabled(enabled: Boolean) {
        sharedPref.edit().putBoolean("insulin_reminder", enabled).apply()
    }

    fun getInsulinReminderMorning(): String {
        return sharedPref.getString("insulin_morning", "07:00") ?: "07:00"
    }

    fun getInsulinReminderNoon(): String {
        return sharedPref.getString("insulin_noon", "12:00") ?: "12:00"
    }

    fun getInsulinReminderEvening(): String {
        return sharedPref.getString("insulin_evening", "18:00") ?: "18:00"
    }

    fun getInsulinReminderNight(): String {
        return sharedPref.getString("insulin_night", "22:00") ?: "22:00"
    }

    fun setInsulinReminderTimes(morning: String, noon: String, evening: String, night: String) {
        sharedPref.edit()
            .putString("insulin_morning", morning)
            .putString("insulin_noon", noon)
            .putString("insulin_evening", evening)
            .putString("insulin_night", night)
            .apply()
    }

    // ===== 胰岛素参数 =====

    /** 胰岛素敏感因子 (mmol/L per U) */
    fun getInsulinSensitivity(): Float {
        return sharedPref.getFloat("insulin_sensitivity", 1.5f)
    }

    fun setInsulinSensitivity(factor: Float) {
        sharedPref.edit().putFloat("insulin_sensitivity", factor).apply()
    }

    /** 碳水系数 (g per U) */
    fun getCarbRatio(): Float {
        return sharedPref.getFloat("carb_ratio", 12.0f)
    }

    fun setCarbRatio(ratio: Float) {
        sharedPref.edit().putFloat("carb_ratio", ratio).apply()
    }

    // ===== AutoParamEstimator 自动参数估计开关 =====
    // 修复 Smell 2: 之前 PredictionViewModel 每次预测都自动覆盖用户的 ISF/CR 设置
    // 临床医生和谨慎用药的用户会失去控制, 现在默认关闭, 需要时手动开启
    fun isAutoParamEstimateEnabled(): Boolean {
        return sharedPref.getBoolean("auto_param_estimate", false)
    }

    fun setAutoParamEstimateEnabled(enabled: Boolean) {
        sharedPref.edit().putBoolean("auto_param_estimate", enabled).apply()
    }

    // ===== AI对话配置 =====

    /** AI服务商: openai / ernie */
    fun getAiProvider(): String {
        return sharedPref.getString("ai_provider", DEFAULT_AI_PROVIDER) ?: DEFAULT_AI_PROVIDER
    }

    fun setAiProvider(provider: String) {
        sharedPref.edit().putString("ai_provider", provider).apply()
    }

    // OpenAI配置（默认使用小米MiMo API）
    fun getOpenAiApiKey(): String {
        return sharedPref.getString("openai_api_key", DEFAULT_OPENAI_API_KEY) ?: DEFAULT_OPENAI_API_KEY
    }

    fun getOpenAiBaseUrl(): String {
        return sharedPref.getString("openai_base_url", DEFAULT_OPENAI_BASE_URL) ?: DEFAULT_OPENAI_BASE_URL
    }

    fun setOpenAiConfig(apiKey: String, baseUrl: String) {
        sharedPref.edit()
            .putString("openai_api_key", apiKey)
            .putString("openai_base_url", baseUrl)
            .apply()
    }

    fun isOpenAiConfigured(): Boolean {
        return getOpenAiApiKey().isNotEmpty()
    }

    // 文心一言配置（使用百度AI的Key）
    fun getErnieApiKey(): String {
        return sharedPref.getString("ernie_api_key", DEFAULT_BAIDU_API_KEY) ?: DEFAULT_BAIDU_API_KEY
    }

    fun getErnieSecretKey(): String {
        return sharedPref.getString("ernie_secret_key", DEFAULT_BAIDU_SECRET_KEY) ?: DEFAULT_BAIDU_SECRET_KEY
    }

    fun setErnieConfig(apiKey: String, secretKey: String) {
        sharedPref.edit()
            .putString("ernie_api_key", apiKey)
            .putString("ernie_secret_key", secretKey)
            .apply()
    }

    fun isErnieConfigured(): Boolean {
        return getErnieApiKey().isNotEmpty() && getErnieSecretKey().isNotEmpty()
    }

    fun isAiConfigured(): Boolean {
        return when (getAiProvider()) {
            "openai" -> isOpenAiConfigured()
            "ernie" -> isErnieConfigured()
            else -> false
        }
    }
}
