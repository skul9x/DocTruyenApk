# Phase 01: R8 & ProGuard Rules Hardening for Retrofit & Kotlin Coroutines

**Status:** ✅ Completed  
**Dependencies:** None  

## Objective
Eliminate the runtime `java.lang.ClassCastException: java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType` crash occurring in release builds with R8 minification enabled. Configure the official Square Retrofit rules for Kotlin Coroutines `Continuation`, preserve generic type signatures for API service interfaces and response wrappers, and ensure R8 Full Mode does not strip reflection metadata required by Retrofit and Gson.

## Scope
- Update `app/proguard-rules.pro` with official Square Retrofit consumer rules and Kotlin Coroutines continuation rules.
- Retain generic type signatures (`Signature`), enclosing method, inner classes, and annotation defaults.
- Protect Retrofit dynamic proxy interfaces and return types from aggressive R8 dead-code stripping.
- Retain Gson `TypeToken` generic type reflection.
- Optionally add safety flags in `gradle.properties` (`android.enableR8.fullMode=false` if needed as fallback, while ensuring rules succeed in both compat and full mode).

## Requirements
### Functional
1. **Continuation Generic Signature Retention**: `kotlin.coroutines.Continuation` must retain its generic type parameter across R8 code shrinking and optimization passes.
2. **Retrofit Reflection Integrity**: Retrofit's `HttpServiceMethod.parseAnnotations` must successfully detect and cast the continuation parameter type as `ParameterizedType` at runtime without throwing `ClassCastException`.
3. **Interface Proxy Preservation**: Methods annotated with `@retrofit2.http.*` (`@GET`, `@POST`, `@Query`, etc.) and their containing interfaces must not be stripped or have parameter types removed.
4. **Call & Response Wrapper Retention**: Generic signatures of `retrofit2.Call` and `retrofit2.Response` must remain intact.

### Non-Functional
- DEX size must not increase substantially; rules must use `allowobfuscation`, `allowshrinking`, and `allowoptimization` where appropriate rather than blanket unshrinkable keeps.
- Clean release build execution without ProGuard warnings or build failure.

## How to Implement
1. **Inspect and Update `app/proguard-rules.pro`**:
   - Add reflection attributes:
     ```pro
     -keepattributes Signature, InnerClasses, EnclosingMethod
     -keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
     -keepattributes RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations
     -keepattributes AnnotationDefault
     ```
   - Add Kotlin Coroutines Continuation retention:
     ```pro
     -keep,allowoptimization,allowshrinking,allowobfuscation class kotlin.coroutines.Continuation
     ```
   - Add Retrofit generic types retention:
     ```pro
     -keep,allowobfuscation,allowshrinking interface retrofit2.Call
     -keep,allowobfuscation,allowshrinking class retrofit2.Response
     ```
   - Add rules protecting Retrofit service methods and return types from R8 Full Mode null-replacement:
     ```pro
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
     ```
   - Add Gson TypeToken preservation:
     ```pro
     -keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
     ```
2. **Review `gradle.properties`**:
   - Ensure compatibility settings are clean and deterministic for R8 release compilation.

## Files to Create/Modify
- `app/proguard-rules.pro` - Add official Retrofit + Coroutines R8 keep rules.
- `gradle.properties` - Verify R8 configuration flags.

## Dependencies
- None. Operates directly on the build toolchain and ProGuard configuration.

## Constraints
- Do not disable code shrinking or minification (`isMinifyEnabled` must remain `true` to keep release APK size minimal).
- Do not add overly broad `-keep class ** { *; }` rules that defeat the purpose of R8 dead-code stripping.

## Acceptance Criteria
- `proguard-rules.pro` contains all official Retrofit, Coroutines Continuation, and generic signature retention directives.
- Unit test `RetrofitR8ProGuardRulesTest` passes 100%, verifying the presence and syntax of every required rule.
- Release assemble builds complete without ProGuard/R8 syntax errors or missing reference warnings.

## Comprehensive File-Based Test
- **File:** `app/src/test/java/com/skul9x/doctruyen/network/RetrofitR8ProGuardRulesTest.kt`
- **Verification Strategy:**
  1. `testSignatureAndReflectionAttributesRetained`: Validates that `Signature`, `InnerClasses`, `EnclosingMethod`, `AnnotationDefault`, and annotation attributes are present in `app/proguard-rules.pro`.
  2. `testKotlinContinuationRulePresent`: Verifies that `kotlin.coroutines.Continuation` is explicitly protected to preserve suspend function type arguments.
  3. `testRetrofitCallAndResponseSignaturesKept`: Verifies that `retrofit2.Call` and `retrofit2.Response` keep rules are defined.
  4. `testRetrofitHttpAnnotationInterfaceRulesKept`: Verifies conditional `-if interface * { @retrofit2.http.* ... }` and `-keepclassmembers` rules for Retrofit service methods.
  5. `testGsonTypeTokenRetention`: Verifies that `com.google.gson.reflect.TypeToken` is retained for generic deserialization.

