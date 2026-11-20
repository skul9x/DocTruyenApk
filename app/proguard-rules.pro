# ProGuard and R8 Rules for DocTruyen & VieNeu-TTS

# ==========================================
# 1. ONNX RUNTIME AND NATIVE BINDINGS
# ==========================================
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ==========================================
# 2. JNI METHODS & SEAG2P PHONEMIZER
# ==========================================
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.skul9x.doctruyen.tts.vieneu.SeaG2P { *; }

# ==========================================
# 3. RETROFIT & OKHTTP & GSON DATA MODELS
# ==========================================
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations
-keepattributes AnnotationDefault

# Preserve Kotlin Coroutines Continuation generic signatures for Retrofit suspend functions
-keep,allowoptimization,allowshrinking,allowobfuscation class kotlin.coroutines.Continuation

# Core Retrofit Call & Response generic signatures
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# Protect Retrofit service methods and return types from R8 stripping / optimization
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-if interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation interface <1>
-if interface * {
    @retrofit2.http.* public *** *(...);
}
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>

-keepattributes *Annotation*
-keepclassmembers class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Preserve Gson TypeToken generic reflection
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

-keep class com.skul9x.doctruyen.network.** { *; }
-keepclassmembers class com.skul9x.doctruyen.network.** {
    <fields>;
    <init>(...);
}
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ==========================================
# 4. VIENEU CONFIG & PRESETS & ENGINE DATA
# ==========================================
-keep class com.skul9x.doctruyen.tts.vieneu.VieNeuConfig { *; }
-keep class com.skul9x.doctruyen.tts.vieneu.VoicePresets** { *; }
-keep class com.skul9x.doctruyen.tts.vieneu.NpzReader** { *; }
-keep class com.skul9x.doctruyen.tts.vieneu.VieNeuOnnxEngine** { *; }

# ==========================================
# 5. GLIDE & MEDIA & COROUTINES
# ==========================================
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
