package com.tangdun.app.api

import android.content.Context
import com.tangdun.app.util.TokenManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // 模拟器用10.0.2.2访问宿主机，真机需改为局域网IP
    private const val BASE_URL = "http://10.0.2.2:8000/api/v1/"

    private var retrofit: Retrofit? = null
    private var apiService: ApiService? = null

    fun init(context: Context) {
        val tokenManager = TokenManager(context)

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE  // 生产环境不记录body(含JWT/健康数据)
        }

        val authInterceptor = Interceptor { chain ->
            val token = tokenManager.getToken()
            val request = if (token.isNotEmpty()) {
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit?.create(ApiService::class.java)
    }

    fun getApi(): ApiService {
        return apiService ?: throw IllegalStateException("RetrofitClient not initialized. Call init() first.")
    }
}
