package com.tangdun.app.util

import android.content.Context

class TokenManager(private val context: Context) {

    private val sharedPref = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    fun getToken(): String {
        return sharedPref.getString("jwt_token", "") ?: ""
    }

    fun saveToken(token: String) {
        sharedPref.edit().putString("jwt_token", token).apply()
    }

    fun clearToken() {
        sharedPref.edit().remove("jwt_token").apply()
    }

    fun isLoggedIn(): Boolean {
        return getToken().isNotEmpty()
    }

    fun getUserId(): String {
        return sharedPref.getString("user_id", "1") ?: "1"
    }

    fun saveUserId(userId: String) {
        sharedPref.edit().putString("user_id", userId).apply()
    }
}
