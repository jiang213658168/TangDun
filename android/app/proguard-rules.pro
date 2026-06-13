# 糖盾 ProGuard 规则

# 保留Room数据库
-keep class com.tangdun.app.data.local.entity.** { *; }
-keep class com.tangdun.app.data.local.dao.** { *; }

# 保留Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# 保留Gson
-keep class com.google.gson.** { *; }
-keep class com.tangdun.app.data.remote.** { *; }

# 保留ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# 保留Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# 保留Compose
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }
