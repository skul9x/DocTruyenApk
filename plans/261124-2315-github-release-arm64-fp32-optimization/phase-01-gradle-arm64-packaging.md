# Phase 01: Gradle Architecture & Packaging Alignment (arm64-v8a only)
Status: ✅ Completed
Dependencies: None

## Objective
Configure the Android Gradle build packaging to target exclusively the 64-bit ARM architecture (`arm64-v8a`) and produce a single unified APK output, eliminating 32-bit (`armeabi-v7a`), x86 (`x86_64`), and universal multi-ABI bloat.

## Scope
- Modify Gradle build script (`app/build.gradle.kts`) ABI filtering and packaging settings.
- Restrict NDK packaging filters exclusively to `arm64-v8a`.
- Disable or adapt `splits.abi` so that build tasks generate a single primary APK artifact containing only 64-bit native binaries.
- Ensure all required uncompressed asset settings for ONNX and dictionary models are retained.

## Requirements
### Functional
- The application build must package solely `arm64-v8a` native libraries (`libonnxruntime.so`, `libonnxruntime4j_jni.so`, `libsea_g2p_android.so`).
- Standard assemble tasks (`:app:assembleDebug` and `:app:assembleRelease`) must generate a single primary APK file (e.g., `app-debug.apk` and `app-release.apk`) rather than generating multiple fragmented per-ABI APKs or bloated universal APKs.
- The resulting APK must remain directly installable on all 64-bit Android physical devices (API 24+).

### Non-Functional & Performance
- Reduce native binary packaging overhead by ~80-90 MB by excluding `armeabi-v7a` and `x86_64` `.so` files.
- Produce a streamlined APK with an overall footprint of ~250-254 MB when packaged with FP32 assets.
- Eliminate redundant multi-ABI compilation, compression, and signing overhead during Gradle builds.

## Constraints
- Do not remove or alter existing zero-copy `mmap` asset configurations (`noCompress += ["onnx", "data", "npz", "json"]`).
- Preserve signing configuration references and build type definitions.
- Maintain full compatibility with existing unit and integration test infrastructure.

## Dependencies
- None.

## Acceptance Criteria
- `app/build.gradle.kts` explicitly restricts native ABIs to `arm64-v8a`.
- ABI splits configuration is streamlined so that multi-APK generation is disabled or unified into a single `arm64-v8a` target.
- Output artifacts from `:app:assembleRelease` and `:app:assembleDebug` cleanly yield single primary APKs containing solely `lib/arm64-v8a/`.
- All automated unit tests in the project continue to pass without regression.

## Comprehensive File-Based Test
- **Test File:** `app/src/test/java/com/skul9x/doctruyen/packaging/GradleArm64PackagingConfigurationTest.kt`
- **Verification Responsibilities:**
  1. Validate that the Gradle build script specifies `arm64-v8a` as the sole targeted NDK ABI filter.
  2. Verify that legacy or redundant ABIs (`armeabi-v7a`, `x86_64`, `x86`) are excluded from native build targets.
  3. Verify that ABI split configurations are configured to output a single unified APK rather than generating multi-ABI splits.
  4. Verify that critical asset compression exclusions (`noCompress`) for `.onnx`, `.data`, `.npz`, and `.json` files are preserved.
