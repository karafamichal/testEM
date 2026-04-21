# Keep line numbers for useful stack traces in production.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Compose / Kotlin metadata
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# Kotlinx coroutines: avoid stripping internal state
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }

# OkHttp / Okio — R8 already handles but silence warnings.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Gson — keep serialized fields, generic signatures, and model classes reflective access needs.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.stream.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# App models parsed via Gson reflection — keep their fields.
-keep class com.ksjd.testem.AccountDetails { *; }
-keep class com.ksjd.testem.AccountDetails$* { *; }
-keep class com.ksjd.testem.CardHistory { *; }
-keep class com.ksjd.testem.CardHistory$* { *; }
-keep class com.ksjd.testem.TimetableModels { *; }
-keep class com.ksjd.testem.TimetableModels$* { *; }
-keep class com.ksjd.testem.ThemePreset { *; }
-keep class com.ksjd.testem.api.QrTokenResponse { *; }

# ZXing
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }

# Jsoup
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# Compose runtime reflection (lambda classes used by compose)
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.runtime.** { *; }

# Keep ViewModel default constructors
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
