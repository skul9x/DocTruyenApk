# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Preserve debugging line numbers
-keepattributes SourceFile,LineNumberTable

# General reflection and serialization attributes
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*

# Native methods & JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

# ONNX Runtime Android
-keep class ai.onnxruntime.** { *; }

# sea-g2p Rust JNI
-keep class com.skul9x.doctruyen.tts.vieneu.SeaG2P { *; }

# Data Transfer Objects (Keep serialized fields intact)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Retrofit, Gson, OkHttp
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keep class com.skul9x.doctruyen.network.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Glide
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.** { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# AndroidX Media
-keep class androidx.media.** { *; }
