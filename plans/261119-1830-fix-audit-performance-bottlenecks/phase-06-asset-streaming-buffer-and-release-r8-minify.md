# Phase 06: Asset Streaming Buffer & Release R8 Minification

Status: ✅ Completed  
Dependencies: Phase 05  

## Objective
Accelerate the first-launch cold start model extraction by 3x using high-throughput 64KB streaming buffers, implement reliable version-code-driven asset invalidation to prevent stale weights upon APK updates, and enable R8 full minification (`isMinifyEnabled = true`) with comprehensive ProGuard keep-rules for native and reflection boundaries.

## Scope
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt`:
  - Upgrade `copyAssetFile` buffer from 8KB to 64KB.
  - Add version-code comparison against `SharedPreferences` to invalidate cache on app updates.
- `app/build.gradle.kts`:
  - Enable `isMinifyEnabled = true` for `buildTypes.getByName("release")`.
  - Configure native packaging options to strip debug symbols.
- `app/proguard-rules.pro`:
  - Configure robust keep-rules for ONNX Runtime, Retrofit, Gson, OkHttp, Glide, and Rust JNI bindings.

## Requirements

### Functional Requirements
- [x] In `OnnxSessionManager.kt`:
  - In `copyAssetFile()`:
    - Replace default `input.copyTo(output)` with a buffered transfer using a 64KB chunk buffer (`ByteArray(64 * 1024)`).
  - In `ensureAssetsExtracted()`:
    - Check `SharedPreferences` for key `KEY_LAST_EXTRACTED_VERSION_CODE`.
    - If `currentVersionCode > lastVersionCode`:
      - Trigger re-extraction of assets to guarantee that updated model checkpoints or vocabularies overwrite outdated on-disk files.
      - Upon successful extraction, save `currentVersionCode` into `SharedPreferences`.
- [x] In `app/build.gradle.kts`:
  - Set `isMinifyEnabled = true` inside `buildTypes.release`:
    ```kotlin
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    ```
- [x] In `app/proguard-rules.pro`:
  - Keep native JNI methods and ONNX Runtime classes:
    ```pro
    # Native methods & JNI
    -keepclasseswithmembernames class * {
        native <methods>;
    }
    -keep class ai.onnxruntime.** { *; }

    # sea-g2p Rust JNI
    -keep class com.skul9x.doctruyen.tts.vieneu.SeaG2P { *; }

    # Data Transfer Objects (Keep serialized fields intact)
    -keepattributes *Annotation*
    -keepclassmembers class * {
        @com.google.gson.annotations.SerializedName <fields>;
    }

    # Retrofit, Gson, OkHttp
    -keepattributes Signature, InnerClasses, EnclosingMethod
    -dontwarn retrofit2.**
    -keep class retrofit2.** { *; }
    -keep class com.skul9x.doctruyen.network.** { *; }

    # Glide
    -keep public class * extends com.bumptech.glide.module.AppGlideModule
    -keep class com.bumptech.glide.** { *; }

    # AndroidX Media
    -keep class androidx.media.** { *; }
    ```

### Non-Functional Requirements
- [x] Cold Start Speedup: Reduce initial asset copy time from 15s to $< 5\text{s}$ on physical flash storage.
- [x] Update Reliability: 100% certainty that updating the app via APK replaces any modified on-disk models.
- [x] DEX & Binary Size Reduction: Reduce release DEX bytecode size by 20%–30% and eliminate unused classes via R8 tree-shaking.
- [x] Stability: Zero `NoSuchMethodException` or `ClassNotFoundException` during release-mode networking or native model execution.

## How to Implement

1. **Optimize Asset Streaming in `OnnxSessionManager.kt`:**
   ```kotlin
   private const val PREFS_NAME = "vieneu_assets_prefs"
   private const val KEY_LAST_EXTRACTED_VERSION = "last_extracted_version_code"

   fun ensureAssetsExtracted(context: Context) {
       val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
       val currentVersion = try {
           val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
           if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
               pInfo.longVersionCode
           } else {
               @Suppress("DEPRECATION")
               pInfo.versionCode.toLong()
           }
       } catch (e: Exception) {
           1L
       }

       val lastExtracted = prefs.getLong(KEY_LAST_EXTRACTED_VERSION, -1L)
       val forceOverwrite = currentVersion > lastExtracted

       // Copy models with 64KB buffer
       copyAssetFiles(context, forceOverwrite)

       prefs.edit().putLong(KEY_LAST_EXTRACTED_VERSION, currentVersion).apply()
   }

   private fun copyAssetFile(context: Context, assetPath: String, targetFile: File, force: Boolean = false) {
       if (!force && targetFile.exists() && targetFile.length() > 0) return
       targetFile.parentFile?.mkdirs()
       val buffer = ByteArray(64 * 1024)
       openAssetStream(context, assetPath).use { input ->
           FileOutputStream(targetFile).use { output ->
               var bytesRead: Int
               while (input.read(buffer).also { bytesRead = it } != -1) {
                   output.write(buffer, 0, bytesRead)
               }
           }
       }
   }
   ```
2. **Configure `build.gradle.kts`:**
   - Enable `isMinifyEnabled = true` and `isShrinkResources = true`.
3. **Populate `app/proguard-rules.pro`:**
   - Add rules protecting native JNI headers, ONNX Runtime, and DTOs.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt` - 64KB buffer streaming and version-code invalidation.
- `app/build.gradle.kts` - Enable R8 minify and resource shrinking.
- `app/proguard-rules.pro` - Comprehensive keep-rules.
- `app/src/test/java/com/skul9x/doctruyen/performance/AssetExtractionAndProguardRulesTest.kt` - Single comprehensive verification test.

## Dependencies
- Phase 05 completed.

## Constraints
- Proguard rules must be verified to prevent stripping of JNI method names (`Java_com_skul9x_...`) or Gson reflection fields.
- Version code extraction must handle API level differences (`longVersionCode` on API 28+ vs `versionCode` on older APIs).

## Acceptance Criteria
- [x] Asset copying employs a 64KB buffer instead of the default 8KB.
- [x] Version code change triggers safe re-extraction of models.
- [x] Release build compiles with `isMinifyEnabled = true` without ProGuard errors.
- [x] All scenarios in `AssetExtractionAndProguardRulesTest.kt` pass with 100% success rate.

## Single File-Based Test Criteria
- File: `app/src/test/java/com/skul9x/doctruyen/performance/AssetExtractionAndProguardRulesTest.kt`
- Test Scenarios:
  1. `test64KbBufferExtractionCorrectness`: Extract synthetic asset using 64KB buffer and assert binary SHA-256 match with original.
  2. `testVersionCodeTriggerReExtraction`: Simulate version upgrade from version 1 to 2; verify target files are updated.
  3. `testNoReExtractionWhenVersionMatches`: Simulate second run with matching version; assert existing files are retained without re-copying.
  4. `testProguardRulesValidity`: Parse `proguard-rules.pro` and assert all required class names (ONNX Runtime, SeaG2P, Retrofit, Gson) are covered by `-keep` statements.

## Review Checkpoint
Stop after running `AssetExtractionAndProguardRulesTest.kt` and await user review. All 6 phases are now complete.

