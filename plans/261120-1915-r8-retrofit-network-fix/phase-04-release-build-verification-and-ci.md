# Phase 04: End-to-End Release Build Verification & CI/CD Pipeline Assurance

**Status:** ✅ Completed  
**Dependencies:** Phase 01, Phase 02, Phase 03  

## Objective
Verify the end-to-end integrity of the minified Release APK build with R8 enabled. Ensure all unit tests pass, ProGuard keep rules do not conflict, ABI splits remain functional, and the CI/CD pipeline on GitHub Actions builds and signs the release artifact without warning or failure.

## Scope
- Update `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AssetCleansingAndBuildOptimizationTest.kt` to expand ProGuard rule coverage assertions for all newly added Retrofit and Coroutines rules.
- Validate local release unit test execution (`./gradlew testReleaseUnitTest`).
- Verify that release APK packaging preserves native libraries (`arm64-v8a`, `armeabi-v7a`, `x86_64`) and shrinks unnecessary bytecode.
- Update `CHANGELOG.md` and `.brain/session.json` to record the comprehensive fix and prepare for release v1.15.

## Requirements
### Functional
1. **Full ProGuard Coverage Verification**: Unit tests must programmatically inspect `app/proguard-rules.pro` and assert that all rules for `kotlin.coroutines.Continuation`, `retrofit2.Call`, `retrofit2.Response`, `@retrofit2.http.*`, `TypeToken`, and `AnnotationDefault` are strictly enforced.
2. **Deterministic Release Compilation**: The Gradle task `testReleaseUnitTest` must execute cleanly with zero compilation errors, zero lint blockers, and zero missing reference warnings.
3. **ABI Split & Universal APK Packaging**: Release packaging must continue generating valid ABI-specific APKs and universal APK with compressed DEX and preserved JNI binaries.

### Non-Functional
- APK size must remain optimal (~130-140MB for Universal APK with full ONNX FP32 weights, and <100MB for ABI splits).
- 100% test pass rate across the entire test suite.

## How to Implement
1. **Update `AssetCleansingAndBuildOptimizationTest.kt`**:
   - Add explicit assertion checks in `testProGuardRulesCoverage()`:
     ```kotlin
     assertTrue("ProGuard must keep kotlin.coroutines.Continuation", proguardContent.contains("class kotlin.coroutines.Continuation"))
     assertTrue("ProGuard must keep retrofit2.Call", proguardContent.contains("interface retrofit2.Call"))
     assertTrue("ProGuard must keep retrofit2.Response", proguardContent.contains("class retrofit2.Response"))
     assertTrue("ProGuard must keep AnnotationDefault", proguardContent.contains("-keepattributes AnnotationDefault"))
     ```
2. **Update Release Metadata & Documentation**:
   - Document fixes in `CHANGELOG.md` under Release v1.15.
   - Update `.brain/session.json` and `.brain/session_log.txt` marking all phases completed.

## Files to Create/Modify
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AssetCleansingAndBuildOptimizationTest.kt` - Expand ProGuard regression checks.
- `CHANGELOG.md` - Document v1.15 release notes.
- `.brain/session.json` - Update session state.

## Dependencies
- Phase 01, Phase 02, Phase 03.

## Constraints
- Do not run tests or execute builds during the planning phase.
- Ensure all CI environment variables and signing configuration compatibility remain intact.

## Acceptance Criteria
- All 4 test suites (`RetrofitR8ProGuardRulesTest`, `DataModelSerializationIntegrityTest`, `NetworkErrorResolverTest`, `AssetCleansingAndBuildOptimizationTest`) define clear, comprehensive assertions.
- Changelog and session records accurately reflect the resolution of the `ClassCastException` issue.

## Comprehensive File-Based Test
- **File:** `app/src/test/java/com/skul9x/doctruyen/network/ReleaseBuildValidationTest.kt`
- **Verification Strategy:**
  1. `testProGuardAndGradleBuildIntegrity`: Verifies that `app/build.gradle.kts` maintains `isMinifyEnabled = true`, valid ABI splits, and references `proguard-rules.pro`.
  2. `testNoDangerousProGuardWildcards`: Verifies that `proguard-rules.pro` does not contain reckless `-dontshrink` or blanket `-keep class **` that would disable R8 entirely.
  3. `testAllNetworkComponentsCohesive`: Verifies that `ApiService`, `RetrofitClient`, `NetworkErrorResolver`, and model classes integrate without unmapped dependencies.

