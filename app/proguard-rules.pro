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
-keepclassmembers class com.skul9x.doctruyen.tts.vieneu.SeaG2P {
    private external fun nativeInit(java.lang.String);
    private external fun nativePhonemize(java.lang.String);
}

# ==========================================
# 3. RETROFIT & OKHTTP & GSON DATA MODELS
# ==========================================
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations

-keepattributes *Annotation*
-keepclassmembers class com.google.gson.** { *; }
-dontwarn com.google.gson.**

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
