# Phase 02: GitHub Actions CI/CD Workflow Streamlining (FP32 Release)
Status: ✅ Completed
Dependencies: `plans/261124-2315-github-release-arm64-fp32-optimization/phase-01-gradle-arm64-packaging.md`

## Objective
Streamline the GitHub Actions CI/CD workflow (`.github/workflows/android.yml`) to eliminate the runtime INT8 model quantization pass and dependencies, publishing a lightweight (~250-254 MB) FP32-only 64-bit release APK directly to GitHub Releases.

## Scope
- Modify `.github/workflows/android.yml`.
- Remove Python 3.10 setup, pip quantization dependencies installation, and `tools/quantize_models.py` execution.
- Remove INT8 assets verification steps.
- Update the APK artifact preparation step to locate the single `arm64-v8a` release APK produced by Phase 01.
- Ensure GitHub Release publishing cleanly uploads the optimized APK asset.

## Requirements
### Functional
- The CI/CD pipeline must build the release APK using solely the pre-packaged FP32 ONNX model files from `app/src/main/assets/vieneu/backbone`.
- The pipeline must not execute Python quantization scripts, eliminating `tools/quantize_models.py` and Python runtime overhead.
- The release preparation step must reliably identify the unified release APK in `app/build/outputs/apk/release/` and rename it to `${APP_NAME}-${VERSION}.apk`.
- The GitHub Release step must publish the release and attach the single ~254 MB APK asset.

### Non-Functional & Performance
- Cut CI workflow execution time by 3 to 5 minutes by omitting model quantization.
- Reduce the published GitHub Release asset size from ~495.7 MB down to ~250-254 MB.
- Eliminate network and compute overhead associated with installing Python packages on GitHub-hosted runners.

## Constraints
- Retain all existing signing secret variables (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
- Retain smart versioning and automatic release tagging logic (`Determine Next Release Version`).
- Retain GitHub Release creation and asset upload idempotency (`gh release create` / `gh release upload --clobber`).
- Do not introduce external third-party actions beyond official GitHub/actions runners.

## Dependencies
- Phase 01 (`phase-01-gradle-arm64-packaging.md`) must be completed so the release build produces the expected single unified APK artifact.

## Acceptance Criteria
- `.github/workflows/android.yml` contains no Python setup, no quantization dependencies install, and no INT8 generation steps.
- The APK discovery and preparation step correctly resolves the release APK without relying on fallback searches for split APKs.
- The published release asset matches the target footprint (~250-254 MB) and contains only FP32 assets and `arm64-v8a` native libraries.
- The workflow syntax and structure are valid and verifiable.

## Comprehensive File-Based Test
- **Test File:** `app/src/test/java/com/skul9x/doctruyen/ci/ReleaseWorkflowPipelineTest.kt`
- **Verification Responsibilities:**
  1. Parse and validate the structure and content of `.github/workflows/android.yml`.
  2. Assert that no steps invoke Python setup (`actions/setup-python`), pip install, or `tools/quantize_models.py`.
  3. Assert that no steps reference `backbone_int8` generation or directory verification.
  4. Assert that the release artifact preparation logic accurately targets the single release APK output from the Gradle build.
  5. Assert that keystore decoding, signing credentials, smart release versioning, and GitHub release upload commands remain intact and properly configured.
